package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份包整包加密（口令派生密钥，跨设备可恢复）。
 *
 * 云端/本地备份含明文敏感内容（settings 的 provider apiKey、数据库内的 SSH 主机
 * 与 vault 密文等），裸 zip 落在第三方网盘有泄漏风险。本类提供"用户口令 → 密钥"
 * 的对称整包加密：口令本身不落盘，任何持有口令的设备都能解密恢复——与
 * AndroidKeyStore 方案（密钥硬件绑定、跨设备不可解）互补，专用于可迁移备份。
 *
 * 文件格式（.zip.enc）：
 * ```
 * MAGIC "RHBE" (4B) | VERSION=1 (1B) | KDF_ITER (4B, big-endian)
 * | SALT (16B) | IV (12B) | AES-256-GCM 密文（流式，tag 附尾）
 * ```
 * - 密钥 = PBKDF2WithHmacSHA256(password, salt, iter, 256bit)
 * - 每次加密生成随机 salt + IV；GCM tag 由 CipherOutputStream 自动写尾。
 * - 200_000 次 PBKDF2 迭代：移动端约 0.3-1s，兼顾强度与体验。
 */
object BackupCrypto {

    private const val MAGIC = "RHBE"
    private const val VERSION: Byte = 1
    private const val PBKDF2_ITER = 200_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** 判断文件是否为 BackupCrypto 加密容器（读 4B magic）。 */
    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC.length) return false
        FileInputStream(file).use { input ->
            val magic = ByteArray(MAGIC.length)
            val read = input.read(magic)
            if (read < MAGIC.length) return false
            return String(magic, Charsets.US_ASCII) == MAGIC
        }
    }

    /** 加密 [plainFile] 为 [encFile]（调用方保证 encFile 可写，存在则覆盖）。 */
    fun encryptFile(plainFile: File, encFile: File, password: String) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        FileOutputStream(encFile).use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(VERSION.toInt())
            out.write(intToBytes(PBKDF2_ITER))
            out.write(salt)
            out.write(iv)
            CipherOutputStream(out, cipher).use { cos ->
                FileInputStream(plainFile).use { input ->
                    input.copyTo(cos, bufferSize = 1 shl 16)
                }
            }
        }
    }

    /**
     * 解密 [encFile] 为 [plainFile]。口令错误 / 文件损坏抛 [IllegalArgumentException]
     * （GCM tag 校验失败），由调用方转成用户可读错误。
     */
    fun decryptFile(encFile: File, plainFile: File, password: String) {
        FileInputStream(encFile).use { input ->
            val magic = ByteArray(MAGIC.length)
            if (input.read(magic) < MAGIC.length || String(magic, Charsets.US_ASCII) != MAGIC) {
                throw IllegalArgumentException("Not a BackupCrypto file")
            }
            val version = input.read()
            if (version != VERSION.toInt()) {
                throw IllegalArgumentException("Unsupported backup encryption version: $version")
            }
            val iterBytes = ByteArray(4)
            if (input.read(iterBytes) < 4) throw IllegalArgumentException("Corrupt backup header")
            val iter = bytesToInt(iterBytes)
            val salt = ByteArray(SALT_SIZE)
            if (input.read(salt) < SALT_SIZE) throw IllegalArgumentException("Corrupt backup header")
            val iv = ByteArray(IV_SIZE)
            if (input.read(iv) < IV_SIZE) throw IllegalArgumentException("Corrupt backup header")

            val key = deriveKey(password, salt, iter)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

            FileOutputStream(plainFile).use { out ->
                CipherInputStream(input, cipher).use { cis ->
                    cis.copyTo(out, bufferSize = 1 shl 16)
                }
            }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITER): SecretKey {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun intToBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun bytesToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
}
