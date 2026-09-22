package me.rerere.rikkahub.data.vault

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPKdfParameters
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 档位 3b（GPG sign/verify/encrypt/decrypt）单测。
 *
 * 覆盖：round-trip、篡改检测、错口令/错密钥、无加密子密钥报错、
 * armored 与二进制密文两态、私钥块当公钥入参（AI 手里常只有一份私钥文本）。
 * gpg 互操作（六向）在沙箱用原型产物做过；本类保证 Kotlin 侧同 API 同结构。
 */
class PgpCryptoTest {

    @Test
    fun `sign and verify round trip returns signer fingerprint`() {
        val key = OpenPgpKeyGenerator.generate("Round Trip <rt@example.com>")
        val data = "payload for signing".toByteArray()
        val signature = PgpCrypto.sign(key.privateKeyArmored, data)
        assertTrue(signature.contains("-----BEGIN PGP SIGNATURE-----"))
        val result = PgpCrypto.verify(key.publicKeyArmored, data, signature.toByteArray())
        assertTrue("expected valid, got $result", result is PgpCrypto.VerifyResult.Valid)
        assertEquals(key.fingerprint, (result as PgpCrypto.VerifyResult.Valid).fingerprint)
    }

    @Test
    fun `tampered data fails verification`() {
        val key = OpenPgpKeyGenerator.generate("Tamper <tm@example.com>")
        val signature = PgpCrypto.sign(key.privateKeyArmored, "original".toByteArray())
        val result = PgpCrypto.verify(key.publicKeyArmored, "tampered".toByteArray(), signature.toByteArray())
        assertTrue("expected invalid, got $result", result is PgpCrypto.VerifyResult.Invalid)
    }

    @Test
    fun `encrypt and decrypt round trip returns plaintext`() {
        val key = OpenPgpKeyGenerator.generate("Enc Round <er@example.com>")
        val plaintext = "secret payload 加解密".toByteArray()
        val ciphertext = PgpCrypto.encrypt(key.publicKeyArmored, plaintext)
        assertTrue(ciphertext.contains("-----BEGIN PGP MESSAGE-----"))
        val decrypted = PgpCrypto.decrypt(key.privateKeyArmored, ciphertext.toByteArray())
        assertEquals(String(plaintext), String(decrypted))
    }

    @Test
    fun `encrypt rejects key without encryption subkey`() {
        val signOnly = signOnlyKeyArmored()
        try {
            PgpCrypto.encrypt(signOnly, "data".toByteArray())
            fail("expected PgpCryptoException for sign-only key")
        } catch (expected: PgpCryptoException) {
            assertTrue(expected.message!!.contains("没有加密子密钥"))
        }
    }

    @Test
    fun `decrypt with wrong key fails`() {
        val alice = OpenPgpKeyGenerator.generate("Alice <a@example.com>")
        val bob = OpenPgpKeyGenerator.generate("Bob <b@example.com>")
        val ciphertext = PgpCrypto.encrypt(alice.publicKeyArmored, "for alice".toByteArray())
        try {
            PgpCrypto.decrypt(bob.privateKeyArmored, ciphertext.toByteArray())
            fail("expected PgpCryptoException for non-matching key")
        } catch (expected: PgpCryptoException) {
            // 无匹配私钥（bob 环里没有 alice 消息的 PKESK 目标）
        }
    }

    @Test
    fun `passphrase protected key requires correct passphrase`() {
        val armored = passphraseKeyArmored("correct-horse")
        val ciphertext = PgpCrypto.encrypt(
            OpenPgpKeyGenerator.generate("PW <pw@example.com>").publicKeyArmored,
            "guarded".toByteArray(),
        )
        // 结构：带口令的私钥环自解（同环 encrypt/decrypt）
        val ownCiphertext = PgpCrypto.encrypt(
            armored.let { pubFromSecret(it) },
            "guarded".toByteArray(),
        )
        try {
            PgpCrypto.decrypt(armored, ownCiphertext.toByteArray())
            fail("expected failure without passphrase")
        } catch (expected: PgpCryptoException) {
            assertTrue(expected.message!!.contains("passphrase"))
        }
        val plaintext = PgpCrypto.decrypt(armored, ownCiphertext.toByteArray(), "correct-horse".toCharArray())
        assertEquals("guarded", String(plaintext))
        // ciphertext 与 ownCiphertext 均存在即可（前者保留在结构上同环加密可用）
        assertTrue(ciphertext.isNotEmpty())
    }

    @Test
    fun `binary ciphertext decrypts`() {
        val key = OpenPgpKeyGenerator.generate("Binary <bin@example.com>")
        val ciphertext = PgpCrypto.encrypt(key.publicKeyArmored, "binary path".toByteArray())
        val raw = unarmor(ciphertext)
        val decrypted = PgpCrypto.decrypt(key.privateKeyArmored, raw)
        assertEquals("binary path", String(decrypted))
    }

    @Test
    fun `private key block works as public key input`() {
        val key = OpenPgpKeyGenerator.generate("Dual Use <du@example.com>")
        val data = "dual".toByteArray()
        val signature = PgpCrypto.sign(key.privateKeyArmored, data)
        // 验签：key 传私钥块（resolvePublicRing 兼容）
        val verified = PgpCrypto.verify(key.privateKeyArmored, data, signature.toByteArray())
        assertTrue("expected valid via private block, got $verified", verified is PgpCrypto.VerifyResult.Valid)
        // 加密：key 传私钥块
        val ciphertext = PgpCrypto.encrypt(key.privateKeyArmored, data)
        val decrypted = PgpCrypto.decrypt(key.privateKeyArmored, ciphertext.toByteArray())
        assertEquals("dual", String(decrypted))
    }

    // ---------- fixtures ----------

    /** 无加密子密钥的简环（3a 早期结构）：只有 Ed25519 主密钥。 */
    private fun signOnlyKeyArmored(): String {
        val random = SecureRandom()
        val edGenerator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        val master: PGPKeyPair = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA, edGenerator.generateKeyPair(), Date())
        val digestProvider = BcPGPDigestCalculatorProvider()
        val subpackets = PGPSignatureSubpacketGenerator().apply { setKeyFlags(false, 0x01 or 0x02) }
        val ringGenerator = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            master,
            "Sign Only <so@example.com>",
            digestProvider.get(HashAlgorithmTags.SHA1),
            subpackets.generate(),
            null,
            BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.EDDSA, HashAlgorithmTags.SHA512),
            null,
        )
        return armor { ringGenerator.generatePublicKeyRing().encode(it) }
    }

    /** 带 passphrase 保护的私钥环。 */
    private fun passphraseKeyArmored(passphrase: String): String {
        val random = SecureRandom()
        val edGenerator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        val master: PGPKeyPair = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA, edGenerator.generateKeyPair(), Date())
        val digestProvider = BcPGPDigestCalculatorProvider()
        val subpackets = PGPSignatureSubpacketGenerator().apply { setKeyFlags(false, 0x01 or 0x02) }
        val keyEncryptor = BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
            .build(passphrase.toCharArray())
        val ringGenerator = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            master,
            "PW Guarded <pwg@example.com>",
            digestProvider.get(HashAlgorithmTags.SHA1),
            subpackets.generate(),
            null,
            BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.EDDSA, HashAlgorithmTags.SHA512),
            keyEncryptor,
        )
        // 与 3b 生成器同构：带口令的环也必须有 ECDH 加密子密钥（否则 encrypt 必报“无加密子密钥”）
        val ecGenerator = ECKeyPairGenerator().apply {
            init(
                ECKeyGenerationParameters(
                    ECNamedDomainParameters(
                        SECObjectIdentifiers.secp256r1,
                        CustomNamedCurves.getByName("secp256r1"),
                    ),
                    random,
                ),
            )
        }
        val encryptionKeyPair: PGPKeyPair = BcPGPKeyPair(
            PublicKeyAlgorithmTags.ECDH,
            PGPKdfParameters(HashAlgorithmTags.SHA256, SymmetricKeyAlgorithmTags.AES_256),
            ecGenerator.generateKeyPair(),
            Date(),
        )
        val encryptionSubpackets = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, 0x08 or 0x10)
        }
        ringGenerator.addSubKey(encryptionKeyPair, encryptionSubpackets.generate(), null)
        return armor { ringGenerator.generateSecretKeyRing().encode(it) }
    }

    /** 从私钥块取可加密用的公钥文本（走 PgpCrypto 的两态解析）。 */
    private fun pubFromSecret(armoredPrivate: String): String = armoredPrivate

    /** 用 BC 自己的 ArmoredInputStream 解出真正的二进制（手写提取会把 Version 头行当 base64 混进去）。 */
    private fun unarmor(armored: String): ByteArray {
        val out = ByteArrayOutputStream()
        ArmoredInputStream(armored.byteInputStream()).use { input -> input.copyTo(out) }
        return out.toByteArray()
    }

    /** 把 Kotlin 真实产物写盘，供沙箱 gpg 做六向互操作复验（立项 §六）。 */
    @Test
    fun `write interop samples for gpg recheck`() {
        val dir = java.io.File("build/pgp-samples")
        dir.mkdirs()
        val key = OpenPgpKeyGenerator.generate("Interop <interop@example.com>")
        java.io.File(dir, "pub.asc").writeText(key.publicKeyArmored)
        java.io.File(dir, "sec.asc").writeText(key.privateKeyArmored)
        val data = "kotlin interop payload".toByteArray()
        java.io.File(dir, "data.txt").writeBytes(data)
        java.io.File(dir, "expected.txt").writeText("kotlin interop payload")
        java.io.File(dir, "sig.asc").writeText(PgpCrypto.sign(key.privateKeyArmored, data))
        java.io.File(dir, "enc.asc").writeText(PgpCrypto.encrypt(key.publicKeyArmored, data))
        assertTrue(dir.listFiles()!!.size >= 6)
    }

    private fun armor(block: (java.io.OutputStream) -> Unit): String {
        val raw = ByteArrayOutputStream()
        ArmoredOutputStream(raw).use { armored -> block(armored) }
        return raw.toString("UTF-8")
    }
}
