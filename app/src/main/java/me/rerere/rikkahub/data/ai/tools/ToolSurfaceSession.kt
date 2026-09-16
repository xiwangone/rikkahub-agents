package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap

/** 会话级按需 schema 状态：只保存已解锁工具名，不保存参数或结果。 */
object ToolSurfaceSession {
    private const val MAX_SESSIONS = 256
    private const val TTL_MS = 6 * 60 * 60 * 1_000L

    private data class Entry(val loaded: MutableSet<String>, var touchedAtMs: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun isLoaded(conversationId: String?, toolName: String): Boolean {
        if (conversationId.isNullOrBlank()) return true
        val entry = entries[conversationId] ?: return false
        val now = System.currentTimeMillis()
        synchronized(entry) {
            if (now - entry.touchedAtMs > TTL_MS) {
                entries.remove(conversationId, entry)
                return false
            }
            entry.touchedAtMs = now
            return toolName in entry.loaded
        }
    }

    fun markLoaded(conversationId: String?, toolName: String) {
        if (conversationId.isNullOrBlank()) return
        val entry = entries.computeIfAbsent(conversationId) {
            Entry(mutableSetOf(), System.currentTimeMillis())
        }
        synchronized(entry) {
            entry.loaded += toolName
            entry.touchedAtMs = System.currentTimeMillis()
        }
        trimIfNeeded()
    }

    fun clear(conversationId: String?) {
        if (!conversationId.isNullOrBlank()) entries.remove(conversationId)
    }

    fun clearAll() {
        entries.clear()
    }

    private fun trimIfNeeded() {
        if (entries.size <= MAX_SESSIONS) return
        entries.entries.minByOrNull { it.value.touchedAtMs }?.let { entries.remove(it.key, it.value) }
    }
}
