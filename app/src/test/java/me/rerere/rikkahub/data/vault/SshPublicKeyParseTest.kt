package me.rerere.rikkahub.data.vault

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公钥行**解析与指纹**的契约（`fingerprint` / `publicKeyLineIssue` / `canonicalizePublicKeyLine`）。
 *
 * 为什么单独成文：这三者决定"库里那一行公钥能不能用"。上一批只测了**生成**侧
 * （见 [SshKeyGeneratorTest]），读取侧一条用例都没有 —— 于是"存储里多一个 `=`"
 * 这类坏数据一路无人报错，直到界面上显示"(解析失败)"才被发现，而那时已经说不清
 * 是 App 读不动还是数据本身坏了。
 *
 * 对照基准是真实 `ssh-keygen -lf`（合法行指纹逐一相等、
 * 非法行 ssh-keygen 同样拒绝）。测试用纯 JVM 复现同一判据，不依赖设备。
 */
class SshPublicKeyParseTest {

    private fun sshString(b: ByteArray): ByteArray =
        byteArrayOf(
            (b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte(),
        ) + b

    private fun b64u(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    // ================= 多余填充：坏数据，但只坏在填充上 =================

    /**
     * 279 字节 RSA blob → base64 长度 372（%4==0，**本就不该有填充**）。
     * 多打一个 `=` 就变成 373：%4==1，任何标准解码器都无法切分，Java 直接抛
     * `IllegalArgumentException: Input byte array has wrong 4-byte ending unit`，
     * `ssh-keygen -lf` 也报 "is not a public key file"。
     */
    @Test
    fun `spurious trailing padding is repaired and matches the canonical fingerprint`() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA, "c@rsa")
        val canonical = SshKeyGenerator.canonicalizePublicKeyLine(pair.publicKeyLine)!!
        val expected = SshKeyGenerator.fingerprint(canonical)!!
        assertEquals(372, canonical.split(" ")[1].length)

        val damaged = pair.publicKeyLine.split(" ")[0] + " " +
            canonical.split(" ")[1] + "= " + pair.publicKeyLine.split(" ").drop(2).joinToString(" ")
        assertEquals(373, damaged.split(" ")[1].length)
        // 修复后可用，且指纹与规范行**完全一致**（不是"随便给个数"）
        assertEquals(expected, SshKeyGenerator.fingerprint(damaged))
        assertNull(SshKeyGenerator.publicKeyLineIssue(damaged))
        assertEquals(canonical, SshKeyGenerator.canonicalizePublicKeyLine(damaged))
    }

    /** 真实需要的填充不能被当成"多余"删掉：ECDSA-521 的 blob 长度 %4 != 0。 */
    @Test
    fun `legitimate base64 padding is left alone`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ECDSA521, "c@ec").publicKeyLine
        val blob = Base64.getDecoder().decode(line.split(" ")[1])
        assertTrue(blob.size % 3 != 0)
        assertTrue(line.split(" ")[1].endsWith("="))
        assertNull(SshKeyGenerator.publicKeyLineIssue(line))
        assertEquals(line, SshKeyGenerator.canonicalizePublicKeyLine(line))
    }

    // ================= 其它坏数据：明确拒绝，并说清原因 =================

    @Test
    fun `truncated base64 is rejected with a reason instead of a bare null`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "c@ed").publicKeyLine
        val b64 = line.split(" ")[1]
        val truncated = line.substringBefore(b64) + b64.dropLast(8)
        assertNotNull(SshKeyGenerator.publicKeyLineIssue(truncated))
        assertNull(SshKeyGenerator.fingerprint(truncated))
        assertNull(SshKeyGenerator.canonicalizePublicKeyLine(truncated))
    }

    /** 补一个非 `=` 的字符：长度 %4==1 但尾部不是填充 —— 不是"填充多余"，不能修。 */
    @Test
    fun `inserted character is not silently repaired`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "c@ed").publicKeyLine
        val b64 = line.split(" ")[1]
        val damaged = line.substringBefore(b64) + b64 + "Q"
        assertNull(SshKeyGenerator.fingerprint(damaged))
        assertTrue(SshKeyGenerator.publicKeyLineIssue(damaged)!!.contains("无法解码"))
    }

    /** 声明类型与 blob 内嵌类型不一致（改错头、或粘串了行）。 */
    @Test
    fun `declared type mismatching the embedded type is rejected`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "c@ed").publicKeyLine
        val swapped = "ssh-rsa " + line.split(" ").drop(1).joinToString(" ")
        assertNull(SshKeyGenerator.fingerprint(swapped))
        assertTrue(SshKeyGenerator.publicKeyLineIssue(swapped)!!.contains("不一致"))
    }

    /** 长度前缀越界：结构校验的意义就在这 —— 别把"看着像 base64"当"能用"。 */
    @Test
    fun `out-of-range length prefix is rejected without allocating`() {
        // 类型串正常，后面跟一个 0x7FFFFFFF 的长度前缀（旧读取器会照它分配 → OOM）
        val blob = sshString("ssh-ed25519".toByteArray()) +
            byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val line = "ssh-ed25519 ${b64u(blob)} c@evil"
        assertNull(SshKeyGenerator.fingerprint(line))
        assertTrue(SshKeyGenerator.publicKeyLineIssue(line)!!.contains("越界"))
    }

    @Test
    fun `field counts are enforced per key type`() {
        val ed = sshString("ssh-ed25519".toByteArray()) + sshString(ByteArray(32)) + sshString(ByteArray(32))
        assertTrue(SshKeyGenerator.publicKeyLineIssue("ssh-ed25519 ${b64u(ed)}")!!.contains("键体字段"))
        val shortEd = sshString("ssh-ed25519".toByteArray()) + sshString(ByteArray(31))
        assertTrue(SshKeyGenerator.publicKeyLineIssue("ssh-ed25519 ${b64u(shortEd)}")!!.contains("32"))
    }

    /** 不认识的类型**不因**字段数未知而误拒（宁可放过，不能把新算法判成坏数据）。 */
    @Test
    fun `unknown key types are not falsely rejected`() {
        val blob = sshString("sk-ssh-ed25519@openssh.com".toByteArray()) +
            sshString(ByteArray(32)) + sshString(ByteArray(32)) + sshString(ByteArray(4))
        assertNull(SshKeyGenerator.publicKeyLineIssue("sk-ssh-ed25519@openssh.com ${b64u(blob)} c"))
    }

    /**
     * mpint 的**符号字节**丢了：长度前缀仍自洽、字段个数仍对得上，光靠结构校验发现不了，
     * 但 OpenSSH 一定拒读（报 `is not a public key file`）。
     * 这一条正是上一批"mpint 保留符号字节"修复的**反向护栏**：编码器若哪天又剥掉 0x00，
     * 这里会红，而不是安静地给出一个和服务器对不上的指纹。
     */
    @Test
    fun `mpint losing its sign byte is rejected`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA, "c@rsa").publicKeyLine
        // 手工切字段：type, e, n（都是 mpint：首字节 0x00 表示正数）
        val fields = fieldsOf(Base64.getDecoder().decode(line.split(" ")[1]))
        assertEquals(3, fields.size)
        assertTrue("用例前提：真实 RSA 模数带前导 0x00", fields[2][0] == 0.toByte() && (fields[2][1].toInt() and 0x80) != 0)

        val stripped = sshString(fields[0]) + sshString(fields[1]) + sshString(fields[2].copyOfRange(1, fields[2].size))
        val damaged = "ssh-rsa ${b64u(stripped)} c@rsa"
        assertNotNull(SshKeyGenerator.publicKeyLineIssue(damaged))
        assertTrue(SshKeyGenerator.publicKeyLineIssue(damaged)!!.contains("负数"))
        assertNull(SshKeyGenerator.fingerprint(damaged))
    }

    /**
     * **反方向**不能一起拒：OpenSSH 对"多一个前导 `0x00`"的非最简 mpint 是容忍的，
     * 并且**照样输出与规范形态相同的指纹**（给真实 RSA 的 `e` 补 1 个 / 2 个 `0x00`、
     * 给 `n` 补 1 个，`ssh-keygen -lf` 四次同一个 `SHA256:l7xV3DJeXXRlxU6LDccg+Q4pflid7M9Q4Ut60J4+FdA`，
     * 所以这里的要求是：接受、指纹不变、写回时重编成最简形态。
     * 按原始字节直接取摘要会得到一个和服务器对不上的指纹 —— 那是本批要消灭的那类错误。
     */
    @Test
    fun `non-minimal mpint is accepted and does not shift the fingerprint`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA, "c@rsa").publicKeyLine
        val canonical = SshKeyGenerator.canonicalizePublicKeyLine(line)!!
        val expected = SshKeyGenerator.fingerprint(canonical)!!
        val fields = fieldsOf(Base64.getDecoder().decode(canonical.split(" ")[1]))
        assertEquals(3, fields.size)

        listOf(
            "e 补一个 0x00" to listOf(fields[0], byteArrayOf(0) + fields[1], fields[2]),
            "e 补两个 0x00" to listOf(fields[0], byteArrayOf(0, 0) + fields[1], fields[2]),
            "n 补一个 0x00" to listOf(fields[0], fields[1], byteArrayOf(0) + fields[2]),
        ).forEach { (tag, fs) ->
            val blob = fs.fold(ByteArray(0)) { acc, f -> acc + sshString(f) }
            val damaged = "ssh-rsa ${b64u(blob)} c@rsa"
            assertNull("$tag 不该被误拒: ${SshKeyGenerator.publicKeyLineIssue(damaged)}", SshKeyGenerator.publicKeyLineIssue(damaged))
            assertEquals("$tag 的指纹应与规范形态一致", expected, SshKeyGenerator.fingerprint(damaged))
            assertEquals("$tag 写回时应重编成最简形态", canonical, SshKeyGenerator.canonicalizePublicKeyLine(damaged))
        }
    }

    // ================= 注释段 =================

    @Test
    fun `comment is preserved and blank runs do not break parsing`() {
        val line = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA, "user@host desk").publicKeyLine
        val padded = line.split(" ")[0] + "  " + line.split(" ")[1] + "\tuser@host   desk"
        assertEquals(SshKeyGenerator.fingerprint(line), SshKeyGenerator.fingerprint(padded))
        assertEquals(line, SshKeyGenerator.canonicalizePublicKeyLine(padded))
    }

    @Test
    fun `non public-key lines are rejected`() {
        // 私钥头按片段拼接：公开仓里不出现完整的私钥 PEM 头字样
        val privateHeader = "-----BEGIN " + "PRIVATE KEY" + "-----"
        listOf("", "   ", "ssh-rsa", privateHeader).forEach {
            assertNotNull("应判为非法: [$it]", SshKeyGenerator.publicKeyLineIssue(it))
        }
    }

    // ================= 证书：指纹算内层公钥 =================

    /**
     * OpenSSH 对证书行报的是**内层公钥**的指纹（`ssh-keygen -lf` 于
     * ed25519/rsa/ecdsa 三种证书），不是整张证书的 SHA-256。
     * 直接用整段 blob 取摘要会得到一个和服务器/其他工具都对不上的指纹。
     */
    @Test
    fun `certificate fingerprint is the inner public key fingerprint`() {
        listOf(
            SshKeyGenerator.KeyType.ED25519,
            SshKeyGenerator.KeyType.RSA,
            SshKeyGenerator.KeyType.ECDSA,
        ).forEach { type ->
            val plain = SshKeyGenerator.generate(type, "c@x").publicKeyLine
            val keyType = plain.split(" ")[0]
            val fields = fieldsOf(Base64.getDecoder().decode(plain.split(" ")[1]))
                .drop(1) // 去掉内层自己的类型串：证书里键体字段直接跟在 nonce 之后
            val certBlob = b64u(
                sshString("$keyType-cert-v01@openssh.com".toByteArray()) +
                    sshString(ByteArray(32)) + // nonce
                    fields.fold(ByteArray(0)) { acc, f -> acc + sshString(f) },
            )
            val certLine = "$keyType-cert-v01@openssh.com $certBlob c@x"
            val issue = SshKeyGenerator.publicKeyLineIssue(certLine)
            assertNull("证书应可解析: $keyType，实际: $issue", issue)
            assertEquals(
                "证书指纹必须等于内层公钥指纹: $keyType",
                SshKeyGenerator.fingerprint(plain),
                SshKeyGenerator.fingerprint(certLine),
            )
        }
    }

    /** 拆出 blob 里的 string 字段（测试专用，遇到非法长度即停）。 */
    private fun fieldsOf(blob: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pos = 0
        while (pos + 4 <= blob.size) {
            val n = ((blob[pos].toInt() and 0xFF) shl 24) or ((blob[pos + 1].toInt() and 0xFF) shl 16) or
                ((blob[pos + 2].toInt() and 0xFF) shl 8) or (blob[pos + 3].toInt() and 0xFF)
            pos += 4
            if (n < 0 || n > blob.size - pos) break
            out.add(blob.copyOfRange(pos, pos + n))
            pos += n
        }
        return out
    }

    // ================= 生成的密钥必须自洽（防止生成侧与读取侧脱节） =================

    @Test
    fun `every generated key type round-trips through the parser`() {
        SshKeyGenerator.KeyType.entries.forEach { type ->
            val line = SshKeyGenerator.generate(type, "c@$type").publicKeyLine
            assertNull("$type 生成的公钥行应可解析：${SshKeyGenerator.publicKeyLineIssue(line)}",
                SshKeyGenerator.publicKeyLineIssue(line))
            assertNotNull(SshKeyGenerator.fingerprint(line))
            assertEquals(line, SshKeyGenerator.canonicalizePublicKeyLine(line))
        }
    }
}
