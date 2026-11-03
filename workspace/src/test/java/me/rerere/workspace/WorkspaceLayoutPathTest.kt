package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：工作区路径用 `..` 绕过免审批前缀检查（isWritable startsWith 漏洞）。
 * 修复后 isWritable 必须基于折叠后的真实位置判定。
 */
class WorkspaceLayoutPathTest {

    private val layout = WorkspaceLayout(
        workspaceDir = "/root",
        writableRoots = listOf("/root", "/tmp", "/skills", "/mnt/shared"),
    )

    @Test
    fun `dotdot inside root resolves back to root and stays writable`() {
        assertTrue(layout.isWritable("/root/../root/notes.md"))
        assertTrue(layout.isWritable("/root/sub/../notes.md"))
    }

    @Test
    fun `dotdot escape leaves writable roots`() {
        // 修复前 "/root/../etc" 满足 startsWith("/root/")，会跳过审批
        assertFalse(layout.isWritable("/root/../etc/passwd"))
        assertFalse(layout.isWritable("/root//../data/secret"))
        assertFalse(layout.isWritable("/tmp/../var/tmp/evil"))
        assertFalse(layout.isWritable("/skills/../mnt/other"))
    }

    @Test
    fun `trailing slashes and dot segments are folded`() {
        assertTrue(layout.isWritable("/root/sub/./nested/..//file.txt"))
        assertTrue(layout.isWritable("/root/"))
        assertTrue(layout.isWritable("/root"))
    }

    @Test
    fun `normalizeWorkspacePath canonical cases`() {
        assertEquals("/", normalizeWorkspacePath("/"))
        assertEquals("/", normalizeWorkspacePath("/.."))
        assertEquals("/", normalizeWorkspacePath("/../.."))
        assertEquals("/b", normalizeWorkspacePath("/a/..//b/./"))
        assertEquals("/b", normalizeWorkspacePath("/a/../b"))
        assertEquals("/a", normalizeWorkspacePath("//a"))
        assertEquals("/", normalizeWorkspacePath("/a/../.."))
    }

    @Test
    fun `non absolute path is rejected`() {
        try {
            normalizeWorkspacePath("root/file")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // pass
        }
    }
}
