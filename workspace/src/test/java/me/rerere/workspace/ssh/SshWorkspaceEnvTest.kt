package me.rerere.workspace.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-11 远程工作区 env 不再进命令行（纯函数单测）：
 * - 旧形态把 env 值放在外层 `env` 命令参数里，同机其他用户 `ps` 可见；
 * - 新形态把值写进远端 0600 临时文件、命令里只剩文件路径，`trap ... EXIT` 删文件。
 * 断言：构建出的远端命令串不含任何 env 明文值；env 文件内容含 `KEY='value'` 赋值。
 * 端到端（SFTP 上传 + trap 删除）需真实 SSH 远端，未验到，见说明。
 */
class SshWorkspaceEnvTest {

    private val secret = "s3cret-Token-9876"
    private val env = linkedMapOf("MY_TOKEN" to secret, "PLAIN" to "hello")

    @Test
    fun `env values never appear in the built remote command`() {
        val command = buildRemotePosixCommand("sh", "/remote/ws", "echo hi", ".rikkahub-env-123")
        assertFalse(command.contains(secret))
        assertFalse(command.contains("MY_TOKEN"))
        assertFalse(command.contains("hello"))
        // 命令里只剩文件路径与 source 逻辑
        assertTrue(command.contains(".rikkahub-env-123"))
        assertTrue(command.contains("set -a"))
        assertTrue(command.contains("trap"))
    }

    @Test
    fun `env file content carries shell-quoted assignments`() {
        assertEquals(
            "MY_TOKEN='s3cret-Token-9876'\nPLAIN='hello'",
            buildEnvFileContent(env),
        )
    }

    @Test
    fun `env file content escapes single quotes and keeps equals and spaces`() {
        val content = buildEnvFileContent(linkedMapOf("A" to "a'b", "B" to "x=y z"))
        assertEquals("A='a'\"'\"'b'\nB='x=y z'", content)
    }

    @Test
    fun `empty env keeps legacy command shape`() {
        val command = buildRemotePosixCommand("sh", "/remote/ws", "echo hi", null)
        assertTrue(command.contains("CI=true"))
        assertTrue(command.contains("NO_COLOR=1"))
        assertFalse(command.contains("trap"))
        assertFalse(command.contains(".rikkahub-env"))
    }

    @Test
    fun `env file name is hidden and unique per call`() {
        val first = remoteEnvFileName()
        val second = remoteEnvFileName()
        assertTrue(first.startsWith(".rikkahub-env-"))
        assertFalse(first == second)
    }
}
