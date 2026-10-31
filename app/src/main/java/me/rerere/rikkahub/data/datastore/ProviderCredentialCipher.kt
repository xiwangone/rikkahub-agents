package me.rerere.rikkahub.data.datastore

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.data.crypto.KeystoreAesKey

/**
 * P0 凭证加密：为 PreferencesStore 的 `providers`（含 LLM provider apiKey、
 * Google Vertex AI 服务账号私钥等明文凭证）提供 AES-256-GCM 加解密。
 *
 * 复用仓库既有 AndroidKeyStore 基建（密钥创建统一在 [KeystoreAesKey]，与 Codex 等共用同一套
 * AES-256-GCM 属性）：密钥仅存于 AndroidKeyStore（应用级，
 * TEE/StrongBox 可用时硬件背书），密钥本身不落盘、不进备份，因此密文即使随备份
 * 泄漏也无法在其它设备解密。
 *
 * 密文格式：`Base64(IV(12B) + AES-GCM ciphertext)`，每次加密生成随机 IV；
 * 解密时先取前 12B 为 IV，剩余为密文，GCM tag 校验失败即视为解密失败。
 */
internal object ProviderCredentialCipher {

    private const val KEY_ALIAS = "rikkahub_providers_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_LENGTH = 128

    /** 加密明文，返回 Base64(IV + ciphertext)。 */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    /**
     * 解密密文。失败返回 null（不抛异常）：
     * - 旧版本安装的明文 JSON（非密文格式）→ 调用方回退原文，升级平滑；
     * - 密钥丢失 / 被替换（卸载重装后备份恢复等）→ 调用方回退原文并最终落空列表，
     *   由默认 providers 重新 seed，行为与 CodexCredentialStore 的失败语义一致。
     */
    fun decrypt(ciphertext: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(ciphertext)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
        cipher.doFinal(encrypted).decodeToString()
    }.onFailure { e ->
        // 解密失败会让 providers 回退默认列表, 属高价值排查线索, 必须留痕并区分失败类别
        android.util.Log.w(
            "ProviderCredentialCipher",
            "provider 凭证解密失败(${e.javaClass.simpleName}): ${e.message}",
        )
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey = KeystoreAesKey.getOrCreate(KEY_ALIAS)
}
