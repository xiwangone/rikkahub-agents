package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 单次生成的**结果归因**：这一次生成到底是怎么结束的（正常 / 用户停 / 超时 / 限流 / …）。
 *
 * 用途：把「运行状态」变成可查、可统计的数据 —— 失败率按模型、打断来源分布、单轮耗时与 token。
 * 边界：这里**只采数据**，不做任何结论（性格/适用场景的判断由 AI 侧进行）。
 * 隐私：只记枚举、id 与计数，**不含消息内容、提示词或工具参数**。
 */
enum class GenerationOutcome {
    /** 正常结束（拿到 finishReason）。 */
    COMPLETED,

    /** 用户主动结束：停止按钮 / 发新消息打断 / 审批取消。 */
    USER_CANCELLED,

    /** 单轮墙钟预算耗尽，被强制收尾。 */
    TIMEOUT,

    /** 反复触发循环保护（模型打转），被强制收尾。 */
    LOOP_GUARD,

    /** token 预算越过硬上限（助手级 soft/hard cap），被强制收尾。 */
    TOKEN_BUDGET,

    /** 网络 / 传输层失败。 */
    NETWORK_ERROR,

    /** 服务端限流。 */
    RATE_LIMIT,

    /** 其它接口错误（鉴权 / 额度 / 内容安全…）——明细保留在 [GenerationRun.errorKind]。 */
    API_ERROR,

    /** 无法归因（例如进程被杀）。 */
    UNKNOWN,
}

/**
 * 归因上下文的可变载体（每次 generateText 一份）。
 *
 * 生命周期：在生成开始时创建，循环体内更新，收尾时读取 —— 之所以用可变载体而不是返回值，
 * 是因为生成有多个出口（正常 break / 墙钟 / 循环保护 / 异常 / 取消），只有收尾钩子能一次看全。
 */
class GenerationRunContext(val startedAtMs: Long) {
    /** 循环体内显式的中止原因（墙钟耗尽 / 循环保护）。 */
    var abortReason: GenerationOutcome? = null

    /** 最后一次拿到的结束原因（服务端给的 finishReason）。 */
    var finishReason: String? = null

    /** 本轮实际走的 step 数。 */
    var steps: Int = 0

    var promptTokens: Int = 0
    var completionTokens: Int = 0
    var cachedTokens: Int = 0

    /** 花在模型请求上的墙钟（含其内部重试）。 */
    var modelMs: Long = 0

    /** 花在工具执行上的墙钟（只统计已批准并真正执行的工具）。 */
    var toolMs: Long = 0

    /** 本轮累计费用（USD）：各步 `usage.cost` 求和；只有 provider 上报时才有值。 */
    var cost: Double = 0.0

    /** 是否有任意一步上报了费用（用于区分"上报为 0"与"未上报"）。 */
    var costReported: Boolean = false
}

/** 一条生成记录（不含任何内容）。 */
@Serializable
data class GenerationRun(
    val ts: Long,
    val conversationId: String? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val assistantId: String? = null,
    val outcome: String,
    val finishReason: String? = null,
    /** 失败细分（复用 [FailureKind] 的名字），如 `NETWORK` / `RATE_LIMIT` / `AUTH`。 */
    val errorKind: String? = null,
    val durationMs: Long = 0,
    val steps: Int = 0,
    /** 其中：模型请求耗时（含内部重试）。 */
    val modelMs: Long = 0,
    /** 其中：工具执行耗时。 */
    val toolMs: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** 本轮费用（USD）：provider 上报时累加各步 `usage.cost`；null = 未上报。 */
    val cost: Double? = null,
    /** 落盘快照：provider 注册键（如 `openai`），防 provider 改名/删除后历史对不上。 */
    val providerKey: String? = null,
    /** 落盘快照：provider 显示名。 */
    val providerName: String? = null,
    /** 落盘快照：模型显示名。 */
    val modelDisplayName: String? = null,
    /** 预留：reasoning token 数（P128 欠账；解码器补齐后自然有数，默认 0）。 */
    val reasoningTokens: Long = 0,
)

/** provider × 模型的交叉聚合（key = `"$providerKey/$modelId"`）。 */
@Serializable
data class ProviderModelStats(
    val runs: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** 累计费用（USD），只含上报了费用的生成。 */
    val cost: Double = 0.0,
    /** 上报了费用的生成数（0 = 该组合费用"未上报"，不按 token 估算）。 */
    val costReportedRuns: Long = 0,
    /** 落盘快照：provider 显示名（provider 删除后仍可显示）。 */
    val providerName: String? = null,
    /** 落盘快照：模型显示名。 */
    val modelDisplayName: String? = null,
)

/** 单日分桶（key = `yyyy-MM-dd`，设备本地时区）。 */
@Serializable
data class DayStats(
    val runs: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cost: Double = 0.0,
    val byProviderModel: Map<String, ProviderModelStats> = emptyMap(),
)

/** 用量统计的时间范围。 */
enum class UsageRange {
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    ALL_TIME,
}

/** 某时间范围的聚合结果（UI / AI 侧共用）。 */
data class RangeUsageStats(
    val runs: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cost: Double = 0.0,
    val costReportedRuns: Long = 0,
    val byProviderModel: Map<String, ProviderModelStats> = emptyMap(),
)

/** 时间戳 → 日分桶 key（设备本地时区，`yyyy-MM-dd` 可做字符串比较）。 */
internal fun dayKeyOf(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** 滚动清理：只保留最近 [keepDays] 天的分桶（含今天）。 */
internal fun pruneDaily(
    daily: Map<String, DayStats>,
    today: LocalDate = LocalDate.now(),
    keepDays: Long = GenerationRunTracker.MAX_DAILY_BUCKETS.toLong(),
): Map<String, DayStats> {
    val cutoff = today.minusDays(keepDays - 1).toString()
    return daily.filterKeys { it >= cutoff }
}

/** 合并两份 [ProviderModelStats]（纯函数：聚合更新与时间范围合并共用）。 */
internal fun mergeProviderModelStats(
    base: ProviderModelStats?,
    add: ProviderModelStats,
): ProviderModelStats =
    ProviderModelStats(
        runs = (base?.runs ?: 0) + add.runs,
        promptTokens = (base?.promptTokens ?: 0) + add.promptTokens,
        completionTokens = (base?.completionTokens ?: 0) + add.completionTokens,
        cachedTokens = (base?.cachedTokens ?: 0) + add.cachedTokens,
        cost = (base?.cost ?: 0.0) + add.cost,
        costReportedRuns = (base?.costReportedRuns ?: 0) + add.costReportedRuns,
        providerName = add.providerName ?: base?.providerName,
        modelDisplayName = add.modelDisplayName ?: base?.modelDisplayName,
    )

/**
 * 归因判定（**纯函数**，便于单测）：优先级 = 循环内显式中止标记 > 未正常结束的异常 > 正常完成。
 *
 * @param abortReason 循环体内显式设置的中止原因（墙钟耗尽 / 循环保护），最可信。
 * @param cancelSource 取消标记的来源（说明是用户/系统主动停），仅对 [CancellationException] 有效。
 * @param rawError 原始错误文本（用于复用 [classifyFailureKind] 的判据）。
 */
fun classifyGenerationOutcome(
    cause: Throwable?,
    abortReason: GenerationOutcome?,
    cancelSource: String?,
    rawError: String = "",
): GenerationOutcome {
    if (abortReason != null) return abortReason
    if (cause == null) return GenerationOutcome.COMPLETED
    if (cause is CancellationException) {
        return if (cancelSource != null) GenerationOutcome.USER_CANCELLED else GenerationOutcome.UNKNOWN
    }
    return when (classifyFailureKind(cause, rawError)) {
        FailureKind.NETWORK -> GenerationOutcome.NETWORK_ERROR
        FailureKind.RATE_LIMIT -> GenerationOutcome.RATE_LIMIT
        else -> GenerationOutcome.API_ERROR
    }
}

/**
 * 运行归因的聚合存储（prefs JSON，不占数据库）。
 *
 * 开关：复用工具统计开关 [ToolUsageTracker.isStatsEnabled]（**单一开关源**，关 = 不采集不落盘）。
 * 存储：聚合计数 + 最近 [MAX_RECENT] 条明细；卸载即失（与工具统计口径一致，不进备份）。
 */
// 纯 prefs 读写聚合点：按 key 拆开反而割裂调用方；函数数在此豁免。
@Suppress("TooManyFunctions")
object GenerationRunTracker {

    private const val PREFS = "generation_runs"
    private const val KEY_STATE = "state"

    /** 最近明细的保留条数：够看「刚才几轮发生了什么」，又不至于让 prefs 膨胀。 */
    const val MAX_RECENT = 200

    /** 取消标记的有效期：超过这个窗口的标记不再采信（避免陈旧标记把后来的取消误判成用户停）。 */
    private const val CANCEL_MARK_TTL_MS = 30_000L

    @Serializable
    data class State(
        val totals: Map<String, Long> = emptyMap(),
        val byModel: Map<String, Map<String, Long>> = emptyMap(),
        val totalDurationMs: Long = 0,
        /** 累计：模型请求耗时 / 工具执行耗时（用于耗时分解）。 */
        val totalModelMs: Long = 0,
        val totalToolMs: Long = 0,
        val totalPromptTokens: Long = 0,
        val totalCompletionTokens: Long = 0,
        val totalCachedTokens: Long = 0,
        /** provider × 模型交叉聚合（key = `"$providerKey/$modelId"`）。 */
        val byProviderModel: Map<String, ProviderModelStats> = emptyMap(),
        /** 累计费用（USD），只含上报了费用的生成。 */
        val totalCost: Double = 0.0,
        /** 按日分桶（key = `yyyy-MM-dd`，设备本地时区），滚动保留 [MAX_DAILY_BUCKETS] 天。 */
        val daily: Map<String, DayStats> = emptyMap(),
        val recent: List<GenerationRun> = emptyList(),
    ) {
        /**
         * 取某时间范围的聚合（纯函数，便于单测与 UI/AI 侧共用）。
         *
         * 口径：**按生成**（一次 `generateText` 为一条），与 [record] 一致；
         * 不与"按请求"口径（`PreferencesStore.accumulateConvUsage`）混用。
         */
        fun rangeStats(range: UsageRange, today: LocalDate = LocalDate.now()): RangeUsageStats {
            if (range == UsageRange.ALL_TIME) {
                return RangeUsageStats(
                    runs = totals.values.sum(),
                    promptTokens = totalPromptTokens,
                    completionTokens = totalCompletionTokens,
                    cachedTokens = totalCachedTokens,
                    cost = totalCost,
                    costReportedRuns = byProviderModel.values.sumOf { it.costReportedRuns },
                    byProviderModel = byProviderModel,
                )
            }
            val days =
                when (range) {
                    UsageRange.TODAY -> 1
                    UsageRange.LAST_7_DAYS -> 7
                    UsageRange.LAST_30_DAYS -> 30
                    else -> 1
                }
            val keys = (0 until days).map { today.minusDays(it.toLong()).toString() }.toSet()
            val buckets = daily.filterKeys { it in keys }.values
            val merged = mutableMapOf<String, ProviderModelStats>()
            buckets.forEach { day ->
                day.byProviderModel.forEach { (key, stats) ->
                    merged[key] = mergeProviderModelStats(merged[key], stats)
                }
            }
            return RangeUsageStats(
                runs = buckets.sumOf { it.runs },
                promptTokens = buckets.sumOf { it.promptTokens },
                completionTokens = buckets.sumOf { it.completionTokens },
                cachedTokens = buckets.sumOf { it.cachedTokens },
                cost = buckets.sumOf { it.cost },
                costReportedRuns = merged.values.sumOf { it.costReportedRuns },
                byProviderModel = merged,
            )
        }
    }

    /** 按日分桶的保留天数（含今天）。 */
    const val MAX_DAILY_BUCKETS = 90

    // 私有成员统一带领域前缀：既表明用途，也避免与别处同名局部变量撞名（结构自检按名字判跨文件引用会误报）。
    private val runJson = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    // @Volatile：收尾协程写、诊断读（跨线程）——不加会读到陈旧快照
    @Volatile
    private var loaded = false

    @Volatile
    private var runState: State = State()

    /** 取消标记（内存态，不落盘）：conversationId → (来源, 时间戳)。 */
    private val cancelMarks = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun isRunStatsEnabled(): Boolean = ToolUsageTracker.isStatsEnabled()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val raw =
                runCatching {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)
                }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching { runState = runJson.decodeFromString<State>(raw) }
            }
            loaded = true
        }
    }

    /** 标记「这次取消是主动的」（用户停止 / 发新消息打断 / 审批取消）。 */
    fun markCancelled(conversationId: String?, source: String) {
        if (conversationId == null) return
        cancelMarks[conversationId] = source to System.currentTimeMillis()
    }

    /** 取用并清除取消标记（一次性）；过期视为没有。 */
    fun consumeCancellation(conversationId: String?): String? {
        if (conversationId == null) return null
        val mark = cancelMarks.remove(conversationId) ?: return null
        val (source, at) = mark
        return if (System.currentTimeMillis() - at <= CANCEL_MARK_TTL_MS) source else null
    }

    /** 记录一次生成结果。未开启统计时直接返回（零写入）。 */
    fun record(context: Context, run: GenerationRun) {
        if (!isRunStatsEnabled()) return
        // 口径统一收在数据层：缓存命中量不可能超过输入量（个别服务端会给出越界值），
        // 与既有会话累计一致（`PreferencesStore.accumulateConvUsage` 同样夹过这一步）。
        val entry = if (run.cachedTokens > run.promptTokens) run.copy(cachedTokens = run.promptTokens) else run
        ensureLoaded(context)
        synchronized(lock) {
            val totals = runState.totals + (entry.outcome to (runState.totals[entry.outcome] ?: 0L) + 1L)
            val modelKey = entry.modelId ?: "unknown"
            val perModel = runState.byModel[modelKey].orEmpty()
            val byModel =
                runState.byModel + (modelKey to (perModel + (entry.outcome to (perModel[entry.outcome] ?: 0L) + 1L)))
            // provider × 模型交叉聚合：费用只累加上报了的部分，未上报的不估算
            val pmKey = "${entry.providerKey ?: "unknown"}/$modelKey"
            val pmDelta =
                ProviderModelStats(
                    runs = 1,
                    promptTokens = entry.promptTokens,
                    completionTokens = entry.completionTokens,
                    cachedTokens = entry.cachedTokens,
                    cost = entry.cost ?: 0.0,
                    costReportedRuns = if (entry.cost != null) 1 else 0,
                    providerName = entry.providerName,
                    modelDisplayName = entry.modelDisplayName,
                )
            val byProviderModel = runState.byProviderModel + (pmKey to mergeProviderModelStats(runState.byProviderModel[pmKey], pmDelta))
            // 按日分桶（设备本地时区）+ 滚动保留
            val dayKey = dayKeyOf(entry.ts)
            val day = runState.daily[dayKey]
            val newDay =
                DayStats(
                    runs = (day?.runs ?: 0) + 1,
                    promptTokens = (day?.promptTokens ?: 0) + entry.promptTokens,
                    completionTokens = (day?.completionTokens ?: 0) + entry.completionTokens,
                    cachedTokens = (day?.cachedTokens ?: 0) + entry.cachedTokens,
                    cost = (day?.cost ?: 0.0) + (entry.cost ?: 0.0),
                    byProviderModel = (day?.byProviderModel.orEmpty()) + (pmKey to mergeProviderModelStats(day?.byProviderModel?.get(pmKey), pmDelta)),
                )
            val daily = pruneDaily(runState.daily + (dayKey to newDay))
            runState =
                runState.copy(
                    totals = totals,
                    byModel = byModel,
                    byProviderModel = byProviderModel,
                    totalCost = runState.totalCost + (entry.cost ?: 0.0),
                    daily = daily,
                    totalDurationMs = runState.totalDurationMs + entry.durationMs.coerceAtLeast(0),
                    totalModelMs = runState.totalModelMs + entry.modelMs.coerceAtLeast(0),
                    totalToolMs = runState.totalToolMs + entry.toolMs.coerceAtLeast(0),
                    totalPromptTokens = runState.totalPromptTokens + entry.promptTokens,
                    totalCompletionTokens = runState.totalCompletionTokens + entry.completionTokens,
                    totalCachedTokens = runState.totalCachedTokens + entry.cachedTokens,
                    recent = (runState.recent + entry).takeLast(MAX_RECENT),
                )
            persist(context)
        }
    }

    private fun persist(context: Context) {
        val payload = runJson.encodeToString(runState)
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, payload).apply()
        }
    }

    /** 只读快照（不改任何计数）。 */
    fun snapshot(context: Context): State {
        ensureLoaded(context)
        return runState
    }

    fun clear(context: Context) {
        synchronized(lock) {
            runState = State()
            loaded = true
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_STATE).apply()
            }
        }
    }
}
