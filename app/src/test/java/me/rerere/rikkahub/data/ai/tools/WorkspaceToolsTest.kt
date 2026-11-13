package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceTreeEntry
import me.rerere.workspace.WorkspaceTreeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [40] G1/G2 纯函数覆盖（WorkspaceToolsHelpers.kt），按本地 API 改写：
 * - [pathOutsideWritableRoots]：写入类工具的强制审批闸门。本地版本以 [WorkspaceLayout.writableRoots]
 *   为准（而非写死的前缀表），并先折叠 `..` 再比较 —— `..` 走私仍被判为可写区之外。
 * - [formatWorkspaceTree]：workspace_read_folder 返回给模型的目录树文本（[D]/[F]/[L] 标记）。
 */
class WorkspaceToolsTest {

    private val layout = WorkspaceLayout(
        workspaceDir = "/workspace",
        writableRoots = listOf("/workspace", "/tmp", "/skills"),
    )

    private fun outside(path: String) = buildJsonObject { put("path", path) }.pathOutsideWritableRoots(layout, "path")

    @Test
    fun `clean path inside a writable root is not outside`() {
        assertFalse(outside("/workspace/notes.txt"))
        assertFalse(outside("/tmp/scratch"))
        assertFalse(outside("/workspace"))
        assertFalse(outside("/skills/foo"))
    }

    @Test
    fun `path outside every writable root is outside`() {
        assertTrue(outside("/etc/passwd"))
        assertTrue(outside("/var/log/x"))
    }

    @Test
    fun `dotdot smuggle attempt is treated as outside even with a writable prefix`() {
        assertTrue(outside("/workspace/../etc/x"))
        assertTrue(outside("/tmp/../../etc/passwd"))
    }

    @Test
    fun `missing or relative path fails closed`() {
        assertTrue(buildJsonObject { }.pathOutsideWritableRoots(layout, "path"))
        assertTrue(outside("workspace/notes.txt"))
    }

    @Test
    fun `formatWorkspaceTree renders an empty directory as just its root line`() {
        val text = formatWorkspaceTree("/workspace", WorkspaceTreeResult(entries = emptyList(), truncated = false))
        assertEquals("/workspace\n", text)
    }

    @Test
    fun `formatWorkspaceTree indents nested entries by depth`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "src", name = "src", isDirectory = true, sizeBytes = 0, depth = 1),
                WorkspaceTreeEntry(path = "src/a.txt", name = "a.txt", isDirectory = false, sizeBytes = 12, depth = 2),
                WorkspaceTreeEntry(path = "b.txt", name = "b.txt", isDirectory = false, sizeBytes = 3, depth = 1),
            ),
            truncated = false,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertEquals(
            "/workspace\n" +
                "  [D] src\n" +
                "    [F] a.txt (12B)\n" +
                "  [F] b.txt (3B)\n",
            text,
        )
    }

    @Test
    fun `formatWorkspaceTree marks symlinks with their target`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "l", name = "l", isDirectory = false, sizeBytes = 7, depth = 1, isSymlink = true, linkTarget = "real.txt"),
                WorkspaceTreeEntry(path = "d", name = "d", isDirectory = true, sizeBytes = 0, depth = 1, isSymlink = true, linkTarget = "dir"),
            ),
            truncated = false,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertTrue(text.contains("  [L] l -> real.txt (7B)\n"))
        assertTrue(text.contains("  [L] d -> dir/\n"))
    }

    @Test
    fun `formatWorkspaceTree appends a truncation notice when capped`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "a.txt", name = "a.txt", isDirectory = false, sizeBytes = 1, depth = 1),
            ),
            truncated = true,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertTrue(text.endsWith("(truncated: more entries omitted)\n"))
    }

    @Test
    fun `buildShellToolDescription appends remote detached guidance only when background is unsupported`() {
        val localDesc = buildShellToolDescription(layout, defaultCwd = null, supportsBackground = true)
        assertFalse(localDesc.contains("NO background-task tools"))
        assertFalse(localDesc.contains("setsid nohup"))

        val remoteLayout = WorkspaceLayout(workspaceDir = "/home/ubuntu/work", writableRoots = listOf("/home/ubuntu/work", "/tmp"))
        val remoteDesc = buildShellToolDescription(remoteLayout, defaultCwd = "sub", supportsBackground = false)
        assertTrue(remoteDesc.contains("/home/ubuntu/work"))
        assertTrue(remoteDesc.contains("Defaults to 'sub'."))
        assertTrue(remoteDesc.contains("NO background-task tools"))
        assertTrue(remoteDesc.contains("setsid nohup <cmd> >/tmp/task.log 2>&1 </dev/null &"))
        assertTrue(remoteDesc.contains("pgrep -f"))
        assertTrue(remoteDesc.contains("pkill -f"))
    }

    @Test
    fun `shellTimeoutHint distinguishes local background tools and remote setsid detach`() {
        val localHint = shellTimeoutHint(30L, supportsBackground = true)
        assertTrue(localHint.contains("workspace_run_background"))
        assertFalse(localHint.contains("setsid nohup"))

        val remoteHint = shellTimeoutHint(30L, supportsBackground = false)
        assertTrue(remoteHint.contains("no background-task tools"))
        assertTrue(remoteHint.contains("setsid nohup"))
    }
}
