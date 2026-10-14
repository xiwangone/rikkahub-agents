package me.rerere.rikkahub.data.vault

/** 引用前缀：与界面引用位、各解析层保持一致。 */
const val VAULT_REF_PREFIX = "$$"

/** 该文本是否是一条 `$$名字` 引用。 */
fun isVaultReference(raw: String?): Boolean =
    raw?.trim()?.startsWith(VAULT_REF_PREFIX) == true

/**
 * 引用串的结构：凭证名 + 可选字段。
 *
 * [field] = null → 取**主值**（密文解密，老行为）；非 null → 取该条目的**明文元数据字段**。
 */
data class VaultRef(val name: String, val field: String?)

/**
 * 解析 `$$` 之后的引用串（不含前缀）为 [VaultRef]。
 *
 * 分隔符是**第一个** `.`：凭证名受 `NAME_REGEX = ^[A-Z][A-Z0-9_]*$` 约束（见
 * `CredentialVaultRepository.validateCredentialName`），永不包含 `.`，所以切点无歧义；
 * 字段部分保留其余的点，`custom.<标签>` 这类自带点的键才能整段传下去。
 *
 * 本函数**纯做字符串切分、不查库**："整名优先"的兼容判定放在解析层
 * （`CredentialResolver.resolveRef`），因为只有那里知道库里有什么。
 */
fun parseVaultRef(rawRef: String): VaultRef {
    val text = rawRef.trim()
    val dot = text.indexOf('.')
    return if (dot < 0) {
        VaultRef(text, null)
    } else {
        VaultRef(text.substring(0, dot).trim(), text.substring(dot + 1).trim())
    }
}

/**
 * 把「字面量 或 `$$引用`」解析为真值。
 *
 * - 以 `$$` 开头 → 走凭证库解析（权限与审计口径与其它用途一致，探测场景传 `audit = false`）；
 *   - `$$名字` → 主值（密文）；
 *   - `$$名字.字段` → 该条目的明文元数据字段（AK ID / endpoint / region / `custom.*` 备注等）；
 * - 其它 → 按字面量返回（兼容历史上的明文/文件路径写法）；
 * - 空/引用名为空/解析不到（不存在、未授权、解密失败、字段没有）→ null，**从不抛异常**。
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
    val ref = text.removePrefix(VAULT_REF_PREFIX).trim()
    if (ref.isEmpty()) return null
    val resolution = CredentialResolver(vaultRepository)
        .resolveRef(ref, purpose, caller = caller, audit = audit)
    return (resolution as? CredentialResolution.Granted)?.value
}
