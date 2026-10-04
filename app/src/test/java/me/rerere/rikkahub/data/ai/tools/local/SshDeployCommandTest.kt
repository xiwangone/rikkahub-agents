package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公钥部署命令的**平台分叉契约**。
 *
 * 为什么值得钉住：该命令曾经只有 posix 一份，对 Windows 目标会直接抛 `ParserError`，
 * 而失败又被当成"未部署"轻描淡写地带过 —— 部署动作看起来跑过了、实际一个字节没写。
 * 本测试锁住两侧不会互相污染：posix 分支不得混入 PowerShell 语法；Windows 分支必须
 * 走管理员文件 + ACL（否则 sshd 不加载，等于白部署）。
 */
class SshDeployCommandTest {

    private val pubKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIexample probe@test"

    @Test
    fun `posix branch stays posix`() {
        val cmd = deployCommandFor(pubKey, windows = false)
        assertTrue("应含权限收紧", cmd.contains("umask 077"))
        assertTrue(cmd.contains("authorized_keys"))
        assertFalse("posix 分支不得混入 PowerShell", cmd.contains("powershell"))
    }

    @Test
    fun `windows branch uses powershell and admin file`() {
        val cmd = deployCommandFor(pubKey, windows = true)
        assertTrue(
            "应经 powershell -NoProfile -Command 包裹（远端默认 shell 可能是 cmd）",
            cmd.contains("powershell -NoProfile -Command"),
        )
        assertTrue(
            "管理员组成员的 key 必须落到 administrators_authorized_keys",
            cmd.contains("administrators_authorized_keys"),
        )
        assertTrue("该文件需收紧 ACL，否则 sshd 拒绝加载", cmd.contains("icacls"))
        assertFalse("Windows 分支不得出现 posix 命令", cmd.contains("umask"))
    }

    @Test
    fun `single quote in public key is escaped for powershell`() {
        val quoted = "ssh-ed25519 AAAkey it's-a-comment"
        val cmd = deployCommandFor(quoted, windows = true)
        assertTrue("单引号必须双写（PowerShell 字符串转义）", cmd.contains("''"))
    }
}
