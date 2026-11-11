package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 回归：read_folder 的软链不再报 (0B)——列为 [L] 并带目标与目标大小；目录软链不递归；
 * 总条目上限（maxListEntries）仍生效。
 *
 * 远程 SSH 的 folderTree 与本地同一份口径（同一上限、同一 [L] 形态），但它需要真实 SFTP
 * 连接，单元测试覆盖不到，见说明 md 的手工验证方式。
 */
class WorkspaceFileSystemTreeSymlinkTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `tree lists file symlink with target size and link target`() {
        val root = tempFolder.newFolder("workspace")
        File(root, "real.txt").writeText("123456789012345") // 15 bytes
        Files.createSymbolicLink(File(root, "symlink.txt").toPath(), Paths.get("real.txt"))
        val fs = WorkspaceFileSystem()
        val result = fs.tree(root, "")
        val link = result.entries.single { it.name == "symlink.txt" }
        assertTrue(link.isSymlink)
        assertEquals("real.txt", link.linkTarget)
        assertEquals(15L, link.sizeBytes)
        assertFalse(link.isDirectory)
        // 真实文件仍正常列出
        assertTrue(result.entries.any { it.name == "real.txt" && !it.isSymlink })
    }

    @Test
    fun `tree lists directory symlink without recursing into it`() {
        val root = tempFolder.newFolder("workspace")
        val realDir = File(root, "realdir").apply { mkdirs() }
        File(realDir, "inside.txt").writeText("x")
        Files.createSymbolicLink(File(root, "linkdir").toPath(), Paths.get("realdir"))
        val fs = WorkspaceFileSystem()
        val result = fs.tree(root, "")
        val link = result.entries.single { it.name == "linkdir" }
        assertTrue(link.isSymlink)
        assertTrue(link.isDirectory)
        assertEquals("realdir", link.linkTarget)
        // 目标目录里的文件只通过真实目录展开一次，不通过软链再展开一次
        val inside = result.entries.filter { it.name == "inside.txt" }
        assertEquals(1, inside.size)
        assertEquals("realdir/inside.txt", inside.single().path)
    }

    @Test
    fun `tree enforces total entry limit and marks truncated`() {
        val root = tempFolder.newFolder("workspace")
        repeat(5) { File(root, "f$it.txt").writeText("x") }
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 3))
        val result = fs.tree(root, "")
        assertEquals(3, result.entries.size)
        assertTrue(result.truncated)
    }
}
