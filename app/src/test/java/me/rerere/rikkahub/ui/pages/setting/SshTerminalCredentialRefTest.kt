package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.db.entity.SshHostEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 无凭证报错里显示的引用名（F2）：优先取实际在用的 `$$` 引用名。
 */
class SshTerminalCredentialRefTest {

    private fun host(
        vaultCredentialRef: String? = null,
        privateKey: String? = null,
        vaultCertificateRef: String? = null,
        certificate: String? = null,
    ) = SshHostEntity(
        name = "test-host",
        host = "example.com",
        user = "test-user",
        vaultCredentialRef = vaultCredentialRef,
        privateKey = privateKey,
        vaultCertificateRef = vaultCertificateRef,
        certificate = certificate,
        createdAtMs = 0L,
    )

    @Test
    fun `legacy reference field wins when present`() {
        assertEquals("PC_ED25519_KEY", credentialRefDisplayName(host(vaultCredentialRef = "PC_ED25519_KEY")))
    }

    @Test
    fun `dollar reference in privateKey is shown without prefix`() {
        assertEquals("PC_ED25519_KEY", credentialRefDisplayName(host(privateKey = "\$\$PC_ED25519_KEY")))
    }

    @Test
    fun `dollar reference in certificate is shown when no key reference`() {
        assertEquals("MY_CERT", credentialRefDisplayName(host(certificate = "\$\$MY_CERT")))
    }

    @Test
    fun `plaintext key is not shown as a reference`() {
        // 断言用的假私钥头，不含真实密钥内容
        val fakePrivateKeyHeader = "-----BEGIN OPENSSH PRIVATE KEY-----" // ALLOW-FAKE-SECRET
        assertEquals("none", credentialRefDisplayName(host(privateKey = fakePrivateKeyHeader)))
    }

    @Test
    fun `no credential at all shows none`() {
        assertEquals("none", credentialRefDisplayName(host()))
    }
}
