package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "SettingsDecode"

/**
 * 缺失值用 schema 默认值；**存在但非法**的值记告警并回退默认值。
 *
 * 此前对「存在但非法」是直接抛异常，导致单个坏键（如 mcp_servers 里的乱码）让整个设置流
 * 解析失败、启动进入崩溃循环，且用户进不去设置页自救。改为逐项容错后：坏值丢弃、其余照常。
 *
 * 回退只发生在**读取**路径，**不回写磁盘**（原始坏值保留，便于事后诊断）。
 */
internal inline fun <reified T> decodePersistedSetting(raw: String?, default: T): T {
    if (raw == null) return default
    return runCatching { JsonInstant.decodeFromString<T>(raw) }
        .onFailure { AppLog.e(TAG, "设置项解析失败，已回退默认值（原始坏值未覆盖，长度=${raw.length}）", it) }
        .getOrDefault(default)
}
