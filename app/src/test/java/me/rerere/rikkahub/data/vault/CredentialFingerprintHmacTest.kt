package me.rerere.rikkahub.data.vault

import android.app.Application
import android.content.Context
import javax.crypto.spec.SecretKeySpec
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1-5 实例指纹（设备密钥）回归：纯 JVM 测试，不碰 KeyStore。
 *
 * - 仓库实例方法算出的指纹，必须等于同密钥的纯函数结果（证明实例路径用的就是
 *   注入的设备密钥，而不是偷偷回到无盐 SHA-256）；
 * - 换密钥就换指纹；没有密钥时离线算不出指纹。
 *
 * KeyStore 本身（KeystoreHmacKey 的生成/取回）不在这里测——Robolectric 的
 * AndroidKeyStore 影子覆盖不了设备密钥生成，需要 Android Keystore 环境。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class CredentialFingerprintHmacTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    // 固定测试密钥：只用来证明"指纹依赖密钥"，不是生产密钥。
    private val testKey = "test-only-fingerprint-key-0123456789".toByteArray()
    private val otherKey = "another-test-only-key-9876543210".toByteArray()

    private fun repositoryWithKey(key: ByteArray): CredentialVaultRepository =
        CredentialVaultRepository(
            dao = object : VaultCredentialDao {
                override suspend fun getAll(): List<VaultCredentialEntity> = emptyList()
                override suspend fun getByName(name: String): VaultCredentialEntity? = null
                override suspend fun upsert(entry: VaultCredentialEntity): Long = 0L
                override suspend fun insertIfAbsent(entry: VaultCredentialEntity): Long = 0L
                override suspend fun update(entry: VaultCredentialEntity) = Unit
                override suspend fun updateAll(entries: List<VaultCredentialEntity>) = Unit
                override suspend fun delete(entry: VaultCredentialEntity) = Unit
                override suspend fun count(): Int = 0
                override suspend fun clearAll() = Unit
            },
            auditDao = object : VaultAuditLogDao {
                override suspend fun getRecent(limit: Int): List<VaultAuditLogEntity> = emptyList()
                override suspend fun queryAudit(
                    credential: String,
                    action: String,
                    sinceMs: Long,
                    limit: Int,
                ): List<VaultAuditLogEntity> = emptyList()

                override suspend fun insert(entry: VaultAuditLogEntity) = Unit
                override suspend fun deleteOlderThan(cutoffMs: Long, protectedActions: List<String>) = Unit
                override suspend fun trimTo(keep: Int, protectedActions: List<String>) = Unit
                override suspend fun count(): Int = 0
                override suspend fun findRollupTarget(
                    credentialName: String,
                    caller: String,
                    action: String,
                    conversationId: String?,
                    sinceMs: Long,
                ): VaultAuditLogEntity? = null

                override suspend fun bumpCount(id: Long, nowMs: Long) = Unit
                override suspend fun clearAll() = Unit
            },
            vaultPreferences = VaultPreferences(context),
            fingerprintKeyProvider = { SecretKeySpec(key, "HmacSHA256") },
        )

    @Test
    fun `instance fingerprint matches pure function with same key`() {
        val repo = repositoryWithKey(testKey)
        val v = "test-value-abcdefghijklmnop"
        assertEquals(CredentialVaultRepository.fingerprint(v, testKey), repo.fingerprint(v))
        assertEquals(repo.fingerprint(v), repo.fingerprint(v))
    }

    @Test
    fun `instance fingerprint changes with key`() {
        val v = "test-value-abcdefghijklmnop"
        assertNotEquals(
            repositoryWithKey(testKey).fingerprint(v),
            repositoryWithKey(otherKey).fingerprint(v),
        )
    }

    @Test
    fun `instance fingerprint is not plain sha256 of the value`() {
        val v = "test-value-abcdefghijklmnop"
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(v.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        assertNotEquals(sha256, repositoryWithKey(testKey).fingerprint(v))
    }
}
