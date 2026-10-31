package me.rerere.rikkahub.data.vault

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 公钥行的**线格式**契约（对照 RFC 4251 §6，并用真实密钥做公私钥配对验证）。
 *
 * 纯 JVM 可跑：这些编码不依赖 Android 组件，可用 `ssh-keygen -l` / `-y` 做等价对照。
 *
 * 核心不变量：**每个 mpint 都必须按有符号最小编码写出**。RSA 模数 bitLength 恒等于
 * 密钥长度 → 首字节必 ≥ 0x80 → 必须带前导 0x00 符号位；剥掉它，OpenSSH 的 bignum
 * 解码会得到**负数**，整条公钥判非法（修复前正是如此，见 SshKeyGenerator.bytes()）。
 */
class SshKeyGeneratorTest {

    // ================= RSA =================

    @Test
    fun `rsa public blob keeps the modulus sign byte`() {
        // 长度写死：e=65537 → 4+3；n 的 mpint 比裸字节多一个符号位
        // 2048: 4+7(类型) + 4+3(e) + 4+257(n) = 279  4096: 4+7 + 4+3 + 4+513 = 535
        assertEquals(279, blob(SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA)).size)
        assertEquals(535, blob(SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA4096)).size)
    }

    @Test
    fun `rsa mpints decode as non-negative minimal big-endian integers`() {
        listOf(SshKeyGenerator.KeyType.RSA, SshKeyGenerator.KeyType.RSA4096).forEach { type ->
            val r = readBlob(blob(SshKeyGenerator.generate(type)))
            assertEquals("ssh-rsa", r.type)
            assertEquals(BigInteger.valueOf(65537), r.e)
            assertTrue("n 必须为正（首字节 ≥0x80 时需符号位）", r.n.signum() > 0)
            // 最小编码：把值重新规范化，应与线上字节逐字节相同
            assertArrayEquals("n 的 mpint 非最小编码", r.n.toByteArray(), r.nBytes)
            assertArrayEquals("e 的 mpint 非最小编码", r.e.toByteArray(), r.eBytes)
        }
    }

    @Test
    fun `rsa public key line matches the generated private key`() {
        listOf(SshKeyGenerator.KeyType.RSA, SshKeyGenerator.KeyType.RSA4096).forEach { type ->
            val pair = SshKeyGenerator.generate(type)
            val r = readBlob(blob(pair))
            val priv = pkcs8Private(pair.privateKeyPem, "RSA")

            // 1) 结构比对：blob 里的 (n, e) 能重建出合法 RSA 公钥
            val pub = KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(r.n, r.e)) as RSAPublicKey
            assertEquals(r.n, pub.modulus)
            assertEquals(r.e, pub.publicExponent)

            // 2) 密码学比对：私钥签名 → 用公钥行还原的公钥验签
            val msg = "rikkahub agents".toByteArray()
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(priv); update(msg); sign()
            }
            assertTrue(
                "公钥行与私钥不匹配",
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(pub); update(msg); verify(sig)
                },
            )
        }
    }

    // ================= Ed25519 =================

    @Test
    fun `ed25519 public blob is a fixed 32-byte string and matches the openssh private key`() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        val b = blob(pair)
        // Ed25519 公钥是 ssh-string（定长 32B），**不是** mpint → 不涉及符号位
        assertEquals(51, b.size)
        val r = readBlob(b)
        assertEquals("ssh-ed25519", r.type)
        assertEquals(32, r.rawPublicKey.size)

        // 私钥必须是 openssh-key-v1（OpenSSH 拒绝 PKCS#8 的 Ed25519），其中内嵌的公钥与
        // seed 要和公钥行一致；再用 seed 签名、公钥行验签坐实配对。
        val priv = parseOpenSshPrivate(pair.privateKeyPem)
        assertEquals("ssh-ed25519", priv.keyType)
        val pw = Wire(priv.sectionBody)
        assertEquals("ssh-ed25519", pw.string().decodeToString())
        assertArrayEquals(r.rawPublicKey, pw.string())
        val sk = pw.string()
        pw.string() // comment
        pw.expectEnd("Ed25519 私钥段尾部有未消费字节")
        assertEquals(64, sk.size)
        assertArrayEquals(r.rawPublicKey, sk.copyOfRange(32, 64))

        val msg = "rikkahub agents".toByteArray()
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(
            true,
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(sk.copyOfRange(0, 32), 0),
        )
        signer.update(msg, 0, msg.size)
        val signature = signer.generateSignature()
        signer.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(r.rawPublicKey, 0))
        signer.update(msg, 0, msg.size)
        assertTrue("公钥行与私钥不匹配", signer.verifySignature(signature))
    }

    // ================= ECDSA =================

    @Test
    fun `ecdsa public blob carries an uncompressed point of the declared curve`() {
        listOf(
            Triple(SshKeyGenerator.KeyType.ECDSA, "nistp256", 32),
            Triple(SshKeyGenerator.KeyType.ECDSA384, "nistp384", 48),
            Triple(SshKeyGenerator.KeyType.ECDSA521, "nistp521", 66),
        ).forEach { (type, curve, fb) ->
            val pair = SshKeyGenerator.generate(type)
            val r = readBlob(blob(pair))
            assertEquals("ecdsa-sha2-$curve", r.type)
            assertEquals(curve, r.curve)
            // 坐标定长左补齐（fixedSize），不是 mpint → 高位为 0 的坐标也不能缩短
            assertEquals(1 + 2 * fb, r.point.size)
            assertEquals(0x04, r.point[0].toInt())
            assertArrayEquals(r.point.copyOfRange(1, 1 + fb), r.x)
            assertArrayEquals(r.point.copyOfRange(1 + fb, r.point.size), r.y)

            // 用私钥签名、用公钥行还原的公钥验签 → 公私钥确实是一对
            val priv = pkcs8Private(pair.privateKeyPem, "EC") as ECPrivateKey
            val msg = "rikkahub agents".toByteArray()
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(priv); update(msg); sign()
            }
            val pub = KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(ECPoint(BigInteger(1, r.x), BigInteger(1, r.y)), priv.params),
            )
            assertTrue(
                "公钥行与私钥不匹配",
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(pub); update(msg); verify(sig)
                },
            )
        }
    }

    // ================= 指纹 =================

    @Test
    fun `fingerprint uses the same base64 alphabet as ssh-keygen`() {
        // OpenSSH 用**标准** base64，只去掉尾部 '='；base64url 替换（+→- /→_）会与
        // `ssh-keygen -lf` 的输出不一致。此向量是合法的最小 ssh-ed25519 公钥，
        // 其摘要同时含 '+' 与 '/'，替换版必然给出不同字符串。
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKurq6urq6urq6urq6urq6urq6urq6urq6urq6urq6ur"
        assertEquals(
            "SHA256:mEwqXdTPoHnFTvEV3e8U+5RuFC2tSO9zs8CTJPIXq/w",
            SshKeyGenerator.fingerprint(line),
        )
    }

    @Test
    fun `fingerprint body is the sha256 of the public blob`() {
        listOf(
            SshKeyGenerator.KeyType.ED25519,
            SshKeyGenerator.KeyType.RSA,
            SshKeyGenerator.KeyType.ECDSA,
        ).forEach { type ->
            val pair = SshKeyGenerator.generate(type)
            val line = pair.publicKeyLine
            val fp = SshKeyGenerator.fingerprint(line)!!
            assertTrue("$fp 应以 SHA256: 开头", fp.startsWith("SHA256:"))
            assertEquals(43, fp.removePrefix("SHA256:").length)
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(blob(pair)),
                Base64.getDecoder().decode(fp.removePrefix("SHA256:") + "="),
            )
        }
    }

    @Test
    fun `fingerprint rejects malformed lines`() {
        // 只有一段（没有 blob）与 blob 段不是合法 base64——两种都必须判空
        assertEquals(null, SshKeyGenerator.fingerprint("ssh-rsa"))
        assertEquals(null, SshKeyGenerator.fingerprint("ssh-rsa !!! definitely not base64 !!!"))
    }

    // ================= 私钥容器格式转换 =================

    @Test
    fun `pkcs8 rsa converts to openssh container carrying the same crt parameters`() {
        listOf(SshKeyGenerator.KeyType.RSA, SshKeyGenerator.KeyType.RSA4096).forEach { type ->
            val pair = SshKeyGenerator.generate(type)
            assertEquals(SshKeyGenerator.PrivateKeyFormat.PKCS8, SshKeyGenerator.privateKeyFormat(pair.privateKeyPem))
            val parsed = parseOpenSshPrivate(SshKeyGenerator.toOpenSshPrivateKey(pair.privateKeyPem)!!)
            assertEquals("ssh-rsa", parsed.keyType)
            assertArrayEquals(blob(pair), parsed.publicBlob)
            val rsa = pkcs8Private(pair.privateKeyPem, "RSA") as RSAPrivateCrtKey
            val w = Wire(parsed.sectionBody)
            assertEquals("ssh-rsa", w.string().decodeToString())
            // OpenSSH 的 RSA 私钥段顺序：n, e, d, iqmp, p, q
            assertArrayEquals(mpint(rsa.modulus), w.string())
            assertArrayEquals(mpint(rsa.publicExponent), w.string())
            assertArrayEquals(mpint(rsa.privateExponent), w.string())
            assertArrayEquals(mpint(rsa.crtCoefficient), w.string())
            assertArrayEquals(mpint(rsa.primeP), w.string())
            assertArrayEquals(mpint(rsa.primeQ), w.string())
            w.string() // comment
            w.expectEnd("RSA 私钥段尾部有未消费字节")
        }
    }

    @Test
    fun `pkcs8 ecdsa converts to openssh container carrying the same point`() {
        listOf(
            SshKeyGenerator.KeyType.ECDSA,
            SshKeyGenerator.KeyType.ECDSA384,
            SshKeyGenerator.KeyType.ECDSA521,
        ).forEach { type ->
            val pair = SshKeyGenerator.generate(type)
            assertEquals(SshKeyGenerator.PrivateKeyFormat.PKCS8, SshKeyGenerator.privateKeyFormat(pair.privateKeyPem))
            val parsed = parseOpenSshPrivate(SshKeyGenerator.toOpenSshPrivateKey(pair.privateKeyPem)!!)
            assertTrue("类型应为 ecdsa-sha2-*，实际 ${parsed.keyType}", parsed.keyType.startsWith("ecdsa-sha2-"))
            assertArrayEquals(blob(pair), parsed.publicBlob)
            // 私钥段：类型 + 曲线名 + 公钥点 + 私钥标量
            val w = Wire(parsed.sectionBody)
            assertEquals(parsed.keyType, w.string().decodeToString())
            val curve = w.string().decodeToString()
            val point = w.string()
            assertTrue("私钥标量不应为空", w.string().isNotEmpty())
            w.string() // comment
            w.expectEnd("ECDSA 私钥段尾部有未消费字节")
            // 段内的曲线名与公钥点必须与公钥 blob 一致
            val pb = Wire(parsed.publicBlob)
            pb.string()
            assertEquals(curve, pb.string().decodeToString())
            assertArrayEquals(pb.string(), point)
        }
    }

    @Test
    fun `ed25519 openssh converts to pkcs8 and round-trips to the same public key`() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        assertEquals(SshKeyGenerator.PrivateKeyFormat.OPENSSH, SshKeyGenerator.privateKeyFormat(pair.privateKeyPem))
        // 已经是 OpenSSH 的：原样返回
        assertEquals(pair.privateKeyPem, SshKeyGenerator.toOpenSshPrivateKey(pair.privateKeyPem))
        val pkcs8 = SshKeyGenerator.toPkcs8PrivateKey(pair.privateKeyPem)!!
        assertTrue(pkcs8.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertEquals(SshKeyGenerator.PrivateKeyFormat.PKCS8, SshKeyGenerator.privateKeyFormat(pkcs8))
        // 已经是 PKCS#8 的：原样返回
        assertEquals(pkcs8, SshKeyGenerator.toPkcs8PrivateKey(pkcs8))
        // PKCS#8 → OpenSSH 后，公钥段必须与原公钥行一致
        assertArrayEquals(blob(pair), parseOpenSshPrivate(SshKeyGenerator.toOpenSshPrivateKey(pkcs8)!!).publicBlob)
    }

    @Test
    fun `conversion returns null for unrecognized input`() {
        val other = "-----BEGIN SOMETHING ELSE-----\nAAAA\n-----END SOMETHING ELSE-----\n"
        assertEquals(SshKeyGenerator.PrivateKeyFormat.UNKNOWN, SshKeyGenerator.privateKeyFormat(other))
        assertEquals(null, SshKeyGenerator.toOpenSshPrivateKey(other))
        assertEquals(null, SshKeyGenerator.toPkcs8PrivateKey(other))
    }

    /** mpint 是**有符号最小编码**：BigInteger.toByteArray() 的形状即为所需。 */
    private fun mpint(v: BigInteger): ByteArray = v.toByteArray()

    // ================= helpers =================

    private fun blob(pair: SshKeyGenerator.SshKeyPair): ByteArray =
        Base64.getDecoder().decode(pair.publicKeyLine.trim().split(Regex("\\s+"))[1])

    private fun pkcs8Private(pem: String, algorithm: String): PrivateKey {
        val body = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        return KeyFactory.getInstance(algorithm)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    /** 一条已按 RFC 4251 解析的公钥 blob（与当前类型无关的字段留空/零）。 */
    private class Blob(
        val type: String,
        val eBytes: ByteArray,
        val e: BigInteger,
        val nBytes: ByteArray,
        val n: BigInteger,
        val curve: String,
        val point: ByteArray,
        val x: ByteArray,
        val y: ByteArray,
        val rawPublicKey: ByteArray,
    )

    /** openssh-key-v1 私钥文件的公钥段/私钥段。 */
    private class OpenSshPrivate(
        val keyType: String,
        /** 容器头声明的公钥 blob（含类型段；RSA/ECDSA 内部还有参数）。 */
        val publicBlob: ByteArray,
        /** 私钥段正文（两个 checkint 之后、填充之前；首段是 keyType，其余按算法而异）。 */
        val sectionBody: ByteArray,
    )

    /** RFC 4251 的 string/uint32 读游标；expectEnd() 保证声明长度与实际字节严格对齐。 */
    private class Wire(private val d: ByteArray) {
        private var pos = 0

        fun bytes(n: Int): ByteArray {
            require(n in 0..d.size - pos) { "声明长度 $n 越界（剩余 ${d.size - pos}）" }
            val out = d.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun u32(): Int = bytes(4).fold(0) { a, x -> (a shl 8) or (x.toInt() and 0xFF) }

        fun string(): ByteArray = bytes(u32())

        val remaining: Int get() = d.size - pos

        fun expectEnd(label: String) = assertEquals(label, d.size, pos)
    }

    /**
     * 严格读一遍公钥 blob：string = uint32 长度 + 字节；mpint = **有符号**最小编码。
     * BigInteger(ByteArray) 即有符号解释 —— 剥掉符号位的旧编码会读出**负数**。
     */
    private fun readBlob(b: ByteArray): Blob {
        val w = Wire(b)
        val type = w.string().decodeToString()
        val parsed = when (type) {
            "ssh-rsa" -> {
                val eBytes = w.string()
                val nBytes = w.string()
                Blob(type, eBytes, BigInteger(eBytes), nBytes, BigInteger(nBytes), "", EMPTY, EMPTY, EMPTY, EMPTY)
            }
            "ssh-ed25519" -> Blob(type, EMPTY, ZERO, EMPTY, ZERO, "", EMPTY, EMPTY, EMPTY, w.string())
            else -> {
                val curve = w.string()
                val point = w.string()
                val fb = (point.size - 1) / 2
                Blob(
                    type, EMPTY, ZERO, EMPTY, ZERO, curve.decodeToString(), point,
                    point.copyOfRange(1, 1 + fb), point.copyOfRange(1 + fb, point.size), EMPTY,
                )
            }
        }
        w.expectEnd("公钥 blob 尾部有未消费字节")
        return parsed
    }

    /** 解析 openssh-key-v1（none cipher、单密钥）的容器结构；私钥段正文交给调用方按算法读。 */
    private fun parseOpenSshPrivate(pem: String): OpenSshPrivate {
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        val der = Base64.getDecoder().decode(
            pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim(),
        )
        val w = Wire(der)
        assertEquals("openssh-key-v1", String(w.bytes(14), Charsets.US_ASCII))
        assertEquals(0, w.bytes(1)[0].toInt())
        assertEquals("none", w.string().decodeToString())
        assertEquals("none", w.string().decodeToString())
        assertEquals(0, w.string().size)
        assertEquals(1, w.u32())

        // 公钥 blob 整段取用：RSA / ECDSA 的 blob 内部是多段结构（类型 + 参数），
        // 这里不做二次解释，需要时交给 readBlob() 按类型严格解析。
        val publicBlob = w.string()
        val section = w.string()
        w.expectEnd("容器尾部有未消费字节")

        // 私钥段：两个相同的 checkint + 正文 + 递增填充（总长对齐 8 字节，填充 1..8 字节）
        val s = Wire(section)
        val check1 = s.bytes(4)
        assertArrayEquals("两个 checkint 必须相同（否则私钥结构不对）", check1, s.bytes(4))
        val afterCheck = section.copyOfRange(8, section.size)
        val padLen = (1..8).firstOrNull { n ->
            n <= afterCheck.size &&
                (0 until n).all { i -> afterCheck[afterCheck.size - n + i] == (i + 1).toByte() }
        }
        assertTrue("私钥段填充不合规（未找到递增填充）", padLen != null)
        val body = afterCheck.copyOfRange(0, afterCheck.size - padLen!!)
        val keyType = Wire(body).string().decodeToString()
        assertEquals("公钥 blob 的类型段应与私钥段一致", keyType, Wire(publicBlob).string().decodeToString())
        return OpenSshPrivate(keyType, publicBlob, body)
    }

    /** 手工粘贴的 PKCS#8 Ed25519 私钥（无配套公钥行）也要能推导出公钥。 */
    @Test
    fun `ed25519 public key line derives from pkcs8 private key`() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        val pkcs8 = SshKeyGenerator.toPkcs8PrivateKey(pair.privateKeyPem) ?: error("pkcs8 转换失败")
        val derived = SshKeyGenerator.ed25519PublicKeyLineFromPrivate(pkcs8) ?: error("公钥推导失败")
        // 类型 + base64 段必须与生成时一致（comment 不参与比较）
        assertEquals(pair.publicKeyLine.split(" ").take(2), derived.split(" ").take(2))
    }

    /** JWK 的 x = 公钥 blob 末尾 32 字节 base64url（RFC 8037），且必须带 kid。 */
    @Test
    fun `ed25519 jwk carries kid and 32 byte x`() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        val jwk = SshKeyGenerator.ed25519Jwk(pair.publicKeyLine, "sdk-test") ?: error("JWK 生成失败")
        assertTrue(jwk.startsWith("{\"kty\":\"OKP\",\"crv\":\"Ed25519\""))
        assertTrue(jwk.contains("\"kid\":\"sdk-test\""))
        val x = Regex("\"x\":\"([^\"]+)\"").find(jwk)?.groupValues?.get(1) ?: error("缺 x 字段")
        assertEquals(43, x.length)
    }

    private companion object {
        val EMPTY = ByteArray(0)
        val ZERO = BigInteger.ZERO
    }
}
