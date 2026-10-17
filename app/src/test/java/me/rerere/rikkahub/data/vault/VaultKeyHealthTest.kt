package me.rerere.rikkahub.data.vault

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证库 SSH 密钥体检器（[VaultKeyHealth]）的契约。
 *
 * 全部向量**现场生成**或**由生成结果改造**，不含任何真实环境的密钥。
 * 纯 JVM：只调编码/解析，不碰 Android 组件。
 *
 * 重点是最后两类判据：
 * - 「`n` 缺前导符号字节」既是结构问题、又**必须**被判成"还是同一把公钥"——
 *   历史上这里按字节比对，把同一把公钥误判成异己公钥。
 * - 「base64 多一个 `=`」只判形态（WARN），不一律判错。
 */
class VaultKeyHealthTest {

    private val rsa = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA)
    private val ed25519 = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
    private val ecdsa = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ECDSA)

    @Test
    fun `healthy key pairs report nothing`() {
        listOf(rsa, ed25519, ecdsa).forEach { pair ->
            assertEquals(setOf<VaultKeyHealth.Code>(), codes(pair))
        }
    }

    @Test
    fun `blank fields are skipped rather than reported`() {
        assertEquals(emptySet<VaultKeyHealth.Code>(), codesOf(VaultKeyHealth.check("", null)))
        assertEquals(emptySet<VaultKeyHealth.Code>(), codesOf(VaultKeyHealth.check("   ", "   ")))
        // 只有公钥、没有私钥：照样做形态/结构检查，但不报私钥问题
        assertEquals(emptySet<VaultKeyHealth.Code>(), codesOf(VaultKeyHealth.check(rsa.publicKeyLine, null)))
    }

    @Test
    fun `trailing padding on the public base64 is a format warning only`() {
        val damaged = rsa.publicKeyLine + "="
        val issues = VaultKeyHealth.check(damaged, rsa.privateKeyPem)
        assertEquals(setOf(VaultKeyHealth.Code.PUBLIC_FORMAT), codesOf(issues))
        assertEquals(VaultKeyHealth.Severity.WARN, issues.single().severity)
    }

    @Test
    fun `undecodable base64 is a format error`() {
        // 长度 %4==1 且不含填充：base64 里不存在的形状，无法成组解码，只能判坏
        val parts = rsa.publicKeyLine.trim().split(Regex("\\s+"))
        val broken = parts[1].dropLast(3)
        assertEquals(1, broken.length % 4)
        val issues = VaultKeyHealth.check("${parts[0]} $broken", rsa.privateKeyPem)
        assertTrue(
            "应报 PUBLIC_FORMAT（ERROR），实际 ${codesOf(issues)}",
            issues.any {
                it.code == VaultKeyHealth.Code.PUBLIC_FORMAT && it.severity == VaultKeyHealth.Severity.ERROR
            },
        )
    }

    @Test
    fun `modulus missing the sign byte is a structure issue but still the same key`() {
        val fields = fields(rsa)
        assertEquals("ssh-rsa", fields[0].decodeToString())
        assertTrue("测试向量的 n 本应带前导符号字节", fields[2][0].toInt() == 0)
        // 剥掉前导 0x00 → mpint 被解释成负数：OpenSSH 拒读（ssh-keygen 报 is not a public key file）
        val damaged = line(fields[0], fields[1], fields[2].copyOfRange(1, fields[2].size))
        val issues = VaultKeyHealth.check(damaged, rsa.privateKeyPem)

        assertTrue(
            "应报 PUBLIC_STRUCTURE，实际 ${codesOf(issues)}：${issues.map { it.detail }}",
            issues.any { it.code == VaultKeyHealth.Code.PUBLIC_STRUCTURE },
        )
        // ★ 核心不变量：只差一个前导符号字节，**不能**判成"另一把公钥"
        assertTrue(
            "缺符号字节是结构问题，不得判成 KEY_MISMATCH",
            issues.none { it.code == VaultKeyHealth.Code.KEY_MISMATCH },
        )
    }

    @Test
    fun `modulus with a redundant leading zero is accepted and matches`() {
        // 反向宽松：OpenSSH 容忍非最简 mpint（补前导 0x00 后 ssh-keygen 输出同一指纹）。
        // 据此拒绝就是误拒真实可用的数据，更不得判成不匹配。
        val fields = fields(rsa)
        val padded = line(fields[0], fields[1], cat(byteArrayOf(0), fields[2]))
        assertEquals(setOf<VaultKeyHealth.Code>(), codes(rsa.privateKeyPem, padded))
    }

    @Test
    fun `private key from a different pair is reported as mismatch`() {
        val other = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA)
        val issues = VaultKeyHealth.check(other.publicKeyLine, rsa.privateKeyPem)
        assertEquals(setOf(VaultKeyHealth.Code.KEY_MISMATCH), codesOf(issues))
    }

    @Test
    fun `cross-algorithm public key is reported as mismatch`() {
        // 公钥字段是 ed25519、私钥是 RSA：两侧都解析得动，只能是配对错了
        val issues = VaultKeyHealth.check(ed25519.publicKeyLine, rsa.privateKeyPem)
        assertEquals(setOf(VaultKeyHealth.Code.KEY_MISMATCH), codesOf(issues))
    }

    @Test
    fun `unparseable private key is reported`() {
        val junk = "-----BEGIN " + "PRIVATE KEY" + "-----"\n!!!!not base64!!!!\n-----END PRIVATE KEY-----\n"
        assertEquals(
            setOf(VaultKeyHealth.Code.PRIVATE_UNPARSEABLE),
            codesOf(VaultKeyHealth.check(rsa.publicKeyLine, junk)),
        )
        // 容器头不认识（PKCS#1 的 BEGIN RSA PRIVATE KEY 不在转换支持范围内）
        val pkcs1 = "-----BEGIN " + "RSA PRIVATE KEY" + "-----"\nMIIBOgIBAAJ\n-----END RSA PRIVATE KEY-----\n"
        assertEquals(SshKeyGenerator.PrivateKeyFormat.UNKNOWN, SshKeyGenerator.privateKeyFormat(pkcs1))
        assertEquals(
            setOf(VaultKeyHealth.Code.PRIVATE_UNPARSEABLE),
            codesOf(VaultKeyHealth.check(rsa.publicKeyLine, pkcs1)),
        )
    }

    @Test
    fun `private-only entry reports nothing`() {
        // 私钥能解析、公钥字段为空：没有可比对象 → 不出结论
        assertEquals(setOf<VaultKeyHealth.Code>(), codes(rsa.privateKeyPem, ""))
    }

    // ================= helpers =================

    private fun codes(pair: SshKeyGenerator.SshKeyPair): Set<VaultKeyHealth.Code> =
        codes(pair.privateKeyPem, pair.publicKeyLine)

    private fun codes(privatePem: String, publicKeyLine: String): Set<VaultKeyHealth.Code> =
        codesOf(VaultKeyHealth.check(publicKeyLine, privatePem))

    private fun codesOf(issues: List<VaultKeyHealth.Issue>): Set<VaultKeyHealth.Code> =
        issues.map { it.code }.toSet()

    /** 公钥行的键体字段（类型串 + 各字段），按 RFC 4251 的 string 线格式读。 */
    private fun fields(pair: SshKeyGenerator.SshKeyPair): List<ByteArray> {
        val blob = Base64.getDecoder().decode(pair.publicKeyLine.trim().split(Regex("\\s+"))[1])
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < blob.size) {
            val n = ((blob[pos].toInt() and 0xFF) shl 24) or ((blob[pos + 1].toInt() and 0xFF) shl 16) or
                ((blob[pos + 2].toInt() and 0xFF) shl 8) or (blob[pos + 3].toInt() and 0xFF)
            pos += 4
            out.add(blob.copyOfRange(pos, pos + n))
            pos += n
        }
        return out
    }

    /** 用给定字段重新拼一条公钥行（长度前缀跟着内容走，保证只改内容不改结构自洽性）。 */
    private fun line(type: ByteArray, vararg keyFields: ByteArray): String {
        val body = ArrayList<ByteArray>()
        body.add(str(type))
        keyFields.forEach { body.add(str(it)) }
        return "${type.decodeToString()} " +
            Base64.getEncoder().encodeToString(cat(*body.toTypedArray()))
    }

    private fun str(b: ByteArray): ByteArray =
        cat(byteArrayOf(
            ((b.size shr 24) and 0xFF).toByte(),
            ((b.size shr 16) and 0xFF).toByte(),
            ((b.size shr 8) and 0xFF).toByte(),
            (b.size and 0xFF).toByte(),
        ), b)

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        parts.forEach {
            System.arraycopy(it, 0, out, pos, it.size)
            pos += it.size
        }
        return out
    }
}
