package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 后端连接（通用化——reasonix/SSH/自定义统一模型）。
 *
 * 2026-08-14：reasonix 从「模型提供商」改为「后端连接」——可保存/切换/删除。
 * 对话页 executionBackend 引用 [id]；local 为内置默认。
 */
@Serializable
data class BackendConnection(
    val id: String,
    val name: String,
    /** 类型：reasonix / ssh / custom */
    val type: String,
    /** 连接地址（reasonix baseUrl / SSH host:port / 自定义 endpoint） */
    val endpoint: String = "",
    /** 附加配置（reasonix 的 username/password/token/connectionMode 等——JSON 字符串） */
    val extra: String = "",
    /** Vault 凭证引用（不存明文） */
    val authRef: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

object BackendTypes {
    const val LOCAL = "local"
    const val REASONIX = "reasonix"
    const val SSH = "ssh"
    const val CUSTOM = "custom"
}
