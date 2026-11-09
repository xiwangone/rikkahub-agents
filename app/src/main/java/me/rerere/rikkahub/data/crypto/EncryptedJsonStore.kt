package me.rerere.rikkahub.data.crypto

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget

/**
 * 统一的 AES-256-GCM 加密 JSON 文件存储基建。
 *
 * 集中管理 [CodexCredentialStore]、[GrokCredentialStore]、[GeminiCredentialStore] 等凭据文件的
 * AES-GCM 加解密流程与原子写盘，密文格式严格保持为 `IV(12B) + ciphertext`，与历史存量数据 100% 兼容。
 */
internal class EncryptedJsonStore<T : Any>(
    private val file: File,
    private val keyAlias: String,
    private val json: Json,
    private val serializer: KSerializer<T>,
    private val defaultFactory: () -> T,
) {
    fun read(): T {
        if (!file.exists()) return defaultFactory()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE) { "Corrupt encrypted file: size ${bytes.size} <= $IV_SIZE" }
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, KeystoreAesKey.getOrCreate(keyAlias), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString(serializer, cipher.doFinal(encrypted).decodeToString())
        }.getOrElse {
            defaultFactory()
        }
    }

    fun write(state: T) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, KeystoreAesKey.getOrCreate(keyAlias))
        val encrypted = cipher.doFinal(json.encodeToString(serializer, state).encodeToByteArray())
        val parent = file.parentFile ?: file
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp-${System.nanoTime()}")
        try {
            temp.writeBytes(cipher.iv + encrypted)
            replaceFilePreservingTarget(temp, file)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}
