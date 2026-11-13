package me.rerere.rikkahub.data.vault

import android.app.Application
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P1-3 解析器 fail-closed 回归（Robolectric）：
 * - 未传授权检查时，需要授权的用途（SSH_AUTH）一律拒绝——不要把 null 读成“不检查”；
 * - 不需要授权的用途（WEB_BRIDGE）不受影响，继续走到库查询；
 * - 传了按名过滤的检查时，白名单外的名字拒绝、白名单内的名字放行到库查询。
 *
 * 授权门在 `getByName` 之前，所以空库就能测到门的行为；解密路径不在本文件测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class CredentialResolverTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): T {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "CredentialResolver operation timed out" }
        return runBlocking { task.await() }
    }

    private fun emptyRepository(): CredentialVaultRepository =
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
        )

    @Test
    fun `missing auth check denies auth-required purpose`() {
        perform {
            val resolver = CredentialResolver(emptyRepository())
            val r = resolver.resolve("ANY_CRED", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.NotAuthorized)
        }
    }

    @Test
    fun `missing auth check does not block purpose without authorization requirement`() {
        perform {
            val resolver = CredentialResolver(emptyRepository())
            // WEB_BRIDGE 不要求授权：门不拦，空库返回 Missing（走到库查询的证明）。
            val r = resolver.resolve("ANY_CRED", CredentialPurpose.WEB_BRIDGE, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.Missing)
        }
    }

    @Test
    fun `name filtered check denies non whitelisted name and passes whitelisted one`() {
        perform {
            val resolver = CredentialResolver(emptyRepository()) { name -> name == "ALLOWED_CRED" }
            val denied = resolver.resolve("OTHER_CRED", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(denied is CredentialResolution.NotAuthorized)
            // 白名单内的名字通过授权门（空库 → Missing，说明门已放行）。
            val passed = resolver.resolve("ALLOWED_CRED", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(passed is CredentialResolution.Missing)
        }
    }

    @Test
    fun `reference path is also fail-closed without auth check`() {
        perform {
            val resolver = CredentialResolver(emptyRepository())
            val r = resolver.resolveRef("ANY_CRED", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.NotAuthorized)
        }
    }
}
