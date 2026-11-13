package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * 点文件导入冲突改名（[WorkspaceFileSystem.importBytes] 经私有 [WorkspaceFileSystem.resolveConflict]）。
 *
 * 点文件（如 `.gitignore`）的 `nameWithoutExtension` 为空，若直接套 `"$stem ($n)$ext"` 会产出
 * 首字符是空格的名字；此时把整个文件名当 stem 用。
 * 这里只覆盖本地冲突改名的纯文件行为。
 */
class WorkspaceFileSystemDotFileConflictTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fs = WorkspaceFileSystem()

    private fun importText(root: java.io.File, path: String, text: String): WorkspaceFileEntry =
        fs.importBytes(root, path, ByteArrayInputStream(text.toByteArray()))

    @Test
    fun `second gitignore import is renamed with whole name as stem`() {
        val root = tempFolder.newFolder("workspace")
        importText(root, ".gitignore", "node_modules\n")
        val second = importText(root, ".gitignore", "*.log\n")

        assertEquals(".gitignore (1)", second.name)
        assertEquals(".gitignore (1)", second.path)
        assertEquals("node_modules\n", java.io.File(root, ".gitignore").readText())
        assertEquals("*.log\n", java.io.File(root, ".gitignore (1)").readText())
    }

    @Test
    fun `second env import is renamed with whole name as stem`() {
        val root = tempFolder.newFolder("workspace")
        importText(root, ".env", "A=1\n")
        val second = importText(root, ".env", "B=2\n")

        assertEquals(".env (1)", second.name)
        assertEquals(".env (1)", second.path)
    }

    @Test
    fun `third gitignore import increments the suffix`() {
        val root = tempFolder.newFolder("workspace")
        importText(root, ".gitignore", "first\n")
        importText(root, ".gitignore", "second\n")
        val third = importText(root, ".gitignore", "third\n")

        assertEquals(".gitignore (2)", third.name)
        assertEquals(".gitignore (2)", third.path)
    }

    @Test
    fun `dotfile with extension keeps the extension after the suffix`() {
        val root = tempFolder.newFolder("workspace")
        importText(root, ".config.json", "{}\n")
        val second = importText(root, ".config.json", "{\"a\":1}\n")

        // `.config.json` 的 nameWithoutExtension 是 `.config`（非空），不走点文件分支。
        assertEquals(".config (1).json", second.name)
        assertEquals(".config (1).json", second.path)
    }

    @Test
    fun `ordinary file still splits stem and extension`() {
        val root = tempFolder.newFolder("workspace")
        importText(root, "notes.txt", "first\n")
        val second = importText(root, "notes.txt", "second\n")

        assertEquals("notes (1).txt", second.name)
        assertEquals("notes (1).txt", second.path)
    }
}
