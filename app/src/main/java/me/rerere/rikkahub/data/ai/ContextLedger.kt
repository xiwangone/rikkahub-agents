package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker

/**
 * 每轮「常驻内容」的账本：记录系统提示词各段与工具描述的**字符量**，回答
 * 「每轮固定成本是多少、谁占大头」。
 *
 * 隐私与开销：只记**长度**与分项计数，**不保存任何文本内容**；开关复用工具统计开关
 * （[ToolUsageTracker.isStatsEnabled]，关 = 不采集不落盘）。
 */
@Serializable
data class ContextLedgerSnapshot(
    /** 助手提示词（或会话级覆盖）字符数。 */
    val assistantPrompt: Int = 0,
    /** 常驻记忆字符数。 */
    val memory: Int = 0,
    /** 最近会话参考字符数。 */
    val recentChats: Int = 0,
    /** 各工具 systemPrompt 的字符数合计。 */
    val toolPrompts: Int = 0,
    /** 本次调用追加的补充说明字符数。 */
    val addendum: Int = 0,
    /** 系统提示词的稳定段（缓存前缀）字符数。 */
    val stable: Int = 0,
    /** 系统提示词的易变段（缓存断点之后）字符数。 */
    val volatile: Int = 0,
    /** 本轮参与拼装的工具个数。 */
    val toolCount: Int = 0,
    val atMs: Long = 0,
) {
    val totalChars: Int get() = stable + volatile

    /** 字符 → token 的**粗估**（用字符数除以 3，与工具面报告保持同一口径，不声称精确）。 */
    val estTokens: Int get() = totalChars / 3
}

/**
 * 账本存储（prefs 单键）。与工具统计/运行归因同一个开关，避免又多一处开关。
 */
object ContextLedger {

    private const val PREFS = "context_ledger"
    private const val KEY_LAST = "last"

    private val ledgerJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastSnapshot: ContextLedgerSnapshot? = null

    @Volatile
    private var loadedLedger = false

    private fun isLedgerEnabled(): Boolean = ToolUsageTracker.isStatsEnabled()

    /** 记录本轮常驻内容构成。未开启统计时直接返回（零写入）。 */
    fun record(context: Context, snapshot: ContextLedgerSnapshot) {
        if (!isLedgerEnabled()) return
        lastSnapshot = snapshot
        loadedLedger = true
        runCatching {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST, ledgerJson.encodeToString(snapshot))
                .apply()
        }
    }

    /** 最近一次记录（进程内缓存优先，其次读盘）。 */
    fun last(context: Context): ContextLedgerSnapshot? {
        lastSnapshot?.let { return it }
        if (!loadedLedger) {
            val raw =
                runCatching {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
                }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching { lastSnapshot = ledgerJson.decodeFromString<ContextLedgerSnapshot>(raw) }
            }
            loadedLedger = true
        }
        return lastSnapshot
    }

    fun clear(context: Context) {
        lastSnapshot = null
        loadedLedger = true
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        }
    }
}
