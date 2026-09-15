package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

/**
 * 凭证使用目的。
 *
 * 两个作用：
 * 1. 决定该场景是否需要**会话授权门**（[requiresAuthorization]）——按"AI 能否间接拿到值"分档，
 *    而非一刀切：把值注入子进程环境（AI 可读该环境）的风险，高于"值只在 App 进程内作请求头"。
 * 2. 决定审计记录的 action 名，保持各通道在审计里可区分。
 */
enum class CredentialPurpose(val action: String, val requiresAuthorization: Boolean) {
    /** 注入子进程环境变量（值会进入沙箱进程环境） */
    ENV_INJECT("env_inject", requiresAuthorization = true),

    /** 仅作 HTTP 请求头，AI 只见响应（值不出 App 进程） */
    HTTP_HEADER("http_exec", requiresAuthorization = false),

    /** SSH 认证（握手在 App 进程内完成） */
    SSH_AUTH("ssh_exec", requiresAuthorization = false),

    /** 部署 SSH 公钥到远端 */
    SSH_DEPLOY_KEY("deploy_key", requiresAuthorization = false),

    /** 本地 MCP 服务的鉴权令牌 */
    MCP_AUTH("mcp_auth", requiresAuthorization = false),

    /** 导出凭证包（明文或密文），必须过授权门 */
    EXPORT("export_env", requiresAuthorization = true),

    /** 后端通道桥接取用（SSH 隧道等），无 UI 会话授权门 */
    WEB_BRIDGE("web_bridge", requiresAuthorization = false),
}

/**
 * 解析结果：类型化，避免各调用方重复处理 null 语义（也避免"静默吞掉失败"）。
 */
sealed interface CredentialResolution {

    /** 成功：明文只交给调用方在内存中使用，用完即弃。 */
    data class Granted(val value: String, val entry: VaultCredentialEntity) : CredentialResolution

    /** 凭证不存在。 */
    data class Missing(val name: String) : CredentialResolution

    /** 未通过会话授权门。 */
    data class NotAuthorized(val name: String) : CredentialResolution

    /** 密文无法解密（密钥变更或密文损坏）。 */
    data class Undecryptable(val name: String) : CredentialResolution

    /** 工具层可直接展示的统一文案。 */
    val message: String
        get() = when (this) {
            is Granted -> ""
            is Missing -> "凭证不存在: $name（用 vault_credential_names 查看可用名称）"
            is NotAuthorized -> "凭证 $name 需要会话授权：请先完成凭证库授权（30 分钟或一直有效）"
            is Undecryptable -> "凭证解密失败: $name"
        }
}

/**
 * 凭证引用解析器（单点）。
 *
 * 所有需要凭证明文的地方都必须经此解析，以保证四项语义**只有一处实现**：
 * 存在性 → 会话授权（按 purpose 策略）→ 解密 → 审计（**成功与拒绝都记**）。
 *
 * 约定：
 * - [audit] = false 仅用于"内部分辨"（如连接前探测候选主机是否有可用凭证），
 *   避免探测噪音污染审计；真正取用时必须记。
 * - 返回的明文禁止落盘、禁止进日志。
 */
class CredentialResolver(
    private val repository: CredentialVaultRepository,
    /** 会话授权检查；null 表示该场景不启用授权门（无 UI 上下文或单测）。 */
    private val isAuthorized: (suspend () -> Boolean)? = null,
) {

    suspend fun resolve(
        name: String,
        purpose: CredentialPurpose,
        caller: String = "app",
        audit: Boolean = true,
    ): CredentialResolution {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CredentialResolution.Missing(name)

        if (purpose.requiresAuthorization && isAuthorized?.invoke() != true) {
            if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_denied")
            return CredentialResolution.NotAuthorized(trimmed)
        }

        val entry = repository.getByName(trimmed)
        if (entry == null) {
            if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_missing")
            return CredentialResolution.Missing(trimmed)
        }

        val value = repository.decryptValue(entry)
        if (value == null) {
            if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_undecryptable")
            return CredentialResolution.Undecryptable(trimmed)
        }

        if (audit) repository.logAccess(trimmed, caller, purpose.action)
        return CredentialResolution.Granted(value, entry)
    }
}
