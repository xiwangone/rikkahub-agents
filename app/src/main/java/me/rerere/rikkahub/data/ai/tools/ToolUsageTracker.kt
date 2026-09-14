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
object ToolUsageTracker {
    private const val PREFS = "tool_usage_stats"
    private const val KEY = "entries"
private const val KEY_INJECTED = "injected_tool_names"

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
