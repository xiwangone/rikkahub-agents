package me.rerere.rikkahub.data.codex

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.crypto.EncryptedJsonStore

internal class CodexCredentialStore(
    context: Context,
    json: Json,
) {
    private val store = EncryptedJsonStore(
        file = File(context.noBackupFilesDir, FILE_NAME),
        keyAlias = KEY_ALIAS,
        json = json,
        serializer = CodexAccountState.serializer(),
        defaultFactory = { CodexAccountState() },
    )

    fun read(): CodexAccountState = store.read()

    fun write(state: CodexAccountState) = store.write(state)

    private companion object {
        const val FILE_NAME = "codex_accounts.enc"
        const val KEY_ALIAS = "rikkahub_codex_accounts"
    }
}

@Serializable
internal data class CodexAccountState(
    val accounts: List<CodexAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
)
