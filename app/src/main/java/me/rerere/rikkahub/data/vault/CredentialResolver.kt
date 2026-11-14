package me.rerere.rikkahub.data.vault

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.log.AppLog

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

    /** 仅作 HTTP 请求头，AI 只见响应（值不出 App 进程）
     *  ⚠ 仍需会话授权：值不出进程不等于“不要紧”——AI 能借此代用户对任意服务发起请求，
     *  风险面在“凭据会被怎么用”，不在“值会不会被读走”。 */
    HTTP_HEADER("http_exec", requiresAuthorization = true),

    /** SSH 认证（握手在 App 进程内完成）
     *  ⚠ 仍需会话授权：同理，可通过 vault_ssh_exec 在任意已配置主机上执行命令。 */
    SSH_AUTH("ssh_exec", requiresAuthorization = true),

    /** 部署 SSH 公钥到远端
     *  ⚠ 仍需会话授权：往远端 authorized_keys 追加公钥 = 授予一个新登录身份，属授权变更。 */
    SSH_DEPLOY_KEY("deploy_key", requiresAuthorization = true),

    /** 本地 MCP 服务的鉴权令牌 */
    MCP_AUTH("mcp_auth", requiresAuthorization = false),

    /** 导出凭证包（明文或密文），必须过授权门 */
    EXPORT("export_env", requiresAuthorization = true),

    /** 后端通道桥接取用（SSH 隧道等），无 UI 会话授权门 */
    WEB_BRIDGE("web_bridge", requiresAuthorization = false),

    /** 本地签名/加解密取用（GPG 等，值只在 App 进程内使用）
     *  ⚠ 仍需会话授权：不解锁就能无限次用私钥签名/解密 = 一个“签名预言机”，
     *  值虽不出进程，但签名/解密结果会交给 AI。 */
    LOCAL_USE("local_use", requiresAuthorization = true),
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
     * 目的地绑定 / 类型校验拒绝（P0-2）：凭证存在、授权通过、能解密，但本次使用
     * 不在凭证允许的范围内（目的地不在绑定的 endpoint 内，或类型与用途不匹配）。
     * [reason] 是可直接展示的拒绝原因。
     */
    data class Rejected(val name: String, val reason: String) : CredentialResolution

    /**
     * 字段引用（`$$名字.字段`）取不到值：该条目没有这个明文字段，或该字段不允许引用。
     *
     * **不与"字段不存在"区分对外文案**——能分辨两者就等于给了一个"这条凭证里藏了什么"的
     * 探测面（拿 `$$X.a` 与 `$$X.b` 的结果差即可枚举）。
     */
    data class MissingField(val name: String, val field: String) : CredentialResolution

}

/**
 * 工具层可直接展示的统一文案。
 *
 * 写成扩展属性而非接口成员（在接口内定义该属性无法通过本工程工具链的编译）。
 * 语义与原来完全一致，调用方写法不变（`resolution.message`）。
 */
val CredentialResolution.message: String
    get() = when (this) {
        is CredentialResolution.Granted -> ""
        is CredentialResolution.Missing -> "Credential does not exist: $name (use vault_credential_names to list available names)"
        is CredentialResolution.NotAuthorized -> "Credential $name needs session authorization: complete vault authorization first (30 minutes or always valid)"
        is CredentialResolution.Undecryptable -> "Credential decryption failed: $name"
        is CredentialResolution.Rejected -> reason
        is CredentialResolution.MissingField ->
            "Credential $name has no referable plaintext field \"$field\":" +
                "\$\$name takes the main value; \$\$name.field only supports plaintext metadata keys (endpoint/access_key_id/region/custom.* etc.)"
    }

/**
 * 凭证引用解析器（单点）。
 *
 * 所有需要凭证明文的地方都必须经此解析，以保证以下语义**只有一处实现**：
 * 存在性 → 会话授权（按 purpose 策略）→ 解密 → 目的地绑定 / 类型校验
 * （仅传 `destination` + `use` 的调用方，如 vault_ssh_exec / vault_http_exec）→ 审计（**成功与拒绝都记**）。
 *
 * 约定：
 * - [audit] = false 仅用于"内部分辨"（如连接前探测候选主机是否有可用凭证），
 *   避免探测噪音污染审计；真正取用时必须记。
 * - 返回的明文禁止落盘、禁止进日志。
 * - 库读失败不向上传：授权检查 / 凭证查找读 Room / DataStore 时抛错，
 *   按"解析不到"处理（授权失败 → [CredentialResolution.NotAuthorized]，
 *   查找失败 → [CredentialResolution.Missing]），审计写失败不影响本次
 *   解析结果；真实原因记一条警告日志。取消（[CancellationException]）
 *   原样上抛，不当作解析失败。
 */
class CredentialResolver(
    private val repository: CredentialVaultRepository,
    /**
     * 会话授权检查；按凭证名过滤（见 [VaultSessionManager.hasActiveAuthorization]）。
     * null 表示**拒绝**（fail-closed）：调用方必须显式传入检查——无 UI 上下文或单测
     * 场景下传 null 意味着"该场景不启用授权门"已不再成立；df5ad9c71 把 SSH_AUTH /
     * HTTP_HEADER / SSH_DEPLOY_KEY / LOCAL_USE 改为必须授权后，未传检查的解析一律
     * 返回 NotAuthorized（而不是静默放行）。不要把 null 读成"不检查"。
     */
    private val isAuthorized: (suspend (String) -> Boolean)? = null,
) {

    /**
     * 库读守卫：Room / DataStore 出错时返回 null（调用方按"解析不到"处理），
     * 异常只记一条警告日志。取消（[CancellationException]）必须原样上抛，
     * 不能把协程取消吞成"解析不到"。
     */
    private suspend fun <T> safeVaultRead(what: String, block: suspend () -> T): T? =
        runCatching { block() }
            .onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.w("CredentialResolver", "$what failed: ${e.message}", e)
            }.getOrNull()

    suspend fun resolve(
        name: String,
        purpose: CredentialPurpose,
        caller: String = "app",
        audit: Boolean = true,
        /**
         * 本次使用的目的地 + 用途（P0-2，仅 vault_ssh_exec / vault_http_exec 传）。
         * 两者都传时才做目的地绑定 + 类型校验；任一为空 = 不做对应检查（其余调用方行为不变）。
         * 校验在解密之后、放行之前：拒绝时记 `${purpose.action}_denied` 审计，值不出进程。
         */
        destination: CredentialDestination? = null,
        use: CredentialUse? = null,
    ): CredentialResolution {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CredentialResolution.Missing(name)

        // 授权门本身读会话 DataStore：查不到授权 = 拒绝（fail-closed），
        // 授权检查自己抛错同样按拒绝处理，不把库错变成调用链上的原始异常。
        if (purpose.requiresAuthorization) {
            val authorized = safeVaultRead("authorization check") { isAuthorized?.invoke(trimmed) } == true
            if (!authorized) {
                if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_denied")
                return CredentialResolution.NotAuthorized(trimmed)
            }
        }

        // 库读失败按"解析不到"处理：调用方只按 Granted / 非 Granted 分支，
        // 库错不该变成调用链上的原始异常（见 [resolveLiteralOrReference] 的
        // "从不抛异常"约定）。真实原因记一条警告日志，不静默。
        val entry = safeVaultRead("credential lookup") { repository.getByName(trimmed) }
        if (entry == null) {
            if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_missing")
            return CredentialResolution.Missing(trimmed)
        }

        val value = repository.decryptValue(entry)
        if (value == null) {
            if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_undecryptable")
            return CredentialResolution.Undecryptable(trimmed)
        }

        if (destination != null && use != null) {
            val rejection = CredentialBinding.check(entry, value, use, destination)
            if (rejection != null) {
                if (audit) repository.logAccess(trimmed, caller, "${purpose.action}_denied")
                return CredentialResolution.Rejected(trimmed, rejection)
            }
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
        // （库读失败按查不到处理，与 [resolve] 同口径）。
        if (whole.isNotEmpty() && safeVaultRead("credential lookup") { repository.getByName(whole) } != null) {
            return resolve(whole, purpose, caller, audit)
        }
        if (ref.name.isEmpty()) return CredentialResolution.Missing(whole)

        val resolution = fieldResolution(
            safeVaultRead("credential lookup") { repository.getByName(ref.name) },
            ref.name,
            ref.field,
        )
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
