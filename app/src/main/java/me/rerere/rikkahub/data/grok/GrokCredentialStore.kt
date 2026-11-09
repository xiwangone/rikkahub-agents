package me.rerere.rikkahub.data.grok

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.crypto.EncryptedJsonStore

internal class GrokCredentialStore(
    context: Context,
    json: Json,
) {
    private val store = EncryptedJsonStore(
        file = File(context.noBackupFilesDir, FILE_NAME),
        keyAlias = KEY_ALIAS,
        json = json,
        serializer = GrokAccountState.serializer(),
        defaultFactory = { GrokAccountState() },
    )

    fun read(): GrokAccountState = store.read()

    fun write(state: GrokAccountState) = store.write(state)

    private companion object {
        const val FILE_NAME = "grok_accounts.enc"
        const val KEY_ALIAS = "rikkahub_grok_accounts"
    }
}

@Serializable
internal data class GrokAccountState(
    val accounts: List<GrokAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)
