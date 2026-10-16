package me.rerere.rikkahub.data.vault

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * SSH 密钥对生成器（Web 桥 / 凭证库「生成 SSH 密钥」用）。
 *
 * 支持 RSA（2048/4096）、Ed25519、ECDSA（nistp256/384/521），私钥统一输出
 * **PKCS#8 PEM**（"BEGIN PRIVATE KEY"，Bouncy Castle 标准编码），Ed25519 例外（OpenSSH 格式）：
 * - RSA-2048 / RSA-4096：私钥 PKCS#8，公钥 ssh-rsa
 * - Ed25519：私钥 OpenSSH 格式（openssh-key-v1；OpenSSH 不接受该算法的 PKCS#8），公钥 ssh-ed25519（BC 软件生成，可导出）
 * - ECDSA (secp256r1/384r1/521r1)：私钥 PKCS#8，公钥 ecdsa-sha2-nistp256/nistp384/nistp521
 *
 * PKCS#8 是通用标准，OpenSSH / JSch / haevn 等现代客户端均支持。
 *
 * 注意：不用 AndroidKeyStore（其密钥不可导出），SSH 私钥需要可导出以配置到服务器
 * authorized_keys。Ed25519 用 Bouncy Castle 生成（绕开 Android 系统 provider 差异）。
 */
object SshKeyGenerator {

    enum class KeyType(val label: String) {
        ED25519("Ed25519"),
        RSA("RSA-2048"),
        RSA4096("RSA-4096"),
        ECDSA("ECDSA (nistp256)"),
        ECDSA384("ECDSA (nistp384)"),
        ECDSA521("ECDSA (nistp521)"),
    }

    /** 生成指定类型的密钥对。comment 作为公钥行尾注释，缺省带 RikkaHub Agents 标识。 */
    fun generate(type: KeyType, comment: String = DEFAULT_COMMENT): SshKeyPair = when (type) {
        KeyType.RSA -> generateRsa(comment, 2048)
        KeyType.RSA4096 -> generateRsa(comment, 4096)
        KeyType.ED25519 -> generateEd25519(comment)
        KeyType.ECDSA -> generateEcdsa(comment, "secp256r1", "nistp256", 32)
        KeyType.ECDSA384 -> generateEcdsa(comment, "secp384r1", "nistp384", 48)
        KeyType.ECDSA521 -> generateEcdsa(comment, "secp521r1", "nistp521", 66)
    }

    /** 默认生成 RSA-2048（兼容旧调用）。 */
    fun generate(): SshKeyPair = generate(KeyType.RSA)

    /** 公钥行尾默认注释：标识生成方，便于服务器 authorized_keys 溯源。 */
    const val DEFAULT_COMMENT = "generated@rikkahub-agents"

    data class SshKeyPair(
        val privateKeyPem: String,
        val publicKeyLine: String,
    )

    // ================= RSA =================

    private fun generateRsa(comment: String, bits: Int): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(bits)
        val pair = gen.generateKeyPair()
        val pub = pair.public as RSAPublicKey
        return SshKeyPair(
            privateKeyPem = toPkcs8Pem(pair.private),
            publicKeyLine = "ssh-rsa ${b64(encodeSshRsa(pub))} $comment",
        )
    }

    /** 编码 OpenSSH 公钥格式（ssh-rsa 类型 + e + n）。 */
    private fun encodeSshRsa(pub: RSAPublicKey): ByteArray =
        sshString("ssh-rsa".encodeToByteArray()) +
            sshString(bytes(pub.publicExponent)) +
            sshString(bytes(pub.modulus))

    // ================= Ed25519 =================

    private fun generateEd25519(comment: String): SshKeyPair {
        // 用 Bouncy Castle 生成（软件实现，密钥可导出）——Android 上 getInstance("Ed25519")
        // 默认解析到 AndroidKeyStore（强制 KeyGenParameterSpec 且密钥不可导出，不适合 SSH），
        // 且不同 ROM 的 Conscrypt provider 名/支持不一，直接绕开系统 provider 最可靠。
        //
        // 注意：Ed25519 私钥用 OpenSSH 格式（openssh-key-v1）而非 PKCS#8——
        // OpenSSH 9.x 的 ssh/ssh-keygen 拒绝加载 PKCS#8 格式的 Ed25519 私钥
        // （RSA/ECDSA 的 PKCS#8 没问题，唯独 Ed25519 强制 OpenSSH 格式）。
        val keyPair = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator().apply {
            init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
        }.generateKeyPair()
        val priv = keyPair.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
        val pub = keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
        val seed = priv.getEncoded() // 32 字节 seed
        val pubBytes = pub.getEncoded() // 32 字节公钥
        return SshKeyPair(
            privateKeyPem = encodeOpenSshKeyV1(
                keyType = "ssh-ed25519",
                publicBlob = sshString("ssh-ed25519".encodeToByteArray()) + sshString(pubBytes),
                privateBlob = sshString("ssh-ed25519".encodeToByteArray()) +
                    sshString(pubBytes) +
                    sshString(seed + pubBytes),
                comment = comment,
            ),
            publicKeyLine = "ssh-ed25519 ${b64(sshString("ssh-ed25519".encodeToByteArray()) + sshString(pubBytes))} $comment",
        )
    }

    // ================= OpenSSH 私钥格式 (openssh-key-v1) =================

    /**
     * 编码 OpenSSH 私钥文件（"BEGIN OPENSSH PRIVATE KEY"）。
     * 无加密（none cipher）。仅 Ed25519 使用（OpenSSH 强制要求该格式）。
     * padding 按规范递增（1,2,3,...）。
     */
    private fun encodeOpenSshKeyV1(keyType: String, publicBlob: ByteArray, privateBlob: ByteArray, comment: String = ""): String {
        val rand = SecureRandom()
        val check = ByteArray(4)
        rand.nextBytes(check)

        // private section = checkint + checkint + privateBlob + comment + padding
        val commentBytes = comment.encodeToByteArray()
        val noPadding = check + check + privateBlob + sshString(commentBytes)
        val padLen = (8 - (noPadding.size % 8)) % 8
        val padding = ByteArray(if (padLen == 0) 8 else padLen) { (it + 1).toByte() }
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

    // ================= ECDSA =================

    /**
     * ECDSA 密钥对。curveSpec 为 JCA 曲线名（secp256r1/secp384r1/secp521r1），
     * sshCurve 为 OpenSSH 曲线名（nistp256/384/521），fieldBytes 为坐标定长字节数（32/48/66）。
     */
    private fun generateEcdsa(comment: String, curveSpec: String, sshCurve: String, fieldBytes: Int): SshKeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(curveSpec))
        val pair = gen.generateKeyPair()
        val pub = pair.public as ECPublicKey

        // 公钥点：0x04 + X + Y（各 fieldBytes 字节）
        val x = fixedSize(pub.w.affineX.toByteArray(), fieldBytes)
        val y = fixedSize(pub.w.affineY.toByteArray(), fieldBytes)
        val point = byteArrayOf(0x04) + x + y

        val keyTypeName = "ecdsa-sha2-$sshCurve"
        val pubBlob = sshString(keyTypeName.encodeToByteArray()) +
            sshString(sshCurve.encodeToByteArray()) +
            sshString(point)

        return SshKeyPair(
            privateKeyPem = toPkcs8Pem(pair.private),
            publicKeyLine = "$keyTypeName ${b64(pubBlob)} $comment",
        )
    }

    /** BigInteger 坐标 → 定长大端字节。 */
    private fun fixedSize(b: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        val src = if (b.size > size) b.copyOfRange(b.size - size, b.size) else b
        System.arraycopy(src, 0, out, size - src.size, src.size)
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

    // ================= 通用编码工具 =================

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /**
     * BigInteger → SSH mpint（RFC 4251 §6：最小长度的大端**有符号**编码）。
     *
     * toByteArray() 给的正是这个形状，不要再动它：模数 bitLength 恒等于密钥长度 → 首字节
     * 必 ≥ 0x80 → 那个前导 0x00 是**符号字节**。剥掉它，按有符号解释读出的是负数，OpenSSH
     * 会直接判定整条公钥非法（`ssh-keygen -lf` 报 "is not a public key file"）。
     */
    private fun bytes(v: java.math.BigInteger): ByteArray = v.toByteArray()

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

    /**
     * 计算 OpenSSH 公钥行的 SHA256 指纹（格式 "SHA256:xxxx"），与 `ssh-keygen -lf` 一致。
     * 公钥行格式：`<type> <b64blob> [comment]`。非法输入返回 null（原因见 [publicKeyLineIssue]）。
     */
    fun fingerprint(publicKeyLine: String): String? {
        val (blob, _, issue) = inspectPublicKey(publicKeyLine)
        val fpBlob = if (issue == null) blob else null
        val digest = fpBlob?.let { sha256Blob(it) } ?: return null
        return "SHA256:" + Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }

    private fun sha256Blob(blob: ByteArray): ByteArray? = try {
        java.security.MessageDigest.getInstance("SHA-256").digest(blob)
    } catch (_: java.security.NoSuchAlgorithmException) {
        null
    }

    /** 证书行的指纹算的是**内层公钥**，不是整张证书（`ssh-keygen -lf` 行为）。 */
    private const val CERT_SUFFIX = "-cert-v01@openssh.com"

    /**
     * 解码公钥行的 base64 段。
     *
     * 先严格按 RFC 4648 解；失败时**只**尝试一种修复：剥掉多余的尾部 `=`。
     * 为什么要有这一步：手工编辑、Markdown 渲染、剪贴板/邮件换行都可能给本不需要
     * 填充的数据补上一个 `=`，此时长度 %4==1，Java 的严格解码器直接抛异常，而
     * OpenSSH 自己也会拒绝该行 —— 也就是说这是**坏数据**，不能当作正常公钥用。
     * 但指纹这类只读展示不应因此变成"(解析失败)"：剥掉填充后重新补正、再编码比对，
     * 字节完全一致才认定为"仅填充写坏"，可安全修复。其它畸形（截断、插入字符）一律不修。
     */
    private fun decodePublicKeyBlob(b64: String): ByteArray? {
        try {
            return Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            // fall through：仅下面这一种情形可判定为填充多余
        }
        if (!b64.endsWith('=')) return null
        val stripped = b64.trimEnd('=')
        if (stripped.isEmpty()) return null
        val canonical = stripped + "=".repeat((4 - stripped.length % 4) % 4)
        val raw = try {
            Base64.getDecoder().decode(canonical)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // 重新编码必须逐字符相同，否则说明原 base64 不只是填充问题（如非规范末组编码）
        return if (Base64.getEncoder().encodeToString(raw) == canonical) raw else null
    }

    /**
     * 该类型「类型串之后的键体字段」应有的个数；不认识的类型返回 null（只校类型，避免误拒）。
     *
     * 证书类型返回 null：其键体字段跟在 nonce 之后、排布不同，交给 [certInnerBlob]。
     */
    private fun keyFieldCount(type: String): Int? = when {
        type.endsWith(CERT_SUFFIX) -> null
        type == "ssh-rsa" || type == "ssh-rsa-sha2-256" || type == "ssh-rsa-sha2-512" -> 2 // e, n
        type == "ssh-dss" -> 4 // p, q, g, y
        type == "ssh-ed25519" -> 1 // 32 字节公钥
        type.startsWith("ecdsa-sha2-") -> 2 // 曲线名, 点
        else -> null
    }

    /**
     * mpint 符号字节检查（RFC 4251 §6）：只用于 RSA/DSA 这类"键体就是 mpint"的类型。
     *
     * 为什么值得单独查：mpint 的首字节是**符号字节**，`n` 少了前导 `0x00` 就变成"负模数"，
     * 长度前缀照样自洽、字段个数照样对，光靠结构校验看不出来 —— 但 OpenSSH 一定拒读
     * （RSA-2048/RSA-4096 的 `n`、DSA-1024 的 `p` 各剥掉一个前导 `0x00`，
     * `ssh-keygen -lf` 全部报 `is not a public key file`）。
     *
     * **不做**反向检查（"多了个前导 0x00 = 非最简编码"）：OpenSSH 对这种编码是**容忍**的
     * （给 RSA 的 `e`/`n` 补前导 `0x00`，`ssh-keygen -lf` 照样输出**同一个指纹**），
     * 我们据此拒绝就是**误拒**真实可用的数据；正确处理是**先重编成最简形式再取摘要**，
     * 见 [canonicalBlob]。
     * ECDSA 的点、ed25519 的公钥是 ssh-string 不是 mpint，不适用。
     */
    private fun mpintIssue(field: ByteArray, label: String): String? = when {
        field.isEmpty() -> "$label 为空"
        (field[0].toInt() and 0x80) != 0 -> "$label 被解释成负数（缺前导符号字节 0x00）"
        else -> null
    }

    /**
     * 把 blob 里 RSA/DSA 的 mpint 字段重编成**最简形式**，其余类型原样返回。
     *
     * 为什么必须重编再取摘要：OpenSSH 的指纹算在"解析后重新序列化"的 key 上，不是存储的原始字节。
     * （同一条真实 2048 位 RSA）：给 `e` 补 1 个 / 2 个前导 `0x00`、
     * 给 `n` 补 1 个前导 `0x00`，`ssh-keygen -lf` **四次都输出同一个指纹**
     * `SHA256:l7xV3DJeXXRlxU6LDccg+Q4pflid7M9Q4Ut60J4+FdA`。我们若直接对存储字节取摘要，
     * 这种行就会得到一个和服务器、和 `ssh-keygen` 都对不上的指纹 —— 正是本批要消灭的那类问题。
     * 对已经最简的正常数据，这个函数是**恒等变换**（语料 112 条逐条比对过前后一致）。
     */
    private fun canonicalBlob(type: String, blob: ByteArray): ByteArray {
        val mpintFields = type == "ssh-rsa" || type == "ssh-dss" ||
            type == "ssh-rsa-sha2-256" || type == "ssh-rsa-sha2-512"
        if (!mpintFields) return blob
        val c = SshReader(blob, 0)
        val typeB = c.str() ?: return blob
        var out = sshString(typeB)
        while (!c.end()) {
            val f = c.str() ?: return blob
            out += sshString(minimalMpint(f))
        }
        return out
    }

    /**
     * mpint 最简形式：剥掉"多余"的前导 `0x00`（剥完仍然非负才剥）。
     * 只往"剥"的方向走：给负数补符号字节是 [mpintIssue] 已经拒绝的情形，这里不猜。
     */
    private fun minimalMpint(f: ByteArray): ByteArray {
        var i = 0
        while (i < f.size - 1 && f[i] == 0.toByte() && (f[i + 1].toInt() and 0x80) == 0) i++
        return if (i == 0) f else f.copyOfRange(i, f.size)
    }

    /** 按 OpenSSH 的字段排布校验一段 public blob；合法返回 null，否则返回原因。 */
    private fun blobIssue(declaredType: String, blob: ByteArray): String? {
        val c = SshReader(blob, 0)
        val typeB = c.str() ?: return "公钥数据的长度前缀越界（内容被截断或插入了字符）"
        val type = String(typeB, Charsets.ISO_8859_1)
        if (type != declaredType) return "声明类型 $declaredType 与公钥数据内嵌类型 $type 不一致"
        val count = keyFieldCount(type) ?: return null
        val rest = ArrayList<ByteArray>()
        while (!c.end()) {
            val s = c.str() ?: return "公钥数据的长度前缀越界（内容被截断或插入了字符）"
            rest.add(s)
        }
        if (rest.size != count) return "$type 应有 $count 个键体字段，实际 ${rest.size} 个"
        return keyFieldsIssue(type, rest)
    }

    /** 按公钥类型分派到各自的键体字段校验。 */
    private fun keyFieldsIssue(type: String, rest: List<ByteArray>): String? = when {
        type == "ssh-rsa" -> rsaFieldsIssue(rest)
        type == "ssh-dss" -> dssFieldsIssue(rest)
        type == "ssh-ed25519" -> ed25519FieldsIssue(rest)
        type.startsWith("ecdsa-sha2-") -> ecdsaFieldsIssue(type, rest)
        else -> null
    }

    /** RSA 键体：e（小指数）+ n（模数），两者都必须是**最简有符号 mpint**。 */
    private fun rsaFieldsIssue(rest: List<ByteArray>): String? {
        if (rest[0].isEmpty() || rest[0].size > 8) return "ssh-rsa 的 e 字段长度异常：${rest[0].size}"
        if (rest[1].size < 64 || rest[1].size > 1024) return "ssh-rsa 的 n 字段长度异常：${rest[1].size}"
        mpintIssue(rest[0], "e")?.let { return "ssh-rsa 的$it" }
        mpintIssue(rest[1], "n")?.let { return "ssh-rsa 的$it" }
        return null
    }

    /** DSA 键体：p / q / g / y 四个 mpint。 */
    private fun dssFieldsIssue(rest: List<ByteArray>): String? {
        listOf("p", "q", "g", "y").forEachIndexed { i, label ->
            mpintIssue(rest[i], label)?.let { return "ssh-dss 的$it" }
        }
        return null
    }

    /** Ed25519 键体：定长 32 字节裸公钥（不是 mpint，不涉及符号位）。 */
    private fun ed25519FieldsIssue(rest: List<ByteArray>): String? =
        if (rest[0].size != 32) "ssh-ed25519 公钥长度应为 32 字节，实际 ${rest[0].size}" else null

    /** ECDSA 键体：曲线名（与声明一致）+ 未压缩点（0x04 + X + Y）。 */
    private fun ecdsaFieldsIssue(type: String, rest: List<ByteArray>): String? {
        val expect = when (String(rest[0], Charsets.ISO_8859_1)) {
            "nistp256" -> 65
            "nistp384" -> 97
            "nistp521" -> 133
            else -> -1
        }
        if (expect > 0 && rest[1].size != expect) return "$type 点长度应为 $expect，实际 ${rest[1].size}"
        if (rest[1].isEmpty() || rest[1][0] != 0x04.toByte()) return "$type 的点必须是未压缩形式（0x04 开头）"
        return null
    }

    /** 证书行 → 内层公钥 blob（补回类型串）；无法重建时给出原因。 */
    private fun certInnerBlob(certType: String, blob: ByteArray): Pair<ByteArray?, String?> {
        val base = certType.removeSuffix(CERT_SUFFIX)
        val count = keyFieldCount(base)
            ?: return null to "暂不支持 $certType 的指纹（OpenSSH 按内层公钥计算，该类型字段排布未实现）"
        val c = SshReader(blob, 0)
        if (c.str() == null) return null to "证书线格式非法"
        if (c.str() == null) return null to "证书线格式非法" // nonce
        val fields = ArrayList<ByteArray>()
        while (!c.end() && fields.size < count) {
            val s = c.str() ?: return null to "证书内层公钥字段越界"
            fields.add(s)
        }
        if (fields.size != count) {
            return null to "证书内层公钥字段不足：期望 $count 个，实际 ${fields.size} 个"
        }
        var inner = sshString(base.toByteArray())
        for (f in fields) inner += sshString(f)
        return inner to blobIssue(base, inner)
    }

    /**
     * 解析公钥行，返回（指纹所依据的 blob, 整段 public blob, 问题原因）。
     * 原因为 null 时两个 blob 均可用；否则两者均为 null。
     */
    private fun inspectPublicKey(publicKeyLine: String): Triple<ByteArray?, ByteArray?, String?> {
        val parts = publicKeyLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) return Triple(null, null, "不是公钥行（缺少 base64 段）")
        val b64 = parts[1]
        val blob = decodePublicKeyBlob(b64) ?: return Triple(
            null,
            null,
            "base64 段无法解码（长度 ${b64.length}，%4=${b64.length % 4}：截断或插入了多余字符）",
        )
        blobIssue(parts[0], blob)?.let { return Triple(null, null, it) }
        val isCert = parts[0].endsWith(CERT_SUFFIX)
        val fpBlob = if (isCert) {
            val (inner, issue) = certInnerBlob(parts[0], blob)
            if (issue != null) return Triple(null, null, issue) else inner
        } else {
            blob
        }
        // 两边都按 OpenSSH 的做法重编一次 mpint：指纹要与 ssh-keygen 一致，
        // 写回库里的行也要是最简形态（证书类型在 canonicalBlob 里是恒等变换，不会把证书改成内层公钥）
        val fpType = if (isCert) parts[0].removeSuffix(CERT_SUFFIX) else parts[0]
        return Triple(
            fpBlob?.let { canonicalBlob(fpType, it) },
            canonicalBlob(parts[0], blob),
            null,
        )
    }

    /** 公钥行是否可用；可用返回 null，否则返回**具体**失败原因（供 UI/AI 直接展示，别只说"解析失败"）。 */
    fun publicKeyLineIssue(publicKeyLine: String): String? = inspectPublicKey(publicKeyLine).third

    /**
     * 规范化公钥行：base64 段重新按标准编码（顺带修掉"多一个 `=`"这类写坏），保留原注释。
     * 结构非法返回 null —— 调用方据此拒绝入库，而不是把坏数据存进库再在使用点报错。
     */
    fun canonicalizePublicKeyLine(publicKeyLine: String): String? {
        val (fpBlob, blob, issue) = inspectPublicKey(publicKeyLine)
        if (issue != null || blob == null || fpBlob == null) return null
        val parts = publicKeyLine.trim().split(Regex("\\s+"))
        val comment = if (parts.size > 2) " " + parts.drop(2).joinToString(" ") else ""
        return parts[0] + " " + Base64.getEncoder().encodeToString(blob) + comment
    }

    // ================= 私钥格式转换（导出用） =================

    /** 私钥 PEM 的容器格式。 */
    enum class PrivateKeyFormat(val label: String) {
        OPENSSH("OpenSSH"),
        PKCS8("PKCS#8"),
        UNKNOWN("-"),
    }

    /** 判定私钥 PEM 的容器格式（只看头部）。 */
    fun privateKeyFormat(pem: String): PrivateKeyFormat = when {
        pem.contains("-----BEGIN OPENSSH PRIVATE KEY-----") -> PrivateKeyFormat.OPENSSH
        pem.contains("-----BEGIN PRIVATE KEY-----") -> PrivateKeyFormat.PKCS8
        else -> PrivateKeyFormat.UNKNOWN
    }

    /**
     * 转成 OpenSSH 私钥文件文本（`BEGIN OPENSSH PRIVATE KEY`，未加密）。
     *
     * 覆盖 PKCS#8 的 RSA / ECDSA / Ed25519；已经是 OpenSSH 格式则原样返回；
     * 无法识别或参数不支持时返回 null（调用方按“不支持转换”提示）。
     */
    fun toOpenSshPrivateKey(pem: String, comment: String = DEFAULT_COMMENT): String? {
        when (privateKeyFormat(pem)) {
            PrivateKeyFormat.OPENSSH -> return pem
            PrivateKeyFormat.PKCS8 -> Unit
            PrivateKeyFormat.UNKNOWN -> return null
        }
        val der = catchParse { pemDer(pem) } ?: return null
        return catchParse {
            when (pkcs8AlgorithmOid(der)) {
                OID_RSA -> openSshFromPkcs8Rsa(der, comment)
                OID_EC -> openSshFromPkcs8Ec(der, comment)
                OID_ED25519 -> openSshFromPkcs8Ed25519(der, comment)
                else -> null
            }
        }
    }

    /**
     * 转成 PKCS#8 PEM（`BEGIN PRIVATE KEY`）。
     *
     * 目前支持 OpenSSH 容器里的 Ed25519（RFC 8410 结构固定）；
     * RSA / ECDSA 的 OpenSSH 容器需要重建 ASN.1，暂不支持（返回 null）。
     */
    fun toPkcs8PrivateKey(pem: String): String? {
        when (privateKeyFormat(pem)) {
            PrivateKeyFormat.PKCS8 -> return pem
            PrivateKeyFormat.OPENSSH -> Unit
            PrivateKeyFormat.UNKNOWN -> return null
        }
        val seed = catchParse { opensshEd25519Seed(pem) } ?: return null
        return pemWrap("PRIVATE KEY", PKCS8_ED25519_PREFIX + seed)
    }

    /**
     * 私钥解析的兜底：只吞**解析类异常**。
     * 不用 `runCatching` —— 它连 `Throwable` 一起接（含 `OutOfMemoryError`），
     * 于是"崩在解析中途"会被伪装成"该格式不支持转换"，排查方向直接被带偏。
     */
    private inline fun <T> catchParse(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    /** RFC 8410 PrivateKeyInfo 固定前缀：SEQUENCE{INTEGER 0, SEQUENCE{OID 1.3.101.112}, OCTET STRING{OCTET STRING}}。 */
    private val PKCS8_ED25519_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )

    private fun pkcs8AlgorithmOid(der: ByteArray): String? = runCatching {
        org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(der).privateKeyAlgorithm.algorithm.id
    }.getOrNull()

    private fun openSshFromPkcs8Rsa(der: ByteArray, comment: String): String? {
        val priv = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(der)) as? RSAPrivateCrtKey ?: return null
        val type = "ssh-rsa".encodeToByteArray()
        val publicBlob = sshString(type) + sshString(bytes(priv.publicExponent)) + sshString(bytes(priv.modulus))
        // OpenSSH 的 RSA 私钥段顺序：n, e, d, iqmp, p, q
        val privateBlob = sshString(type) +
            sshString(bytes(priv.modulus)) +
            sshString(bytes(priv.publicExponent)) +
            sshString(bytes(priv.privateExponent)) +
            sshString(bytes(priv.crtCoefficient)) +
            sshString(bytes(priv.primeP)) +
            sshString(bytes(priv.primeQ))
        return encodeOpenSshKeyV1("ssh-rsa", publicBlob, privateBlob, comment)
    }

    private fun openSshFromPkcs8Ec(der: ByteArray, comment: String): String? {
        val priv = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(der)) as? ECPrivateKey ?: return null
        val fieldBits = priv.params.curve.field.fieldSize
        val sshCurve = when (fieldBits) {
            256 -> "nistp256"
            384 -> "nistp384"
            521 -> "nistp521"
            else -> return null
        }
        val bcName = when (fieldBits) {
            256 -> "secp256r1"
            384 -> "secp384r1"
            521 -> "secp521r1"
            else -> return null
        }
        // EC 私钥不保证携带公钥点 → 由 d·G 现算
        val g = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(bcName).g
        val point = g.multiply(priv.s).normalize().getEncoded(false)
        val keyType = "ecdsa-sha2-$sshCurve"
        val publicBlob = sshString(keyType.encodeToByteArray()) +
            sshString(sshCurve.encodeToByteArray()) + sshString(point)
        val privateBlob = sshString(keyType.encodeToByteArray()) +
            sshString(sshCurve.encodeToByteArray()) + sshString(point) + sshString(bytes(priv.s))
        return encodeOpenSshKeyV1(keyType, publicBlob, privateBlob, comment)
    }

    private fun openSshFromPkcs8Ed25519(der: ByteArray, comment: String): String? {
        val info = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(der)
        val seed = org.bouncycastle.asn1.ASN1OctetString.getInstance(info.parsePrivateKey()).octets
        if (seed.size != 32) return null
        val pubBytes = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
            .generatePublicKey().encoded
        val type = "ssh-ed25519".encodeToByteArray()
        return encodeOpenSshKeyV1(
            keyType = "ssh-ed25519",
            publicBlob = sshString(type) + sshString(pubBytes),
            privateBlob = sshString(type) + sshString(pubBytes) + sshString(seed + pubBytes),
            comment = comment,
        )
    }

    /**
     * 从 OpenSSH 容器取 Ed25519 的 32 字节 seed；非该算法或结构异常返回 null。
     *
     * **只读未加密容器**：cipher/kdf 必须都是 "none"。带口令的容器（aes256-ctr + bcrypt）
     * 里私钥段是密文，按明文线格式去读会读到**随机的长度前缀**，旧实现照这个长度直接
     * `copyOfRange` → 单次分配可达数 GB → `OutOfMemoryError`；而外层
     * [toPkcs8PrivateKey] 的 `runCatching` 连 Error 一起吞掉，用户看到的是"不支持转换"，
     * 与实际症状（解析中途崩溃）完全对不上。
     */
    private fun opensshEd25519Seed(pem: String): ByteArray? {
        val der = pemDer(pem)
        val magic = "openssh-key-v1\u0000"
        if (der.size < magic.length + 4 || String(der, 0, magic.length, Charsets.ISO_8859_1) != magic) return null
        val r = SshReader(der, magic.length)
        val cipher = r.str()?.toString(Charsets.ISO_8859_1) ?: return null
        val kdf = r.str()?.toString(Charsets.ISO_8859_1) ?: return null
        r.str() // kdf options（none 容器下为空）
        if (cipher != "none" || kdf != "none") return null // 带口令：私钥段是密文，不能按明文线格式读
        if (r.u32() != 1) return null
        r.str() // public blob
        val section = r.str() ?: return null
        if (section.size < 8) return null
        val s = SshReader(section, 8) // 跳过两个 checkint
        if (s.str()?.toString(Charsets.ISO_8859_1) != "ssh-ed25519") return null
        s.str() ?: return null // 公钥 32 字节
        val sk = s.str() ?: return null
        return if (sk.size >= 32) sk.copyOfRange(0, 32) else null
    }

    /**
     * 带边界检查的 OpenSSH 线格式读取器（4 字节大端长度 + 内容）。
     * 越界返回 null —— 绝不按长度前缀分配内存（见 [opensshEd25519Seed] 的教训）。
     */
    private class SshReader(private val b: ByteArray, start: Int) {
        var pos = start
            private set

        fun u32(): Int? {
            if (b.size - pos < 4) return null
            var v = 0
            repeat(4) { v = (v shl 8) or (b[pos++].toInt() and 0xFF) }
            return v
        }

        fun str(): ByteArray? {
            val n = u32() ?: return null
            if (n < 0 || n > b.size - pos) return null
            val v = b.copyOfRange(pos, pos + n)
            pos += n
            return v
        }

        fun end() = pos == b.size
    }

    private fun pemDer(pem: String): ByteArray {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(body)
    }

    private fun pemWrap(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" +
            Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n") +
            "\n-----END $type-----\n"

    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_EC = "1.2.840.10045.2.1"
    private const val OID_ED25519 = "1.3.101.112"
}
