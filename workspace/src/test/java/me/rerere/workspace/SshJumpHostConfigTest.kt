package me.rerere.workspace

import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshHostKey
import me.rerere.workspace.ssh.SshHostKeyChangedException
import me.rerere.workspace.ssh.SshJumpHostConfig
import me.rerere.workspace.ssh.SshUnknownHostKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳板机配置的纯逻辑回归：
 * - 地址格式与 toString 不泄露凭据（凭据只在建连调用链里存在，日志与异常信息里不能出现）；
 * - 主机密钥异常能区分跳板机与目标主机，编辑器的确认弹窗才能把密钥归到正确的一方。
 * 多跳实连需要两台真实 SSH 服务器，ibk 上验不到，属“未验到”。
 */
class SshJumpHostConfigTest {
    private val key = SshHostKey(type = "ssh-ed25519", key = "aGVsbG8=")

    @Test
    fun `jump host address formats as user at host colon port`() {
        val jump = SshJumpHostConfig(
            host = "jump.example.com",
            port = 2222,
            username = "jumpuser",
            authType = SshAuthType.PASSWORD,
            password = "secret",
        )
        assertEquals("jumpuser@jump.example.com:2222", jump.address)
    }

    @Test
    fun `jump host toString never leaks credentials`() {
        val jump = SshJumpHostConfig(
            host = "jump.example.com",
            username = "jumpuser",
            authType = SshAuthType.PRIVATE_KEY,
            password = "p4ssw0rd-secret",
            privateKey = "PRIVATE-KEY-SECRET",
            passphrase = "phrase-secret",
            hostKey = key,
        )
        val text = jump.toString()
        assertFalse(text.contains("p4ssw0rd-secret"))
        assertFalse(text.contains("PRIVATE-KEY-SECRET"))
        assertFalse(text.contains("phrase-secret"))
        assertTrue(text.contains("jumpuser@jump.example.com:22"))
    }

    @Test
    fun `connection config toString with jump host never leaks credentials`() {
        val config = SshConnectionConfig(
            host = "target.example.com",
            username = "targetuser",
            authType = SshAuthType.PASSWORD,
            password = "target-secret",
            remoteDir = "/home/targetuser",
            hostKey = key,
            jumpHost = SshJumpHostConfig(
                host = "jump.example.com",
                username = "jumpuser",
                authType = SshAuthType.PASSWORD,
                password = "jump-secret",
            ),
        )
        val text = config.toString()
        assertFalse(text.contains("target-secret"))
        assertFalse(text.contains("jump-secret"))
        assertTrue(text.contains("jumpuser@jump.example.com:22"))
    }

    @Test
    fun `unknown jump host key exception is flagged as jump host`() {
        val e = SshUnknownHostKeyException(key, jumpHost = true)
        assertTrue(e.jumpHost)
        assertTrue(e.message!!.contains("jump host"))
    }

    @Test
    fun `changed jump host key exception is flagged as jump host`() {
        val other = SshHostKey(type = "ssh-ed25519", key = "d29ybGQ=")
        val e = SshHostKeyChangedException(key, other, jumpHost = true)
        assertTrue(e.jumpHost)
        assertTrue(e.message!!.contains("jump host"))
    }

    @Test
    fun `host key exceptions default to target host`() {
        assertFalse(SshUnknownHostKeyException(key).jumpHost)
        assertFalse(SshHostKeyChangedException(key, key).jumpHost)
    }
}
