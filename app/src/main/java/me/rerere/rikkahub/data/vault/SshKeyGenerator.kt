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
 * 支持三种算法：
 * - RSA-2048：私钥 PKCS#1 PEM（"BEGIN RSA PRIVATE KEY"），JSch 直接 addIdentity
 * - Ed25519：私钥 OpenSSH 格式（"BEGIN OPENSSH PRIVATE KEY"，openssh-key-v1），公钥 ssh-ed25519
 * - ECDSA (secp256r1/nistp256)：私钥 OpenSSH 格式，公钥 ecdsa-sha2-nistp256
 *
 * 注意：不用 AndroidKeyStore（其密钥不可导出），SSH 私钥需要可导出以配置到服务器
 * authorized_keys。私钥生成后保存到 Vault（安全凭证库，AES-GCM 加密）或用户指定路径。
 *
 * Ed25519 需要 Android 14+（API 34）才内置支持；旧系统会抛 NoSuchAlgorithmException，
 * 调用方应捕获并提示改用 RSA/ECDSA。
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
        val priv = pair.private as RSAPrivateCrtKey
        return SshKeyPair(
            privateKeyPem = encodePkcs1(priv),
            publicKeyLine = "ssh-rsa ${b64(encodeSshRsa(pub))} generated@rikkahub-agents",
        )
    }

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

    // ================= Ed25519 =================

    private fun generateEd25519(): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        // 部分 Provider（Conscrypt）必须显式 initialize，否则 generateKeyPair 报 Not initialized
        gen.initialize(255)
        val pair = gen.generateKeyPair()
        val pubBytes = (pair.public as java.security.interfaces.EdECPublicKey).point.y.toByteArray()
            .let { y ->
                val out = ByteArray(32)
                val src = if (y.size > 32) y.copyOfRange(y.size - 32, y.size) else y
                System.arraycopy(src, 0, out, 32 - src.size, src.size)
                out
            }
        // 私钥：从 PKCS#8 提取 32 字节 seed
        val pkcs8 = pair.private.encoded
        val seed = extractEd25519SeedFromPkcs8(pkcs8)
        return SshKeyPair(
            privateKeyPem = encodeOpenSshKeyV1(
                keyType = "ssh-ed25519",
                publicBlob = sshString("ssh-ed25519".encodeToByteArray()) + sshString(pubBytes),
                privateBlob = sshString("ssh-ed25519".encodeToByteArray()) +
                    sshString(pubBytes) +
                    sshString(seed + pubBytes),
            ),
            publicKeyLine = "ssh-ed25519 ${b64(sshString("ssh-ed25519".encodeToByteArray()) + sshString(pubBytes))} generated@rikkahub-agents",
        )
    }

    /** 从 PKCS#8 DER 提取 Ed25519 私钥 seed（OCTET STRING 内嵌 32 字节）。 */
    private fun extractEd25519SeedFromPkcs8(pkcs8: ByteArray): ByteArray {
        // PKCS#8: SEQUENCE { version, AlgorithmIdentifier, OCTET STRING { seed } }
        // 简单扫描：找最后一个 OCTET STRING 头（0x04 0x20）后的 32 字节
        var idx = 0
        while (idx < pkcs8.size - 2) {
            if (pkcs8[idx].toInt() == 0x04 && pkcs8[idx + 1].toInt() == 0x20) {
                return pkcs8.copyOfRange(idx + 2, idx + 34)
            }
            idx++
        }
        throw IllegalStateException("无法从 PKCS#8 解析 Ed25519 seed")
    }

    // ================= ECDSA (secp256r1) =================

    private fun generateEcdsa(): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        val pub = pair.public as ECPublicKey
        val priv = pair.private as ECPrivateKey

        // 公钥点：0x04 + X + Y（各 32 字节）
        val x = fixed32(pub.w.affineX.toByteArray())
        val y = fixed32(pub.w.affineY.toByteArray())
        val point = byteArrayOf(0x04) + x + y

        val pubBlob = sshString("ecdsa-sha2-nistp256".encodeToByteArray()) +
            sshString("nistp256".encodeToByteArray()) +
            sshString(point)
        val privBlob = sshString("ecdsa-sha2-nistp256".encodeToByteArray()) +
            sshString("nistp256".encodeToByteArray()) +
            sshString(point) +
            sshString(bytes(priv.s))

        return SshKeyPair(
            privateKeyPem = encodeOpenSshKeyV1("ecdsa-sha2-nistp256", pubBlob, privBlob),
            publicKeyLine = "ecdsa-sha2-nistp256 ${b64(pubBlob)} generated@rikkahub-agents",
        )
    }

    private fun fixed32(b: ByteArray): ByteArray {
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    // ================= OpenSSH 私钥格式 (openssh-key-v1) =================

    /**
     * 编码 OpenSSH 私钥文件（"BEGIN OPENSSH PRIVATE KEY"）。
     * 无加密（none cipher），JSch (mwiede fork) 可直接 addIdentity。
     */
    private fun encodeOpenSshKeyV1(keyType: String, publicBlob: ByteArray, privateBlob: ByteArray): String {
        val rand = SecureRandom()
        val check = ByteArray(4)
        rand.nextBytes(check)

        // private section = checkint + checkint + privateBlob + comment + padding
        val comment = ByteArray(0)
        val noPadding = check + check + privateBlob + sshString(comment)
        val padLen = (8 - (noPadding.size % 8)) % 8
        val padding = ByteArray(if (padLen == 0) 8 else padLen) { padLen.toByte() }
        val privateSection = noPadding + padding

        val blob =
            "openssh-key-v1\u0000".encodeToByteArray() +
                sshString("none".encodeToByteArray()) +
                sshString("none".encodeToByteArray()) +
                sshString(ByteArray(0)) +
                bigInt(1) +
                sshString(publicBlob) +
                sshString(privateSection)

        val b64 = Base64.getEncoder().encodeToString(blob).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
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
