package me.rerere.rikkahub.data.vault

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore by preferencesDataStore(name = "vault")

/** Vault 偏好：指纹门禁开关（独立 DataStore，不动 Settings 序列化结构）。 */
class VaultPreferences(private val context: Context) {
    private val store = context.vaultDataStore

    private object Keys {
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
    }

    val biometricEnabled: Flow<Boolean> =
        store.data.map { it[Keys.BIOMETRIC_ENABLED] ?: true }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        store.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }
}
