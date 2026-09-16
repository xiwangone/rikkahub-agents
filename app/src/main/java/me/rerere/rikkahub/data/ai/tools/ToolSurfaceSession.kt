package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.SharedPreferences
import me.rerere.rikkahub.data.log.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级按需 schema 状态：只保存已解锁的工具名，不保存参数或结果。
 *
 * **只增不减（前缀缓存约束）**：provider 走前缀缓存——工具 schema 一变，其后全部内容失效。
 * 因此同一会话内已解锁的工具**不再退回空 schema**：若切走会话后被清空，切回时的请求前缀
 * 会与已发出的历史不一致，导致长历史一次性全价重算，代价远超按需 schema 省下的几千 token。
 *
 * 只有两处会移除条目：
 * 1. 显式 [clear]——由调用方在会话被真正删除/重置时决定；
 * 2. 容量淘汰——条目数超过 [MAX_SESSIONS] 时按 LRU 淘汰最久未触碰的会话。
 *    这是必要的内存上限，触发需同时存在 256 个活跃会话，日常不可达。
 *
 * 状态同时落盘（SharedPreferences），使**进程重启后也不回退**——移动端进程被系统回收是常态。
 */
object ToolSurfaceSession {
    private const val TAG = "ToolSurfaceSession"
    private const val MAX_SESSIONS = 256
    private const val PREFS_NAME = "tool_surface_session"
    private const val KEY_PREFIX = "loaded_"
    private const val SEPARATOR = "\u0001"

    private data class Entry(val loaded: MutableSet<String>, var touchedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 进程启动时调用一次（须在任何工具注入之前）：恢复上次已解锁的工具名。
     * 幂等；未初始化时纯内存工作（单测无需 Context）。
     */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val now = System.currentTimeMillis()
            p.all.forEach { (key, value) ->
                if (!key.startsWith(KEY_PREFIX) || value !is String) return@forEach
                val conversationId = key.removePrefix(KEY_PREFIX)
                val loaded = value.split(SEPARATOR).filter { it.isNotBlank() }
                if (conversationId.isNotBlank() && loaded.isNotEmpty()) {
                    entries[conversationId] = Entry(loaded.toMutableSet(), now)
                }
            }
            trimIfNeeded()
        }.onFailure {
            AppLog.w(TAG, "init: restore from prefs failed", it)
        }
    }

    fun isLoaded(conversationId: String?, toolName: String): Boolean {
        if (conversationId.isNullOrBlank()) return true
        val entry = entries[conversationId] ?: return false
        synchronized(entry) {
            entry.touchedAtMs = System.currentTimeMillis()
            return toolName in entry.loaded
        }
    }

    fun markLoaded(conversationId: String?, toolName: String) {
        if (conversationId.isNullOrBlank()) return
        val entry =
            entries.computeIfAbsent(conversationId) {
                Entry(mutableSetOf(), System.currentTimeMillis())
            }
        synchronized(entry) {
            if (!entry.loaded.add(toolName)) return // 已解锁：无需重复落盘
            entry.touchedAtMs = System.currentTimeMillis()
            persist(conversationId, entry.loaded)
        }
        trimIfNeeded()
    }

    /** 显式清除单个会话（内存 + 持久化）。仅在会话被删除/重置时调用。 */
    fun clear(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        if (entries.remove(conversationId) != null) {
            prefs?.edit()?.remove(KEY_PREFIX + conversationId)?.apply()
        }
    }

    /**
     * 只清内存，**保留持久化**：用于进程内清理（如 ChatService.cleanup）。
     * 重启后仍可从 prefs 恢复，避免已解锁的冷档工具退回空 schema。
     */
    fun clearAllInMemory() {
        entries.clear()
    }

    private fun persist(conversationId: String, loaded: Set<String>) {
        val p = prefs ?: return
        runCatching {
            p.edit().putString(KEY_PREFIX + conversationId, loaded.joinToString(SEPARATOR)).apply()
        }.onFailure {
            AppLog.w(TAG, "persist failed: $conversationId", it)
        }
    }

    private fun trimIfNeeded() {
        while (entries.size > MAX_SESSIONS) {
            val oldest = entries.entries.minByOrNull { it.value.touchedAtMs } ?: return
            if (entries.remove(oldest.key, oldest.value)) {
                prefs?.edit()?.remove(KEY_PREFIX + oldest.key)?.apply()
                AppLog.d(TAG, "trim: evicted oldest session ${oldest.key}")
            }
        }
    }
}
