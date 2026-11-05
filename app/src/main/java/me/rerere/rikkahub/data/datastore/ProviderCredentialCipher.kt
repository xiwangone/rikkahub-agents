package me.rerere.rikkahub.data.datastore

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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
    private const val MAX_CACHE_ENTRIES = 128

    // 按 (keyIdentity, ciphertext) 缓存已验证通过的明文，避免每次设置项变更时重复进入 TEE 解密未改动的密文字段。
    private val verifiedPlaintextByCipher = ConcurrentHashMap<String, String>()

    private fun cacheKey(key: SecretKey, ciphertext: String): String =
        "${System.identityHashCode(key)}:$ciphertext"

    private fun putCache(cacheKeyStr: String, plaintext: String) {
        if (verifiedPlaintextByCipher.size >= MAX_CACHE_ENTRIES) {
            verifiedPlaintextByCipher.clear()
        }
        verifiedPlaintextByCipher[cacheKeyStr] = plaintext
    }

    /** 加密明文，返回 Base64(IV + ciphertext)。 */
    fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        val result = Base64.getEncoder().encodeToString(cipher.iv + encrypted)
        putCache(cacheKey(key, result), plaintext)
        return result
    }

    /**
     * 解密密文。失败返回 null（不抛异常）：
     * - 旧版本安装的明文 JSON（非密文格式）→ 调用方回退原文，升级平滑；
     * - 密钥丢失 / 被替换（卸载重装后备份恢复等）→ 调用方回退原文并最终落空列表，
     *   由默认 providers 重新 seed，行为与 CodexCredentialStore 的失败语义一致。
     */
    fun decrypt(ciphertext: String): String? = runCatching {
        val key = getOrCreateKey()
        val cacheKeyStr = cacheKey(key, ciphertext)
        verifiedPlaintextByCipher[cacheKeyStr]?.let { return@runCatching it }
        val bytes = Base64.getDecoder().decode(ciphertext)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val plaintext = cipher.doFinal(encrypted).decodeToString()
        putCache(cacheKeyStr, plaintext)
        plaintext
    }.onFailure { e ->
        // 解密失败会让 providers 回退默认列表, 属高价值排查线索, 必须留痕并区分失败类别
        android.util.Log.w(
            "ProviderCredentialCipher",
            "provider 凭证解密失败(${e.javaClass.simpleName}): ${e.message}",
        )
    }.getOrNull()

    /** 判断存量字符串是否已是当前有效密钥加密的密文（命中缓存时 O(1) 无密码学开销）。 */
    fun isValidCiphertext(raw: String?): Boolean =
        !raw.isNullOrBlank() && decrypt(raw) != null

    private fun getOrCreateKey(): SecretKey = KeystoreAesKey.getOrCreate(KEY_ALIAS)
}
