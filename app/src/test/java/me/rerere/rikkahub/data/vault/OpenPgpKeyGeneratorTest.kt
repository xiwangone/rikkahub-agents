package me.rerere.rikkahub.data.vault

import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPgpKeyGeneratorTest {

    @Test
    fun `emits armored public and private key blocks`() {
        val key = OpenPgpKeyGenerator.generate("Test User <test@example.com>")

        assertTrue(key.publicKeyArmored.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertTrue(key.publicKeyArmored.trimEnd().endsWith("-----END PGP PUBLIC KEY BLOCK-----"))
        assertTrue(key.privateKeyArmored.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----"))
        assertTrue(key.privateKeyArmored.trimEnd().endsWith("-----END PGP PRIVATE KEY BLOCK-----"))
    }

    @Test
    fun `key id and fingerprint have the expected shape`() {
        val key = OpenPgpKeyGenerator.generate()
        val hex = "0123456789ABCDEF"

        assertEquals(16, key.keyId.length)
        assertEquals(40, key.fingerprint.length)
        assertTrue(key.keyId.all { it in hex })
        assertTrue(key.fingerprint.all { it in hex })
    }

    @Test
    fun `armored public key parses back with the requested uid`() {
        val uid = "Round Trip <roundtrip@example.com>"
        val key = OpenPgpKeyGenerator.generate(uid)

        val publicKey = readPublicRing(key.publicKeyArmored).publicKey

        assertEquals(uid, publicKey.userIDs.next())
        assertEquals(PublicKeyAlgorithmTags.EDDSA, publicKey.algorithm)
        assertTrue(publicKey.isMasterKey)
        // 文本块可被重新解析本身即说明 armored 输出有效
        assertEquals(java.lang.Long.toHexString(publicKey.keyID).uppercase(), key.keyId)
    }

    @Test
    fun `private block belongs to the same key as the public block`() {
        val key = OpenPgpKeyGenerator.generate("Same <same@example.com>")
        val secretKey = readSecretRing(key.privateKeyArmored).secretKey

        assertEquals(key.keyId, java.lang.Long.toHexString(secretKey.keyID).uppercase())
    }

    @Test
    fun `blank uid falls back to the default identity`() {
        val key = OpenPgpKeyGenerator.generate("   ")

        assertEquals(
            OpenPgpKeyGenerator.DEFAULT_UID,
            readPublicRing(key.publicKeyArmored).publicKey.userIDs.next(),
        )
    }

    @Test
    fun `each call produces a distinct key`() {
        val first = OpenPgpKeyGenerator.generate()
        val second = OpenPgpKeyGenerator.generate()
        assertTrue(first.fingerprint != second.fingerprint)
        assertTrue(first.privateKeyArmored != second.privateKeyArmored)
    }

    private fun readPublicRing(armored: String): PGPPublicKeyRing =
        PGPObjectFactory(
            PGPUtil.getDecoderStream(armored.byteInputStream()),
            JcaKeyFingerprintCalculator(),
        ).nextObject() as PGPPublicKeyRing

    private fun readSecretRing(armored: String): PGPSecretKeyRing =
        PGPObjectFactory(
            PGPUtil.getDecoderStream(armored.byteInputStream()),
            JcaKeyFingerprintCalculator(),
        ).nextObject() as PGPSecretKeyRing
}
