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
import me.rerere.ai.util.ProviderKeyRefs
import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/**
 * `quickImport` 的契约（Robolectric + 内存 fake DAO，跑真加密/真指纹）。
 *
 * 钉住本次修的四条：
 * - 中文名入库不再撞 CRED（七牛云 → QI_NIU_YUN）；
 * - 同名不同值加 `_2` 后缀，不再静默覆盖旧值；
 * - 同值重导：非退化名静默复用，退化名（CRED）改名为新名；
 * - 端点随 key 一起入库（新建写 `endpoint` 元数据，重导合并进已有条目）。
 *
 * `save()` 会往全局 [ProviderKeyRefs] 装解析钩子，tearDown 必须还原，
 * 否则污染其它用例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class QuickImportTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider

    /**
     * 值加密走真 [ProviderCredentialCipher]，密钥源是 AndroidKeyStore——Robolectric 下没有，
     * 故注册与 `ProviderFieldEncryptionTest` 同款合成 JCA provider（只含测试密钥，无真实凭据），
     * 让 `KeyStore.getInstance("AndroidKeyStore")` 返回合成密钥。
     */
    @Before
    fun setUp() {
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
        ProviderCredentialCipher.clearDegradedStateForTest()
    }

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): T {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "QuickImport operation timed out" }
        return runBlocking { task.await() }
    }

    /** fake DAO 的读操作也是 suspend，测试体里经此助手读。 */
    private fun entry(dao: FakeVaultDao, name: String): VaultCredentialEntity? =
        perform { dao.getByName(name) }

    private class FakeVaultDao : VaultCredentialDao {
        val store = LinkedHashMap<String, VaultCredentialEntity>()
        private var nextId = 1L

        override suspend fun getAll(): List<VaultCredentialEntity> =
            store.values.sortedWith(compareBy({ it.grp }, { it.name }))

        override suspend fun getByName(name: String): VaultCredentialEntity? = store[name]

        override suspend fun upsert(entry: VaultCredentialEntity): Long {
            val id = if (entry.id == 0L) nextId++ else entry.id
            store[entry.name] = entry.copy(id = id)
            return id
        }

        override suspend fun insertIfAbsent(entry: VaultCredentialEntity): Long {
            check(!store.containsKey(entry.name)) { "UNIQUE constraint failed: vault_credentials.name" }
            return upsert(entry)
        }

        override suspend fun update(entry: VaultCredentialEntity) {
            store[entry.name] = entry
        }

        override suspend fun updateAll(entries: List<VaultCredentialEntity>) {
            entries.forEach { store[it.name] = it }
        }

        override suspend fun delete(entry: VaultCredentialEntity) {
            store.remove(entry.name)
        }

        override suspend fun count(): Int = store.size
        override suspend fun clearAll() = store.clear()
    }

    private class FakeAuditDao : VaultAuditLogDao {
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
    }

    private fun repository(dao: FakeVaultDao): CredentialVaultRepository =
        CredentialVaultRepository(
            dao = dao,
            auditDao = FakeAuditDao(),
            vaultPreferences = VaultPreferences(context),
            fingerprintKeyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256") },
        )

    @After
    fun tearDown() {
        ProviderKeyRefs.resolve = null
        ProviderKeyRefs.onExpanded = null
        VaultProviderKeyRefs.replaceCacheForTest(emptyMap())
        ProviderCredentialCipher.clearDegradedStateForTest()
        SyntheticSettingsKeyStore.key = null
        SyntheticSettingsKeyStore.unavailable = false
        Security.removeProvider(securityProvider.name)
    }

    @Test
    fun `chinese name becomes pinyin instead of CRED`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        val result = perform {
            repo.quickImport("sk-qiniu-1", preferredName = "七牛云", group = "AI", type = "api-key")
        }
        assertEquals("QI_NIU_YUN", result.name)
        assertFalse(result.reusedExisting)
        perform {
            val entry = dao.getByName("QI_NIU_YUN")
            assertEquals("AI", entry?.grp)
            assertEquals("api-key", entry?.type)
        }
    }

    @Test
    fun `same name different value gets numeric suffix instead of overwriting`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        val first = perform { repo.quickImport("sk-first", preferredName = "七牛云") }
        val second = perform { repo.quickImport("sk-second", preferredName = "七牛云") }
        assertEquals("QI_NIU_YUN", first.name)
        assertEquals("QI_NIU_YUN_2", second.name)
        assertFalse(second.reusedExisting)
        perform {
            assertEquals("sk-first", repo.decryptValue(dao.getByName("QI_NIU_YUN")!!))
            assertEquals("sk-second", repo.decryptValue(dao.getByName("QI_NIU_YUN_2")!!))
        }
    }

    @Test
    fun `reimport same value reuses a non-degenerate name`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        perform { repo.quickImport("sk-same", preferredName = "七牛云") }
        val reused = perform { repo.quickImport("sk-same", preferredName = "商汤") }
        assertTrue(reused.reusedExisting)
        assertEquals("QI_NIU_YUN", reused.name)
        assertNull(reused.renamedFrom)
        assertEquals(1, dao.store.size)
    }

    @Test
    fun `reimport with a new name renames a degenerate entry`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        val created = perform { repo.quickImport("sk-rename-1", preferredName = "!!!") }
        assertEquals("CRED_UNNAMED", created.name)
        val renamed = perform { repo.quickImport("sk-rename-1", preferredName = "七牛云") }
        assertEquals("CRED_UNNAMED", renamed.renamedFrom)
        assertEquals("QI_NIU_YUN", renamed.name)
        assertTrue(renamed.reusedExisting)
        perform {
            assertNull(dao.getByName("CRED_UNNAMED"))
            assertEquals("sk-rename-1", repo.decryptValue(dao.getByName("QI_NIU_YUN")!!))
        }
    }

    @Test
    fun `endpoint is stored on create and merged on reimport`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        val created = perform {
            repo.quickImport(
                "sk-endpoint-1",
                preferredName = "七牛云",
                endpoint = "https://api.qiniu.com/v1",
            )
        }
        assertTrue(created.endpointStored)
        assertEquals("https://api.qiniu.com/v1", CredentialMeta.decode(entry(dao, "QI_NIU_YUN")!!.metaJson)["endpoint"])
        val merged = perform {
            repo.quickImport(
                "sk-endpoint-1",
                preferredName = "七牛云",
                endpoint = "https://api2.qiniu.com/v1",
            )
        }
        assertTrue(merged.reusedExisting)
        assertTrue(merged.endpointStored)
        val meta = CredentialMeta.decode(entry(dao, "QI_NIU_YUN")!!.metaJson)
        assertEquals("https://api2.qiniu.com/v1", meta["endpoint"])
        assertEquals("sk-endpoint-1", repo.decryptValue(entry(dao, "QI_NIU_YUN")!!))
    }

    @Test
    fun `group defaults to Other when not specified`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        perform { repo.quickImport("sk-group-1", preferredName = "SOME_KEY") }
        assertEquals("Other", entry(dao, "SOME_KEY")?.grp)
    }

    @Test
    fun `explicit type wins over inference`() {
        val dao = FakeVaultDao()
        val repo = repository(dao)
        perform { repo.quickImport("sk-type-1", preferredName = "QINIUYUN", type = "api-key") }
        assertEquals("api-key", entry(dao, "QINIUYUN")?.type)
    }
}
