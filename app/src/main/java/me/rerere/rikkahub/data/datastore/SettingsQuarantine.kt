package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "SettingsQuarantine"

/** 恢复快照目录：隔离时把解码前的原始 prefs 原样拷贝一份，供人工恢复。 */
private const val RECOVERY_DIR_NAME = "settings-recovery"

/** 快照只留最近 5 份，避免坏字段反复触发时无界增长。 */
private const val MAX_SNAPSHOTS_KEPT = 5

/** 单个快照值最多记这么多字符，超大字段只留头部（仍可定位是哪个字段坏了）。 */
private const val MAX_SNAPSHOT_VALUE_CHARS = 65_536

/** 逐字段隔离的上限：超过这么多个坏字段就不再逐个试，直接按原来 fail-loud 处理。 */
private const val MAX_QUARANTINED_FIELDS = 8

/**
 * 永不隔离的字段：值在落盘时走加密的键。
 *
 * 解密失败可能是暂时的（key 不可用、key 错了），密文本身可能完好、可恢复；把这类字段
 * 按缺失处理（走默认值），随后一次写盘就会用默认值覆盖掉尚可恢复的密文——这正是
 * `MediaGenerationSettingsPersistenceTest` 钉死的 destructive default fallback。
 * 因此探针跳过这些键：它们坏了就按原来 fail-loud 处理（初始化失败、停在 warm seed，
 * 更新与替换一律拒绝），而不是隔离。
 * 清单口径 = 解码体里走 `decryptPrefValue` / `decodeEncryptedPrefOrDefault` /
 * `decodeProvidersFieldEncrypted` 的键（见 PreferencesStore 解码体）；新增加密字段时
 * 同步加进这里，否则它会默认可隔离。
 */
private val QUARANTINE_EXCLUDED_KEYS: Set<Preferences.Key<*>> =
    setOf(
        SettingsStore.MEDIA_GENERATION_PROVIDERS,
        SettingsStore.PROVIDERS,
        SettingsStore.NETWORK_SETTING,
        SettingsStore.SEARCH_SERVICES,
        SettingsStore.MCP_SERVERS,
        SettingsStore.WEBDAV_CONFIG,
        SettingsStore.S3_CONFIG,
        SettingsStore.UPLOAD_S3_CONFIG,
        SettingsStore.WEBDAV_CONFIGS,
        SettingsStore.S3_CONFIGS,
        SettingsStore.TTS_PROVIDERS,
        SettingsStore.ASR_PROVIDERS,
        SettingsStore.WEB_BRIDGE_PASSWORD,
        SettingsStore.WEB_SERVER_ACCESS_PASSWORD,
    )

/** 一个被隔离的损坏字段：键名 + 解码失败原因 + 隔离时间。 */
data class QuarantinedField(
    val keyName: String,
    val error: String,
    val quarantinedAtMs: Long,
)

/**
 * 隔离状态的持有者：放在 SettingsStore 外，避免它继续变大（detekt LargeClass）。
 * SettingsStore 只留一个实例与几行委托。
 */
internal class SettingsQuarantineState(
    private val context: Context,
) {
    private val _quarantinedFields = MutableStateFlow<List<QuarantinedField>>(emptyList())

    /** 本次启动里被隔离的损坏字段；为空表示没有。 */
    val quarantinedFields: StateFlow<List<QuarantinedField>> = _quarantinedFields.asStateFlow()

    private val _quarantineSnapshot = MutableStateFlow<File?>(null)

    /** 隔离时写下的原始值快照；没有隔离过时为 null。 */
    val quarantineSnapshot: StateFlow<File?> = _quarantineSnapshot.asStateFlow()

    /** 字段清掉后（见 [SettingsStore.resetStoredField]），同步去掉它的隔离记录，横幅随即消失。 */
    fun clearQuarantinedField(keyName: String) {
        _quarantinedFields.value = _quarantinedFields.value.filterNot { it.keyName == keyName }
    }

    /**
     * 解码整份设置；单个字段损坏时不让整个加载失败。
     *
     * 正常情况直接返回 [decode] 的结果；单个（或少数几个）已存字段损坏时，先把原始 prefs
     * 快照到恢复目录，再逐个试出坏字段（拿掉它就解得出来、或失败原因变了），坏字段按缺失
     * 处理（走 schema 默认值），其余字段照常解码。隔离后初始化仍能成功，App 用真实设置
     * （坏字段为默认值）运行，而不是停在 warm seed 上。
     *
     * fail-loud 语义保留在三处：[decodePersistedSetting] 本身仍对损坏的 present 值抛错；
     * 坏字段太多、或失败与任何单个字段无关时，这里原样重抛，调用方按原来的降级路径处理；
     * 解密类字段（[QUARANTINE_EXCLUDED_KEYS]）一律不隔离：解密失败可能是暂时的，
     * 密文本身可能可恢复，默认回退 + 随后写盘会销毁它。
     */
    fun <T> decodeOrQuarantine(
        preferences: Preferences,
        decode: (Preferences) -> T,
    ): T {
        val result =
            runCatching { decode(preferences) }.fold(
                onSuccess = { QuarantinedDecode(it, emptyList(), null) },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    quarantineAfterFailure(context, preferences, decode, failure)
                },
            )
        if (result.quarantined.isNotEmpty()) {
            _quarantinedFields.value = result.quarantined
            _quarantineSnapshot.value = result.snapshotFile
            AppLog.e(
                TAG,
                "Settings decode quarantined fields: ${result.quarantined.map { it.keyName }}; " +
                    "snapshot=${result.snapshotFile?.path}",
            )
        }
        return result.value
    }
}

/** 一次隔离解码的结果：解码出的设置 + 被隔离的字段 + 原始值快照（未隔离时为空 / null）。 */
internal data class QuarantinedDecode<T>(
    val value: T,
    val quarantined: List<QuarantinedField>,
    val snapshotFile: File?,
)

private fun <T> quarantineAfterFailure(
    context: Context,
    preferences: Preferences,
    decode: (Preferences) -> T,
    firstFailure: Throwable,
): QuarantinedDecode<T> {
    val snapshot = snapshotRawPreferences(context, preferences)
    // toMutablePreferences 是 Preferences 接口的成员函数（DataStore 1.3），不用 import。
    val remaining = preferences.toMutablePreferences()
    val quarantined = mutableListOf<QuarantinedField>()
    var lastFailure: Throwable = firstFailure
    while (quarantined.size < MAX_QUARANTINED_FIELDS) {
        runCatching { decode(remaining) }.fold(
            onSuccess = { return QuarantinedDecode(it, quarantined, snapshot) },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                lastFailure = failure
                val badKey = findImplicatedKey(remaining, decode, failure) ?: throw failure
                remaining.remove(badKey)
                quarantined +=
                    QuarantinedField(
                        keyName = badKey.name,
                        error = failure.toString(),
                        quarantinedAtMs = System.currentTimeMillis(),
                    )
            },
        )
    }
    throw lastFailure
}

/**
 * 在 [preferences] 里找出导致解码失败的那个键：逐个拿掉重解，
 * 拿掉后能解出来、或失败原因变了（解码按固定顺序进行，先坏的字段先抛错），
 * 就说明这个键是坏的。返回 null 表示失败与任何单个字段无关。
 * 加密类字段（[QUARANTINE_EXCLUDED_KEYS]）不参与探针：它们坏了必须 fail-loud。
 */
private fun <T> findImplicatedKey(
    preferences: Preferences,
    decode: (Preferences) -> T,
    baselineFailure: Throwable,
): Preferences.Key<*>? {
    val baseline = baselineFailure.toString()
    for (key in preferences.asMap().keys) {
        if (key in QUARANTINE_EXCLUDED_KEYS) continue
        val trial = preferences.toMutablePreferences().apply { remove(key) }
        val attempt = runCatching { decode(trial) }
        if (attempt.isSuccess) return key
        val trialFailure = attempt.exceptionOrNull() ?: continue
        if (trialFailure is CancellationException) throw trialFailure
        if (trialFailure.toString() != baseline) return key
    }
    return null
}

/**
 * 把解码前的原始 prefs 原样拷贝到恢复目录（`filesDir/settings-recovery/`），
 * 供人工恢复。只做最佳努力：快照失败绝不能再挡住解码本身。
 */
internal fun snapshotRawPreferences(context: Context, preferences: Preferences): File? =
    runCatching {
        val dir = File(context.filesDir, RECOVERY_DIR_NAME).apply { mkdirs() }
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_SNAPSHOTS_KEPT - 1)
            ?.forEach { it.delete() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).format(LocalDateTime.now())
        val file = File(dir, "settings-$stamp.json")
        val entries =
            preferences.asMap().entries.joinToString(",\n") { (key, value) ->
                val raw =
                    when (value) {
                        is String -> value.take(MAX_SNAPSHOT_VALUE_CHARS)
                        else -> value.toString()
                    }
                "\"${key.name}\": ${JsonInstant.encodeToString(raw)}"
            }
        file.writeText("{\n$entries\n}")
        file
    }.onFailure {
        AppLog.e(TAG, "Failed to snapshot raw settings for quarantine", it)
    }.getOrNull()
