package me.rerere.rikkahub.data.ai.tools.inject

/*
 * env 注入的两道护栏（纯 Kotlin、无 Android 依赖，便于脱离 Android 工程单测）：
 *
 * 1. 旧写法兼容护栏：6b0dbd9 起 env 值改为「`$$` 前缀 = 凭证引用，其余 = 字面值」。
 *    此前 workspace_shell 的写法是 {"X":"凭证名"}（值直接写凭证名），升级后同一写法会
 *    **静默**把凭证名字面量注入环境（命令拿到 10 个字符的 "GITHUB_TOKEN" 而不是 token）。
 *    这里在字面值恰好等于某个已存在凭证名（或 凭证名.字段）时直接拒绝，提示改写 `$$名字`。
 *    只比对名字列表：不解密、不过授权门、不写审计。
 *
 * 2. 注入值输出脱敏：由 `$$` 解析出的值在本次调用（及其后台任务的状态输出）返回前替换为
 *    占位符。字面值不脱敏（本来就在参数里，模型可见）。与全局 SecretMasker 分层：
 *    全局掩码按「全库凭证 + 分类规则」掩，会放过名称不敏感的短值和 `$$名字.字段` 的明文
 *    元数据；这里按「本次确实注入了什么」精确掩，补上这两个缺口。
 */

/** 一条经 `$$` 解析后注入的值（仅内存态，用于输出脱敏）。 */
internal data class InjectedSecret(
    /** 注入到的环境变量名。 */
    val envName: String,
    /** 引用串本体（去掉 `$$` 前缀），如 `GITHUB_TOKEN` 或 `MY_S3.access_key_id`。 */
    val ref: String,
    /** 解析出的真值。 */
    val value: String,
) {
    /** `$$名字.字段` 取的是明文元数据字段。 */
    val isField: Boolean get() = ref.contains('.')

    // data class 默认 toString 会带出明文，日志/异常里误打印就泄露：显式改掉
    override fun toString(): String = "InjectedSecret(envName=$envName, ref=$ref, value=<redacted>)"
}

/** 主值（密文解密）脱敏的最短长度：更短的值全文替换误伤面太大（如 "1"、"on"、"dev"）。 */
internal const val INJECTED_SECRET_MIN_LEN = 4

/**
 * 明文元数据字段（endpoint/region/access_key_id/custom.* …）的最短脱敏长度：
 * 这类值本就是明文存储、多为低敏标识，取更高门槛减少 "us-east-1" 之类的误伤。
 */
internal const val INJECTED_FIELD_MIN_LEN = 8

/** 占位符：带上引用名，模型知道这里是哪个凭证，但看不到值。 */
internal fun injectedSecretPlaceholder(ref: String): String = "[masked:\$\$$ref]"

/**
 * 找出「字面值恰好是已存在凭证名（或 凭证名.字段）」的 env 项，返回 (环境变量名, 值)。
 *
 * - 比对前 trim；大小写敏感（凭证名受 `^[A-Z][A-Z0-9_]*$` 约束，小写的 "production" 永不命中）；
 * - `NAME.xxx` 只在 NAME 存在且点后非空时命中（凭证名本身不含 `.`，切点无歧义）。
 */
internal fun findLegacyCredentialNameLiterals(
    literals: Map<String, String>,
    credentialNames: Set<String>,
): List<Pair<String, String>> {
    if (literals.isEmpty() || credentialNames.isEmpty()) return emptyList()
    return literals.mapNotNull { (envName, raw) ->
        val v = raw.trim()
        if (v.isEmpty()) return@mapNotNull null
        val dot = v.indexOf('.')
        val hit = if (dot < 0) {
            v in credentialNames
        } else {
            v.substring(0, dot) in credentialNames && v.substring(dot + 1).isNotBlank()
        }
        if (hit) envName to v else null
    }
}

/** 兼容护栏的拒绝文案（给模型看，告诉它怎么改）。 */
internal fun legacyCredentialNameError(hits: List<Pair<String, String>>): String {
    val detail = hits.joinToString { (env, v) -> "$env=\"$v\"" }
    val fix = hits.joinToString { (env, v) -> "\"$env\": \"\$\$$v\"" }
    return "❌ env injection refused: $detail looks like a credential name, but values without the `\$\$` prefix " +
        "are injected LITERALLY (the command would receive the name itself, not the secret). " +
        "To inject the credential, write {$fix}. " +
        "If you really need the literal text, set it inside the command instead (e.g. export VAR=...)."
}

/**
 * 把文本里出现的注入值替换为占位符。
 *
 * - 主值长度 < [INJECTED_SECRET_MIN_LEN]、字段值长度 < [INJECTED_FIELD_MIN_LEN] 的不替换（防误伤）；
 * - 同时替换原值与 trim 后的值（命令常 `echo -n`/`printf` 掉首尾空白）；
 * - 长值优先替换，避免短值先吃掉长值的一部分。
 */
internal fun maskInjectedValues(
    text: String,
    secrets: Collection<InjectedSecret>,
    legacyRules: Map<String, String> = emptyMap(),
): String {
    if (text.isEmpty() || (secrets.isEmpty() && legacyRules.isEmpty())) return text
    val rules = LinkedHashMap<String, String>() // 值 -> 占位符（同值取第一个引用名）
    for (s in secrets) {
        val min = if (s.isField) INJECTED_FIELD_MIN_LEN else INJECTED_SECRET_MIN_LEN
        for (candidate in listOf(s.value, s.value.trim())) {
            if (candidate.isBlank() || candidate.length < min) continue
            rules.putIfAbsent(candidate, injectedSecretPlaceholder(s.ref))
        }
    }
    // 被淘汰任务留下的永久掩码规则：活动规则优先（占位符带新鲜 ref），同值不重复
    for ((value, placeholder) in legacyRules) {
        for (candidate in listOf(value, value.trim())) {
            if (candidate.isBlank()) continue
            rules.putIfAbsent(candidate, placeholder)
        }
    }
    if (rules.isEmpty()) return text
    var out = text
    rules.entries.sortedByDescending { it.key.length }.forEach { (value, placeholder) ->
        out = out.replace(value, placeholder)
    }
    return out
}

/** 有多少条注入值因太短而没有脱敏（交给调用方在输出里提示）。 */
internal fun countUnmaskableInjected(secrets: Collection<InjectedSecret>): Int =
    secrets.count { s ->
        val min = if (s.isField) INJECTED_FIELD_MIN_LEN else INJECTED_SECRET_MIN_LEN
        s.value.trim().length < min
    }

/**
 * 后台任务 → 本次注入值的进程内登记，供 workspace_background_status 脱敏。
 *
 * 明文只活在内存里、生命周期与进程一致（同后台进程表本身）；kill 时移除；
 * 超过 [MAX_TASKS] 条时淘汰最早的，防止长跑进程无限增长。淘汰不丢脱敏能力：
 * 被淘汰任务的值并入 [legacyMasks] 永久掩码集（见 [retainedRules]）。
 */
internal object BackgroundInjectedSecrets {
    private const val MAX_TASKS = 128

    // 被淘汰任务的注入值进永久掩码集（值 -> 占位符），否则第 129 个任务启动后，
    // 最早任务的 stdout/stderr 里 `$$` 值会以明文直达模型且无任何提示。
    // 值来自凭证库、去重后规模极小；MAX_LEGACY_VALUES 只是防御性上限。
    private const val MAX_LEGACY_VALUES = 4096
    private val legacyMasks = LinkedHashMap<String, String>(16, 0.75f, false)

    private fun retainLegacy(secrets: List<InjectedSecret>) {
        for (s in secrets) {
            val min = if (s.isField) INJECTED_FIELD_MIN_LEN else INJECTED_SECRET_MIN_LEN
            for (candidate in listOf(s.value, s.value.trim())) {
                if (candidate.isBlank() || candidate.length < min) continue
                if (candidate !in legacyMasks && legacyMasks.size >= MAX_LEGACY_VALUES) {
                    val oldest = legacyMasks.keys.firstOrNull() ?: break
                    legacyMasks.remove(oldest)
                }
                legacyMasks.putIfAbsent(candidate, injectedSecretPlaceholder(s.ref))
            }
        }
    }

    private val byTask = object : LinkedHashMap<String, List<InjectedSecret>>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<InjectedSecret>>?): Boolean {
            if (size <= MAX_TASKS) return false
            eldest?.value?.let { retainLegacy(it) }
            return true
        }
    }

    private fun key(workspaceId: String, taskId: String) = "$workspaceId\u0000$taskId"

    @Synchronized
    fun register(workspaceId: String, taskId: String, secrets: List<InjectedSecret>) {
        if (secrets.isEmpty()) return
        byTask[key(workspaceId, taskId)] = secrets.toList()
    }

    @Synchronized
    fun get(workspaceId: String, taskId: String): List<InjectedSecret> =
        byTask[key(workspaceId, taskId)].orEmpty()

    /**
     * 该 workspace 全部任务的注入值（供 read_file / search_code 掩后台任务日志：
     * 日志路径形如 `$workspaceDir/.rikkahub-bg/<taskId>/log`，工具侧只看得到路径、
     * 不知道 taskId，按 workspace 维度取全集兜底）。
     */
    @Synchronized
    fun allForWorkspace(workspaceId: String): List<InjectedSecret> {
        val prefix = "$workspaceId\u0000"
        return byTask.entries.filter { it.key.startsWith(prefix) }.flatMap { it.value }
    }

    @Synchronized
    fun remove(workspaceId: String, taskId: String) {
        byTask.remove(key(workspaceId, taskId))
    }

    @Synchronized
    internal fun clearForTest() {
        byTask.clear()
        legacyMasks.clear()
    }

    /** 被淘汰任务的值 → 占位符；调用方在后台状态输出时并入 [maskInjectedValues]。 */
    @Synchronized
    fun retainedRules(): Map<String, String> = legacyMasks.toMap()
}
