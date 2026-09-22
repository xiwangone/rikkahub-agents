package me.rerere.rikkahub.data.vault

/** 引用前缀：与界面引用位、各解析层保持一致。 */
const val VAULT_REF_PREFIX = "$$"

/** 该文本是否是一条 `$$名字` 引用。 */
fun isVaultReference(raw: String?): Boolean =
    raw?.trim()?.startsWith(VAULT_REF_PREFIX) == true

/**
 * 把「字面量 或 `$$引用`」解析为真值。
 *
 * - 以 `$$` 开头 → 走凭证库解析（权限与审计口径与其它用途一致，探测场景传 `audit = false`）；
 * - 其它 → 按字面量返回（兼容历史上的明文/文件路径写法）；
 * - 空/引用名为空 → null。
 *
 * 存在的意义：SSH 的 `password` / `privateKey` / `passphrase`、Web 桥的私钥与口令这类字段
 * **既要能填明文、又要能填引用**，否则用户没法从明文平滑迁移到引用 —— 而"私钥走了引用、
 * 口令却还是明文"本身就是个假安全。
 */
suspend fun resolveLiteralOrReference(
    raw: String?,
    purpose: CredentialPurpose,
    vaultRepository: CredentialVaultRepository,
    caller: String,
    audit: Boolean = false,
): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (!isVaultReference(text)) return text
    val name = text.removePrefix(VAULT_REF_PREFIX).trim()
    if (name.isEmpty()) return null
    val resolution = CredentialResolver(vaultRepository)
        .resolve(name, purpose, caller = caller, audit = audit)
    return (resolution as? CredentialResolution.Granted)?.value
}
