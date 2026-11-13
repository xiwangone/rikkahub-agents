package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Base64 内联上限判定（[requireInlineBase64Size]）：二进制文件超过 256KB 不直接内联进消息，
 * 改由调用方用 shell（head/xxd/python）查看，避免撑爆上下文。
 * 守卫在新基线里缺失，随基建统一批补回（与旧栈行为一致）；执行路径（需真实文件读取）
 * 不在这里测。
 */
class WorkspaceToolsBase64LimitTest {

    @Test
    fun `exactly at limit passes`() {
        requireInlineBase64Size(MAX_BINARY_BASE64_BYTES, "/tmp/a.bin")
    }

    @Test
    fun `below limit passes`() {
        requireInlineBase64Size(0, "/tmp/a.bin")
        requireInlineBase64Size(1024, "/tmp/a.bin")
    }

    @Test
    fun `one byte over limit throws with size and limit in message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            requireInlineBase64Size(MAX_BINARY_BASE64_BYTES + 1, "/tmp/a.bin")
        }
        assertTrue(ex.message!!.contains("${MAX_BINARY_BASE64_BYTES + 1}"))
        assertTrue(ex.message!!.contains("$MAX_BINARY_BASE64_BYTES"))
        assertTrue(ex.message!!.contains("/tmp/a.bin"))
    }

    @Test
    fun `limit constant is 256KB`() {
        assertEquals(256 * 1024, MAX_BINARY_BASE64_BYTES)
    }
}
