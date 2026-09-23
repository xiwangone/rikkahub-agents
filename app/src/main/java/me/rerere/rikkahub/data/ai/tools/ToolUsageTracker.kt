package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地工具调用统计。
 *
 * 用途：为「工具面分档/合并」提供数据（哪些高频、哪些从未使用）。
 * 隐私：只记录工具名、次数、失败数、累计耗时与最近调用时间，**不记录任何参数**。
 * 存储：SharedPreferences 中的单个 JSON 字符串，随时可清空。
 */
// 纯 prefs 读写聚合点：按 key 拆成多个对象反而割裂（调用方要在多处注入）；函数数量在此豁免。
@Suppress("TooManyFunctions")
object ToolUsageTracker {
    private const val PREFS = "tool_usage_stats"
    private const val KEY = "entries"
private const val KEY_INJECTED = "injected_tool_names"

/** 冷档解锁次数（`get_tool_schema` 命中 COLD 工具）。 */
private const val KEY_UNLOCKS = "unlock_counts"

/** 上一次的注入集哈希 + 累计变化次数。 */
private const val KEY_SURFACE = "surface_hash_last"
private const val KEY_SURFACE_CHANGES = "surface_hash_changes"

    @Serializable
    data class Entry(
        val name: String,
        val count: Long = 0,
        val failures: Long = 0,
        val totalMs: Long = 0,
        val lastUsedAt: Long = 0,
    ) {
        val avgMs: Long get() = if (count > 0) totalMs / count else 0
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var loaded = false

    private val loadLock = Any()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val raw =
                runCatching {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching {
                    json.decodeFromString<List<Entry>>(raw).forEach { entry -> cache[entry.name] = entry }
                }
            }
            loaded = true
        }
    }

    /** 记录一次调用（失败需由调用方标记）。 */
    fun record(
        context: Context,
        name: String,
        durationMs: Long,
        failed: Boolean,
    ) {
        ensureLoaded(context)
        cache.compute(name) { _, old ->
            val base = old ?: Entry(name = name)
            base.copy(
                count = base.count + 1,
                failures = base.failures + if (failed) 1 else 0,
                totalMs = base.totalMs + durationMs.coerceAtLeast(0),
                lastUsedAt = System.currentTimeMillis(),
            )
        }
        persist(context)
    }

    private fun persist(context: Context) {
        val snapshot = cache.values.sortedByDescending { it.count }
        runCatching {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, json.encodeToString(snapshot))
                .apply()
        }
    }

    /** 记录最近一次注入给模型的工具名集合（用于识别"已启用但从未调用"）。 */
    fun recordInjected(
        context: Context,
        names: List<String>,
    ) {
        runCatching {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_INJECTED, json.encodeToString(names.sorted()))
                .apply()
        }
    }

    /** 最近一次注入的工具名集合；从未记录过则返回空集。 */
    fun injectedNames(context: Context): Set<String> =
        runCatching {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_INJECTED, null)
                ?.let { json.decodeFromString<List<String>>(it) }
                ?.toSet()
        }.getOrNull().orEmpty()

    /** 全量快照（按调用次数降序）。 */
    fun snapshot(context: Context): List<Entry> {
        ensureLoaded(context)
        return cache.values.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.name })
    }

    /**
     * 记录一次「冷档解锁」（`get_tool_schema` 命中 COLD 工具时调用）。
     *
     * 用途：与调用次数对照，判断降温名单配得对不对 ——
     * 解锁后真调用（名单留着）/ 解锁了却从不用（降档降对了，甚至可再降）。
     */
    fun recordUnlock(context: Context, name: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val counts = decodeUnlocks(prefs.getString(KEY_UNLOCKS, null)).toMutableMap()
            counts[name] = (counts[name] ?: 0) + 1
            prefs.edit().putString(KEY_UNLOCKS, json.encodeToString(counts)).apply()
        }
    }

    /** 各工具的冷档解锁次数。 */
    fun unlockCounts(context: Context): Map<String, Int> =
        runCatching {
            decodeUnlocks(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNLOCKS, null),
            )
        }.getOrNull().orEmpty()

    private fun decodeUnlocks(raw: String?): Map<String, Int> =
        if (raw.isNullOrBlank()) emptyMap() else json.decodeFromString<Map<String, Int>>(raw)

    /** 注入集哈希的变化记录（用于解释“为什么这一轮的前缀缓存失效了”）。 */
    data class SurfaceChange(
        val previous: String?,
        val current: String,
        val changed: Boolean,
        val totalChanges: Long,
    )

    /** 记录本次注入集哈希，返回与上次的对比。写入失败返回 null（不干扰主流程）。 */
    fun recordSurfaceHash(
        context: Context,
        hash: String,
    ): SurfaceChange? =
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getString(KEY_SURFACE, null)
            val changed = previous != null && previous != hash
            val total = prefs.getLong(KEY_SURFACE_CHANGES, 0L) + if (changed) 1L else 0L
            prefs
                .edit()
                .putString(KEY_SURFACE, hash)
                .putLong(KEY_SURFACE_CHANGES, total)
                .apply()
            SurfaceChange(previous, hash, changed, total)
        }.getOrNull()

    /** 当前记录的注入集哈希与累计变化次数（只读，不写入）。 */
    fun surfaceHashState(context: Context): Pair<String, Long>? =
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hash = prefs.getString(KEY_SURFACE, null) ?: return@runCatching null
            hash to prefs.getLong(KEY_SURFACE_CHANGES, 0L)
        }.getOrNull()

    fun clear(context: Context) {
        synchronized(loadLock) {
            cache.clear()
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            }
            loaded = true
        }
    }
}
