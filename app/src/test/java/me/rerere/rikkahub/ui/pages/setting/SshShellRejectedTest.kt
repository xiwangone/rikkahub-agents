package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 被拒文案匹配（纯字符串逻辑，F3/F5）：
 * - 两条 JSch 原文命中；
 * - `channel request: timeout` 命中（F3：服务端静默丢请求）；
 * - 通用连接失败与普通网络超时不命中。
 */
class SshShellRejectedTest {

    @Test
    fun `failed to send channel request matches`() {
        assertTrue(isShellRejected("failed to send channel request"))
    }

    @Test
    fun `channel is not opened matches with period and case-insensitive`() {
        assertTrue(isShellRejected("channel is not opened."))
        assertTrue(isShellRejected("Channel Is Not Opened"))
    }

    @Test
    fun `channel request timeout matches`() {
        assertTrue(isShellRejected("channel request: timeout"))
    }

    @Test
    fun `auth failure and session down do not match`() {
        assertFalse(isShellRejected("Auth fail"))
        assertFalse(isShellRejected("session is down"))
    }

    @Test
    fun `generic network timeout does not match`() {
        assertFalse(isShellRejected("timeout: socket is not established"))
        assertFalse(isShellRejected("SocketTimeoutException: Read timed out"))
        assertFalse(isShellRejected("Connection timed out"))
    }

    @Test
    fun `null and blank do not match`() {
        assertFalse(isShellRejected(null))
        assertFalse(isShellRejected(""))
        assertFalse(isShellRejected("   "))
    }
}
