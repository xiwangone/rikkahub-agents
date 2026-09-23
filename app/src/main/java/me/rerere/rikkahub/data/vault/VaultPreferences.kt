package me.rerere.rikkahub.data.vault

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.entity.VaultAuditDefaults

private val Context.vaultDataStore by preferencesDataStore(name = "vault")

/** Vault 偏好：指纹门禁开关（独立 DataStore，不动 Settings 序列化结构）。 */
class VaultPreferences(private val context: Context) {
    private val store = context.vaultDataStore

    private object Keys {
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val AUDIT_CAP = intPreferencesKey("audit_cap")
        val AUDIT_RETENTION_DAYS = intPreferencesKey("audit_retention_days")
    }

    val biometricEnabled: Flow<Boolean> =
        store.data.map { it[Keys.BIOMETRIC_ENABLED] ?: true }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        store.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    /** 审计保留条数上限（默认 [VaultAuditDefaults.CAP]）。 */
    val auditCap: Flow<Int> =
        store.data.map { it[Keys.AUDIT_CAP] ?: VaultAuditDefaults.CAP }

    /** 审计保留天数上限（默认 [VaultAuditDefaults.RETENTION_DAYS]）。 */
    val auditRetentionDays: Flow<Int> =
        store.data.map { it[Keys.AUDIT_RETENTION_DAYS] ?: VaultAuditDefaults.RETENTION_DAYS.toInt() }

    suspend fun setAuditCap(cap: Int) {
        store.edit { it[Keys.AUDIT_CAP] = cap }
    }

    suspend fun setAuditRetentionDays(days: Int) {
        store.edit { it[Keys.AUDIT_RETENTION_DAYS] = days }
    }
}
