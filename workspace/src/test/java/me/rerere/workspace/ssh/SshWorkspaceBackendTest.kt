package me.rerere.workspace.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Windows 交互终端启动命令的纯函数单测（不连接远端）：
 * - 探测一次定 shell：`where pwsh` 找到用 pwsh，否则 powershell，两者皆无回退 cmd；
 * - 交互 shell 后跟 `& exit /b 0`：shell 以非零码退出（用户敲 `exit 1`、pwsh 崩溃）
 *   时不触发 `||` 回退到下一个 shell；
 * - 目录处理：空目录不切目录；`/C:/Users/x` 与 `C:\`、`D:/work` 归一化；
 *   UNC 路径保留前导 `\\`；单引号转义为 `''`。
 */
class SshWorkspaceBackendTest {

    private val backend = SshWorkspaceBackend(
        pool = SshConnectionPool(),
        id = "test",
        config = SshConnectionConfig(
            host = "example.invalid",
            username = "test",
            authType = SshAuthType.PASSWORD,
            remoteDir = "/tmp/ws",
        ),
    )

    /** 从命令里取出 -EncodedCommand 的值并按 UTF-16LE 解码，还原 Set-Location 脚本。 */
    private fun decodedScript(command: String): String {
        val encoded = command.substringAfter("-EncodedCommand ").substringBefore(" ")
            .substringBefore(")").trim()
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
    }

    @Test
    fun `pwsh present uses pwsh and guards exit code`() {
        val command = backend.windowsShellCommand("C:\\Users\\x")
        assertTrue(command.startsWith("cmd /c "))
        assertTrue(command.contains("where pwsh >nul 2>&1"))
        assertTrue(command.contains("pwsh -NoLogo -NoProfile -NoExit"))
        // shell 退出码不触发回退
        assertTrue(command.contains("pwsh -NoLogo -NoProfile -NoExit -EncodedCommand"))
        assertTrue(command.contains("& exit /b 0"))
        // powershell 与 cmd 仍在命令里当回退
        assertTrue(command.contains("where powershell >nul 2>&1"))
        assertTrue(command.contains("powershell -NoLogo -NoProfile -NoExit"))
        assertTrue(command.endsWith("|| cmd)\""))
    }

    @Test
    fun `windows shell command is quoted so a pwsh default shell cannot parse it`() {
        val command = backend.windowsShellCommand("C:\\Users\\x")
        // Windows 上 sshd 默认 shell 可能是 pwsh：裸 cmd 语法会被 pwsh 当表达式解析报
        // "表达式中缺少右括号" → 终端一开就死。整条必须包在双引号里。
        assertTrue(command.startsWith("cmd /c \""))
        assertTrue(command.endsWith("\""))
    }

    @Test
    fun `windows dir conversion supports sftp style`() {
        assertEquals("C:\\Users\\x", backend.toWindowsDir("/C:/Users/x"))
        assertEquals("D:\\work", backend.toWindowsDir("D:/work"))
        assertEquals("", backend.toWindowsDir(""))
    }

    @Test
    fun `empty dir does not change directory`() {
        val command = backend.windowsShellCommand("")
        assertFalse(command.contains("-EncodedCommand"))
        assertFalse(command.contains("Set-Location"))
        assertTrue(command.contains("pwsh -NoLogo -NoProfile -NoExit"))
    }

    @Test
    fun `root dir C colon backslash is kept`() {
        val script = decodedScript(backend.windowsShellCommand("C:\\"))
        assertEquals("Set-Location -LiteralPath 'C:'", script)
    }

    @Test
    fun `sftp style slash C path is normalized`() {
        val script = decodedScript(backend.windowsShellCommand("/C:/Users/x"))
        assertEquals("Set-Location -LiteralPath 'C:\\Users\\x'", script)
    }

    @Test
    fun `forward slash drive path is normalized`() {
        val script = decodedScript(backend.windowsShellCommand("D:/work"))
        assertEquals("Set-Location -LiteralPath 'D:\\work'", script)
    }

    @Test
    fun `UNC path keeps leading double backslash`() {
        val script = decodedScript(backend.windowsShellCommand("\\\\server\\share"))
        assertEquals("Set-Location -LiteralPath '\\\\server\\share'", script)
    }

    @Test
    fun `single quote in dir is doubled`() {
        val script = decodedScript(backend.windowsShellCommand("C:\\Users\\o'brien"))
        assertEquals("Set-Location -LiteralPath 'C:\\Users\\o''brien'", script)
    }
}
