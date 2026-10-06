package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [WorkspaceManager.rootfsShell] / [WorkspaceManager.isUsableRootfs] 行为。
 *
 * 背景：Alpine 等最小发行版可能只有 busybox sh、无 bash；且其 /bin/sh 是指向
 * /bin/busybox 的绝对路径软链，在宿主机上解析不到目标，须按"软链存在即存在"判定。
 */
class RootfsShellProbeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun linuxDir(vararg bins: String): File =
        tempFolder.newFolder("linux").also { dir ->
            File(dir, "bin").mkdirs()
            bins.forEach { File(dir, "bin/$it").createNewFile() }
        }

    @Test
    fun `bash present wins over sh`() {
        val dir = linuxDir("bash", "sh")
        assertEquals("/bin/bash", WorkspaceManager.rootfsShell(dir))
        assertTrue(WorkspaceManager.isUsableRootfs(dir))
    }

    @Test
    fun `sh only falls back to sh`() {
        val dir = linuxDir("sh")
        assertEquals("/bin/sh", WorkspaceManager.rootfsShell(dir))
        assertTrue(WorkspaceManager.isUsableRootfs(dir))
    }

    @Test
    fun `sh as symlink counts as usable rootfs`() {
        val dir = linuxDir("busybox")
        val shLink = File(dir, "bin/sh").toPath()
        Files.createSymbolicLink(shLink, File("/bin/busybox").toPath())
        assertTrue(WorkspaceManager.isUsableRootfs(dir))
        assertEquals("/bin/sh", WorkspaceManager.rootfsShell(dir))
    }

    @Test
    fun `empty dir is not usable rootfs`() {
        assertFalse(WorkspaceManager.isUsableRootfs(linuxDir()))
    }
}
