package me.rerere.rikkahub.data.tunnel

import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.message

/** 凭证引用前缀：与 SecretRefField、运行时解析层保持一致。 */
internal const val TUNNEL_VAULT_REF_PREFIX = "\$\$"

/**
 * 把「认证密钥」输入解析成可以交给隧道层的明文。
 *
 * 支持两种形态：
 * - 直接填写的密钥（原样返回，兼容已有配置）；
 * - `$$名字` 引用 —— 运行时从凭证库取真值，界面上不留明文。
 *
 * 解析失败时返回带原因的错误，由调用方显示到状态行 / 写日志，
 * 不把引用串本身当成密钥传给底层。
 */
internal suspend fun resolveTunnelAuthKey(
    resolver: CredentialResolver,
    raw: String,
): Result<String> {
    val value = raw.trim()
    if (value.isEmpty()) return Result.failure(IllegalStateException("认证密钥为空"))
    if (!value.startsWith(TUNNEL_VAULT_REF_PREFIX)) return Result.success(value)
    val name = value.removePrefix(TUNNEL_VAULT_REF_PREFIX).trim()
    return when (val resolution = resolver.resolveRef(name, CredentialPurpose.LOCAL_USE, caller = "tunnel")) {
        is CredentialResolution.Granted -> Result.success(resolution.value)
        else -> Result.failure(IllegalStateException(resolution.message.ifBlank { "认证密钥引用无效" }))
    }
}
