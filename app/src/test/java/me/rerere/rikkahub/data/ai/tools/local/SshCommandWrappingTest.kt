package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    }

    @Test
    fun `sshOptionInt reads overrides and ignores comments`() {
        assertEquals(10, sshOptionInt("# comment\nServerAliveInterval 10\nServerAliveCountMax 6", "ServerAliveInterval"))
        assertEquals(6, sshOptionInt("ServerAliveInterval 10\nServerAliveCountMax 6", "ServerAliveCountMax"))
        assertEquals(null, sshOptionInt("ServerAliveInterval 10", "ServerAliveCountMax"))
        assertEquals(null, sshOptionInt(null, "ServerAliveInterval"))
        // 前缀相同但键不同的行不能被误命中
        assertEquals(null, sshOptionInt("ServerAliveIntervalX 5", "ServerAliveInterval"))
    }

    @Test
    fun `joinCommandBatch uses newlines for POSIX and semicolons for PowerShell`() {
        assertEquals("a\nb\nc", joinCommandBatch(listOf("a", "b", "c")))
        assertEquals("Write-Output a; Write-Output b", joinCommandBatch(listOf("Write-Output a", "Write-Output b")))
        // 空白条目被丢弃；全空返回空串（调用方据此报参数错误）
        assertEquals("a\nb", joinCommandBatch(listOf(" a ", "", "  ", "b")))
        assertEquals("", joinCommandBatch(listOf("", "   ")))
    }

    @Test
    fun `env injection is rendered as env argv assignments`() {
        // 见 workspace 模块 EnvAssignmentTest：`env -i` 会清空继承环境，必须显式传 K=V
        assertEquals(
            listOf("TOKEN=abc"),
            me.rerere.workspace.buildEnvAssignments(mapOf("TOKEN" to "abc")),
        )
    }

    // ---- env 前置语句与失败归类（SSH 工具的 env 参数 / 通道切换可见）----

    @Test
    fun `envPrelude uses dollar-env syntax on windows`() {
        assertEquals(listOf("\$env:FOO='bar'"), envPrelude(mapOf("FOO" to "bar"), windows = true))
    }

    @Test
    fun `envPrelude exports on posix`() {
        assertEquals(listOf("FOO='bar'; export FOO"), envPrelude(mapOf("FOO" to "bar"), windows = false))
    }

    @Test
    fun `envPrelude escapes single quotes per shell`() {
        assertEquals("\$env:FOO='a''b'", envPrelude(mapOf("FOO" to "a'b"), true).single())
        assertEquals("FOO='a'\\''b'; export FOO", envPrelude(mapOf("FOO" to "a'b"), false).single())
    }

    @Test
    fun `envPrelude drops invalid key names`() {
        val out = envPrelude(mapOf("OK_1" to "v", "1BAD" to "v", "A B" to "v", "A-B" to "v"), true)
        assertEquals(1, out.size)
        assertTrue(out.single().startsWith("\$env:OK_1="))
    }

    @Test
    fun `readEnvParam coerces primitive values to strings`() {
        val p =
            kotlinx.serialization.json.buildJsonObject {
                put(
                    "env",
                    kotlinx.serialization.json.buildJsonObject {
                        put("A", kotlinx.serialization.json.JsonPrimitive("1"))
                        put("B", kotlinx.serialization.json.JsonPrimitive(2))
                    },
                )
            }
        // 宽松：数字/布尔等原始值按其字面量转字符串（env 值本就是字符串语义）；非原始值（对象/数组）忽略
        assertEquals(mapOf("A" to "1", "B" to "2"), readEnvParam(p))
        assertTrue(readEnvParam(kotlinx.serialization.json.buildJsonObject { }).isEmpty())
    }

    @Test
    fun `classifySshError maps failures to categories`() {
        assertEquals("timeout", classifySshError("Connect timed out after 5000ms"))
        assertEquals("dns", classifySshError("java.net.UnknownHostException: nope"))
        assertEquals("refused", classifySshError("Connection refused"))
        assertEquals("auth", classifySshError("Auth fail for user"))
        assertEquals("host_key", classifySshError("Host key verification failed"))
        assertEquals("other", classifySshError("something odd"))
    }

    @Test
    fun `classifySshPayload reads raw and attempts not just the label`() {
        // 标签是抽象词（tcp_unreachable / connect_failed），关键词只出现在 raw / attempts 里，
        // 只看标签会一律归成 other（实测 ECONNREFUSED 被判 other）。
        val refused =
            buildJsonObject {
                put("error", "tcp_unreachable")
                put(
                    "attempts",
                    buildJsonObject {
                        put("cellular", "ConnectException: ECONNREFUSED (Connection refused)")
                    },
                )
            }
        assertEquals("refused", classifySshPayload(refused))

        val changed =
            buildJsonObject {
                put("error", "host_key_changed")
                put("raw", "HostKey verification failed")
            }
        assertEquals("host_key", classifySshPayload(changed))

        assertEquals("ok", classifySshPayload(buildJsonObject { }))
    }

    @Test
    fun `classifySshError maps underscore host_key label`() {
        assertEquals("host_key", classifySshError("host_key_changed"))
    }
}
