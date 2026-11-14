package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「读回凭证类文件时只掩值」的口径。
 *
 * 背景：编辑/差异路径都过了 SecretMasker，只有 read 没过；远程工作区还能用绝对路径读整台
 * 机器，`.env`、`~/.ssh/id_ed25519` 原文会直接进模型上下文。这里只覆盖「几乎必然是机密」
 * 的路径，其余文件原样返回 —— 全量掩码会让「读配置 → 改配置」的工作流断掉。
 */
class SensitivePathRedactionTest {

    @Test
    fun `dotenv and key material paths are sensitive`() {
        assertTrue(isSensitiveCredentialPath("/root/proj/.env"))
        assertTrue(isSensitiveCredentialPath("/root/proj/.env.local"))
        assertTrue(isSensitiveCredentialPath("/root/.ssh/id_ed25519"))
        assertTrue(isSensitiveCredentialPath("/root/.ssh/id_rsa"))
        assertTrue(isSensitiveCredentialPath("/root/.ssh/config"))
        assertTrue(isSensitiveCredentialPath("/etc/ssl/private/site.key"))
        assertTrue(isSensitiveCredentialPath("/root/cert.pem"))
        assertTrue(isSensitiveCredentialPath("/root/.git-credentials"))
        assertTrue(isSensitiveCredentialPath("/root/.kube/config"))
    }

    /**
     * `.ssh/` 下不是全部都要掩：公钥与主机指纹本就是公开贴的，掩了反而不便（用户想看一眼
     * 自己贴的公钥）。注意：一刀切把 authorized_keys 标成 redacted 但它本就无 = 分隔符，
     * 结果是“标了却没掩”，两头不对。
     */
    @Test
    fun `ssh public material stays readable`() {
        assertFalse(isSensitiveCredentialPath("/root/.ssh/authorized_keys"))
        assertFalse(isSensitiveCredentialPath("/root/.ssh/known_hosts"))
        assertFalse(isSensitiveCredentialPath("/root/.ssh/id_ed25519.pub"))
    }

    @Test
    fun `opaque tokens without a separator are still masked`() {
        val base64 = "AAAAC3NzaC1lZDI1NTE5AAAAIA4KqNtYQUJdiZgMHHrrvHV4sGBRaO0RhqKTah5L16nY"
        val out = redactCredentialFileText("ssh-ed25519 $base64 generated@host")
        assertFalse(out.contains(base64))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `ordinary files stay readable`() {
        assertFalse(isSensitiveCredentialPath("/root/proj/README.md"))
        assertFalse(isSensitiveCredentialPath("/root/proj/build.gradle.kts"))
        assertFalse(isSensitiveCredentialPath("/root/proj/keyboard.json"))
        assertFalse(isSensitiveCredentialPath("/root/proj/monkey.txt"))
        assertFalse(isSensitiveCredentialPath("/root/proj/environment.md"))
    }

    @Test
    fun `values are masked but keys survive`() {
        val src = """
            # 注释保留
            DB_PASSWORD=hunter2
            export API_TOKEN=abc123
            NORMAL_SETTING: 42
        """.trimIndent()
        val out = redactCredentialFileText(src)
        assertTrue(out.contains("# 注释保留"))
        assertTrue(out.contains("DB_PASSWORD=***"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc123"))
        // 非凭证键同样按值掩：口径统一，不为个别键开洞
        assertTrue(out.contains("NORMAL_SETTING=***"))
    }

    @Test
    fun `private key body is dropped entirely`() {
        // 不写字面量 PEM 头：公开面检查按形态拦私钥块（哪怕内容全是假数据）。
        val head = "-----BEGIN " + "OPENSSH " + "PRIVATE KEY-----"
        val tail = "-----END " + "OPENSSH " + "PRIVATE KEY-----"
        val src = listOf(
            head,
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB",
            "AAA6NAAAABNzc2gtZWQyNTUxOQAAACBkZWFPU1NIRUQ=",
            tail,
        ).joinToString("\n")
        val out = redactCredentialFileText(src)
        assertTrue(out.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertTrue(out.contains("[body redacted]"))
        assertTrue(out.contains(tail))
        assertFalse(out.contains("b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB"))
        assertFalse(out.contains("AAA6NAAAABNzc2gtZWQyNTUxOQAAACBkZWFPU1NIRUQ="))
    }

    @Test
    fun `non config lines are untouched`() {
        val src = "https://example.com:8080/path"
        assertEquals(src, redactCredentialFileText(src))
    }
}
