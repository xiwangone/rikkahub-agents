package me.rerere.rikkahub.data.ai.tools.inject

import android.content.Context
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialResolution
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultSessionManager
import me.rerere.rikkahub.data.vault.message
import org.koin.java.KoinJavaComponent.getKoin

/*
 * [SecretInjector] 的 App 侧适配：凭证库、会话授权、审计经 Koin 取得。
 * 依赖取不到（单测无 Koin）时：名字列表为空（护栏放行）、解析器为 null（引用 fail-closed 报错），
 * 与改造前 resolveInjectedEnv / readEnvParam 的行为一致。
 */

/**
 * 读取全部凭证名：只读名字，不解密、不过授权门、不写审计。
 * 凭证库不可用时返回空集。
 */
internal suspend fun vaultCredentialNamesOrEmpty(): Set<String> {
    val repo = runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull() ?: return emptySet()
    return runCatching { repo.getAll().mapTo(HashSet()) { it.name } }.getOrDefault(emptySet())
}

/**
 * 基于凭证库的引用解析器（purpose = ENV_INJECT，走会话授权门 + 审计）。
 * [caller] 写入审计（workspace 侧沿用 "ai-tool"，ssh 侧沿用 "ssh-env"）。
 */
internal fun vaultRefResolverOrNull(caller: String): SecretRefResolver? {
    val repo = runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull() ?: return null
    val context = runCatching { getKoin().get<Context>() }.getOrNull()
    val resolver = CredentialResolver(repo) { name ->
        context?.let { VaultSessionManager(it) }?.hasActiveAuthorization(name) == true
    }
    return SecretRefResolver { ref ->
        when (val r = resolver.resolveRef(ref, CredentialPurpose.ENV_INJECT, caller = caller)) {
            is CredentialResolution.Granted -> RefOutcome.Granted(r.value)
            else -> RefOutcome.Denied(r.message)
        }
    }
}

/** 各通道的默认注入器。 */
internal fun vaultSecretInjector(caller: String): SecretInjector =
    SecretInjector(
        credentialNames = ::vaultCredentialNamesOrEmpty,
        resolverFactory = { vaultRefResolverOrNull(caller) },
    )

/** 审计 caller 常量：沿用改造前各通道的取值，审计日志口径不变。 */
internal const val INJECT_CALLER_WORKSPACE = "ai-tool"
internal const val INJECT_CALLER_SSH = "ssh-env"
