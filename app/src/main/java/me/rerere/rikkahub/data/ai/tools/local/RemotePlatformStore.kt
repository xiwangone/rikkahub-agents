package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.utils.JsonInstant

/** 专用小 DataStore：只放「host:port → 远端是否 Windows」，与主设置存储隔离。 */
private val Context.remotePlatformDataStore by
    preferencesDataStore(name = "remote_platform")

/**
 * 远端平台的**落盘缓存**（来源 = SSH banner 判定）。
 *
 * 为什么需要：命令包装发生在**建连之前**（`execOneShot` 收到的是已包装好的命令），那一刻
 * 读不到 banner → 首次只能用命令特征兜底。把连接时读到的 banner 结论落盘，**下一次调用
 * 即可用上权威判据**（同一主机反复使用时收益最明显）。
 *
 * 存 JSON 映射（与 `backend_session_paths` 同模式），保留最近 [KEEP] 条防无界增长。
 */
class RemotePlatformStore(
    private val appContext: Context,
) {

    private companion object {
        val KEY_PLATFORMS = stringPreferencesKey("platforms")
        const val KEEP = 100
        const val WINDOWS = "windows"
    }

    private val dataStore = appContext.remotePlatformDataStore

    /** 查询已知平台；未记录 / 读失败返回 null（调用方回退命令特征判据）。 */
    suspend fun get(host: String, port: Int): Boolean? =
        runCatching {
            val raw = dataStore.data.first()[KEY_PLATFORMS] ?: return null
            JsonInstant.decodeFromString<Map<String, String>>(raw)[RemotePlatform.cacheKey(host, port)]
                ?.let { it == WINDOWS }
        }.getOrNull()

    /** 记录一条判定；[isWindows] 为 null（banner 判不出来）时**不写**，避免污染缓存。 */
    suspend fun put(host: String, port: Int, isWindows: Boolean?) {
        if (isWindows == null) return
        runCatching {
            dataStore.edit { preferences ->
                val current =
                    preferences[KEY_PLATFORMS]?.let {
                        runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrNull()
                    } ?: emptyMap()
                val key = RemotePlatform.cacheKey(host, port)
                val updated =
                    LinkedHashMap(current).apply {
                        remove(key)
                        put(key, if (isWindows) WINDOWS else "posix")
                    }
                val trimmed =
                    if (updated.size <= KEEP) {
                        updated
                    } else {
                        LinkedHashMap(updated.entries.toList().takeLast(KEEP).associate { it.key to it.value })
                    }
                preferences[KEY_PLATFORMS] = JsonInstant.encodeToString<Map<String, String>>(trimmed)
            }
        }
    }
}
