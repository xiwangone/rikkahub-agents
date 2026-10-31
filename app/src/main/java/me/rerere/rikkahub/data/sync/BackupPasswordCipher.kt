package me.rerere.rikkahub.data.sync

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.data.crypto.KeystoreAesKey

/**
 * 备份加密"记住口令"的本机保护：AndroidKeyStore AES-256-GCM 包裹明文口令后存入
 * Settings（[me.rerere.rikkahub.data.datastore.Settings.backupEncryptionPasswordEnc]）。
 *
 * 与 [me.rerere.rikkahub.data.datastore.ProviderCredentialCipher] 同构（密钥创建统一在
 * [KeystoreAesKey]，AES-256-GCM），但独立 alias：
 * - 密钥仅存 AndroidKeyStore（TEE/StrongBox 可用时硬件背书），不落盘不进备份；
 * - 因此 Settings 随备份迁移到新设备后，此密文解不开 → 用户在新设备重输口令即可，
 *   旧密文自然失效，不会把口令带出设备。
 *
 * 密文格式：Base64(IV(12B) + AES-GCM ciphertext)。
 */
internal object BackupPasswordCipher {

    private const val KEY_ALIAS = "rikkahub_backup_password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_LENGTH = 128

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    /** 失败返回 null（密钥丢失/被替换/非密文），调用方提示重输口令。 */
    fun decrypt(ciphertext: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(ciphertext)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
        cipher.doFinal(encrypted).decodeToString()
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey = KeystoreAesKey.getOrCreate(KEY_ALIAS)
}
