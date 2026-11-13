package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.ssh.SshHostProbeResult

/**
 * 首次连接主机探针结果缓存，与 [RemotePlatformStore] 同一个 DataStore 模式、
 * 同一个 host:port 键（[RemotePlatform.cacheKey]）：app SSH 探出来的结果，
 * 远程工作区能直接用，反之亦然；两者读写同一份缓存，不各写一份。
 *
 * 保留最近 100 条防无界增长（与 RemotePlatformStore 同口径）。
 * 探针缓存与平台缓存是两份数据：平台缓存（banner 结论）由 execOneShot 每次建连回填；
 * 探针缓存只在首次连接（无缓存时）跑探针后写入，或测试连接时手动刷新覆盖。
 * 平台决议读探针缓存优先（探针是真实执行，比 banner 字符串更可靠），见
 * [probePreferredWindows]。
 *
 * ⚠ delegate 必须声明在**顶层**（与 [RemotePlatformStore] 的 remotePlatformDataStore 一致）：
 * 写在类体里的话，每 new 一个 SshHostProbeStore 都会新建一个 DataStore 实例，
 * 第二个实例构造时即抛 “multiple DataStores active for the same file”。
 */
private val Context.hostProbeDataStore: DataStore<Preferences> by preferencesDataStore(name = "host_probes")

class SshHostProbeStore(private val context: Context) {
    private val probesKey = stringPreferencesKey("probes")

    suspend fun get(host: String, port: Int): SshHostProbeResult? =
        readAll()[RemotePlatform.cacheKey(host, port)]

    suspend fun put(host: String, port: Int, result: SshHostProbeResult) {
        val key = RemotePlatform.cacheKey(host, port)
        context.hostProbeDataStore.edit { prefs ->
            val map = readAll().toMutableMap()
            map.remove(key)
            map[key] = result
            val trimmed = LinkedHashMap<String, SshHostProbeResult>()
            map.entries.toList().takeLast(KEEP).forEach { trimmed[it.key] = it.value }
            prefs[probesKey] = JsonInstant.encodeToString(
                MapSerializer(String.serializer(), SshHostProbeResult.serializer()),
                trimmed,
            )
        }
    }

    /** 清掉某台主机的探针缓存（供“重新探测”入口调用；测试连接每次重跑会直接覆盖，无需先清）。 */
    suspend fun clear(host: String, port: Int) {
        val key = RemotePlatform.cacheKey(host, port)
        context.hostProbeDataStore.edit { prefs ->
            val map = readAll().toMutableMap()
            map.remove(key)
            prefs[probesKey] = JsonInstant.encodeToString(
                MapSerializer(String.serializer(), SshHostProbeResult.serializer()),
                map,
            )
        }
    }

    private suspend fun readAll(): Map<String, SshHostProbeResult> {
        val raw = context.hostProbeDataStore.data.map { it[probesKey] }.first() ?: return emptyMap()
        return runCatching {
            JsonInstant.decodeFromString(
                MapSerializer(String.serializer(), SshHostProbeResult.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val KEEP = 100
    }
}

/**
 * 平台决议的缓存读数统一入口（两边共用）：探针结果优先于 banner 缓存。
 * 探针跑出来的平台是真实执行的结果，不一致时以探针为准；无探针结果时回退 banner 缓存。
 */
internal suspend fun probePreferredWindows(context: Context, host: String, port: Int): Boolean? =
    SshHostProbeStore(context).get(host, port)?.isWindows
        ?: RemotePlatformStore(context).get(host, port)
