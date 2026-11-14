package me.rerere.rikkahub.data.repository

import android.app.Application
import java.security.Security
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-8 解密失败语义（Robolectric + 合成 Keystore）：
 * - 单主机读取解不开时抛 [UndecryptableCredentialException]，文案明确写「凭证无法解密」，
 *   密文原文绝不作为密码返回（旧 `?: this` 回退会把 `enc:...` 当密码发出）；
 * - 密钥轮换（模拟卸载重装/跨设备恢复导致 Keystore 密钥丢失）后同样抛错；
 * - 列表读取宽容：解不开的主机仍在列表里、密文原文保留（供展示/备份/引用扫描），
 *   但同一主机走单主机读取时抛错——宽容只适用于列表路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class SshHostRepositoryTest {
    private lateinit var securityProvider: SyntheticSettingsSecurityProvider
    private lateinit var dao: FakeSshHostDao
    private lateinit var repo: SshHostRepository

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        securityProvider = SyntheticSettingsSecurityProvider()
        Security.insertProviderAt(securityProvider, 1)
        SyntheticSettingsKeyStore.key = newKey()
        SyntheticSettingsKeyStore.unavailable = false
        ProviderCredentialCipher.clearDegradedStateForTest()
        dao = FakeSshHostDao()
        repo = SshHostRepository(dao)
    }

    @After
    fun tearDown() {
        SyntheticSettingsKeyStore.key = null
        SyntheticSettingsKeyStore.unavailable = false
        Security.removeProvider(securityProvider.name)
    }

    @Test
    fun `plaintext password passes through unchanged`() = runBlocking {
        dao.upsert(SshHostEntity(name = "plain-host", host = "203.0.113.1", user = "tester", createdAtMs = 0L, password = "plain-pw-1001"))
        assertEquals("plain-pw-1001", repo.getByName("plain-host")?.password)
    }

    @Test
    fun `invalid ciphertext throws explicit error on single-host read`() = runBlocking {
        val badCipher = "enc:v1:!!!not-base64!!!"
        dao.upsert(SshHostEntity(name = "bad-host", host = "203.0.113.2", user = "tester", createdAtMs = 0L, password = badCipher))
        try {
            repo.getByName("bad-host")
            fail("expected UndecryptableCredentialException")
        } catch (e: UndecryptableCredentialException) {
            assertTrue(e.message!!.contains("cannot be decrypted"))
            assertFalse(e.message!!.contains(badCipher))
            assertEquals("bad-host", e.hostName)
            assertEquals("password", e.field)
        }
        // 同一主机走 getByHost 同样抛错
        try {
            repo.getByHost("203.0.113.2")
            fail("expected UndecryptableCredentialException")
        } catch (e: UndecryptableCredentialException) {
            assertTrue(e.message!!.contains("cannot be decrypted"))
        }
    }

    @Test
    fun `round-trip encrypts on disk and decrypts on read`() = runBlocking {
        val password = "roundtrip-pw-1002-unique"
        repo.upsert(SshHostEntity(name = "rt-host", host = "203.0.113.3", user = "tester", createdAtMs = 0L, password = password))
        val stored = dao.getByName("rt-host")?.password.orEmpty()
        assertTrue(stored.startsWith("enc:v1:"))
        assertNotEquals(password, stored)
        assertEquals(password, repo.getByName("rt-host")?.password)
    }

    @Test
    fun `key loss throws instead of returning ciphertext as password`() = runBlocking {
        val password = "keyloss-pw-1003-unique"
        repo.upsert(SshHostEntity(name = "kl-host", host = "203.0.113.4", user = "tester", createdAtMs = 0L, password = password))
        assertEquals(password, repo.getByName("kl-host")?.password)
        // 模拟 Keystore 密钥丢失（卸载重装/跨设备恢复）：轮换合成密钥
        SyntheticSettingsKeyStore.key = newKey()
        try {
            repo.getByName("kl-host")
            fail("expected UndecryptableCredentialException after key loss")
        } catch (e: UndecryptableCredentialException) {
            assertTrue(e.message!!.contains("cannot be decrypted"))
            assertFalse(e.message!!.contains("enc:v1:"))
        }
    }

    @Test
    fun `getAll stays lenient while single-host read throws`() = runBlocking {
        val password = "lenient-pw-1004-unique"
        repo.upsert(SshHostEntity(name = "good-host", host = "203.0.113.5", user = "tester", createdAtMs = 0L, password = "good-pw-1004"))
        repo.upsert(SshHostEntity(name = "broken-host", host = "203.0.113.6", user = "tester", createdAtMs = 0L, password = password))
        SyntheticSettingsKeyStore.key = newKey()
        val all = repo.getAll()
        assertEquals(2, all.size)
        val broken = all.first { it.name == "broken-host" }
        // 列表里保留密文原文（供展示 has_password / 备份 / 引用扫描），不静默丢主机
        assertTrue(broken.password!!.startsWith("enc:v1:"))
        assertTrue(repo.getAll().first { it.name == "good-host" }.password!!.startsWith("enc:v1:"))
        // 但同一主机走连接路径时必须抛错，不能把密文当密码发出
        try {
            repo.getByName("broken-host")
            fail("expected UndecryptableCredentialException")
        } catch (e: UndecryptableCredentialException) {
            assertTrue(e.message!!.contains("cannot be decrypted"))
        }
    }

    private class FakeSshHostDao : SshHostDao {
        private val store = LinkedHashMap<String, SshHostEntity>()
        override suspend fun getAll(): List<SshHostEntity> = store.values.sortedBy { it.name }
        override suspend fun getByName(name: String): SshHostEntity? = store[name]
        override suspend fun getByHost(host: String): SshHostEntity? = store.values.firstOrNull { it.host == host }
        override suspend fun upsert(host: SshHostEntity) {
            store[host.name] = host
        }
        override suspend fun delete(host: SshHostEntity) {
            store.remove(host.name)
        }
        override suspend fun deleteByName(name: String) {
            store.remove(name)
        }
    }
}
