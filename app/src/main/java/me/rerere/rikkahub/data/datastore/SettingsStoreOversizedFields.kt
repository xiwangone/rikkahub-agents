package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 超大已存字段的列出与清除，挂在 [SettingsStore] 名下供安全模式调用。
 *
 * 单独成类是为了不让 SettingsStore 继续变大（detekt LargeClass 阈值 600 行）；
 * 需要的内部件（dataStore、transformLock、settingsFlowRaw、persistSettings、initialization）
 * 由 SettingsStore 构造时传入，本类不直接碰 SettingsStore 的私有成员。
 */
internal class OversizedStoredFields(
    private val dataStore: DataStore<Preferences>,
    private val transformLock: Mutex,
    private val settingsFlowRaw: Flow<Settings>,
    private val persistSettings: suspend (Settings) -> Unit,
    private val initialization: Deferred<Unit>,
) {
    /**
     * 订阅盘上超大字符串字段（默认 ≥ 1_000_000 字符），从大到小排序。
     * 只扫 String 值：超大 JSON 字段（如 lorebooks/assistants）是 OOM 循环闪退的常见来源，
     * 编码时把整个字符串拼进内存，超过堆上限就崩在启动解码里。
     */
    fun largeStoredFieldsFlow(minChars: Int = 1_000_000): Flow<List<SettingsStore.LargeStoredField>> =
        dataStore.data
            .map { preferences ->
                preferences.asMap().entries
                    .mapNotNull { (key, value) ->
                        val text = value as? String ?: return@mapNotNull null
                        if (text.length >= minChars) SettingsStore.LargeStoredField(key.name, text.length) else null
                    }
                    .sortedByDescending { it.charCount }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * 清除单个已存字段并恢复缺省值：先删盘上的键，再按缺失键重解一份全新 Settings
     * 写回内存。只删盘不写内存的话，下次 persistSettings 会把内存里的旧值写回去。
     */
    suspend fun resetStoredField(keyName: String) {
        initialization.await()
        transformLock.withLock {
            dataStore.edit { preferences ->
                preferences.remove(stringPreferencesKey(keyName))
            }
            val fresh = settingsFlowRaw.first()
            persistSettings(fresh)
        }
    }
}
