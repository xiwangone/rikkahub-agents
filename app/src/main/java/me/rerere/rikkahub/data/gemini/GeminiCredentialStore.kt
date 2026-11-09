package me.rerere.rikkahub.data.gemini

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.crypto.EncryptedJsonStore

internal class GeminiCredentialStore(
    context: Context,
    json: Json,
) {
    private val store = EncryptedJsonStore(
        file = File(context.noBackupFilesDir, FILE_NAME),
        keyAlias = KEY_ALIAS,
        json = json,
        serializer = GeminiAccountState.serializer(),
        defaultFactory = { GeminiAccountState() },
    )

    fun read(): GeminiAccountState = store.read()

    fun write(state: GeminiAccountState) = store.write(state)

    private companion object {
        const val FILE_NAME = "gemini_accounts.enc"
        const val KEY_ALIAS = "rikkahub_gemini_accounts"
    }
}

@Serializable
internal data class GeminiAccountState(
    val accounts: List<GeminiAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)
