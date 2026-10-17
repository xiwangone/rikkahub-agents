package me.rerere.rikkahub.data.vault

import java.math.BigInteger
import java.util.Base64

/**
 * 凭证库里 **SSH 密钥条目**的体检器：一条条目进、一组问题出。
 *
 * 输入 = 条目的公钥字段 + 私钥值（可空）；输出 = [Issue] 列表（空 = 没查出问题）。
 * 纯函数：不读写存储、不联网、不依赖 Android 框架类，JVM 单测可直接调。
 *
 * 为什么要它：入库侧的清洗是**尽力而为**——`CredentialVaultRepository.normalizePublicKey`
 * 在公钥结构非法时照样存原样，把报错推迟到使用点。于是存量库里可能躺着解析不出来的
 * 公钥行、缺符号字节的 RSA 模数、或与私钥不成对的公钥。本器在**读取时**一次报清楚。
 *
 * 判据口径：**以「能否解析」为准**。填充（padding）、长度等只作**解释性细节**——
 * 部分算法的公钥带 base64 填充仍可能被解析器接受，所以「末尾多一个 `=`」
 * 判 [Severity.WARN]（可规范化修复），**不一律判错**。
 */
object VaultKeyHealth {

    /** 问题类别。 */
    enum class Code {
        /** 私钥值解析不出密钥（容器头不认识、DER/base64 坏、参数不支持）。 */
        PRIVATE_UNPARSEABLE,

        /** 公钥 base64 的**形态**异常：多余填充（[Severity.WARN]）/ 无法成组解码（[Severity.ERROR]）。 */
        PUBLIC_FORMAT,

        /** 公钥**结构**非法：线格式长度前缀越界、mpint 缺前导符号字节、键体字段数不符、尾部残留字节。 */
        PUBLIC_STRUCTURE,

        /** 私钥推导出的公钥与条目存的公钥不是同一把。 */
        KEY_MISMATCH,
    }

    /** WARN = 数据可用但写法非标准；ERROR = 按现状不可用。 */
    enum class Severity { WARN, ERROR }

    data class Issue(val code: Code, val severity: Severity, val detail: String)

    /**
     * 体检一条凭证。空白字段跳过对应检查（公钥私钥可以只有一条）。
     */
    fun check(publicKeyLine: String, privateKeyPem: String?): List<Issue> {
        val out = ArrayList<Issue>()
        val pub = publicKeyLine.trim()
        if (pub.isNotEmpty()) out += inspectPublic(pub)

        val priv = privateKeyPem?.trim().orEmpty()
        if (priv.isEmpty()) return out

        val derived = derivePublic(priv)
        if (derived == null) {
            out += Issue(
                Code.PRIVATE_UNPARSEABLE,
                Severity.ERROR,
                "私钥无法解析（容器头：${SshKeyGenerator.privateKeyFormat(priv).label}）",
            )
            return out
        }
        // 公钥字段空白（没有可比对象），或分段都不够两段（ PUBLIC_FORMAT 已在上面报过）→ 不下配对结论
        val parts = pub.split(Regex("\\s+"))
        if (parts.size < 2) return out

        val (derivedType, derivedBlob) = derived
        val storedType = parts[0]
        val storedBlob = SshKeyGenerator.decodePublicKeyBlob(parts[1])
        // 任一侧读不出字段就不下「不匹配」的结论——那是 PUBLIC_FORMAT / PUBLIC_STRUCTURE
        // 已经报过的问题，重复报只会把归因带偏（把坏数据说成"别人的公钥"）。
        val storedIdentity = storedBlob?.let { identity(storedType, it) }
        val derivedIdentity = identity(derivedType, derivedBlob)
        if (storedIdentity != null && derivedIdentity != null && storedIdentity != derivedIdentity) {
            out += Issue(
                Code.KEY_MISMATCH,
                Severity.ERROR,
                "私钥推导出的公钥（${derivedType} ${fingerprintOf(derivedType, derivedBlob)}）" +
                    "与条目存的公钥（${storedType} ${fingerprintOf(storedType, storedBlob!!)}）不是同一把",
            )
        }
        return out
    }

    // ================= 公钥侧 =================

    /** 公钥字段的形态 + 结构问题；完全合法返回空列表。 */
    private fun inspectPublic(publicKeyLine: String): List<Issue> {
        val out = ArrayList<Issue>()
        val parts = publicKeyLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            out += Issue(
                Code.PUBLIC_FORMAT,
                Severity.ERROR,
                "不是公钥行：期望「<类型> <base64> [注释]」，实际 ${parts.size} 段",
            )
            return out
        }
        val b64 = parts[1]
        val blob = SshKeyGenerator.decodePublicKeyBlob(b64)
        if (blob == null) {
            val pad = b64.length - b64.trimEnd('=').length
            // 长度 %4==1 是 base64 里**不存在**的形状：必然是少字符或被插了字符，连补正都无从下手。
            val reason = if (pad == 0 && b64.length % 4 == 1) {
                "base64 长度 ${b64.length}（%4=1）无法成组解码：内容被截断或插入了字符"
            } else {
                "base64 段无法按标准解码（长度 ${b64.length}）：非规范编码或内容被截断"
            }
            out += Issue(Code.PUBLIC_FORMAT, Severity.ERROR, reason)
            return out
        }
        // 仅当填充「多余」时才判形态问题：解码结果重新标准编码后与原文一致，说明这就是
        // 规范 base64 —— 键体长度不是 3 的倍数时，标准编码本就带 `=`（如 ECDSA nistp256 的
        // 104 字节键体带 1 个 `=`），那是合法写法，不该报；只有比规范形式多出的 `=`（或非规范
        // 末组编码）才是可规范化修复的形态问题。
        if (b64 != Base64.getEncoder().encodeToString(blob)) {
            out += Issue(
                Code.PUBLIC_FORMAT,
                Severity.WARN,
                "base64 写法非标准（多余的填充符 `=` 或非规范末组）：公钥行的标准写法不带多余填充，" +
                    "可规范化修复（仍可解析成功，故不判错）",
            )
        }
        SshKeyGenerator.blobIssue(parts[0], blob)?.let {
            out += Issue(Code.PUBLIC_STRUCTURE, Severity.ERROR, it)
        }
        return out
    }

    // ================= 私钥侧 → 公钥推导 =================

    /**
     * 私钥 → 其公钥（类型 + public blob）；解析不出返回 null。
     *
     * 复用而非重写：PKCS#8 走 [SshKeyGenerator.toOpenSshPrivateKey]（内部按 OID 分派，
     * RSA 取 `modulus`/`publicExponent`、ECDSA 由 `d·G` 现算点、Ed25519 由 seed 导出公钥），
     * 再读回容器头声明的 public blob。已是 OpenSSH 容器的直接读容器头。
     *
     * **不比对密钥内容以外的东西**：带口令的 OpenSSH 容器只读明文公钥段，
     * 口令对错不影响本判定（也不在此报错）。
     */
    private fun derivePublic(privatePem: String): Pair<String, ByteArray>? = try {
        val container = when (SshKeyGenerator.privateKeyFormat(privatePem)) {
            SshKeyGenerator.PrivateKeyFormat.OPENSSH -> privatePem
            SshKeyGenerator.PrivateKeyFormat.PKCS8 -> SshKeyGenerator.toOpenSshPrivateKey(privatePem)
            SshKeyGenerator.PrivateKeyFormat.UNKNOWN -> null
        }
        container?.let { SshKeyGenerator.opensshPublicBlob(it) }?.let { blob ->
            val type = SshKeyGenerator.SshReader(blob, 0).str()?.let { String(it, Charsets.ISO_8859_1) }
            type?.let { it to blob }
        }
    } catch (_: Exception) {
        // 只吞解析类异常，口径同 SshKeyGenerator.catchParse：不用 runCatching——
        // 它连 Error 一起接，会把"崩在解析中途"伪装成"该格式不支持"。
        null
    }

    // ================= 比对：按解析后的值，不按字节 =================

    /**
     * 公钥的**可比身份**：由解析后的字段值构造。
     *
     * RSA/DSA 的键体是 mpint（RFC 4251 §6 有符号最小编码）→ 这里按**无符号幅度**取值
     * （`BigInteger(1, bytes)`），于是「带前导符号字节 `0x00`」与「缺它」的两种写法得到
     * **同一个身份**。
     *
     * ⚠ 这是本器唯一允许的宽松处，也是必须宽松的地方：按字节比会把历史上那类
     * 「`n` 缺前导 `0x00`」的公钥判成**异己公钥**（`KEY_MISMATCH`），而它其实是同一把——
     * 结构问题该由 [Code.PUBLIC_STRUCTURE] 报，身份问题才由 [Code.KEY_MISMATCH] 报。
     *
     * 非 mpint 字段（Ed25519 的 32 字节公钥、ECDSA 的曲线名与点）是定长 ssh-string，
     * 不存在"同一值多种编码"，按原字节比。
     */
    private fun identity(type: String, blob: ByteArray): String? {
        val r = SshKeyGenerator.SshReader(blob, 0)
        val embedded = r.str()?.let { String(it, Charsets.ISO_8859_1) } ?: return null
        if (embedded != type) return null
        val signed = type == "ssh-rsa" || type == "ssh-dss" ||
            type == "ssh-rsa-sha2-256" || type == "ssh-rsa-sha2-512"
        val sb = StringBuilder(type)
        while (!r.end()) {
            val field = r.str() ?: return null
            sb.append(':').append(if (signed) BigInteger(1, field).toString(16) else b64(field))
        }
        return sb.toString()
    }

    private fun fingerprintOf(type: String, blob: ByteArray): String =
        SshKeyGenerator.fingerprint("$type ${b64(blob)}") ?: "(无法计算指纹)"

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
