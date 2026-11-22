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
     * 二进制凭据后缀必须命中：read_file / workspace_read_file 的 base64 分支靠这个判定
     * 扣下整个文件内容（二进制无法按行掩值，整文件就是机密）。
     */
    @Test
    fun `binary credential suffixes are sensitive`() {
        assertTrue(isSensitiveCredentialPath("/root/keystore.p12"))
        assertTrue(isSensitiveCredentialPath("/root/keystore.pfx"))
        assertTrue(isSensitiveCredentialPath("/root/app.jks"))
        assertTrue(isSensitiveCredentialPath("/root/app.keystore"))
        assertTrue(isSensitiveCredentialPath("/etc/ssl/private/server.KEY"))
        // 公开材料不因二进制形态误伤
        assertFalse(isSensitiveCredentialPath("/root/.ssh/id_ed25519.pub"))
        assertFalse(isSensitiveCredentialPath("/root/photo.jpg"))
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

    /**
     * （凭证行掩码盲区）与 #6（URL 误掩/漏掩）的口径钉：
     * - `.netrc` 同行多键值行，短密码也掩；
     * - JSON 引号键、`.npmrc` 路径形键照样命中；
     * - 无内嵌凭证的纯 URL 不掩（`REPO_URL=…`、裸 URL 行、协议相对 `//` 值）；
     * - URL 内嵌 `user:pass@` 照掩；
     * - 远程 env 临时文件路径命中敏感判定（修 #4 读侧）。
     */
    @Test
    fun `netrc keyword lines mask short passwords`() {
        val out = redactCredentialFileText("machine example.com login alice password hunter2")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("password ***"))
        assertTrue(out.contains("machine example.com login alice"))
    }

    @Test
    fun `json quoted keys are masked`() {
        val out = redactCredentialFileText("""{"password": "s3cret", "name": "app"}""")
        assertFalse(out.contains("s3cret"))
        assertTrue(out.contains("password"))
    }

    @Test
    fun `npmrc path keys are masked`() {
        val out = redactCredentialFileText("//registry.npmjs.org/:_authToken=npm_abc123")
        assertFalse(out.contains("npm_abc123"))
        assertTrue(out.contains("_authToken=***"))
    }

    @Test
    fun `plain urls without credentials stay readable`() {
        // 注意：路径段 ≥20 字符会命中既有的长 token 兜底（与 URL 豁免无关），用短段 URL 钉口径
        val src = """
            REPO_URL=https://git.example.com/proj
            DOCS=https://example.com:8080/path
        """.trimIndent()
        assertEquals(src, redactCredentialFileText(src))
    }

    @Test
    fun `urls with embedded credentials are masked`() {
        val out = redactCredentialFileText("DATABASE_URL=postgres://alice:hunter2@db.internal:5432/app")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("DATABASE_URL=***"))
    }

    @Test
    fun `remote env temp files are sensitive`() {
        assertTrue(isSensitiveCredentialPath("/tmp/.rikkahub-env-123456"))
        assertTrue(isSensitiveCredentialPath("/home/u/.cache/.rikkahub-env-abcdef"))
    }
}
