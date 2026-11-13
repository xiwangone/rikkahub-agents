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
 * 首次连接主机探针结果缓存，与 [RemotePlatformStore] 同一个 DataStore 模式：
 * app SSH 探出来的结果，远程工作区能直接用，反之亦然；两者读写同一份缓存，不各写一份。
 *
 * 键：探针缓存的键含登录用户名（[RemotePlatform.cacheKey]，host:port:user；同一主机的
 * shell/工具因用户而异）。平台缓存（banner 结论与登录用户无关）仍用 host:port 键，
 * 两处键格式不再相同是有意的，见 [RemotePlatform.cacheKey]。
 * 读时先查 user 键、再回退旧 host:port 键（老版本写下的条目仍可读；写只走 user 键）。
 *
 * 过期：成功结果用 [SshHostProbeResult.probedAtMs] 判过期，TTL [POSITIVE_TTL_MS]（30 天；
 * 主机重装会换系统摘要，30 天是“缓存仍有用”与“摘要不过时太久”的折中）。过期视作未命中重探。
 *
 * 否定缓存：探针失败（路由器/交换机/ForceCommand/受限 shell 拿不到 END 标记等）也写一条
 * 带时间戳的否定记录，TTL [NEGATIVE_TTL_MS]（24 小时；失败多为远端限制而非瞬时网络，
 * 24 小时内自动入口不再重探，手动入口仍可重跑）。成功解析会覆盖掉同一键的否定记录。
 * 否定记录与成功结果分开存（不借用 SshHostProbeResult 的 platform 字段做标记，
 * 避免消费者把失败标记误读成平台结论）。
 *
 * 保留最近 100 条防无界增长（与 RemotePlatformStore 同口径）。
 * 探针缓存与平台缓存是两份数据：平台缓存（banner 结论）由 execOneShot 每次建连回填；
 * 探针缓存只在显式入口（测试连接）或 AUTO 模式的进工作区自动补一次时写入。
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
    private val failuresKey = stringPreferencesKey("probe_failures")

    /** 成功结果：user 键优先，旧 host:port 键回退；过期视作未命中。 */
    suspend fun get(host: String, port: Int, user: String? = null): SshHostProbeResult? {
        val map = readAll()
        val now = System.currentTimeMillis()
        for (key in probeKeys(host, port, user)) {
            val result = map[key] ?: continue
            if (isFresh(result, now)) return result
        }
        return null
    }

    suspend fun put(host: String, port: Int, result: SshHostProbeResult, user: String? = null) {
        val key = RemotePlatform.cacheKey(host, port, user)
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
            // 成功解析覆盖同一键的否定记录（下次不再因旧失败跳过）。
            val failures = readFailures().toMutableMap()
            if (failures.remove(key) != null) {
                prefs[failuresKey] = JsonInstant.encodeToString(
                    MapSerializer(String.serializer(), Long.serializer()),
                    failures,
                )
            }
        }
    }

    /** 探针失败也记一条带时间戳的否定记录（24h 内自动入口不再重探，手动入口不受影响）。 */
    suspend fun putFailure(
        host: String,
        port: Int,
        user: String? = null,
        failedAtMs: Long = System.currentTimeMillis(),
    ) {
        val key = RemotePlatform.cacheKey(host, port, user)
        context.hostProbeDataStore.edit { prefs ->
            val failures = readFailures().toMutableMap()
            failures.remove(key)
            failures[key] = failedAtMs
            val now = System.currentTimeMillis()
            val trimmed = failures.entries
                .filter { now - it.value <= NEGATIVE_TTL_MS }
                .toList().takeLast(KEEP)
                .associate { it.key to it.value }
            prefs[failuresKey] = JsonInstant.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                trimmed,
            )
        }
    }

    /** 否定缓存是否命中（TTL 内才算命中；过期视作未命中）。 */
    suspend fun isNegativeCached(host: String, port: Int, user: String? = null): Boolean {
        val failedAt = readFailures()[RemotePlatform.cacheKey(host, port, user)] ?: return false
        return System.currentTimeMillis() - failedAt <= NEGATIVE_TTL_MS
    }

    /** 清掉指定主机的探针缓存（测试连接每次重跑会直接覆盖，无需先清）。同时清掉否定记录，下次不再跳过。 */
    suspend fun clear(host: String, port: Int, user: String? = null) {
        val keys = probeKeys(host, port, user)
        context.hostProbeDataStore.edit { prefs ->
            val map = readAll().toMutableMap()
            keys.forEach { map.remove(it) }
            prefs[probesKey] = JsonInstant.encodeToString(
                MapSerializer(String.serializer(), SshHostProbeResult.serializer()),
                map,
            )
            val failures = readFailures().toMutableMap()
            var changed = false
            keys.forEach { if (failures.remove(it) != null) changed = true }
            if (changed) {
                prefs[failuresKey] = JsonInstant.encodeToString(
                    MapSerializer(String.serializer(), Long.serializer()),
                    failures,
                )
            }
        }
    }

    private fun probeKeys(host: String, port: Int, user: String?): List<String> {
        val keyed = RemotePlatform.cacheKey(host, port, user)
        val legacy = RemotePlatform.cacheKey(host, port)
        return if (keyed == legacy) listOf(legacy) else listOf(keyed, legacy)
    }

    private fun isFresh(result: SshHostProbeResult, nowMs: Long): Boolean =
        result.probedAtMs > 0 && nowMs - result.probedAtMs <= POSITIVE_TTL_MS

    private suspend fun readAll(): Map<String, SshHostProbeResult> {
        val raw = context.hostProbeDataStore.data.map { it[probesKey] }.first() ?: return emptyMap()
        return runCatching {
            JsonInstant.decodeFromString(
                MapSerializer(String.serializer(), SshHostProbeResult.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    private suspend fun readFailures(): Map<String, Long> {
        val raw = context.hostProbeDataStore.data.map { it[failuresKey] }.first() ?: return emptyMap()
        return runCatching {
            JsonInstant.decodeFromString(
                MapSerializer(String.serializer(), Long.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val KEEP = 100
        /** 成功结果 TTL：30 天（主机重装会换系统摘要，30 天是缓存收益与摘要时效的折中）。 */
        internal const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        /** 否定记录 TTL：24 小时（失败多为远端限制而非瞬时网络，24 小时内不再自动重探）。 */
        internal const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * 平台决议的缓存读数统一入口（两边共用）：探针结果优先于 banner 缓存。
 * 探针跑出来的平台是真实执行的结果，不一致时以探针为准；无探针结果时回退 banner 缓存。
 */
internal suspend fun probePreferredWindows(context: Context, host: String, port: Int, user: String? = null): Boolean? =
    SshHostProbeStore(context).get(host, port, user)?.isWindows
        ?: RemotePlatformStore(context).get(host, port)
