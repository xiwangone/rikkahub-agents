package me.rerere.rikkahub.data.vault

import me.rerere.ai.util.ProviderKeyRefs
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * - **同一份缓存也带字段键 `名字.字段`**（值 = 该条目的明文元数据，规则见
 *   [CredentialMeta.resolveField]）：这条同步路径覆盖了 provider 密钥轮询、MCP 出站头、
 *   S3 / WebDAV / Telegram 代理 / Web 服务口令等取值点，字段引用只有进缓存才能在这些位置通用；
 *   顺带让引用位 UI 的"引用已失效"判定（`SecretRefField.canResolve`）无需改动就认得字段引用。
 * - 刷新时机：App 启动、凭证变更（保存/删除/导入）、回填之后。
 */
/**
 * 注：本对象聚合「引用缓存的建 / 查 / 清 / 失效」这一组按职责不可再分的操作，
 * 函数数已超 detekt 默认阈值（处置与仓内 ToolErrors / ToolUsageTracker / WebDavSync 一致）。
 */
@Suppress("TooManyFunctions")
object VaultProviderKeyRefs {

    /** 引用前缀：与 provider 密钥解析层一致。 */
    const val PREFIX = "\$\$"

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    private val _revision = MutableStateFlow(0L)
    /** Changes whenever the in-memory credential cache is refreshed, updated, or pruned. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun signalCacheChanged() {
        _revision.update { it + 1L }
    }

    /**
     * provider 取用命中的引用名（只入队，不落库）。
     *
     * provider 取 key 是同步热路径 → 这里只记录名字；真正的审计写在生成链路
     * （`ProviderKeyAudit.flush`），因为只有那里知道归属（会话/模型/助手）且有 suspend 写库能力。
     */
    private val expandedHits = ConcurrentLinkedQueue<String>()

    /** 是否已注入（便于诊断）。 */
    @Volatile
    var installed: Boolean = false
        private set

    /**
     * 刷新缓存并注入钩子。
     *
     * @return 缓存的**主值**条目数（0 表示库为空或全部解密失败；不含 `名字.字段` 的字段键）。
     */
    suspend fun refresh(repository: CredentialVaultRepository): Int {
        val entries = repository.getAll()
        val map = buildCache(entries) { repository.decryptValue(it) }
        cache = map
        signalCacheChanged()
        // 每次刷新都重新注入：避免进程早期（缓存为空）时留下"解析不到"的印象
        installHooks()
        return map.keys.count { !it.contains('.') }
    }

    /** 装配两个钩子：解析器 + 展开通知（后者只入队，供生成链路写审计）。 */
    private fun installHooks() {
        ProviderKeyRefs.resolve = { name -> cache[name] }
        ProviderKeyRefs.onExpanded = { name -> recordExpanded(name) }
        installed = true
    }

    /** 展开回调体（同步、轻量）：同轮去重 + 上限保护，避免高频轮询把审计表刷满。（internal 以便单测） */
    internal fun recordExpanded(name: String) {
        if (expandedHits.size >= MAX_HITS_PER_REQUEST) return
        if (!expandedHits.contains(name)) expandedHits.add(name)
    }

    /** 取走并清空本次请求期间命中的引用名（由审计写入点调用）。 */
    internal fun drainExpanded(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val next = expandedHits.poll() ?: break
            out.add(next)
        }
        return out
    }

    /** 清零点：丢弃遗留命中，保证归属不串到下一次请求（异常/取消路径可能留下残留）。 */
    internal fun resetExpanded() {
        expandedHits.clear()
    }

    /**
     * 解析配置里的值：形如 `$$名字`（或 `$$名字.字段`）时替换为库内真值；**非引用或未命中原样返回**。
     *
     * 这是各配置读取点的统一入口（provider 的密钥轮询另有同源钩子）。
     * 同步实现：只读内存缓存，可在任意取值处直接调用。
     */
    fun resolveValue(raw: String): String {
        if (!raw.startsWith(PREFIX)) return raw
        return cache[raw.removePrefix(PREFIX)] ?: raw
    }

    /**
     * 解析引用但**不降级**：非引用返回原值，引用未命中返回 null。
     *
     * 与 [resolveValue] 的差别：后者解析不到会原样返回 `$$名字` 字面量（provider 密钥链路依赖此行为），
     * 而“要么用真值、要么明确失败”的位置（如调试接口口令）必须用本函数，
     * 否则条目名写错时字面量会被当成口令，调用方与用户都无从发现。
     */
    fun resolveOrNull(raw: String): String? {
        if (!raw.startsWith(PREFIX)) return raw
        return cache[raw.removePrefix(PREFIX)]
    }

    /**
     * 增量更新单条（保存后调用）。
     *
     * 相比全量刷新，避免「改一条就解密整库」的无谓消耗；条目不存在或解密失败时按删除处理。
     * 删除/替换都按**整个条目**算：`名字` 与它所有 `名字.字段` 一起清，否则元数据改空后
     * 旧字段值会留在缓存里继续被引用到。
     */
    suspend fun updateOne(repository: CredentialVaultRepository, name: String) {
        val entry = repository.getByName(name)
        if (entry == null) {
            cache = withoutEntry(cache, name)
            signalCacheChanged()
            return
        }
        val fresh = buildCache(listOf(entry)) { repository.decryptValue(it) }
        val others = withoutEntry(cache, name)
        // 整名优先：本条目自己的键之外，不覆盖别的名占用的键（历史带点名字场景）
        cache = others + fresh.filterKeys { it !in others }
        signalCacheChanged()
        installHooks()
    }

    /** 增量删除单条（删除后调用）。 */
    fun removeOne(name: String) {
        cache = withoutEntry(cache, name)
        signalCacheChanged()
    }

    /** 当前是否能解析某个引用（`名字` 或 `名字.字段`；只回答存在性，不返回值）。 */
    fun canResolve(name: String): Boolean = cache.containsKey(name)

    /**
     * 建缓存：主值键 `名字` + 字段键 `名字.字段`。
     *
     * 两轮放入有先后：**主值先、字段后且只补空位**。凭证名受 `NAME_REGEX` 约束永不含 `.`，
     * 两类键在合规库里不可能相撞；仍按 putIfAbsent 兜住历史带点名字 ——
     * 与解析层「整名优先」是同一条规则的两处落点。
     *
     * 抽成纯函数（entries + 解密函数入参）是为了能在单测里直接比对建键结果：
     * 本对象绑 Room/DataStore，测试环境构造不出来。
     */
    internal fun buildCache(
        entries: List<VaultCredentialEntity>,
        decrypt: (VaultCredentialEntity) -> String?,
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        entries.forEach { entry -> decrypt(entry)?.let { map[entry.name] = it } }
        entries.forEach { entry ->
            CredentialMeta.referenceableFields(entry.metaJson).forEach { (field, value) ->
                map.putIfAbsent("${entry.name}.$field", value)
            }
        }
        return map
    }

    /** 摘掉某条目的全部键（主值 + 它的所有字段）。 */
    private fun withoutEntry(map: Map<String, String>, name: String): Map<String, String> =
        map.filterKeys { it != name && !it.startsWith("$name.") }

    /** 单次请求最多记这么多条命中（超出丢弃）。（internal 以便单测） */
    internal const val MAX_HITS_PER_REQUEST = 16
}
