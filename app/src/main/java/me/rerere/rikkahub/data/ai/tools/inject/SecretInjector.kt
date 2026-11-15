package me.rerere.rikkahub.data.ai.tools.inject

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * 统一的 env 凭证注入服务（方案 D）。
 *
 * 此前四个执行通道（workspace_shell / workspace_run_background / ssh_exec / ssh_exec_saved）
 * 各自解析 `env` 参数：workspace 侧在 resolveInjectedEnv、ssh 侧在 readEnvParam，两份代码
 * 语义「约定一致」但细节已分叉（非字符串值：workspace 拒绝、ssh 静默丢弃；非法键：workspace
 * 在解析里拒、ssh 在工具里另拒）。本文件把「解析 → 旧写法护栏 → 凭证解析 → 输出脱敏」收成
 * 一条路径，通道只负责「怎么把 env 交给进程」。现有传递方式（本次未改，A/B 方案替换的就是这一层）：
 * - 本地 proot：`/usr/bin/env K=V ...` 作为 argv（同机 /proc/<pid>/cmdline 可见）；
 * - app 侧 ssh_exec / ssh_exec_saved：赋值语句拼在远端命令文本前（远端 ps / 审计日志可见）；
 * - 远程工作区：远端 0600 临时文件 + EXIT trap（P2-11；Windows 仍为 base64 命令行）。
 *
 * 纯 Kotlin + kotlinx.serialization.json，不依赖 Android / Koin：凭证库经 [SecretRefResolver]
 * 与名字来源函数注入，App 侧适配见 VaultSecretInjection.kt。
 */

/** 环境变量名的合法形态（与 ssh 侧 ENV_KEY_REGEX 同一口径）。 */
internal val INJECT_ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** 引用前缀（与 vault 层 VAULT_REF_PREFIX 一致；此处复写一份以保持本文件零 Android 依赖）。 */
internal const val INJECT_REF_PREFIX = "\$\$"

/** 单条 `$$引用` 的解析结果。 */
internal sealed interface RefOutcome {
    data class Granted(val value: String) : RefOutcome {
        override fun toString(): String = "Granted(<redacted>)"
    }

    data class Denied(val message: String) : RefOutcome
}

/** 凭证引用解析器：负责授权门 / 存在性 / 解密 / 审计。[ref] 不含 `$$` 前缀。 */
internal fun interface SecretRefResolver {
    suspend fun resolve(ref: String): RefOutcome
}

/** 失败分类：通道据此选择自己的错误外形（文本 / 信封 / 异常）。 */
internal enum class InjectionFailureKind { INVALID_ARGUMENT, LEGACY_NAME, VAULT_UNAVAILABLE, REF_DENIED }

internal sealed interface InjectionResult {
    data class Ok(val injected: InjectedEnv) : InjectionResult

    /** [message] 不带 "❌ " 前缀，以 "env injection failed/refused: " 开头。 */
    data class Failed(val kind: InjectionFailureKind, val message: String) : InjectionResult
}

/**
 * 一次调用实际注入的 env 与其中经 `$$` 解析出的值。
 * 只活在内存；toString 不带值。
 */
internal class InjectedEnv(
    val env: Map<String, String>,
    val secrets: List<InjectedSecret>,
) {
    fun isEmpty(): Boolean = env.isEmpty()

    /** 自由文本脱敏。 */
    fun mask(text: String): String = maskInjectedValues(text, secrets)

    /** 结构化脱敏：递归替换所有字符串值（键名不动），避免对 JSON 编码后的文本做替换时被转义打断。 */
    fun maskJson(element: JsonElement): JsonElement = maskJsonStrings(element, secrets)

    fun maskJsonObject(obj: JsonObject): JsonObject = maskJson(obj) as JsonObject

    /** 因太短而无法脱敏的注入值个数。 */
    val unmaskableCount: Int get() = countUnmaskableInjected(secrets)

    /** 脱敏后，若有注入值太短没法脱敏，在对象上追加提示字段（[noteKey] 随通道的键名风格）。 */
    fun maskJsonObjectWithNote(obj: JsonObject, noteKey: String): JsonObject {
        val masked = maskJsonObject(obj)
        val n = unmaskableCount
        if (n == 0) return masked
        return JsonObject(masked + (noteKey to JsonPrimitive(unmaskableNote(n))))
    }

    override fun toString(): String = "InjectedEnv(keys=${env.keys}, secrets=${secrets.size})"

    companion object {
        val EMPTY = InjectedEnv(emptyMap(), emptyList())
    }
}

/** 太短无法脱敏时给模型的提示文案（各通道共用）。 */
internal fun unmaskableNote(count: Int): String =
    "$count injected vault value(s) are too short to mask safely and may appear verbatim in the output."

/** 递归替换 JSON 中所有字符串原语里的注入值。 */
internal fun maskJsonStrings(element: JsonElement, secrets: Collection<InjectedSecret>): JsonElement {
    if (secrets.isEmpty()) return element
    return when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> maskJsonStrings(v, secrets) })
        is JsonArray -> JsonArray(element.map { maskJsonStrings(it, secrets) })
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(maskInjectedValues(element.content, secrets)) else element
    }
}

/**
 * 统一注入服务。
 *
 * @param credentialNames 已存在的凭证名（只读名字，不解密）；供旧写法护栏比对。
 *   返回空集 = 护栏放行（凭证库不可用时纯字面量路径不应被它卡住）。
 * @param resolverFactory 需要解析 `$$` 引用时才调用；返回 null = 凭证库不可用（fail-closed 报错）。
 */
internal class SecretInjector(
    private val credentialNames: suspend () -> Set<String>,
    private val resolverFactory: suspend () -> SecretRefResolver?,
) {
    suspend fun prepare(envParam: JsonElement?): InjectionResult {
        val envObj = envParam as? JsonObject ?: return InjectionResult.Ok(InjectedEnv.EMPTY)
        if (envObj.isEmpty()) return InjectionResult.Ok(InjectedEnv.EMPTY)

        // 1) 键名：非法键会被注入层静默丢弃（模型以为注入成功）→ 显式拒绝
        val invalidKeys = envObj.keys.filterNot { INJECT_ENV_KEY_REGEX.matches(it) }
        if (invalidKeys.isNotEmpty()) {
            return fail(
                InjectionFailureKind.INVALID_ARGUMENT,
                "env injection failed: invalid variable name(s): ${invalidKeys.joinToString()} " +
                    "(must match [A-Za-z_][A-Za-z0-9_]*)",
            )
        }

        // 2) 值：只接受原语（数字/布尔按文本）；null / 对象 / 数组拒绝（此前 ssh 侧静默丢弃）
        val literals = LinkedHashMap<String, String>()
        val references = mutableListOf<Pair<String, String>>()
        for ((envName, valueJson) in envObj) {
            val raw = (valueJson as? JsonPrimitive)?.contentOrNull
                ?: return fail(
                    InjectionFailureKind.INVALID_ARGUMENT,
                    "env injection failed: value for '$envName' must be a string",
                )
            if (!raw.trim().startsWith(INJECT_REF_PREFIX)) {
                literals[envName] = raw
                continue
            }
            val ref = raw.trim().removePrefix(INJECT_REF_PREFIX).trim()
            if (ref.isEmpty()) {
                return fail(
                    InjectionFailureKind.INVALID_ARGUMENT,
                    "env injection failed: '$envName' has an empty vault reference (expected \$\$CRED_NAME)",
                )
            }
            references += envName to ref
        }

        // 3) 旧写法护栏：字面值恰好是已存在凭证名 / 名字.字段 → 拒绝（只读名字列表）
        if (literals.isNotEmpty()) {
            val hits = findLegacyCredentialNameLiterals(literals, credentialNames())
            if (hits.isNotEmpty()) {
                return fail(InjectionFailureKind.LEGACY_NAME, legacyCredentialNameError(hits).removePrefix("❌ "))
            }
        }

        // 4) 纯字面量：不碰凭证库、不需要授权
        if (references.isEmpty()) return InjectionResult.Ok(InjectedEnv(literals, emptyList()))

        // 5) 凭证解析：任何一条失败都整体失败，绝不回退字面值
        val resolver = resolverFactory()
            ?: return fail(InjectionFailureKind.VAULT_UNAVAILABLE, "env injection failed: credential vault unavailable")
        val env = LinkedHashMap(literals)
        val secrets = mutableListOf<InjectedSecret>()
        for ((envName, ref) in references) {
            when (val outcome = resolver.resolve(ref)) {
                is RefOutcome.Granted -> {
                    env[envName] = outcome.value
                    secrets += InjectedSecret(envName, ref, outcome.value)
                }
                is RefOutcome.Denied -> return fail(
                    InjectionFailureKind.REF_DENIED,
                    "env injection failed: $envName -> ${outcome.message}",
                )
            }
        }
        // 保持入参顺序（字面值与引用交错时，注入顺序与调用方书写一致）
        val ordered = LinkedHashMap<String, String>()
        envObj.keys.forEach { k -> env[k]?.let { ordered[k] = it } }
        return InjectionResult.Ok(InjectedEnv(ordered, secrets))
    }

    private fun fail(kind: InjectionFailureKind, message: String) = InjectionResult.Failed(kind, message)
}
