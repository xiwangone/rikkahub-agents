package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/** 目标系统类型：AUTO（自动探测）/ LINUX / WINDOWS，空与未知按 AUTO 处理。 */
@Serializable
enum class SshPlatform(val preferenceName: String) {
    AUTO("AUTO"),
    LINUX("LINUX"),
    WINDOWS("WINDOWS");

    companion object {
        fun fromPreference(value: String?): SshPlatform = entries.firstOrNull { it.preferenceName == value } ?: AUTO
    }
}

