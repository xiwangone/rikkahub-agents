package me.rerere.rikkahub.data.vault

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.ai.tools.local.BiometricResult
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.ToolHostActivity
import java.util.UUID

/**
 * Vault 指纹门禁封装。
 *
 * 复用项目既有 ToolHostActivity（AppCompatActivity）承载 BiometricPrompt——
 * 当前 App 主 Activity 是 ComponentActivity，biometric 库的 BiometricPrompt
 * 构造函数要求 FragmentActivity，因此走 ToolHostActivity 中转：
 * 启动 ToolHostActivity(MODE_BIOMETRIC) → 用户指纹验证 → BiometricResultBuffer 回调。
 *
 * 安全模型：Vault 凭证明文由 ProviderCredentialCipher（AndroidKeyStore AES-GCM）
 * 加密，本封装在 UI 层提供「查看/导出前必须指纹（或锁屏）验证」的门禁——
 * 验证通过后才允许解密展示。
 */
object VaultBiometric {

    /**
     * 弹指纹窗，验证通过返回 true，取消/失败返回 false。
     * 用于查看/导出凭证明文前的门禁。
     */
    suspend fun authenticate(
        context: Context,
        buffer: BiometricResultBuffer,
        title: String,
        subtitle: String? = null,
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)
        val intent =
            Intent(context, ToolHostActivity::class.java).apply {
                putExtra(ToolHostActivity.EXTRA_MODE, ToolHostActivity.MODE_BIOMETRIC)
                putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
                putExtra(ToolHostActivity.EXTRA_BIO_TITLE, title)
                putExtra(ToolHostActivity.EXTRA_BIO_SUBTITLE, subtitle)
                putExtra(ToolHostActivity.EXTRA_BIO_ALLOW_CRED, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        val result = withTimeoutOrNull(300_000L) { deferred.await() }
        return when (result) {
            is BiometricResult.Success -> true
            else -> false
        }
    }
}
