package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A saved SSH host the LLM (or user) can reference by name.
 *
 * Secrets (password, privateKey, passphrase) are stored in plaintext in Room. This is the
 * same posture as the rest of the app's stored credentials (provider API keys, etc.).
 * Encryption-at-rest via Android Keystore would be a future hardening.
 */
@Serializable
@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    /** Display name; also the lookup key from the LLM. */
    @PrimaryKey val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    /** 引用 Vault 密钥凭证名（连接时从 Vault 读私钥，不明文存 Room） */
    val vaultCredentialRef: String? = null,
    /** 来源服务器样板名（可选，用于追溯） */
    val templateRef: String? = null,
    /** 备用主机名列表（JSON 数组字符串，如 ["host-b","host-c"]）。主 host 连不上时按序尝试 */
    val fallbackHostsJson: String? = null,
    /** 跳板主机名（saved host 名）。非空时先连跳板再经它连目标（ProxyJump 语义，单层） */
    val jumpHost: String? = null,
    /** 自定义 ssh 选项，每行一个 "键 值"（如 "ConnectTimeout 5"），透传 JSch setConfig */
    val sshOptions: String? = null,
    /** 目标系统类型：AUTO（自动探测）/ LINUX / WINDOWS，空与未知按 AUTO 处理 */
    @ColumnInfo(defaultValue = "AUTO") val platform: String = "AUTO",
    /** 是否允许该主机密钥轮换（默认不允许；开启后密钥变化时重新信任） */
    @ColumnInfo(defaultValue = "0") val allowHostKeyRotation: Boolean = false,
    /** OpenSSH 用户证书文本（`-cert.pub` 文件内容）；公开材料，明文存放 */
    val certificate: String? = null,
    /** 引用 Vault 凭证名（连接时从 Vault 读证书文本，不明文存 Room）；有值时优先于 certificate */
    val vaultCertificateRef: String? = null,
    /**
     * 代理命令（本地拉起做传输，`%h`/`%p` 展开为目标主机/端口，如
     * `cloudflared access ssh --hostname %h`）。只允许设置页 UI 填写，
     * 模型建主机工具不得设置。
     */
    val proxyCommand: String? = null,
    val createdAtMs: Long,
)
