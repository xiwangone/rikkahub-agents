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

    /** 本地签名/加解密取用（GPG 等，值只在 App 进程内使用，不出进程、无授权门） */
    LOCAL_USE("local_use", requiresAuthorization = false),
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

    /**
     * 字段引用（`$$名字.字段`）取不到值：该条目没有这个明文字段，或该字段不允许引用。
     *
     * **不与"字段不存在"区分对外文案**——能分辨两者就等于给了一个"这条凭证里藏了什么"的
     * 探测面（拿 `$$X.a` 与 `$$X.b` 的结果差即可枚举）。
     */
    data class MissingField(val name: String, val field: String) : CredentialResolution

    /** 工具层可直接展示的统一文案。 */
    val message: String
        get() = when (this) {
            is Granted -> ""
            is Missing -> "凭证不存在: $name（用 vault_credential_names 查看可用名称）"
            is NotAuthorized -> "凭证 $name 需要会话授权：请先完成凭证库授权（30 分钟或一直有效）"
            is Undecryptable -> "凭证解密失败: $name"
            is MissingField ->
                "凭证 $name 没有可引用的明文字段「$field」：" +
                    "\$\$名字 取主值，\$\$名字.字段 只支持明文元数据键（endpoint/access_key_id/region/custom.* 等）"
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

    /**
     * 解析**引用串本体**（已去掉 `$$` 前缀）：`名字` 取主值、`名字.字段` 取明文元数据字段。
     *
     * 与 [resolve] 的分工：
     * - 不含 `.` 的引用**原样转给 [resolve]**，主值路径的授权门 / 解密 / 审计一字未动
     *   —— 现存所有引用走的就是这条路；
     * - 含 `.` 时**先按"整个串就是凭证名"查一次库**，命中仍按主值取（历史脏数据兜底：
     *   名字校验收紧前入库的带点名字，行为不能从"有值"变成"null"）；查不到才走字段路径。
     *
     * 字段路径**不过会话授权门**：这些键本来就是明文，且 `vault_credential_meta` 早已无门打印
     * 全部元数据（`VaultTools.kt` 的元数据段）。给字段单独加门只会自相矛盾——同一条信息，
     * 用工具读不要授权、用引用要。审计仍照记（`<action>_field` / `<action>_field_missing`），
     * 且**只记凭证名，不记字段值**。
     */
    suspend fun resolveRef(
        rawRef: String,
        purpose: CredentialPurpose,
        caller: String = "app",
        audit: Boolean = true,
    ): CredentialResolution {
        val whole = rawRef.trim()
        val ref = parseVaultRef(whole)
        if (ref.field == null) return resolve(ref.name, purpose, caller, audit)
        // 整名优先：带点的串若能对上库里某条名字，按老语义取主值
        if (whole.isNotEmpty() && repository.getByName(whole) != null) {
            return resolve(whole, purpose, caller, audit)
        }
        if (ref.name.isEmpty()) return CredentialResolution.Missing(whole)

        val resolution = fieldResolution(repository.getByName(ref.name), ref.name, ref.field)
        if (audit) {
            val action = if (resolution is CredentialResolution.Granted) {
                "${purpose.action}_field"
            } else {
                "${purpose.action}_field_missing"
            }
            repository.logAccess(ref.name, caller, action)
        }
        return resolution
    }

    companion object {
        /**
         * 字段引用的取值判定：**不碰库、不过门、不写审计**，只做"这条目的这个字段能不能引用"。
         *
         * 单测钉住的就是这一处（凭证库带 DataStore/Room 依赖，测试里构造不出来，也没装 mockk）。
         */
        internal fun fieldResolution(
            entry: VaultCredentialEntity?,
            name: String,
            field: String,
        ): CredentialResolution {
            if (entry == null) return CredentialResolution.Missing(name)
            val value = CredentialMeta.resolveField(CredentialMeta.decode(entry.metaJson), field)
                ?: return CredentialResolution.MissingField(name, field)
            return CredentialResolution.Granted(value, entry)
        }
    }
}
