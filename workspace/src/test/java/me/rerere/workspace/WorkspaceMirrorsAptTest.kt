package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * apt 源生成（按 rootfs 的发行版与版本代号拼 deb822），覆盖 Ubuntu / Debian / 留空 / 未知发行版。
 */
class WorkspaceMirrorsAptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 源路径随运行架构变化（x86 用 /ubuntu/，其他用 /ubuntu-ports/），断言按同一规则推导。 */
    private val ubuntuSuffix: String =
        if (System.getProperty("os.arch").orEmpty().lowercase().let { it == "x86_64" || it == "amd64" }) {
            "/ubuntu/"
        } else {
            "/ubuntu-ports/"
        }

    private fun rootWithOsRelease(id: String, codename: String): File {
        val root = tmp.newFolder()
        File(root, "etc").mkdirs()
        File(File(root, "etc"), "os-release").writeText("ID=$id\nVERSION_CODENAME=$codename\n")
        return root
    }

    private fun sourcesFile(root: File, name: String) = File(root, "etc/apt/sources.list.d/$name")

    @Test
    fun `ubuntu mirror writes deb822 sources with all suites`() {
        val root = rootWithOsRelease("ubuntu", "noble")
        applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "https://mirror.example")).getOrThrow()
        val file = sourcesFile(root, "ubuntu.sources")
        assertTrue(file.isFile)
        val text = file.readText()
        assertTrue("marker missing", text.contains("# managed by workspace mirror"))
        assertTrue("uri wrong: $text", text.contains("URIs: https://mirror.example$ubuntuSuffix"))
        assertTrue("suites wrong: $text", text.contains("Suites: noble noble-updates noble-security noble-backports"))
        assertFalse(sourcesFile(root, "debian.sources").exists())
    }

    @Test
    fun `debian mirror writes main and security sections`() {
        val root = rootWithOsRelease("debian", "trixie")
        applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "https://mirror.example/")).getOrThrow()
        val file = sourcesFile(root, "debian.sources")
        assertTrue(file.isFile)
        val text = file.readText()
        assertTrue(text.contains("URIs: https://mirror.example/debian/"))
        assertTrue(text.contains("URIs: https://mirror.example/debian-security/"))
        assertTrue(text.contains("Suites: trixie-security"))
        assertFalse(sourcesFile(root, "ubuntu.sources").exists())
    }

    @Test
    fun `blank apt deletes our own file`() {
        val root = rootWithOsRelease("ubuntu", "noble")
        applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "https://mirror.example")).getOrThrow()
        assertTrue(sourcesFile(root, "ubuntu.sources").isFile)
        applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "")).getOrThrow()
        assertFalse(sourcesFile(root, "ubuntu.sources").exists())
    }

    @Test
    fun `blank apt keeps foreign sources`() {
        val root = rootWithOsRelease("ubuntu", "noble")
        val file = sourcesFile(root, "ubuntu.sources")
        file.parentFile?.mkdirs()
        file.writeText("Types: deb\nURIs: http://example.org/ubuntu/\n")
        applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "")).getOrThrow()
        assertTrue("foreign source must be kept", file.isFile)
    }

    @Test
    fun `unknown distro leaves sources untouched`() {
        val root = rootWithOsRelease("alpine", "3.21")
        applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "https://mirror.example")).getOrThrow()
        assertFalse(sourcesFile(root, "ubuntu.sources").exists())
        assertFalse(sourcesFile(root, "debian.sources").exists())
    }

    @Test
    fun `missing os-release does not fail`() {
        val root = tmp.newFolder()
        val result = applyWorkspaceMirrors(root, WorkspaceMirrors(apt = "https://mirror.example"))
        assertTrue("should not throw", result.isSuccess)
    }
}
