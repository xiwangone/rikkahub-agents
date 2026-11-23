package me.rerere.rikkahub.data.crypto

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Serializable
private data class TestCredentialState(
    val token: String = "",
    val count: Int = 0,
)

/**
 * EncryptedJsonStore 损坏隔离：纯 JVM 测试。
 *
 * AndroidKeyStore 在 JVM 单测通道不可用，因此经 [EncryptedJsonStore] 的
 * `keyProvider` 注入 JVM 生成的 AES 密钥；生产路径默认仍走 KeystoreAesKey。
 */
class EncryptedJsonStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var dir: File
    private lateinit var file: File
    private val corruptionReports = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("encrypted-json-store-test").toFile()
        file = File(dir, "test_accounts.enc")
        corruptionReports.clear()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun storeFor(key: SecretKey): EncryptedJsonStore<TestCredentialState> =
        EncryptedJsonStore(
            file = file,
            keyAlias = "test-alias",
            json = json,
            serializer = TestCredentialState.serializer(),
            defaultFactory = { TestCredentialState() },
            keyProvider = { key },
        ).apply { onCorruptionDetected = { corruptionReports += it } }

    private fun quarantineFiles(): List<File> =
        dir.listFiles()?.filter { it.name.startsWith("${file.name}.corrupt-") }.orEmpty()

    @Test
    fun normalWriteThenReadReturnsState() {
        val store = storeFor(testKey())
        store.write(TestCredentialState(token = "secret", count = 3))

        assertEquals(TestCredentialState(token = "secret", count = 3), store.read())
        assertTrue(corruptionReports.isEmpty())
        assertTrue(quarantineFiles().isEmpty())
    }

    @Test
    fun missingFileReturnsDefaultWithoutCallback() {
        val store = storeFor(testKey())

        assertEquals(TestCredentialState(), store.read())
        assertTrue(corruptionReports.isEmpty())
        assertTrue(quarantineFiles().isEmpty())
    }

    @Test
    fun garbageFileReturnsDefaultAndQuarantines() {
        val key = testKey()
        val store = storeFor(key)
        store.write(TestCredentialState(token = "secret", count = 1))
        file.writeBytes(ByteArray(64) { (it * 7).toByte() })

        assertEquals(TestCredentialState(), store.read())
        assertEquals(1, corruptionReports.size)
        assertFalse(file.exists())
        val quarantined = quarantineFiles()
        assertEquals(1, quarantined.size)
        assertTrue(quarantined.single().length() > 0)
    }

    @Test
    fun shortFileReturnsDefaultAndQuarantines() {
        val key = testKey()
        val store = storeFor(key)
        store.write(TestCredentialState(token = "secret", count = 1))
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertEquals(TestCredentialState(), store.read())
        assertEquals(1, corruptionReports.size)
        assertFalse(file.exists())
        assertEquals(1, quarantineFiles().size)
    }

    @Test
    fun writeAfterCorruptReadCreatesNewUsableFile() {
        val key = testKey()
        val store = storeFor(key)
        store.write(TestCredentialState(token = "old", count = 1))
        file.writeBytes(ByteArray(64) { (it * 7).toByte() })
        assertEquals(TestCredentialState(), store.read())
        assertEquals(1, quarantineFiles().size)

        store.write(TestCredentialState(token = "new", count = 2))

        assertEquals(TestCredentialState(token = "new", count = 2), store.read())
        assertEquals(1, quarantineFiles().size)
        assertEquals(1, corruptionReports.size)
    }

    @Test
    fun wrongKeyReturnsDefaultAndQuarantines() {
        storeFor(testKey()).write(TestCredentialState(token = "secret", count = 1))

        val otherKeyStore = storeFor(testKey())
        assertEquals(TestCredentialState(), otherKeyStore.read())
        assertEquals(1, corruptionReports.size)
        assertFalse(file.exists())
        assertEquals(1, quarantineFiles().size)
    }
}
