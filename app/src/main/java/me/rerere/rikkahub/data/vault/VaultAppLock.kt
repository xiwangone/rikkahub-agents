package me.rerere.rikkahub.data.vault

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.first

private val Context.vaultLockStore by preferencesDataStore(name = "vault_app_lock")
private val PIN_HASH = stringPreferencesKey("pin_hash")
private val PIN_SALT = stringPreferencesKey("pin_salt")

/**
 * 凭证库 App 内密码门禁（设备无生物识别/锁屏凭据时必须设置，否则凭证库无法打开）。
 *
 * 只存加盐的 PBKDF2-HMAC-SHA256 哈希（20 万次迭代，与备份口令同一口令拉伸口径），
 * 不经 AndroidKeyStore——keystore 密钥不随换机迁移，密文存储会让凭证库永久锁死；
 * 哈希与设备无关，换机后仍可验证或重设。旧版的两轮 SHA-256 哈希仍可验证，
 * 验证通过后即场升级为 PBKDF2 哈希。
 */
object VaultAppLock {

    private const val PBKDF2_ITERATIONS = 200_000
    private const val PBKDF2_KEY_BITS = 256
    private const val HASH_PREFIX_V2 = "v2:"

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
        val matched = if (storedHash.startsWith(HASH_PREFIX_V2)) {
            MessageDigest.isEqual(storedHash.toByteArray(), hash(salt, pin).toByteArray())
        } else {
            MessageDigest.isEqual(storedHash.toByteArray(), legacyHash(salt, pin).toByteArray())
        }
        if (matched && !storedHash.startsWith(HASH_PREFIX_V2)) {
            // 旧哈希验证通过：即场升级为 PBKDF2，升级写失败不影响本次验证结果。
            runCatching {
                context.vaultLockStore.edit { it[PIN_HASH] = hash(salt, pin) }
            }
        }
        return matched
    }

    suspend fun clearPin(context: Context) {
        context.vaultLockStore.edit { it.remove(PIN_HASH); it.remove(PIN_SALT) }
    }

    private fun hash(salt: ByteArray, pin: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return HASH_PREFIX_V2 + Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** 旧版哈希（两轮 SHA-256）：仅用于验证存量 PIN，验证通过后即升级为 [hash]。 */
    private fun legacyHash(salt: ByteArray, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(salt + pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md.digest(digest), Base64.NO_WRAP)
    }
}
