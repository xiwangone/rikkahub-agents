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
        // 空字符串键值存在但内容为空: require(isNotBlank) 抛 IllegalArgumentException
        try {
            obj("").absolutePath("path")
            throw AssertionError("expected IllegalArgumentException for blank path")
        } catch (expected: IllegalArgumentException) {
            // pass
        }
        // 键缺失: string() 返回 null, error() 抛 IllegalStateException
        try {
            buildJsonObject {}.absolutePath("path")
            throw AssertionError("expected IllegalStateException for missing path key")
        } catch (expected: IllegalStateException) {
            // pass
        }
    }

    @Test
    fun `uri paths fail with guidance to use device file tools`() {
        for (uri in listOf("content://com.android.providers.downloads/1", "file:///sdcard/a.txt")) {
            try {
                obj(uri).absolutePath("path")
                throw AssertionError("expected IllegalArgumentException for $uri")
            } catch (expected: IllegalArgumentException) {
                org.junit.Assert.assertTrue(expected.message.orEmpty().contains("read_file"))
            }
        }
    }

    @Test
    fun `lenient json helpers accept strings and never throw on objects`() {
        val sample = buildJsonObject {
            put("num_str", " 42 ")
            put("num_int", 7)
            put("bool_str", "TRUE")
            put("bool_zero", "0")
            put("nested", buildJsonObject { put("k", "v") })
        }
        assertEquals(42, sample.int("num_str"))
        assertEquals(7, sample.int("num_int"))
        assertEquals(null, sample.int("nested"))
        assertEquals(true, sample.boolean("bool_str"))
        assertEquals(false, sample.boolean("bool_zero"))
        assertEquals(null, sample.boolean("nested"))
        assertEquals(null, sample.string("nested"))
    }
}
