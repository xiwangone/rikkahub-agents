package me.rerere.rikkahub.data.ai.tools.inject

/**
 * SSH 侧按 host 维度登记最近注入的凭证值，供 `ssh_job_poll` 等**后续独立调用**脱敏。
 *
 * 背景：`ssh_exec` 的注入值只在当次调用返回前掩码；后台任务的日志尾部
 * 由 `ssh_job_poll` 这个独立调用读回，拿不到当次注入记录——全局 SecretMasker 对短值和
 * `$$名字.字段` 的明文元数据仍有盲区。workspace 侧后台任务已有等价修复
 * （[BackgroundInjectedSecrets]，652671420），本对象把同一思路搬到 SSH 侧。
 *
 * 明文只活在内存里、生命周期与进程一致；按 host 淘汰最早的（上限 [MAX_HOSTS] 台主机），
 * 单机累计注入超过 [MAX_PER_HOST] 条时最早的并入 [legacyMasks] 永久掩码集，淘汰不丢脱敏能力。
 */
internal object SshInjectedSecrets {
    private const val MAX_HOSTS = 32
    private const val MAX_PER_HOST = 16
    private const val MAX_LEGACY_VALUES = 4096

    private val legacyMasks = LinkedHashMap<String, String>(16, 0.75f, false)

    private val byHost = object : LinkedHashMap<String, MutableList<InjectedSecret>>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<InjectedSecret>>?): Boolean {
            if (size <= MAX_HOSTS) return false
            eldest?.value?.forEach { retainLegacy(it) }
            return true
        }
    }

    /** 把淘汰出活动表的注入值并入永久掩码集（值 -> 占位符），口径与 [BackgroundInjectedSecrets] 一致。 */
    private fun retainLegacy(s: InjectedSecret) {
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

    /** 登记一次注入（同一 host 累计，去重；超上限的最早条目转永久掩码）。 */
    @Synchronized
    fun register(host: String, secrets: List<InjectedSecret>) {
        if (secrets.isEmpty() || host.isBlank()) return
        val list = byHost.getOrPut(host) { mutableListOf() }
        for (s in secrets) {
            if (list.none { it.ref == s.ref && it.value == s.value }) list.add(s)
        }
        while (list.size > MAX_PER_HOST) {
            retainLegacy(list.removeAt(0))
        }
    }

    /** 该 host 最近登记的注入值（供 poll 等后续调用脱敏）。 */
    @Synchronized
    fun get(host: String): List<InjectedSecret> = byHost[host].orEmpty()

    /** 被淘汰条目的永久掩码规则；调用方并入 [maskInjectedValues] 的 legacyRules。 */
    @Synchronized
    fun retainedRules(): Map<String, String> = legacyMasks.toMap()

    @Synchronized
    internal fun clearForTest() {
        byHost.clear()
        legacyMasks.clear()
    }
}
