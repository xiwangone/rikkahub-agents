package me.rerere.rikkahub.data.vault

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val Context.vaultSessionStore by preferencesDataStore(name = "vault_session")

/** 默认作用域：可解密凭据（顶层常量，data class 默认值需在 companion 前可解析） */
private val DEFAULT_SCOPES: List<String> = listOf("decrypt")

/** 多会话信息（UI 展示用） */
@Serializable
data class VaultSessionInfo(
    val id: String,
    val label: String,
    val ttlMs: Long,
    val createdAt: Long,
    val lastUsedAt: Long,
    val scopes: List<String> = DEFAULT_SCOPES,
) {
    /** 剩余有效毫秒（当场有效 = Long.MAX_VALUE） */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else (createdAt + ttlMs - now).coerceAtLeast(0)
}

@Serializable
private data class VaultSessionRecord(
    val id: String,
    val label: String,
    val ttlMs: Long,
    val createdAt: Long,
    val lastUsedAt: Long,
    val scopes: List<String> = DEFAULT_SCOPES,
)

/**
 * Vault 解锁会话管理器（多会话版）。
 *
 * 安全模型（连接管理中枢 v2 阶段 1）：
 * - 指纹 gate 本地访问（App 内查看/签发 token）
 * - 多会话：每个会话独立 token（id.expiry.HMAC），独立过期，可单独撤销
 * - 主密钥 master_secret 一次性生成存 DataStore，不出设备
 * - 兼容旧单会话（旧 secret 字段保留校验，迁移期）
 */
class VaultSessionManager(private val context: Context) {

    private object Keys {
        /** 主密钥（一次性生成，多会话签名用） */
        val MASTER_SECRET = stringPreferencesKey("master_secret")
        /** 会话列表 JSON */
        val SESSIONS = stringPreferencesKey("sessions")

        // ---- 旧单会话字段（兼容迁移）----
        val SECRET = stringPreferencesKey("secret")
        val CREATED_AT = longPreferencesKey("created_at")
        val SESSION_MODE = booleanPreferencesKey("session_mode")
    }

    companion object {
        const val TTL_MS = 30L * 60 * 1000
        const val TTL_7D_MS = 7L * 24 * 60 * 60 * 1000
        const val TTL_30D_MS = 30L * 24 * 60 * 60 * 1000
        const val TTL_SESSION_MS = Long.MAX_VALUE // 当场有效
        const val DEFAULT_LABEL = "默认"

        /** 作用域常量 */
        const val SCOPE_DECRYPT = "decrypt"
        const val SCOPE_SESSION = "session"

        private val json = Json { ignoreUnknownKeys = true }
    }

    // ---------- 多会话核心 ----------

    /**
     * 签发新会话 token。
     * [label] 会话用途标识；[ttlMs] 有效期（TTL_SESSION_MS=当场有效）。
     * 返回完整 token（id.expiry.HMAC）。
     */
    suspend fun issueSessionToken(label: String = DEFAULT_LABEL, ttlMs: Long = TTL_MS): String {
        val master = ensureMasterSecret()
        val id = randomId()
        val now = System.currentTimeMillis()
        val record = VaultSessionRecord(
            id = id, label = label, ttlMs = ttlMs, createdAt = now, lastUsedAt = now,
        )
        updateSessions { it + record }
        val expiry = if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else now + ttlMs
        return signToken(master, id, expiry)
    }

    /** 校验 token 是否有效（多会话）。[requiredScope] 指定后还需会话拥有该作用域。 */
    suspend fun verifyToken(token: String, requiredScope: String? = null): Boolean {
        val master = context.vaultSessionStore.data.first()[Keys.MASTER_SECRET] ?: return legacyVerify(token)
        val parts = token.split(".")
        if (parts.size != 3) return legacyVerify(token)
        val id = parts[0]
        val expiry = parts[1].toLongOrNull() ?: return false
        if (System.currentTimeMillis() > expiry) return false
        val expected = signToken(master, id, expiry)
        if (!constantTimeEquals(expected, token)) return false
        // 会话必须仍存在（撤销后立即失效）
        val sessions = readSessions()
        val rec = sessions.find { it.id == id } ?: return false
        // 作用域校验
        if (requiredScope != null && rec.scopes.none { it == requiredScope || it == "all" }) return false
        // 更新 lastUsedAt（审计）
        if (System.currentTimeMillis() - rec.lastUsedAt > 60_000) {
            updateSessions { list ->
                list.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
            }
        }
        return true
    }

    /** 会话列表（UI 展示）。 */
    suspend fun listSessions(): List<VaultSessionInfo> =
        readSessions().map { VaultSessionInfo(it.id, it.label, it.ttlMs, it.createdAt, it.lastUsedAt) }

    /** 撤销单个会话。 */
    suspend fun revokeSession(tokenId: String) {
        updateSessions { list -> list.filterNot { it.id == tokenId } }
    }

    /** 撤销全部会话。 */
    suspend fun revokeAll() {
        updateSessions { emptyList() }
    }

    // ---------- 兼容旧单会话 API ----------

    /** 兼容：签发单会话（委托多会话，label=默认）。 */
    suspend fun issueToken(sessionMode: Boolean = false): String {
        val ttl = if (sessionMode) TTL_SESSION_MS else TTL_MS
        return issueSessionToken(label = DEFAULT_LABEL, ttlMs = ttl)
    }

    /** 兼容：旧字段校验（迁移期）。 */
    private suspend fun legacyVerify(token: String): Boolean {
        val prefs = context.vaultSessionStore.data.first()
        val secretB64 = prefs[Keys.SECRET] ?: return false
        val created = prefs[Keys.CREATED_AT] ?: return false
        val sessionMode = prefs[Keys.SESSION_MODE] ?: false
        if (!sessionMode && System.currentTimeMillis() - created > TTL_MS) return false
        val parts = token.split(".")
        if (parts.size != 2) return false
        val expiry = parts[0].toLongOrNull() ?: return false
        if (System.currentTimeMillis() > expiry) return false
        val expected = legacySign(secretB64, expiry)
        return constantTimeEquals(expected, token)
    }

    /** 兼容：读当前会话模式（true=当场，false=30min）——若有多会话，返回最近签发的。 */
    suspend fun getSessionMode(): Boolean {
        val sessions = readSessions()
        if (sessions.isNotEmpty()) {
            return sessions.maxByOrNull { it.createdAt }?.ttlMs == TTL_SESSION_MS
        }
        return context.vaultSessionStore.data.first()[Keys.SESSION_MODE] ?: false
    }

    /** 兼容：是否已签发任何会话。 */
    suspend fun hasSession(): Boolean {
        val sessions = readSessions()
        if (sessions.isNotEmpty()) return true
        return context.vaultSessionStore.data.first()[Keys.SECRET] != null
    }

    /** 兼容：重新生成最近会话 token（页面重进恢复展示）。 */
    suspend fun reissueToken(): String? {
        val master = context.vaultSessionStore.data.first()[Keys.MASTER_SECRET]
        val sessions = readSessions()
        if (master != null && sessions.isNotEmpty()) {
            val latest = sessions.maxByOrNull { it.createdAt } ?: return null
            return reissueTokenFor(latest.id)
        }
        // 旧单会话
        val prefs = context.vaultSessionStore.data.first()
        val secretB64 = prefs[Keys.SECRET] ?: return null
        val created = prefs[Keys.CREATED_AT] ?: return null
        val sessionMode = prefs[Keys.SESSION_MODE] ?: false
        val expiry = if (sessionMode) Long.MAX_VALUE else created + TTL_MS
        if (!sessionMode && System.currentTimeMillis() > expiry) return null
        return legacySign(secretB64, expiry)
    }

    /** 按会话 id 重新生成 token（UI 列表复制用）。返回 null 表示会话不存在或已过期。 */
    suspend fun reissueTokenFor(tokenId: String): String? {
        val master = context.vaultSessionStore.data.first()[Keys.MASTER_SECRET] ?: return null
        val sessions = readSessions()
        val rec = sessions.find { it.id == tokenId } ?: return null
        val expiry = if (rec.ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else rec.createdAt + rec.ttlMs
        if (System.currentTimeMillis() > expiry) return null
        return signToken(master, rec.id, expiry)
    }

    /** 兼容：撤销全部（旧 revoke 语义）。 */
    suspend fun revoke() {
        updateSessions { emptyList() }
        context.vaultSessionStore.edit { it.clear() }
    }

    // ---------- 内部 ----------

    private suspend fun ensureMasterSecret(): String {
        val prefs = context.vaultSessionStore.data.first()
        prefs[Keys.MASTER_SECRET]?.let { return it }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.encodeToString(secret, Base64.NO_WRAP)
        context.vaultSessionStore.edit { it[Keys.MASTER_SECRET] = b64 }
        return b64
    }

    private fun randomId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private suspend fun readSessions(): List<VaultSessionRecord> {
        val raw = context.vaultSessionStore.data.first()[Keys.SESSIONS] ?: return emptyList()
        return runCatching { json.decodeFromString<List<VaultSessionRecord>>(raw) }.getOrDefault(emptyList())
    }

    private suspend fun updateSessions(transform: (List<VaultSessionRecord>) -> List<VaultSessionRecord>) {
        context.vaultSessionStore.edit { prefs ->
            val current = prefs[Keys.SESSIONS]?.let {
                runCatching { json.decodeFromString<List<VaultSessionRecord>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = transform(current)
            prefs[Keys.SESSIONS] = json.encodeToString(updated)
        }
    }

    private fun signToken(masterB64: String, id: String, expiry: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(Base64.decode(masterB64, Base64.NO_WRAP), "HmacSHA256")
        mac.init(key)
        val payload = "$id.$expiry"
        val sig = mac.doFinal(payload.encodeToByteArray())
        return "$id.$expiry.${Base64.encodeToString(sig, Base64.NO_WRAP)}"
    }

    private fun legacySign(secretB64: String, expiry: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(Base64.decode(secretB64, Base64.NO_WRAP), "HmacSHA256")
        mac.init(key)
        val sig = mac.doFinal(expiry.toString().encodeToByteArray())
        return "$expiry.${Base64.encodeToString(sig, Base64.NO_WRAP)}"
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
