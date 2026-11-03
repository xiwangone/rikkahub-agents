package me.rerere.rikkahub.data.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
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
 * - V2（默认，分块流式 AEAD，消除 Android Conscrypt 单次 GCM 整包缓冲导致的 130MB+ OOM）：
 *   `MAGIC "RHBE" (4B) | ver=2 (1B) | KDF_ITER (4B) | SALT (16B) | BASE_IV (12B)`
 *   后接分块序列：`[PLAIN_LEN (4B) | IS_FINAL (1B) | AES-256-GCM(chunk) (PLAIN_LEN + 16B)]...`
 * - V1（旧版单流格式，仅解密向后兼容）。
 */
object BackupCrypto {

    private const val MAGIC = "RHBE"
    private const val LEGACY_V1: Byte = 1
    private const val VERSION: Byte = 2
    private const val PBKDF2_ITER = 200_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KEY_BITS = 256
    private const val CHUNK_SIZE = 256 * 1024
    private const val IO_BUFFER_SIZE = 64 * 1024
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

    /**
     * 加密 [plainFile] 为 [encFile]（调用方保证 encFile 可写，存在则覆盖）。
     * 采用 V2 分块流式 AES-256-GCM（每块 256 KiB），恒定堆内存占用 < 1 MB，
     * 避免 Android Conscrypt OpenSSLAeadCipher 在单次 GCM 流中把百兆文件全量缓冲进堆内存引发 OOM。
     */
    fun encryptFile(
        plainFile: File,
        encFile: File,
        password: String,
        iterations: Int = PBKDF2_ITER,
        chunkSize: Int = CHUNK_SIZE,
    ) {
        require(chunkSize in 1024..(16 * 1024 * 1024)) { "Invalid chunkSize: $chunkSize" }
        val usable = (encFile.parentFile ?: encFile).usableSpace
        if (usable in 1 until (plainFile.length() + 16L * 1024 * 1024)) {
            throw IOException("Insufficient disk space to encrypt backup")
        }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val baseIv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val key = deriveKey(password, salt, iterations)

        var success = false
        try {
            BufferedInputStream(FileInputStream(plainFile), IO_BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(encFile), IO_BUFFER_SIZE).use { out ->
                    out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                    out.write(VERSION.toInt())
                    out.write(ByteBuffer.allocate(4).putInt(iterations).array())
                    out.write(salt)
                    out.write(baseIv)
                    encryptStreamV2(input, out, key, baseIv, chunkSize)
                }
            }
            success = true
        } finally {
            if (!success && encFile.exists()) encFile.delete()
        }
    }

    /**
     * 解密 [encFile] 为 [plainFile]。口令错误 / 文件损坏抛 [IllegalArgumentException]
     * （GCM tag 校验失败），由调用方转成用户可读错误。支持 V2 分块流式格式并向后兼容 V1。
     */
    fun decryptFile(encFile: File, plainFile: File, password: String) {
        var success = false
        try {
            BufferedInputStream(FileInputStream(encFile), IO_BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(plainFile), IO_BUFFER_SIZE).use { out ->
                    decryptStream(input, out, password)
                }
            }
            success = true
        } finally {
            if (!success && plainFile.exists()) plainFile.delete()
        }
    }

    private fun decryptStream(
        input: InputStream,
        out: OutputStream,
        password: String,
    ) {
        val magic = ByteArray(MAGIC.length)
        require(readBytesFully(input, magic) == MAGIC.length && String(magic, Charsets.US_ASCII) == MAGIC) {
            "Not a BackupCrypto file"
        }
        val version = input.read()
        require(version == LEGACY_V1.toInt() || version == VERSION.toInt()) {
            "Unsupported backup encryption version: $version"
        }
        val iterBytes = ByteArray(4)
        require(readBytesFully(input, iterBytes) == 4) { "Corrupt backup header" }
        val iter = ByteBuffer.wrap(iterBytes).int
        require(iter > 0) { "Invalid KDF iteration count: $iter" }
        val salt = ByteArray(SALT_SIZE)
        val baseIv = ByteArray(IV_SIZE)
        require(readBytesFully(input, salt) == SALT_SIZE && readBytesFully(input, baseIv) == IV_SIZE) {
            "Corrupt backup header"
        }
        val key = deriveKey(password, salt, iter)
        if (version == VERSION.toInt()) {
            decryptStreamV2(input, out, key, baseIv)
        } else {
            decryptStreamV1(input, out, key, baseIv)
        }
    }

    private fun encryptStreamV2(
        input: InputStream,
        out: OutputStream,
        key: SecretKey,
        baseIv: ByteArray,
        chunkSize: Int,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val plainBuf = ByteArray(chunkSize)
        val cipherBuf = ByteArray(chunkSize + TAG_BYTES)
        var nextByte = input.read()
        var chunkIndex = 0L

        while (true) {
            val plainLen =
                if (nextByte < 0) {
                    0
                } else {
                    plainBuf[0] = nextByte.toByte()
                    1 + readBytesFully(input, plainBuf, offset = 1, length = chunkSize - 1)
                }
            nextByte = if (plainLen == chunkSize) input.read() else -1
            val isFinal = nextByte < 0

            initChunkCipher(cipher, Cipher.ENCRYPT_MODE, key, baseIv, chunkIndex, plainLen, isFinal)
            val cipherLen = cipher.doFinal(plainBuf, 0, plainLen, cipherBuf, 0)

            out.write(ByteBuffer.allocate(4).putInt(plainLen).array())
            out.write(if (isFinal) 1 else 0)
            out.write(cipherBuf, 0, cipherLen)

            chunkIndex++
            if (isFinal) break
        }
    }

    private fun decryptStreamV2(
        input: InputStream,
        out: OutputStream,
        key: SecretKey,
        baseIv: ByteArray,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val lenBytes = ByteArray(4)
        var plainBuf = ByteArray(CHUNK_SIZE)
        var cipherBuf = ByteArray(CHUNK_SIZE + TAG_BYTES)
        var chunkIndex = 0L

        while (true) {
            require(readBytesFully(input, lenBytes) == 4) {
                "Truncated encrypted backup: missing final chunk"
            }
            val plainLen = ByteBuffer.wrap(lenBytes).int
            require(plainLen in 0..(16 * 1024 * 1024)) {
                "Corrupt encrypted backup chunk length: $plainLen"
            }
            val isFinalByte = input.read()
            require(isFinalByte == 0 || isFinalByte == 1) {
                "Corrupt encrypted backup chunk flag: $isFinalByte"
            }
            val isFinal = isFinalByte == 1
            val cipherLen = plainLen + TAG_BYTES
            if (plainBuf.size < plainLen) plainBuf = ByteArray(plainLen)
            if (cipherBuf.size < cipherLen) cipherBuf = ByteArray(cipherLen)
            require(readBytesFully(input, cipherBuf, length = cipherLen) == cipherLen) {
                "Truncated encrypted backup chunk payload"
            }

            try {
                initChunkCipher(cipher, Cipher.DECRYPT_MODE, key, baseIv, chunkIndex, plainLen, isFinal)
                val decLen = cipher.doFinal(cipherBuf, 0, cipherLen, plainBuf, 0)
                if (decLen > 0) out.write(plainBuf, 0, decLen)
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("Failed to decrypt backup: wrong password or corrupted data", e)
            }

            chunkIndex++
            if (isFinal) {
                require(input.read() == -1) { "Corrupt encrypted backup: unexpected trailing data" }
                break
            }
        }
    }

    private fun decryptStreamV1(
        input: InputStream,
        out: OutputStream,
        key: SecretKey,
        iv: ByteArray,
    ) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            CipherInputStream(input, cipher).use { cis ->
                cis.copyTo(out, bufferSize = IO_BUFFER_SIZE)
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Failed to decrypt v1 backup: wrong password or corrupted data", e)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Failed to decrypt v1 backup: wrong password or corrupted data", e)
        }
    }

    private fun initChunkCipher(
        cipher: Cipher,
        opmode: Int,
        key: SecretKey,
        baseIv: ByteArray,
        chunkIndex: Long,
        plainLen: Int,
        isFinal: Boolean,
    ) {
        val iv = baseIv.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            iv[IV_SIZE - 8 + i] = (iv[IV_SIZE - 8 + i].toInt() xor ((chunkIndex ushr shift).toInt() and 0xFF)).toByte()
        }
        val aad =
            ByteBuffer
                .allocate(14)
                .put(VERSION)
                .putLong(chunkIndex)
                .putInt(plainLen)
                .put(if (isFinal) 1.toByte() else 0.toByte())
                .array()
        cipher.init(opmode, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
    }

    private fun readBytesFully(
        input: InputStream,
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buf, offset + total, length - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITER): SecretKey {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
