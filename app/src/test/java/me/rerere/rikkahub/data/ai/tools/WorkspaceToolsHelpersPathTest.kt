package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回归：workspace 工具入参路径必须折叠 .. 再交给 isWritable 审批闸门。 */
class WorkspaceToolsHelpersPathTest {

    private fun obj(path: String): JsonObject = buildJsonObject { put("path", path) }

    @Test
    fun `absolutePath folds dotdot and dot segments`() {
        assertEquals("/root/sub/file.txt", obj("/root/../root/sub/../sub/file.txt").absolutePath("path"))
        assertEquals("/root/file.txt", obj("//root///file.txt").absolutePath("path"))
        assertEquals("/", obj("/..").absolutePath("path"))
    }

    @Test
    fun `absolutePath normalizes escapes of writable roots`() {
        // 修复前这两条会以未折叠形态通过 pathOutsideWritableRoots 的 startsWith 检查
        assertEquals("/etc/passwd", obj("/root/../etc/passwd").absolutePath("path"))
        assertEquals("/var/tmp/evil", obj("/tmp/../var/tmp/evil").absolutePath("path"))
    }

    @Test
    fun `invalid paths still fail fast`() {
        for (bad in listOf("relative/path.txt", "/bad\u0000path")) {
            try {
                obj(bad).absolutePath("path")
                throw AssertionError("expected IllegalArgumentException for $bad")
            } catch (expected: IllegalArgumentException) {
                // pass
            }
        }
        try {
            obj("").absolutePath("path")
            throw AssertionError("expected IllegalStateException for blank path")
        } catch (expected: IllegalStateException) {
            // pass
        }
    }
}
