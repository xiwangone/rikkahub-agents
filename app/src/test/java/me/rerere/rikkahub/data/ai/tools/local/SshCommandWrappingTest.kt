package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure shell-command wrapping helpers used by the SSH and Termux tools.
 * These cover the escaping logic that is easy to get wrong; the actual channel/stdin behaviour
 * needs a live SSH server and is verified on-device.
 */
class SshCommandWrappingTest {

    @Test
    fun `shellSingleQuote wraps a plain string in single quotes`() {
        assertEquals("'echo hi'", shellSingleQuote("echo hi"))
    }

    @Test
    fun `shellSingleQuote wraps an empty string`() {
        assertEquals("''", shellSingleQuote(""))
    }

    @Test
    fun `shellSingleQuote escapes embedded single quotes`() {
        // close-quote, escaped quote, reopen-quote: a'b -> 'a'\''b'
        assertEquals("'a'\\''b'", shellSingleQuote("a'b"))
    }

    @Test
    fun `shellSingleQuote escapes every embedded single quote`() {
        assertEquals("''\\''x'\\'''", shellSingleQuote("'x'"))
    }

    @Test
    fun `wrapDetachedCommand redirects all streams, backgrounds, and echoes the pid`() {
        assertEquals(
            "nohup sh -c 'echo hi' >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\"",
            wrapDetachedCommand("echo hi"),
        )
    }

    @Test
    fun `wrapDetachedCommand keeps a compound command intact inside the single-quoted body`() {
        val wrapped = wrapDetachedCommand("rm -rf /tmp/x; python3 -m http.server 9999")
        assertTrue(wrapped.startsWith("nohup sh -c 'rm -rf /tmp/x; python3 -m http.server 9999' "))
        assertTrue(wrapped.contains(">/dev/null 2>&1 </dev/null &"))
    }

    @Test
    fun `wrapDetachedCommand escapes a command that itself contains single quotes`() {
        assertEquals(
            "nohup sh -c 'echo '\\''a'\\''' >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\"",
            wrapDetachedCommand("echo 'a'"),
        )
    }

    // ---- 2026-09-10：Windows 误判 / busybox 无 nohup 两个实测坏点的回归 ----

    @Test
    fun `pure PowerShell cmdlet commands are recognised as Windows`() {
        // 曾因只认 powershell 前缀与 C:\ 字面量而被误判 POSIX → nohup sh -c … → ParserError
        assertTrue(looksLikeWindowsCommand("Start-Sleep -Seconds 12; Write-Output BG_DONE_12S"))
        assertTrue(looksLikeWindowsCommand("Get-ChildItem A:\\workspace | Measure-Object"))
        assertTrue(looksLikeWindowsCommand("\$env:COMPUTERNAME"))
        assertTrue(looksLikeWindowsCommand("pwsh -NoProfile -Command \"Write-Output ok\""))
        assertTrue(looksLikeWindowsCommand("Test-Path C:\\Users\\chen\\tmp\\x.txt"))
    }

    @Test
    fun `posix commands are not treated as Windows`() {
        assertFalse(looksLikeWindowsCommand("uname -s"))
        assertFalse(looksLikeWindowsCommand("bash -lc \"echo POSIX_OK\""))
        assertFalse(looksLikeWindowsCommand("cmd.exe /c \"ver\""))
        assertFalse(looksLikeWindowsCommand(""))
        assertFalse(looksLikeWindowsCommand("rm -rf /tmp/x; python3 -m http.server 9999"))
    }

    @Test
    fun `detached POSIX wrapper degrades when nohup is missing`() {
        val (wrapped, logPath) = wrapDetachedCommandSmart("sleep 30; echo done")
        assertTrue(wrapped, wrapped.contains("command -v nohup >/dev/null 2>&1 && nohup sh -c "))
        assertTrue(wrapped, wrapped.contains("command -v setsid >/dev/null 2>&1 && setsid sh -c "))
        assertTrue(wrapped, wrapped.contains("|| sh -c "))
        assertTrue(wrapped, wrapped.endsWith("& echo \"rikkahub_bg_pid=\$!\""))
        assertTrue(logPath!!.startsWith("/tmp/rikkahub_bg_"))
    }

    @Test
    fun `detached wrapper routes PowerShell cmdlets to the Windows branch`() {
        val (wrapped, logPath) = wrapDetachedCommandSmart("Start-Sleep -Seconds 12; Write-Output BG_DONE_12S")
        assertTrue(wrapped, wrapped.startsWith("powershell -NoProfile -NonInteractive -EncodedCommand "))
        assertTrue(logPath!!.startsWith("C:\\Users\\Public\\rikkahub_bg_"))
    }

    @Test
    fun `utf8 console prefix is added for PowerShell but not for cmd or posix`() {
        val ps = withUtf8ConsoleEncoding("Write-Output \"中文\"")
        assertTrue(ps, ps.startsWith("[Console]::OutputEncoding=[Text.Encoding]::UTF8;"))
        assertEquals("cmd.exe /c \"chcp 65001\"", withUtf8ConsoleEncoding("cmd.exe /c \"chcp 65001\""))
        assertEquals("uname -s", withUtf8ConsoleEncoding("uname -s"))
        // 幂等：已有前缀不重复添加
        assertEquals(ps, withUtf8ConsoleEncoding(ps))
    }
}
