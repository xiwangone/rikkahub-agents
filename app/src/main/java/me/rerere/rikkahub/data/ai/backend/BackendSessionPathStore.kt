package me.rerere.rikkahub.data.ai.backend

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.providers.backend.SessionPathStore
import me.rerere.rikkahub.utils.JsonInstant

/** 专用小 DataStore：只放「对话 id → 服务端会话路径」，与主设置存储隔离。 */
private val Context.backendSessionPathDataStore by
    preferencesDataStore(name = "backend_session_paths")

/**
 * [SessionPathStore] 的落盘实现：把「对话 id → 服务端会话路径」存进 DataStore。
 *
 * 存的是 JSON 映射（同 `conv_lifetime_usage` 的模式），并保留最近 [KEEP] 条，
 * 避免长期使用后无界增长（超出部分按插入顺序淘汰最旧的）。
 *
 * 为什么需要：内存映射会随进程消失 —— 没有它，App 重启后旧对话会退化成新建服务端会话，
 * 上下文接不上。
 */
class BackendSessionPathStore(
    private val appContext: Context,
) : SessionPathStore {

    private companion object {
        val KEY_SESSION_PATHS = stringPreferencesKey("paths")
        const val KEEP = 200
    }

    private val dataStore = appContext.backendSessionPathDataStore

    override suspend fun get(conversationId: String): String? =
        runCatching {
            val raw = dataStore.data.first()[KEY_SESSION_PATHS] ?: return null
            JsonInstant.decodeFromString<Map<String, String>>(raw)[conversationId]
        }.getOrNull()

    override suspend fun put(conversationId: String, path: String) {
        if (conversationId.isBlank() || path.isBlank()) return
        runCatching {
            dataStore.edit { preferences ->
                val current =
                    preferences[KEY_SESSION_PATHS]?.let {
                        runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrNull()
                    } ?: emptyMap()
                val updated =
                    LinkedHashMap(current).apply {
                        remove(conversationId)
                        put(conversationId, path)
                    }
                val trimmed =
                    if (updated.size <= KEEP) {
                        updated
                    } else {
                        LinkedHashMap(updated.entries.toList().takeLast(KEEP).associate { it.key to it.value })
                    }
                preferences[KEY_SESSION_PATHS] =
                    JsonInstant.encodeToString<Map<String, String>>(trimmed)
            }
        }
    }

    override suspend fun remove(conversationId: String) {
        if (conversationId.isBlank()) return
        runCatching {
            dataStore.edit { preferences ->
                val current =
                    preferences[KEY_SESSION_PATHS]?.let {
                        runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrNull()
                    } ?: return@edit
                if (!current.containsKey(conversationId)) return@edit
                preferences[KEY_SESSION_PATHS] =
                    JsonInstant.encodeToString<Map<String, String>>(
                        LinkedHashMap(current).apply { remove(conversationId) },
                    )
            }
        }
    }
}
