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

    /** 从命令里取出 -EncodedCommand 的值并按 UTF-16LE 解码，剥掉固定的编码设置前缀。 */
    private fun decodedScript(command: String): String {
        val encoded = command.substringAfter("-EncodedCommand ").substringBefore(" ").trim()
        val full = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
        return full.substringAfter("[Console]::OutputEncoding=[Text.Encoding]::UTF8", full)
            .trim().removePrefix(";").trim()
    }

    @Test
    fun `pwsh is launched directly without cmd shell operators`() {
        val command = backend.windowsShellCommand("C:\\Users\\x")
        assertTrue(command.startsWith("pwsh -NoLogo -NoProfile -NoExit -EncodedCommand "))
        // 关键：不能出现任何 shell 运算符 —— 分配 PTY 时命令由远端默认 shell 解析，
        // Windows 上它常是 pwsh，cmd 语法的 `(`/`&` 会直接报 ParserError。
        listOf("cmd /c", "&&", "||", "& exit", ">nul", "where pwsh").forEach {
            assertFalse("命令不应含 $it: $command", command.contains(it))
        }
    }

    @Test
    fun `windows shell command has no parentheses or operators`() {
        val command = backend.windowsShellCommand("C:\\Users\\x")
        listOf("(", ")", "&", "|", ">").forEach {
            assertFalse("命令不应含 $it: $command", command.contains(it))
        }
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
        // 空目录仍然走 EncodedCommand（脚本里带 UTF-8 编码设置），但不含 Set-Location。
        assertFalse(decodedScript(command).contains("Set-Location"))
        assertTrue(command.startsWith("pwsh -NoLogo -NoProfile -NoExit -EncodedCommand "))
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
