package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceTreeEntry
import me.rerere.workspace.WorkspaceTreeResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：read_file 读二进制不再返回 UTF-8 乱码；read_folder 不再把软链报成 (0B)。
 * 嗅探与格式化都是纯函数，可直接单测；tool 的执行路径（需 WorkspaceRepository / 真实 SFTP）
 * 不在这里测，见说明 md 的手工验证方式。
 */
class WorkspaceToolsBinaryAndTreeTest {

    // ---- isBinaryContent ----

    @Test
    fun `empty content is text`() {
        assertFalse(ByteArray(0).isBinaryContent())
    }

    @Test
    fun `plain utf8 text with cjk is text`() {
        assertFalse("hello world\n第二行中文".toByteArray(Charsets.UTF_8).isBinaryContent())
    }

    @Test
    fun `nul byte in first 8KB is binary`() {
        assertTrue(byteArrayOf(0x61, 0x00, 0x62).isBinaryContent())
    }

    @Test
    fun `invalid utf8 without nul is binary`() {
        assertTrue(byteArrayOf(0x61, 0xFF.toByte(), 0x62).isBinaryContent())
    }

    @Test
    fun `utf16 text with nul bytes is binary`() {
        assertTrue("hi".toByteArray(Charsets.UTF_16LE).isBinaryContent())
    }

    @Test
    fun `all byte values incl nul and invalid utf8 is binary`() {
        assertTrue(ByteArray(256) { it.toByte() }.isBinaryContent())
    }

    // ---- formatWorkspaceTree ----

    @Test
    fun `formatWorkspaceTree marks symlink with target and target size`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry("real.txt", "real.txt", false, 15, 1),
                WorkspaceTreeEntry("symlink.txt", "symlink.txt", false, 15, 1, isSymlink = true, linkTarget = "real.txt"),
                WorkspaceTreeEntry("linkdir", "linkdir", true, 0, 1, isSymlink = true, linkTarget = "realdir"),
                WorkspaceTreeEntry("sub", "sub", true, 0, 1),
            ),
            truncated = false,
        )
        val text = formatWorkspaceTree("/workspace", result)
        assertTrue(text.contains("[F] real.txt (15B)"))
        assertTrue(text.contains("[L] symlink.txt -> real.txt (15B)"))
        assertTrue(text.contains("[L] linkdir -> realdir/"))
        assertTrue(text.contains("[D] sub"))
        assertFalse(text.contains("(truncated"))
    }

    @Test
    fun `formatWorkspaceTree appends truncated hint`() {
        val result = WorkspaceTreeResult(
            entries = listOf(WorkspaceTreeEntry("a.txt", "a.txt", false, 1, 1)),
            truncated = true,
        )
        assertTrue(formatWorkspaceTree("/workspace", result).contains("(truncated: more entries omitted)"))
    }
}
