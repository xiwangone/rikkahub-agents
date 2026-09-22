package me.rerere.rikkahub.data.vault

/**
 * SSH 公钥行的识别与命名推断。
 *
 * 存在意义：**公钥是公开信息，不该单独占一条凭证** —— 它属于某个私钥条目
 * （`VaultCredentialEntity.publicKey` 字段）。此前"粘贴即入库"会把一段公钥
 * 新建为独立条目，条目列表里于是多出一堆"只有公钥"的项（2026-09-22 用户提出）。
 *
 * 本对象只做**纯判定**，不碰存储；并入动作在
 * [CredentialVaultRepository.mergePublicKeyLine] 里按「指纹 → 名称」两级匹配。
 */
object SshKeyFormat {

    /** 公钥行前缀（与 SecretMasker 的"公钥不掩码"判据同源，单一来源）。 */
    val PUBLIC_KEY_PREFIXES: List<String> = listOf(
        "ssh-rsa ", "ssh-ed25519 ", "ecdsa-sha2-", "ssh-dss ",
        "sk-ssh-ed25519 ", "sk-ssh-ecdsa-",
    )

    /** 值是否是**单行公钥**：多行内容或含私钥头都不是（那属于密钥对/私钥条目）。 */
    fun isPublicKeyLine(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (v.contains('\n') || v.contains('\r')) return false
        if (v.contains("PRIVATE KEY-----")) return false
        return PUBLIC_KEY_PREFIXES.any { v.startsWith(it) }
    }

    /**
     * 由公钥条目名推断**可能的私钥条目名**（按优先级返回，调用方取库里真实存在的第一个）。
     *
     * 覆盖常见写法：`ID_ED25519.PUB` / `FOO_PUB` / `BAR_PUBLIC` / `BAZ_PUBKEY`。
     * 不返回原名自身，也不返回空串。
     */
    fun privateKeyNameCandidates(publicKeyEntryName: String): List<String> {
        val n = publicKeyEntryName.trim().uppercase()
        if (n.isEmpty()) return emptyList()
        return listOf(
            n.removeSuffix(".PUB"),
            n.removeSuffix("_PUB"),
            n.removeSuffix("_PUBLIC"),
            n.removeSuffix("_PUBKEY"),
            n.removeSuffix("_PUBLIC_KEY"),
        ).filter { it.isNotBlank() && it != n }.distinct()
    }
}
