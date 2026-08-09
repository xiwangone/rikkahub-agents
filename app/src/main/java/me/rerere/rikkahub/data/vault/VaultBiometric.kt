package me.rerere.rikkahub.data.vault

import android.app.Activity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Vault 指纹门禁封装。
 *
 * 安全模型：Vault 凭证明文由 ProviderCredentialCipher（AndroidKeyStore AES-GCM）
 * 加密，本封装在 UI 层提供「查看/导出前必须指纹（或锁屏）验证」的门禁——
 * 验证通过后才允许解密展示。指纹本身不绑定具体密钥（无需迁移密文），
 * 是访问控制而非加密密钥的一部分。
 */
object VaultBiometric {

    /** 系统是否支持指纹/锁屏认证（BIOMETRIC_STRONG 或 DEVICE_CREDENTIAL）。 */
    fun canAuthenticate(activity: Activity): Boolean =
        BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 弹指纹窗，验证通过返回 true，取消/失败返回 false。
     * 用于查看/导出凭证明文前的门禁。
     */
    suspend fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String? = null,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val executor = Executors.newSingleThreadExecutor()
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    cont.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // 指纹不匹配：不结束，等待重试
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle ?: "")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }
}
