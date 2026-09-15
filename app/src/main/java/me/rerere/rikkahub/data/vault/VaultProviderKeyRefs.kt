package me.rerere.rikkahub.data.vault

import me.rerere.ai.util.ProviderKeyRefs

/**
 * 把凭证库接入 provider 的密钥引用（`$$凭证名`）。
 *
 * 背景：provider 的 apiKey 此前只能明文写在配置里；本类让它可以写成 `$$名字`，
 * 由 [ProviderKeyRefs] 在取用时替换为库内真值——**AI 与配置文件都不再持有明文**。
 *
 * 设计要点：
 * - **全量缓存 name → 明文**：与 `SecretMasker` 的规则缓存同源同量级（后者本就已把
 *   全库值解密进内存做脱敏），因此**不新增暴露面**，换来 `resolve` 可以同步返回
 *   （provider 取 key 的调用点是同步的）。
 * - **惰性生效**：只有真的写了 `$$引用` 才会用到缓存；不写引用时行为与从前一致。
 * - 刷新时机：App 启动、凭证变更（保存/删除/导入）、回填之后。
 */
object VaultProviderKeyRefs {

    /** 引用前缀：与 provider 密钥解析层一致。 */
    const val PREFIX = "\$\$"

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    /** 是否已注入（便于诊断）。 */
    @Volatile
    var installed: Boolean = false
        private set

    /**
     * 刷新缓存并注入钩子。
     *
     * @return 缓存的条目数（0 表示库为空或全部解密失败）。
     */
    suspend fun refresh(repository: CredentialVaultRepository): Int {
        val map = HashMap<String, String>()
        repository.getAll().forEach { entry ->
            repository.decryptValue(entry)?.let { map[entry.name] = it }
        }
        cache = map
        // 每次刷新都重新注入：避免进程早期（缓存为空）时留下"解析不到"的印象
        ProviderKeyRefs.resolve = { name -> cache[name] }
        installed = true
        return map.size
    }

    /**
     * 解析配置里的值：形如 `$$名字` 时替换为库内真值；**非引用或未命中原样返回**。
     *
     * 这是各配置读取点的统一入口（provider 的密钥轮询另有同源钩子）。
     * 同步实现：只读内存缓存，可在任意取值处直接调用。
     */
    fun resolveValue(raw: String): String {
        if (!raw.startsWith(PREFIX)) return raw
        return cache[raw.removePrefix(PREFIX)] ?: raw
    }

    /**
     * 增量更新单条（保存后调用）。
     *
     * 相比全量刷新，避免「改一条就解密整库」的无谓消耗；条目不存在或解密失败时按删除处理。
     */
    suspend fun updateOne(repository: CredentialVaultRepository, name: String) {
        val entry = repository.getByName(name)
        if (entry == null) {
            cache = cache - name
            return
        }
        val value = repository.decryptValue(entry)
        cache = if (value == null) cache - name else cache + (name to value)
        ProviderKeyRefs.resolve = { n -> cache[n] }
        installed = true
    }

    /** 增量删除单条（删除后调用）。 */
    fun removeOne(name: String) {
        cache = cache - name
    }

    /** 当前是否能解析某个引用名（只回答存在性，不返回值）。 */
    fun canResolve(name: String): Boolean = cache.containsKey(name)
}
