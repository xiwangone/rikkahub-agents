package me.rerere.rikkahub.data.vault

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.SshHostRepository

/**
 * 引用反查：回答"某凭证被哪些配置**按名字**引用"。
 *
 * 为什么必须先有这个：改名或删除凭证时，若不知道有哪些地方引用它，
 * 改完/删完才会发现某处配置**静默失效**（表现为莫名的 401 或连接失败）。
 * 因此它是"安全改名 / 安全删除"的共同前置。
 *
 * 只做**只读匹配**：不返回值、不改配置。
 */
object VaultReferenceLocator {

    /** 引用前缀（与运行时解析层保持一致）。 */
    const val PREFIX = "\$\$"

    /**
     * 文本里是否引用了该凭证名。
     *
     * 用负向前瞻限定 token 边界，避免 `$$A` 命中 `$$AB` 这类前缀误判。
     *
     * 正则按名字缓存：本方法会在"逐个配置项"的循环里被调用（引用反查），
     * 每次新建 Regex 是无谓开销。条目数量有限，缓存无需淘汰。
     */
    fun mentions(text: String, name: String): Boolean {
        if (text.isBlank() || name.isBlank()) return false
        val pattern =
            PATTERN_CACHE.computeIfAbsent(name) { n ->
                Regex(Regex.escape(PREFIX + n) + "(?![A-Za-z0-9_])")
            }
        return pattern.containsMatchIn(text)
    }

    /** 名字 → 匹配模式（并发安全；条目数有限，无需淘汰）。 */
    private val PATTERN_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * 从一段文本中提取所有 `$$名字` 引用（按出现顺序、去重）。
     *
     * 用于「反向检查」：配置引用了什么，而不是「某个名字被谁引用」。
     */
    fun extractRefs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return Regex(Regex.escape(PREFIX) + "([A-Za-z0-9_]+)")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /** 取出某提供方配置里可能与凭证有关的字段（拼接后做匹配）。 */
    fun providerSecrets(provider: ProviderSetting): String = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey
        is ProviderSetting.Claude -> provider.apiKey
        is ProviderSetting.Google -> provider.apiKey
        is ProviderSetting.Backend -> "${provider.token} ${provider.password}"
        else -> ""
    }
}


/**
 * 检查配置里的**失效引用**：引用了不存在的凭证名。
 *
 * 为什么需要：引用写错、或凭证被删/改名之后，配置不会立刻报错，而是等到真正使用时
 * 才以 401 / 连接失败的形式出现 —— 排查成本高。本工具把这类问题提前暴露。
 *
 * 只读：不返回值，也不改配置。
 */
fun vaultDanglingRefsTool(
    repository: CredentialVaultRepository,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
): Tool = Tool(
    name = "vault_dangling_refs",
    description =
        "List configurations that reference a credential name which does NOT exist in the vault " +
            "(dangling references). These otherwise surface much later as confusing 401 / connection errors. " +
            "Read-only: reports locations and missing names, never values.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = { _ ->
        val known = repository.getAll().map { it.name }.toSet()
        val settings = settingsStore.settingsFlow.first()
        val issues = mutableListOf<String>()

        fun checkText(location: String, text: String) {
            VaultReferenceLocator.extractRefs(text)
                .filter { it !in known }
                .forEach { issues += location + " → " + "\$\$" + it + "（库中不存在）" }
        }
        fun checkName(location: String, name: String) {
            if (name.isNotBlank() && name !in known) {
                issues += location + " → " + name + "（库中不存在）"
            }
        }

        settings.providers.forEach { p ->
            checkText("模型提供方：" + p.name, VaultReferenceLocator.providerSecrets(p))
        }
        settings.mcpServers.forEach { m ->
            checkText(
                "MCP 服务器：" + m.commonOptions.name.ifBlank { m.id.toString().take(8) },
                m.commonOptions.headers.joinToString(" ") { it.second },
            )
        }
        (listOf(settings.s3Config) + settings.s3Configs).distinctBy { it.id }
            .forEach { checkText("S3 备份：" + it.name.ifBlank { it.id.take(8) }, it.secretAccessKey) }
        (listOf(settings.webDavConfig) + settings.webDavConfigs).distinctBy { it.id }
            .forEach { checkText("WebDAV 备份：" + it.name.ifBlank { it.id.take(8) }, it.password) }
        settings.localMcpProfiles.forEach {
            checkName("本地 MCP：" + it.name.ifBlank { it.id }, it.authTokenRef.trim())
        }
        checkName("Web 桥", settings.webBridgeCredentialRef.trim())
        sshHostRepository.getAll().forEach {
            checkName("SSH 主机：" + it.name, it.vaultCredentialRef?.trim().orEmpty())
        }

        val body =
            if (issues.isEmpty()) {
                "✅ 未发现失效引用（配置里的凭证引用都能在库中找到）"
            } else {
                buildString {
                    appendLine("⚠ 发现 " + issues.size + " 处失效引用：")
                    issues.forEach { appendLine("- " + it) }
                    append("请修正配置或补建对应凭证；改名前可用 vault_credential_refs 预检。")
                }
            }
        listOf(UIMessagePart.Text(body))
    },
)

/** 查出某凭证被哪些配置引用（只读；不含任何值）。 */
fun vaultCredentialRefsTool(
    settingsStore: SettingsStore,
    sshHostRepository: SshHostRepository,
): Tool = Tool(
    name = "vault_credential_refs",
    description =
        "Find which configurations reference a credential BY NAME (e.g. `\$\$GITHUB_TOKEN`), so that " +
            "renaming or deleting it can be done safely. Read-only: returns locations only, never values. " +
            "Covers model providers (apiKey / backend token+password), MCP server outbound headers, " +
            "local MCP auth token, S3 / WebDAV credentials, the web-bridge credential reference, " +
            "and saved SSH hosts. " +
            "Use before vault_credential_update(rename) or vault_credential_delete; " +
            "an empty result means no configuration references it.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Credential name to look up, e.g. GITHUB_TOKEN")
                        },
                    )
                },
            required = listOf("name"),
        )
    },
    execute = { params ->
        val name = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text("❌ name 必填"))
        }

        val settings = settingsStore.settingsFlow.first()
        val hits = mutableListOf<String>()

        // 模型提供方：apiKey（含后端提供方的 token / password）
        settings.providers.forEach { provider ->
            if (VaultReferenceLocator.mentions(VaultReferenceLocator.providerSecrets(provider), name)) {
                hits += "模型提供方：${provider.name}"
            }
        }

        // MCP 服务器出站头部值
        settings.mcpServers.forEach { server ->
            val headerValues = server.commonOptions.headers.joinToString(" ") { it.second }
            if (VaultReferenceLocator.mentions(headerValues, name)) {
                hits += "MCP 服务器：${server.commonOptions.name.ifBlank { server.id.toString().take(8) }}"
            }
        }

        // 备份目标：S3 / WebDAV 的密钥字段（漏掉它们会让人误判"无引用"从而改断配置）
        (listOf(settings.s3Config) + settings.s3Configs).distinctBy { it.id }.forEach { s3 ->
            if (VaultReferenceLocator.mentions(s3.secretAccessKey, name)) {
                hits += "S3 备份：${s3.name.ifBlank { s3.id.take(8) }}"
            }
        }
        (listOf(settings.webDavConfig) + settings.webDavConfigs).distinctBy { it.id }.forEach { dav ->
            if (VaultReferenceLocator.mentions(dav.password, name)) {
                hits += "WebDAV 备份：${dav.name.ifBlank { dav.id.take(8) }}"
            }
        }

        // 本地 MCP 服务的鉴权令牌（按名引用）
        settings.localMcpProfiles.forEach { profile ->
            if (profile.authTokenRef.trim() == name) {
                hits += "本地 MCP：${profile.name.ifBlank { profile.id }}"
            }
        }

        // Web 桥的凭证引用（直接就是凭证名）
        if (settings.webBridgeCredentialRef.trim() == name) {
            hits += "Web 桥：凭证引用"
        }

        // 已保存的 SSH 主机（私钥引用）
        sshHostRepository.getAll().forEach { host ->
            if (host.vaultCredentialRef?.trim() == name) {
                hits += "SSH 主机：${host.name}"
            }
        }

        val head = "引用「$name」的位置（${hits.size}）："
        val body =
            if (hits.isEmpty()) {
                "$head\n（无）—— 没有配置按名字引用它，改名/删除不会破坏引用"
            } else {
                buildString {
                    appendLine(head)
                    hits.forEach { appendLine("- $it") }
                    append("改名或删除前，请同步更新以上位置。")
                }
            }
        listOf(UIMessagePart.Text(body))
    },
)
