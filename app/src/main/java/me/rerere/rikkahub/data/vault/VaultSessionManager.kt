package me.rerere.rikkahub.data.vault

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val Context.vaultSessionStore by preferencesDataStore(name = "vault_session")

/**
 * Vault 解锁会话管理器。
 *
 * 安全模型（设计文档阶段 2）：
 * - 指纹 gate 本地访问（App 内查看/签发 token）
 * - 远程调用（ECS/沙箱经 Web 桥）不弹指纹，用短时会话 token
 * - token = base64(expiry_ms) + "." + HMAC-SHA256(secret, expiry_ms)
 * - 默认 30 分钟过期；过期后需重新在 App 内指纹验证签发
 * - secret 存 DataStore（App 私有存储），不导出
 */
class VaultSessionManager(private val context: Context) {

    private object Keys {
        val SECRET = stringPreferencesKey("secret")
        val CREATED_AT = longPreferencesKey("created_at")
    }

    /** 会话有效期：30 分钟。 */
    companion object {
        const val TTL_MS = 30L * 60 * 1000
    }

    /**
     * 签发新会话 token。调用方应在 App 内指纹验证通过后调用。
     * 每次签发刷新 secret + 过期时间（单次有效，旧 token 立即失效）。
     */
    suspend fun issueToken(): String {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secretB64 = Base64.getEncoder().encodeToString(secret)
        val now = System.currentTimeMillis()
        context.vaultSessionStore.edit { prefs ->
            prefs[Keys.SECRET] = secretB64
            prefs[Keys.CREATED_AT] = now
        }
        return sign(secretB64, now + TTL_MS)
    }

    /**
     * 校验 token 是否有效且未过期。
     * 返回 true 表示可以解密。校验失败/过期返回 false。
     */
    suspend fun verifyToken(token: String): Boolean {
        val prefs = context.vaultSessionStore.data.first()
        val secretB64 = prefs[Keys.SECRET] ?: return false
        val created = prefs[Keys.CREATED_AT] ?: return false
        // 会话超时（30 分钟）直接拒绝
        if (System.currentTimeMillis() - created > TTL_MS) return false

        val parts = token.split(".")
        if (parts.size != 2) return false
        val expiry = parts[0].toLongOrNull() ?: return false
        // token 本身的过期时间校验
        if (System.currentTimeMillis() > expiry) return false
        val expected = sign(secretB64, expiry)
        return constantTimeEquals(expected, token)
    }

    /** 撤销当前会话（登出/安全事件）。 */
    suspend fun revoke() {
        context.vaultSessionStore.edit { it.clear() }
    }

    private fun sign(secretB64: String, expiry: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(Base64.getDecoder().decode(secretB64), "HmacSHA256")
        mac.init(key)
        val payload = expiry.toString()
        val sig = mac.doFinal(payload.encodeToByteArray())
        return "$expiry.${Base64.getEncoder().encodeToString(sig)}"
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
