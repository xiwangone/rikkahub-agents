package me.rerere.rikkahub.data.vault

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder

/**
 * X.509 自签证书 / PKCS#10 CSR 生成器（凭证库「生成密钥」用）。
 *
 * 用途：
 * - **自签证书**：mTLS 客户端证书、本地服务端证书（导入到客户端信任库）等自管场景；
 * - **CSR**：把公钥交给 CA / 企业 PKI 签发，私钥始终留在本机凭证库。
 *
 * 曲线固定 **EC P-256（secp256r1）**：生成快、现代 PKI 与 mTLS 的主流选择。
 * RSA 兼容面更广，但生成慢且现代场景已非必需；需要时再作为独立类型加入。
 */
object X509KeyGenerator {

    /** 证书有效期：365 天（个人/客户端证书的常见期限，且不受 Apple 对超长有效期的限制影响）。 */
    private const val VALIDITY_DAYS = 365L

    /** Subject 未填写时的默认标识。 */
    const val DEFAULT_SUBJECT = "CN=rikkahub-agents"

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    enum class Kind(val label: String) {
        SELF_SIGNED_CERTIFICATE("X.509 自签证书"),
        CSR("PKCS#10 CSR"),
    }

    data class GeneratedKey(
        /** PKCS#8 私钥 PEM（自签与 CSR 都需要保留，前者用于 mTLS，后者用于后续签发）。 */
        val privateKeyPem: String,
        /** 证书 PEM 或 CSR PEM。 */
        val publicText: String,
    )

    fun generate(kind: Kind, subject: String = DEFAULT_SUBJECT): GeneratedKey {
        val name = parseSubject(subject)
        val keyPair = newKeyPair()
        return when (kind) {
            Kind.SELF_SIGNED_CERTIFICATE -> GeneratedKey(
                privateKeyPem = encodePrivateKey(keyPair),
                publicText = selfSignedCertificate(name, keyPair),
            )

            Kind.CSR -> GeneratedKey(
                privateKeyPem = encodePrivateKey(keyPair),
                publicText = certificationRequest(name, keyPair),
            )
        }
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /**
     * 解析 Subject：既支持完整 RFC 2253（`CN=host, O=Org`），
     * 也接受只写一个名字（此时按 CN 处理）。
     */
    private fun parseSubject(raw: String): X500Name {
        val trimmed = raw.trim().ifBlank { DEFAULT_SUBJECT }
        return runCatching { X500Name(trimmed) }
            .getOrElse { X500Name("CN=$trimmed") }
    }

    private fun selfSignedCertificate(subject: X500Name, keyPair: KeyPair): String {
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject, // 自签：issuer == subject
            BigInteger(64, SecureRandom()),
            Date.from(now.minusSeconds(60)), // 容忍客户端轻微时钟偏差
            Date.from(now.plusSeconds(VALIDITY_DAYS * 24 * 3600)),
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
        return pem("CERTIFICATE", builder.build(signer).encoded)
    }

    private fun certificationRequest(subject: X500Name, keyPair: KeyPair): String {
        val builder = JcaPKCS10CertificationRequestBuilder(subject, keyPair.public)
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
        return pem("CERTIFICATE REQUEST", builder.build(signer).encoded)
    }

    private fun encodePrivateKey(keyPair: KeyPair): String = pem("PRIVATE KEY", keyPair.private.encoded)

    /** DER → PEM（64 字符换行，符合 RFC 7468）。 */
    private fun pem(label: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }
}
