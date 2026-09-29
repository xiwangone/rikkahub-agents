package me.rerere.rikkahub.data.vault

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.first

private val Context.vaultLockStore by preferencesDataStore(name = "vault_app_lock")
private val PIN_HASH = stringPreferencesKey("pin_hash")
private val PIN_SALT = stringPreferencesKey("pin_salt")

/**
 * 凭证库 App 内密码门禁（设备无生物识别/锁屏凭据时必须设置，否则凭证库无法打开）。
 *
 * 只存加盐 SHA-256 哈希，不经 AndroidKeyStore——keystore 密钥不随换机迁移，
 * 密文存储会让凭证库永久锁死；哈希与设备无关，换机后仍可验证或重设。
 */
object VaultAppLock {

    suspend fun hasPin(context: Context): Boolean =
        context.vaultLockStore.data.first()[PIN_HASH] != null

    suspend fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(salt, pin)
        context.vaultLockStore.edit {
            it[PIN_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[PIN_HASH] = hash
        }
    }

    suspend fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.vaultLockStore.data.first()
        val storedHash = prefs[PIN_HASH] ?: return false
        val salt = prefs[PIN_SALT]?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?: return false
        return MessageDigest.isEqual(storedHash.toByteArray(), hash(salt, pin).toByteArray())
    }

    suspend fun clearPin(context: Context) {
        context.vaultLockStore.edit { it.remove(PIN_HASH); it.remove(PIN_SALT) }
    }

    private fun hash(salt: ByteArray, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(salt + pin.toByteArray(Charsets.UTF_8))
        // stretch: re-hash to slow offline brute force a bit
        return Base64.encodeToString(md.digest(digest), Base64.NO_WRAP)
    }
}
