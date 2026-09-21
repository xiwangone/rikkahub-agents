package me.rerere.rikkahub.data.vault

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultKeyGeneratorTest {

    // ================= bech32（age 编码基础） =================

    @Test
    fun `bech32 encoding is self-consistent`() {
        val encoded = Bech32.encode("age", ByteArray(32) { it.toByte() })
        assertTrue(Bech32.verify(encoded))
        // 32 字节 → 52 个 5-bit 组（含末尾补零）；加 "age" + "1" + 6 位校验和
        assertEquals("age1".length + 52 + 6, encoded.length)
    }

    @Test
    fun `bech32 rejects a tampered string`() {
        val encoded = Bech32.encode("age", ByteArray(32) { it.toByte() })
        val tampered = encoded.dropLast(1) + if (encoded.last() == 'q') "p" else "q"
        assertTrue(!Bech32.verify(tampered))
    }

    // ================= SSH 类 =================

    @Test
    fun `ecdsa public keys carry the matching curve name and point size`() {
        val p256 = VaultKeyGenerator.generate(VaultKeyType.ECDSA256)
        val p384 = VaultKeyGenerator.generate(VaultKeyType.ECDSA384)
        val p521 = VaultKeyGenerator.generate(VaultKeyType.ECDSA521)

        assertTrue(p256.publicText.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(p384.publicText.startsWith("ecdsa-sha2-nistp384 "))
        assertTrue(p521.publicText.startsWith("ecdsa-sha2-nistp521 "))

        // blob 长度 = 4+19（类型）+ 4+8（曲线名）+ 4+（1 + 2*坐标字节数）
        assertEquals(104, blob(p256.publicText).size)
        assertEquals(136, blob(p384.publicText).size)
        assertEquals(172, blob(p521.publicText).size)

        assertTrue(p384.privateText.contains("PRIVATE KEY"))
    }

    @Test
    fun `ssh types still produce single-line public keys with a fingerprint`() {
        listOf(VaultKeyType.ED25519, VaultKeyType.ECDSA256, VaultKeyType.ECDSA384).forEach { type ->
            val key = VaultKeyGenerator.generate(type)
            assertEquals(1, key.publicText.lines().size)
            assertTrue(VaultKeyGenerator.fingerprintOf(key.publicText)!!.startsWith("SHA256:"))
        }
    }

    @Test
    fun `non-ssh types expose no openssh fingerprint`() {
        val wireGuard = VaultKeyGenerator.generate(VaultKeyType.WIREGUARD)
        assertEquals(null, VaultKeyGenerator.fingerprintOf(wireGuard.publicText))
    }

    // ================= WireGuard / age =================

    @Test
    fun `wireguard keys are 32 bytes in standard base64`() {
        val key = VaultKeyGenerator.generate(VaultKeyType.WIREGUARD)

        assertEquals(32, Base64.getDecoder().decode(key.privateText).size)
        assertEquals(32, Base64.getDecoder().decode(key.publicText).size)
        // 标准 base64（含 '=' 填充）—— 与 `wg genkey` / `wg pubkey` 的输出长度一致
        assertEquals(44, key.publicText.length)
        assertTrue(key.publicText.endsWith("="))
    }

    @Test
    fun `age keys use the bech32 text format age expects`() {
        val key = VaultKeyGenerator.generate(VaultKeyType.AGE)

        assertTrue(key.privateText.startsWith("AGE-SECRET-KEY-1"))
        assertTrue(key.publicText.startsWith("age1"))
        // 15（hrp）+ 1（分隔）+ 52（32 字节数据）+ 6（校验和）
        assertEquals(74, key.privateText.length)
        assertEquals(62, key.publicText.length)
        // 校验和自洽
        assertTrue(Bech32.verify(key.publicText))
        assertTrue(Bech32.verify(key.privateText))
    }

    @Test
    fun `each call returns fresh key material`() {
        val first = VaultKeyGenerator.generate(VaultKeyType.WIREGUARD)
        val second = VaultKeyGenerator.generate(VaultKeyType.WIREGUARD)
        assertTrue(first.privateText != second.privateText)
        assertTrue(first.publicText != second.publicText)
    }

    private fun blob(publicKeyLine: String): ByteArray =
        Base64.getDecoder().decode(publicKeyLine.split(" ")[1])
}
