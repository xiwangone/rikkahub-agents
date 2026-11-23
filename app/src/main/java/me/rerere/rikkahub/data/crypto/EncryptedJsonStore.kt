package me.rerere.rikkahub.data.crypto

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.rerere.common.log.AppLogger
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget

/**
 * 统一的 AES-256-GCM 加密 JSON 文件存储基建。
 *
 * 集中管理 [CodexCredentialStore]、[GrokCredentialStore]、[GeminiCredentialStore] 等凭据文件的
 * AES-GCM 加解密流程与原子写盘，密文格式严格保持为 `IV(12B) + ciphertext`，与历史存量数据 100% 兼容。
 *
 * 读取失败不等于数据不存在：解密/解析失败时原文件先重命名隔离为 `<name>.corrupt-<时间戳>`
 * 再返回默认值，隔离的密文保留在原目录、可人工恢复；随后一次 [write] 在原路径创建新文件，
 * 不会覆盖隔离文件。隔离失败时记错误日志并照常调回调，绝不静默。
 */
internal class EncryptedJsonStore<T : Any>(
    private val file: File,
    private val keyAlias: String,
    private val json: Json,
    private val serializer: KSerializer<T>,
    private val defaultFactory: () -> T,
    private val keyProvider: () -> SecretKey = { KeystoreAesKey.getOrCreate(keyAlias) },
) {
    /** 隔离损坏文件后的回调（默认记错误日志），可在构造后替换。 */
    var onCorruptionDetected: (Throwable) -> Unit = { e ->
        AppLogger.e("EncryptedJsonStore", "credential store corrupt: ${file.name}", e)
    }
    fun read(): T {
        if (!file.exists()) return defaultFactory()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE) { "Corrupt encrypted file: size ${bytes.size} <= $IV_SIZE" }
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString(serializer, cipher.doFinal(encrypted).decodeToString())
        }.getOrElse { e ->
            quarantineCorruptFile(e)
            onCorruptionDetected(e)
            defaultFactory()
        }
    }

    fun write(state: T) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
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

    /**
     * 把损坏的原文件隔离到同目录的 `<name>.corrupt-<时间戳>`，保留密文供人工恢复。
     * rename 失败时退化为复制后删除原文件；仍失败则记错误日志（调用方回调照常触发）。
     */
    private fun quarantineCorruptFile(cause: Throwable) {
        val quarantine = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        if (runCatching { file.renameTo(quarantine) }.getOrDefault(false)) return
        val preserved = runCatching {
            file.copyTo(quarantine, overwrite = false)
            file.delete()
        }.getOrDefault(false)
        if (!preserved) {
            AppLogger.e("EncryptedJsonStore", "failed to quarantine corrupt credential file: ${file.name}", cause)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}
