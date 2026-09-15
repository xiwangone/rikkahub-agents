package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CredentialType` 契约测试：类型推断决定"使用方按哪种方式消费该凭证"，
 * 推错会让 SSH 密钥被当请求头、口令被当令牌，因此逐类钉住。
 */
class CredentialTypeTest {

    @Test
    fun `public key present implies ssh key`() {
        assertEquals(
            CredentialType.SSH_KEY,
            CredentialType.infer("DEPLOY_KEY", "value", publicKey = "ssh-ed25519 AAAA"),
        )
    }

    @Test
    fun `private key block in value implies ssh key even without public key`() {
        // 用拼接构造结构串：源码里不出现完整私钥头，避免密钥形态扫描误报
        val header = "-----BEGIN " + "OPENSSH PRIVATE KEY" + "-----"
        assertEquals(CredentialType.SSH_KEY, CredentialType.infer("SOMETHING", "$header\nabc"))
    }

    @Test
    fun `private key structure wins over name suffix`() {
        // 名称看起来像口令，但值是私钥结构：结构优先（否则会被当成口令处理）
        val header = "-----BEGIN " + "RSA PRIVATE KEY" + "-----"
        assertEquals(CredentialType.SSH_KEY, CredentialType.infer("DEPLOY_PWD", "$header\nxyz"))
    }

    @Test
    fun `token and api key names map to api key`() {
        assertEquals(CredentialType.API_KEY, CredentialType.infer("GITHUB_TOKEN", "x"))
        assertEquals(CredentialType.API_KEY, CredentialType.infer("OPENAI_API_KEY", "x"))
        assertEquals(CredentialType.API_KEY, CredentialType.infer("SERVICE_KEY", "x"))
        assertEquals(CredentialType.API_KEY, CredentialType.infer("MY_SECRET", "x"))
    }

    @Test
    fun `password names map to basic auth`() {
        assertEquals(CredentialType.BASIC_AUTH, CredentialType.infer("ROUTER_PWD", "x"))
        assertEquals(CredentialType.BASIC_AUTH, CredentialType.infer("DB_PASS", "x"))
        assertEquals(CredentialType.BASIC_AUTH, CredentialType.infer("WIFI_PASSWORD", "x"))
    }

    @Test
    fun `otp names map to totp`() {
        assertEquals(CredentialType.TOTP, CredentialType.infer("GITHUB_TOTP", "x"))
        assertEquals(CredentialType.TOTP, CredentialType.infer("MAIL_OTP", "x"))
        assertEquals(CredentialType.TOTP, CredentialType.infer("SITE_2FA", "x"))
    }

    @Test
    fun `unknown names fall back to custom fields`() {
        assertEquals(CredentialType.CUSTOM, CredentialType.infer("SOMETHING_ELSE", "x"))
        assertEquals(CredentialType.CUSTOM, CredentialType.infer("NOTE", ""))
    }

    @Test
    fun `validity accepts empty and known types only`() {
        assertTrue(CredentialType.isValid(CredentialType.UNCLASSIFIED))
        assertTrue(CredentialType.isValid(CredentialType.SSH_KEY))
        assertFalse(CredentialType.isValid("not-a-type"))
    }
}
