package me.rerere.rikkahub.data.vault

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class X509KeyGeneratorTest {

    @Test
    fun `self-signed certificate verifies with its own key and stays self-issued`() {
        val key = X509KeyGenerator.generate(
            X509KeyGenerator.Kind.SELF_SIGNED_CERTIFICATE,
            "CN=test.local, O=Test",
        )
        val certificate = readCertificate(key.publicText)

        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        // 真正的有效性检查：用证书自身公钥验签
        certificate.verify(certificate.publicKey)
        assertTrue(certificate.subjectX500Principal.name.contains("CN=test.local"))
    }

    @Test
    fun `certificate validity is about one year`() {
        val key = X509KeyGenerator.generate(X509KeyGenerator.Kind.SELF_SIGNED_CERTIFICATE, "CN=validity")
        val certificate = readCertificate(key.publicText)
        val days = (certificate.notAfter.time - certificate.notBefore.time) / 86_400_000L

        assertTrue("validity days=$days", days in 364..366)
        // notBefore 需要容忍客户端时钟偏差（不晚于当前时间）
        assertTrue(certificate.notBefore.time <= System.currentTimeMillis())
    }

    @Test
    fun `bare name is treated as the CN and blank falls back to the default`() {
        val bare = X509KeyGenerator.generate(X509KeyGenerator.Kind.SELF_SIGNED_CERTIFICATE, "myhost")
        assertTrue(readCertificate(bare.publicText).subjectX500Principal.name.contains("CN=myhost"))

        val blank = X509KeyGenerator.generate(X509KeyGenerator.Kind.SELF_SIGNED_CERTIFICATE, "   ")
        assertTrue(
            readCertificate(blank.publicText).subjectX500Principal.name.contains("CN=rikkahub-agents"),
        )
    }

    @Test
    fun `csr parses back with its subject and a verifiable signature`() {
        val key = X509KeyGenerator.generate(X509KeyGenerator.Kind.CSR, "CN=csr.example.com")
        val csr = PKCS10CertificationRequest(pemToDer(key.publicText))

        assertEquals("CN=csr.example.com", csr.subject.toString())
        // 用 CSR 内嵌公钥验证其自签名
        val verifier = JcaContentVerifierProviderBuilder().build(csr.subjectPublicKeyInfo)
        assertTrue(csr.isSignatureValid(verifier))
    }

    @Test
    fun `both kinds emit pem blocks with the expected labels`() {
        val certificate = X509KeyGenerator.generate(X509KeyGenerator.Kind.SELF_SIGNED_CERTIFICATE)
        assertTrue(certificate.publicText.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(certificate.publicText.trimEnd().endsWith("-----END CERTIFICATE-----"))
        assertTrue(certificate.privateKeyPem.startsWith("-----BEGIN PRIVATE KEY-----"))

        val csr = X509KeyGenerator.generate(X509KeyGenerator.Kind.CSR)
        assertTrue(csr.publicText.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        assertTrue(csr.publicText.trimEnd().endsWith("-----END CERTIFICATE REQUEST-----"))
        assertTrue(csr.privateKeyPem.startsWith("-----BEGIN PRIVATE KEY-----"))
    }

    @Test
    fun `each call produces fresh material`() {
        val first = X509KeyGenerator.generate(X509KeyGenerator.Kind.CSR)
        val second = X509KeyGenerator.generate(X509KeyGenerator.Kind.CSR)
        assertTrue(first.publicText != second.publicText)
        assertTrue(first.privateKeyPem != second.privateKeyPem)
    }

    private fun readCertificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    private fun pemToDer(pem: String): ByteArray =
        Base64.getMimeDecoder().decode(
            pem.lineSequence().filter { !it.startsWith("-----") }.joinToString(""),
        )
}
