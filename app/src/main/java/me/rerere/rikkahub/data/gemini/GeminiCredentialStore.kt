package me.rerere.rikkahub.data.gemini

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.data.crypto.KeystoreAesKey

internal class GeminiCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun read(): GeminiAccountState {
        if (!file.exists()) return GeminiAccountState()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString<GeminiAccountState>(cipher.doFinal(encrypted).decodeToString())
        }.getOrElse {
            GeminiAccountState()
        }
    }

    fun write(state: GeminiAccountState) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(cipher.iv + encrypted)
        temporary.copyTo(file, overwrite = true)
        temporary.delete()
    }

    private fun getOrCreateKey(): SecretKey = KeystoreAesKey.getOrCreate(KEY_ALIAS)

    private companion object {
        const val FILE_NAME = "gemini_accounts.enc"
        const val KEY_ALIAS = "rikkahub_gemini_accounts"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}

@Serializable
internal data class GeminiAccountState(
    val accounts: List<GeminiAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)
