package me.rerere.rikkahub.data.vault

import android.app.Application
import android.content.Context
import android.os.Looper
import java.security.Security
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    /**
     * F1 用例要造一条可解密的条目：值加密走真 [ProviderCredentialCipher]，
     * 密钥源是 AndroidKeyStore——Robolectric 下没有，故注册与 QuickImportTest
     * 同款合成 JCA provider（只含测试密钥，无真实凭据），让
     * `KeyStore.getInstance("AndroidKeyStore")` 返回合成密钥。
     */
    @Before
    fun setUp() {
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
    }

    @After
    fun tearDown() {
        SyntheticSettingsKeyStore.key = null
        SyntheticSettingsKeyStore.unavailable = false
        Security.removeProvider(securityProvider.name)
    }

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

    private fun emptyRepository(
        getByNameThrows: Boolean = false,
        auditThrows: Boolean = false,
        entries: Map<String, VaultCredentialEntity> = emptyMap(),
    ): CredentialVaultRepository =
        CredentialVaultRepository(
            dao = object : VaultCredentialDao {
                override suspend fun getAll(): List<VaultCredentialEntity> = entries.values.toList()
                override suspend fun getByName(name: String): VaultCredentialEntity? =
                    if (getByNameThrows) throw IllegalStateException("vault db unavailable") else entries[name]
                override suspend fun upsert(entry: VaultCredentialEntity): Long = 0L
                override suspend fun insertIfAbsent(entry: VaultCredentialEntity): Long = 0L
                override suspend fun update(entry: VaultCredentialEntity) = Unit
                override suspend fun updateAll(entries: List<VaultCredentialEntity>) = Unit
                override suspend fun delete(entry: VaultCredentialEntity) = Unit
                override suspend fun count(): Int = entries.size
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

                override suspend fun insert(entry: VaultAuditLogEntity) {
                    if (auditThrows) throw IllegalStateException("audit db unavailable")
                }
                override suspend fun deleteOlderThan(cutoffMs: Long, protectedActions: List<String>) = Unit
                override suspend fun trimTo(keep: Int, protectedActions: List<String>) = Unit
                override suspend fun count(): Int = 0
                override suspend fun findRollupTarget(
                    credentialName: String,
                    caller: String,
                    action: String,
                    conversationId: String?,
                    sinceMs: Long,
                ): VaultAuditLogEntity? =
                    if (auditThrows) throw IllegalStateException("audit db unavailable") else null

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

    /**
     * 2-13 回归：`resolveLiteralOrReference` 承诺"从不抛异常"，但解析器
     * 原来把 Room / DataStore 调用裸露在外——库错会变成调用链上的原始异常。
     * 库读失败按"解析不到"处理（查找失败 → Missing，授权检查失败 → 拒绝，
     * 审计写失败不影响本次解析结果），真实原因只记警告日志。
     */
    @Test
    fun `vault lookup failure maps to Missing instead of throwing`() {
        perform {
            val resolver = CredentialResolver(emptyRepository(getByNameThrows = true))
            val r = resolver.resolve("ANY_CRED", CredentialPurpose.WEB_BRIDGE, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.Missing)
        }
    }

    @Test
    fun `field reference lookup failure maps to Missing instead of throwing`() {
        perform {
            val resolver = CredentialResolver(emptyRepository(getByNameThrows = true))
            val r = resolver.resolveRef("ANY_CRED.endpoint", CredentialPurpose.WEB_BRIDGE, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.Missing)
        }
    }

    @Test
    fun `authorization check failure denies instead of throwing`() {
        perform {
            val resolver = CredentialResolver(emptyRepository()) { throw IllegalStateException("session store unavailable") }
            val r = resolver.resolve("ANY_CRED", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.NotAuthorized)
        }
    }

    @Test
    fun `audit write failure does not fail the operation`() {
        perform {
            val repository = emptyRepository(auditThrows = true)
            val resolver = CredentialResolver(repository)
            val r = resolver.resolve("ANY_CRED", CredentialPurpose.WEB_BRIDGE, caller = "test", audit = true)
            assertTrue(r is CredentialResolution.Missing)
            // 审计本身也不抛：直接调 logAccess 应正常返回。
            repository.logAccess("ANY_CRED", "test", "web_bridge")
        }
    }

    @Test
    fun `literal-or-reference never throws on vault failure`() {
        perform {
            val repository = emptyRepository(getByNameThrows = true)
            val value = resolveLiteralOrReference(
                "\$\$ANY_CRED",
                CredentialPurpose.WEB_BRIDGE,
                repository,
                caller = "test",
            )
            assertTrue(value == null)
        }
    }

    // ---- F1：归一前旧名经 `$$` 解析 ----

    /** 库里只有归一后的名字（`PC ED25519 KEY` 入库前是归一前形态）。 */
    private fun legacyNameRepository(): CredentialVaultRepository {
        val entry = VaultCredentialEntity(
            name = "PC_ED25519_KEY",
            metaJson = """{"endpoint":"https://example.com"}""",
            valueEncrypted = ProviderCredentialCipher.encrypt("fake-private-key"),
        )
        return emptyRepository(entries = mapOf(entry.name to entry))
    }

    @Test
    fun `legacy pre-normalization name resolves via normalized retry`() {
        perform {
            val resolver = CredentialResolver(legacyNameRepository()) { true }
            val r = resolver.resolve("PC ED25519 KEY", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.Granted)
            assertEquals("fake-private-key", (r as CredentialResolution.Granted).value)
        }
    }

    @Test
    fun `exact normalized name still resolves directly`() {
        perform {
            val resolver = CredentialResolver(legacyNameRepository()) { true }
            val r = resolver.resolve("PC_ED25519_KEY", CredentialPurpose.SSH_AUTH, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.Granted)
            assertEquals("fake-private-key", (r as CredentialResolution.Granted).value)
        }
    }

    @Test
    fun `dollar reference with legacy name resolves`() {
        perform {
            val repository = legacyNameRepository()
            val value = resolveLiteralOrReference(
                "\$\$PC ED25519 KEY",
                CredentialPurpose.SSH_AUTH,
                repository,
                caller = "test",
                isAuthorized = { true },
            )
            assertEquals("fake-private-key", value)
        }
    }

    @Test
    fun `field reference with legacy name resolves via normalized retry`() {
        perform {
            val resolver = CredentialResolver(legacyNameRepository()) { true }
            val r = resolver.resolveRef(
                "PC ED25519 KEY.endpoint",
                CredentialPurpose.WEB_BRIDGE,
                caller = "test",
                audit = false,
            )
            assertTrue(r is CredentialResolution.Granted)
            assertEquals("https://example.com", (r as CredentialResolution.Granted).value)
        }
    }

    @Test
    fun `truly missing name stays Missing after normalized retry`() {
        perform {
            val resolver = CredentialResolver(emptyRepository()) { true }
            val r = resolver.resolve("NO_SUCH_CRED", CredentialPurpose.WEB_BRIDGE, caller = "test", audit = false)
            assertTrue(r is CredentialResolution.Missing)
            assertEquals("NO_SUCH_CRED", (r as CredentialResolution.Missing).name)
        }
    }
}
