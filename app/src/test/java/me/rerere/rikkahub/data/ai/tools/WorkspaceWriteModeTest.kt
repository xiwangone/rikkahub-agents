package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.sanitizeFileMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件写入的八进制权限校验（会拼进 rootfs 内的 shell 命令，必须限定取值）。
 */
class WorkspaceWriteModeTest {

    @Test
    fun `null and blank mean no mode`() {
        assertNull(sanitizeFileMode(null))
        assertNull(sanitizeFileMode(""))
        assertNull(sanitizeFileMode("   "))
    }

    @Test
    fun `valid octal modes pass through`() {
        assertEquals("755", sanitizeFileMode("755"))
        assertEquals("0644", sanitizeFileMode("0644"))
        assertEquals("600", sanitizeFileMode("600"))
        assertEquals("755", sanitizeFileMode(" 755 "))
    }

    @Test
    fun `invalid modes are rejected`() {
        val bad = listOf("7", "77777", "999", "abc", "08", "-755", "755; rm -rf /", "755 && echo x", "7 5 5")
        for (value in bad) {
            var threw = false
            try {
                sanitizeFileMode(value)
            } catch (_: IllegalStateException) {
                threw = true
            }
            assertTrue("should reject mode: $value", threw)
        }
    }
}
