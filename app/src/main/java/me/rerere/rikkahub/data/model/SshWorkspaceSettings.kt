package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey

/**
 * SSH 工作区的持久化配置, 以 JSON 存在 workspaces.ssh_config 列。
 *
 * 数据库会随备份导出, 所以这里**一个凭证明文都不放**: 密码/私钥/私钥口令只存
 * `$$凭证名` 形式的[凭证库引用][me.rerere.rikkahub.data.vault.VAULT_REF_PREFIX],
 * 真值留在凭证库, 建连时经 CredentialResolver 取用。
 * 换设备恢复备份后只要把同名凭证导入凭证库即可, 不需要重填凭据。
 */
@Serializable
data class SshWorkspaceSettings(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    /** 远程工作目录, 绝对路径 */
    val remoteDir: String,
    /** 用户确认过的主机公钥 */
    val hostKey: SshHostKey? = null,
    /** 密码凭证条目 (`$$NAME`) */
    val passwordRef: String? = null,
    /** 私钥凭证条目 (`$$NAME`) */
    val privateKeyRef: String? = null,
    /** 私钥口令凭证条目 (`$$NAME`), 无口令时为空 */
    val passphraseRef: String? = null,
)

/**
 * SSH 工作区编辑表单。凭据栏填的是**凭证库条目名**, 不是凭据本身;
 * 编辑已有工作区时留空表示沿用已保存的条目名。
 */
data class SshWorkspaceDraft(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val passwordRef: String = "",
    val privateKeyRef: String = "",
    val passphraseRef: String = "",
    val remoteDir: String,
    val hostKey: SshHostKey? = null,
)
