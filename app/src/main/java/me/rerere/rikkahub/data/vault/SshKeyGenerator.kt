package me.rerere.rikkahub.data.vault

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * SSH 密钥对生成器（Web 桥用）。
 *
 * 生成 RSA-2048 密钥对，输出：
 * - 私钥：OpenSSH PEM 格式（PKCS#1 "RSA PRIVATE KEY"，JSch/mwiede 可直接 addIdentity）
 * - 公钥：OpenSSH authorized_keys 格式（ssh-rsa AAAA...）
 *
 * 注意：不用 AndroidKeyStore（其密钥不可导出），SSH 私钥需要可导出以配置到 ECS
 * authorized_keys。私钥生成后保存到 Vault（安全凭证库，AES-GCM 加密）或用户指定路径。
 */
object SshKeyGenerator {

    /** 生成 RSA-2048 密钥对。 */
    fun generate(): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        val pub = pair.public as RSAPublicKey
        val priv = pair.private as RSAPrivateCrtKey
        return SshKeyPair(
            privateKeyPem = encodePkcs1(priv),
            publicKeyLine = "ssh-rsa ${Base64.getEncoder().encodeToString(encodeSshRsa(pub))} generated@rikkahub-agents",
        )
    }

    data class SshKeyPair(
        val privateKeyPem: String,
        val publicKeyLine: String,
    )

    /** 编码 PKCS#1 DER → PEM。 */
    private fun encodePkcs1(key: RSAPrivateCrtKey): String {
        val der = encodeDer(
            listOf(
                bytes(java.math.BigInteger.ZERO),
                bytes(key.modulus),
                bytes(key.publicExponent),
                bytes(key.privateExponent),
                bytes(key.primeP),
                bytes(key.primeQ),
                bytes(key.primeExponentP),
                bytes(key.primeExponentQ),
                bytes(key.crtCoefficient),
            )
        )
        val b64 = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN RSA PRIVATE KEY-----\n$b64\n-----END RSA PRIVATE KEY-----\n"
    }

    /** 编码 OpenSSH 公钥格式（ssh-rsa 类型 + e + n）。 */
    private fun encodeSshRsa(pub: RSAPublicKey): ByteArray =
        sshString("ssh-rsa".encodeToByteArray()) +
            sshString(bytes(pub.publicExponent)) +
            sshString(bytes(pub.modulus))

    /** BigInteger → 裸字节（去多余前导 0）。DER 与 sshString 共用。 */
    private fun bytes(v: java.math.BigInteger): ByteArray {
        var bytes = v.toByteArray()
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        return bytes
    }

    private fun sshString(bytes: ByteArray): ByteArray =
        bigInt(bytes.size.toLong()) + bytes

    private fun bigInt(v: Long): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = ((v shr 24) and 0xFF).toByte()
        bytes[1] = ((v shr 16) and 0xFF).toByte()
        bytes[2] = ((v shr 8) and 0xFF).toByte()
        bytes[3] = (v and 0xFF).toByte()
        return bytes
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    /** DER 编码 SEQUENCE of INTEGER。 */
    private fun encodeDer(ints: List<ByteArray>): ByteArray {
        val content = ints.map { derInteger(it) }.reduce { a, b -> a + b }
        return byteArrayOf(0x30) + derLength(content.size) + content
    }

    private fun derInteger(bytes: ByteArray): ByteArray {
        var b = bytes
        if ((b[0].toInt() and 0x80) != 0) {
            b = byteArrayOf(0) + b
        }
        return byteArrayOf(0x02) + derLength(b.size) + b
    }

    private fun derLength(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val bytes = java.nio.ByteBuffer.allocate(4).putInt(len).array()
        var start = 0
        while (start < 3 && bytes[start] == 0.toByte()) start++
        val lenBytes = bytes.copyOfRange(start, 4)
        return byteArrayOf((0x80 or lenBytes.size).toByte()) + lenBytes
    }
}
