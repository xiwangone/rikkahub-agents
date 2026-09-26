package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CA 证书合并安装（Android 系统 CA → rootfs bundle），覆盖正常合并 / 空目录 /
 * 已存在跳过（force=false）/ Alpine 额外写 cert.pem。
 */
class WorkspaceCaCertsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pem1 = "-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----\n"
    private val pem2 = "-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----\n"

    private fun fakeSystemCaDir(vararg contents: String): File {
        val dir = tmp.newFolder("system-ca-$System.nanoTime")
        contents.forEachIndexed { i, c ->
            File(dir, "hash.${i}0").writeText(c)
        }
        return dir
    }

    private fun rootWithOsRelease(id: String?): File {
        val root = tmp.newFolder()
        File(root, "etc/ssl/certs").mkdirs()
        if (id != null) {
            File(root, "etc/os-release").writeText("ID=$id\nVERSION_CODENAME=test\n")
        }
        return root
    }

    @Test
    fun `merges pem files in name order and skips non-certificate files`() {
        val root = rootWithOsRelease("ubuntu")
        // 放入：一张证书 + 一个非证书文本（应被忽略）
        val caDir = fakeSystemCaDir(pem1, "not a certificate")
        File(caDir, "hash.10").writeText(pem2)

        val result = installCaCerts(root, systemCaDir = caDir)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())
        val bundle = File(root, CA_BUNDLE_PATH).readText()
        assertTrue(bundle.contains("AAA"))
        assertTrue(bundle.contains("BBB"))
        assertFalse(bundle.contains("not a certificate"))
        // 排序确定性：hash.00（AAA）在 hash.10（BBB）之前
        assertTrue(bundle.indexOf("AAA") < bundle.indexOf("BBB"))
    }

    @Test
    fun `missing or empty system ca dir fails`() {
        val root = rootWithOsRelease("ubuntu")
        assertTrue(installCaCerts(root, systemCaDir = File(root, "nonexistent")).isFailure)

        val emptyDir = tmp.newFolder("empty-ca")
        assertTrue(installCaCerts(root, systemCaDir = emptyDir).isFailure)
    }

    @Test
    fun `force false skips when bundle already exists`() {
        val root = rootWithOsRelease("ubuntu")
        val bundleFile = File(root, CA_BUNDLE_PATH)
        bundleFile.writeText("existing")

        val result = installCaCerts(root, systemCaDir = fakeSystemCaDir(pem1), force = false)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
        assertEquals("existing", bundleFile.readText())
    }

    @Test
    fun `force true rewrites existing bundle`() {
        val root = rootWithOsRelease("ubuntu")
        File(root, CA_BUNDLE_PATH).writeText("stale")

        val result = installCaCerts(root, systemCaDir = fakeSystemCaDir(pem1), force = true)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertTrue(File(root, CA_BUNDLE_PATH).readText().contains("AAA"))
    }

    @Test
    fun `alpine additionally writes etc ssl cert pem`() {
        val alpine = rootWithOsRelease("alpine")
        installCaCerts(alpine, systemCaDir = fakeSystemCaDir(pem1))
        val pemFile = File(alpine, "etc/ssl/cert.pem")
        assertTrue(pemFile.isFile)
        assertTrue(pemFile.readText().contains("AAA"))

        val ubuntu = rootWithOsRelease("ubuntu")
        installCaCerts(ubuntu, systemCaDir = fakeSystemCaDir(pem1))
        assertFalse(File(ubuntu, "etc/ssl/cert.pem").exists())
    }
}
