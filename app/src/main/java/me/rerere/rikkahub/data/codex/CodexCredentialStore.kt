package me.rerere.rikkahub.data.codex

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.data.crypto.KeystoreAesKey

internal class CodexCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun read(): CodexAccountState {
        if (!file.exists()) return CodexAccountState()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString<CodexAccountState>(cipher.doFinal(encrypted).decodeToString())
        }.getOrElse {
            CodexAccountState()
        }
    }

    fun write(state: CodexAccountState) {
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
        const val FILE_NAME = "codex_accounts.enc"
        const val KEY_ALIAS = "rikkahub_codex_accounts"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}

@Serializable
internal data class CodexAccountState(
    val accounts: List<CodexAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)
