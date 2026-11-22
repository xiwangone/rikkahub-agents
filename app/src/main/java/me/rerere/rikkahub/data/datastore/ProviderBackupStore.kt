package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "ProviderBackupStore"

/** Name of the encrypted providers snapshot kept in the app-private files directory. */
internal const val PROVIDER_BACKUP_FILE_NAME = "providers_backup.json"

/**
 * Encrypted snapshot of the providers setting, kept in a separate file so a lost or
 * corrupted settings file does not take the providers with it.
 *
 * The snapshot holds the same field-level-encrypted providers JSON that is stored
 * under the providers key, wrapped in one more whole-payload encryption pass with
 * [ProviderCredentialCipher] so provider metadata is not left in cleartext on disk.
 * The key lives in the platform keystore and never leaves the device, so the snapshot
 * is only readable on this install.
 */
internal object ProviderBackupStore {

    fun backupFile(context: Context): File = File(context.filesDir, PROVIDER_BACKUP_FILE_NAME)

    /**
     * Encrypt and persist the providers JSON. Atomic replace via a temp sibling file
     * that is fsynced before rename, so a crash mid-write never leaves a torn snapshot.
     * Best-effort: returns false on failure, never throws.
     */
    fun writeBackup(context: Context, providersJson: String): Boolean = runCatching {
        val file = backupFile(context)
        val ciphertext = ProviderCredentialCipher.encrypt(providersJson)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write(ciphertext.toByteArray())
            stream.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(ciphertext)
        }
        true
    }.onFailure { AppLog.w(TAG, "Provider backup write failed", it) }.getOrDefault(false)

    /** Read and decrypt the snapshot; null when absent, unreadable, or undecryptable. */
    fun readBackupJson(context: Context): String? {
        val file = backupFile(context)
        if (!file.isFile) return null
        val ciphertext = runCatching { file.readText() }.getOrNull() ?: return null
        if (ciphertext.isBlank()) return null
        return ProviderCredentialCipher.decrypt(ciphertext)
    }

    /** Decode the snapshot into providers; empty when absent, unreadable, or undecodable. */
    fun readBackupProviders(context: Context): List<ProviderSetting> {
        val json = readBackupJson(context) ?: return emptyList()
        return runCatching { decodeProvidersFieldEncrypted(json) }
            .onFailure { AppLog.w(TAG, "Provider backup decode failed", it) }
            .getOrDefault(emptyList())
    }
}

/**
 * Write the snapshot when the provider list changed; best-effort, never throws.
 * Lives outside SettingsStore so the store class stays under its size limit.
 */
internal fun maybeWriteProvidersBackup(
    context: Context,
    previous: List<ProviderSetting>,
    current: List<ProviderSetting>,
) {
    if (previous == current) return
    runCatching {
        ProviderBackupStore.writeBackup(context, encodeProvidersFieldEncrypted(current))
    }.onFailure { AppLog.w(TAG, "Provider backup snapshot write failed", it) }
}

/**
 * Restore providers from the encrypted snapshot when the settings file has no
 * providers key (lost or corrupted file). Runs before the first decode so no
 * in-memory default can overwrite the restored value. A missing or undecodable
 * snapshot is a no-op: a fresh install has neither. Lives outside SettingsStore
 * so the store class stays under its size limit.
 */
internal suspend fun restoreProvidersFromBackup(
    dataStore: DataStore<Preferences>,
    context: Context,
) {
    val preferences = runCatching { dataStore.data.first() }.getOrNull() ?: return
    if (preferences[SettingsStore.PROVIDERS] != null) return
    val backupJson = ProviderBackupStore.readBackupJson(context) ?: return
    val backupProviders = runCatching { decodeProvidersFieldEncrypted(backupJson) }
        .onFailure { AppLog.w(TAG, "Provider backup snapshot undecodable; skipping restore", it) }
        .getOrNull()
        .orEmpty()
    if (backupProviders.isEmpty()) return
    runCatching {
        dataStore.edit { prefs ->
            // Only fill the gap: another writer may have stored providers meanwhile.
            if (prefs[SettingsStore.PROVIDERS] == null) {
                prefs[SettingsStore.PROVIDERS] = backupJson
            }
        }
    }.onFailure { AppLog.w(TAG, "Provider restore from backup failed", it) }
        .onSuccess { AppLog.i(TAG, "Restored ${backupProviders.size} providers from backup snapshot") }
}
