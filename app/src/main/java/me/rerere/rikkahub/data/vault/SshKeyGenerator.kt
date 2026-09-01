package me.rerere.rikkahub.data.vault

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * SSH 密钥对生成器（Web 桥 / 凭证库「生成 SSH 密钥」用）。
 *
 * 支持三种算法，私钥统一输出 **PKCS#8 PEM**（"BEGIN PRIVATE KEY"，Bouncy Castle 标准编码）：
 * - RSA-2048：私钥 PKCS#8，公钥 ssh-rsa
 * - Ed25519：私钥 PKCS#8，公钥 ssh-ed25519（BC 软件生成，可导出）
 * - ECDSA (secp256r1/nistp256)：私钥 PKCS#8，公钥 ecdsa-sha2-nistp256
 *
 * PKCS#8 是通用标准，OpenSSH / JSch / haevn 等现代客户端均支持。
 *
 * 注意：不用 AndroidKeyStore（其密钥不可导出），SSH 私钥需要可导出以配置到服务器
 * authorized_keys。Ed25519 用 Bouncy Castle 生成（绕开 Android 系统 provider 差异）。
 */
object SshKeyGenerator {

    enum class KeyType(val label: String) {
        ED25519("Ed25519（推荐）"),
        RSA("RSA-2048"),
        ECDSA("ECDSA (nistp256)"),
    }

    /** 生成指定类型的密钥对。 */
    fun generate(type: KeyType): SshKeyPair = when (type) {
        KeyType.RSA -> generateRsa()
        KeyType.ED25519 -> generateEd25519()
        KeyType.ECDSA -> generateEcdsa()
    }

    /** 默认生成 RSA-2048（兼容旧调用）。 */
    fun generate(): SshKeyPair = generate(KeyType.RSA)

    data class SshKeyPair(
        val privateKeyPem: String,
        val publicKeyLine: String,
    )

    // ================= RSA =================

    private fun generateRsa(): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        val pub = pair.public as RSAPublicKey
        return SshKeyPair(
            privateKeyPem = toPkcs8Pem(pair.private),
            publicKeyLine = "ssh-rsa ${b64(encodeSshRsa(pub))} generated@rikkahub-agents",
        )
    }

    /** 编码 OpenSSH 公钥格式（ssh-rsa 类型 + e + n）。 */
    private fun encodeSshRsa(pub: RSAPublicKey): ByteArray =
        sshString("ssh-rsa".encodeToByteArray()) +
            sshString(bytes(pub.publicExponent)) +
            sshString(bytes(pub.modulus))

    // ================= Ed25519 =================

    private fun generateEd25519(): SshKeyPair {
        // 用 Bouncy Castle 生成（软件实现，密钥可导出）——Android 上 getInstance("Ed25519")
        // 默认解析到 AndroidKeyStore（强制 KeyGenParameterSpec 且密钥不可导出，不适合 SSH），
        // 且不同 ROM 的 Conscrypt provider 名/支持不一，直接绕开系统 provider 最可靠。
        val keyPair = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator().apply {
            init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
        }.generateKeyPair()
        val priv = keyPair.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
        val pub = keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
        val pubBytes = pub.getEncoded() // 32 字节公钥
        return SshKeyPair(
            privateKeyPem = toPkcs8Pem(priv),
            publicKeyLine = "ssh-ed25519 ${b64(sshString("ssh-ed25519".encodeToByteArray()) + sshString(pubBytes))} generated@rikkahub-agents",
        )
    }

    // ================= ECDSA (secp256r1) =================

    private fun generateEcdsa(): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        val pub = pair.public as ECPublicKey

        // 公钥点：0x04 + X + Y（各 32 字节）
        val x = fixed32(pub.w.affineX.toByteArray())
        val y = fixed32(pub.w.affineY.toByteArray())
        val point = byteArrayOf(0x04) + x + y

        val pubBlob = sshString("ecdsa-sha2-nistp256".encodeToByteArray()) +
            sshString("nistp256".encodeToByteArray()) +
            sshString(point)

        return SshKeyPair(
            privateKeyPem = toPkcs8Pem(pair.private),
            publicKeyLine = "ecdsa-sha2-nistp256 ${b64(pubBlob)} generated@rikkahub-agents",
        )
    }

    private fun fixed32(b: ByteArray): ByteArray {
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    // ================= PKCS#8 标准编码 =================

    /**
     * java.security.PrivateKey → PKCS#8 PEM（"BEGIN PRIVATE KEY"）。
     * 标准 JCA 的 PrivateKey.encoded 即为 PKCS#8 DER，直接 base64 包装即可，
     * 无需额外库（OpenSSH / JSch / haevn 均支持 PKCS#8）。
     */
    private fun toPkcs8Pem(key: java.security.PrivateKey): String {
        val der = key.encoded
        val b64 = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
    }

    /** BC Ed25519 私钥参数 → PKCS#8 PEM（"BEGIN PRIVATE KEY"）。 */
    private fun toPkcs8Pem(key: org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters): String {
        val der = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(key).encoded
        val b64 = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
    }

    // ================= 通用编码工具 =================

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** BigInteger → 裸字节（去多余前导 0）。 */
    private fun bytes(v: java.math.BigInteger): ByteArray {
        var b = v.toByteArray()
        if (b.size > 1 && b[0] == 0.toByte()) b = b.copyOfRange(1, b.size)
        return b
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
}
