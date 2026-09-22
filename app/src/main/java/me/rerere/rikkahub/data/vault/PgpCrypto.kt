package me.rerere.rikkahub.data.vault

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import org.bouncycastle.util.encoders.Hex

/** GPG 操作失败（口令错 / 无加密子密钥 / 结构不识别等），message 直接面向用户。 */
class PgpCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OpenPGP 签名 / 验签 / 加密 / 解密（档位 3b）。
 *
 * 全部走 Bouncy Castle 软件实现（与 3a 生成器同路径，不依赖 JCA provider 注册），
 * API 用法已在沙箱以 gpg 2.4.4 做过六向互操作验证（见 规划/凭证密钥类型扩展-GPG操作-20260922.md §二）。
 *
 * 边界：
 * - 载荷按文本类处理（明文 UTF-8 进出）；二进制文件的写出走通用文件工具。
 * - 密文/签名入参自适应 armored 与二进制（gpg 默认输出二进制，只认 armored 会栽）。
 * - passphrase 传 null/空 = 兼容 3a 生成的无口令私钥；带口令私钥口令错会抛 [PgpCryptoException]。
 *
 * 宽 catch 是有意的边界收口：BC 各层会抛 PGPException/IOException/IllegalState 等多种异常，
 * 统一转成面向用户的 [PgpCryptoException]；extractPrivateKeyOrNull 返回 null 即「口令错/不可用」语义。
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "SwallowedException")
object PgpCrypto {

    /** 验签结果：通过时带签名者指纹（大写 hex）。 */
    sealed interface VerifyResult {
        data class Valid(val fingerprint: String) : VerifyResult
        data class Invalid(val reason: String) : VerifyResult
    }

    // ================= 签名 =================

    /**
     * 对 [data] 生成 detached armored 签名（BINARY_DOCUMENT）。
     * 签名密钥取私钥环的主密钥（3a/3b 结构主密钥均声明 certify+sign）。
     */
    fun sign(armoredPrivateKey: String, data: ByteArray, passphrase: CharArray? = null): String {
        val ring = parseSecretRing(armoredPrivateKey)
        val secretKey = ring.secretKey ?: throw PgpCryptoException("私钥环里没有可用的签名密钥")
        val privateKey = extractPrivateKey(secretKey, passphrase)
        val hashAlg =
            if (secretKey.publicKey.algorithm == PublicKeyAlgorithmTags.EDDSA) {
                HashAlgorithmTags.SHA512 // EdDSA 在 OpenPGP 中要求 SHA-512（3a 踩坑表）
            } else {
                HashAlgorithmTags.SHA256
            }
        val generator = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(secretKey.publicKey.algorithm, hashAlg),
            secretKey.publicKey,
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        generator.update(data)
        val signature = generator.generate()
        return armor { signature.encode(it) }
    }

    /** 校验 detached 签名；[signature] 可为 armored 或二进制。 */
    fun verify(armoredPublicKey: String, data: ByteArray, signature: ByteArray): VerifyResult {
        val sig = runCatching {
            val factory = PGPObjectFactory(maybeArmored(signature), BcKeyFingerprintCalculator())
            var list: PGPSignatureList? = null
            val iterator = factory.iterator()
            while (iterator.hasNext()) {
                val obj = iterator.next()
                if (obj is PGPSignatureList) {
                    list = obj
                    break
                }
            }
            list?.takeIf { !it.isEmpty }?.get(0)
        }.getOrElse { return VerifyResult.Invalid("签名解析失败: ${it.message}") }
            ?: return VerifyResult.Invalid("未找到签名对象")

        val publicKeyRing = resolvePublicRing(armoredPublicKey)
        val signerKey: PGPPublicKey = publicKeyRing.getPublicKey(sig.keyID)
            ?: return VerifyResult.Invalid("签名者 Key ID ${"%016X".format(sig.keyID)} 不在提供的公钥环中")
        return try {
            sig.init(BcPGPContentVerifierBuilderProvider(), signerKey)
            sig.update(data)
            if (sig.verify()) {
                VerifyResult.Valid(Hex.toHexString(signerKey.fingerprint).uppercase())
            } else {
                VerifyResult.Invalid("签名与数据不匹配（数据被篡改或签名者不同）")
            }
        } catch (e: Exception) {
            VerifyResult.Invalid("验签过程失败: ${e.message}")
        }
    }

    // ================= 加解密 =================

    /** 用公钥环中的加密子密钥加密（AES-256 + 完整性包），返回 armored 密文。 */
    fun encrypt(armoredPublicKey: String, data: ByteArray): String {
        val ring = resolvePublicRing(armoredPublicKey)
        val encryptionKey = ring.firstOrNull { it.isEncryptionKey }
            ?: throw PgpCryptoException(
                "该公钥环没有加密子密钥（只有签名能力）——需要带 ECDH 加密子密钥的公钥",
            )
        val generator = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom()),
        )
        generator.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(encryptionKey))
        val ciphertext = ByteArrayOutputStream()
        generator.open(ciphertext, ByteArray(1 shl 16)).use { packetOut ->
            PGPLiteralDataGenerator().open(
                packetOut,
                PGPLiteralData.BINARY,
                "data.txt",
                Date(),
                ByteArray(4096),
            ).use { literalOut -> literalOut.write(data) }
        }
        return armor { it.write(ciphertext.toByteArray()) }
    }

    /** 解密 armored 或二进制密文，返回明文字节。 */
    fun decrypt(armoredPrivateKey: String, ciphertext: ByteArray, passphrase: CharArray? = null): ByteArray {
        val ring = parseSecretRing(armoredPrivateKey)
        val factory = PGPObjectFactory(maybeArmored(ciphertext), BcKeyFingerprintCalculator())
        var encryptedList: PGPEncryptedDataList? = null
        val iterator = factory.iterator()
        while (iterator.hasNext()) {
            val obj = iterator.next()
            if (obj is PGPEncryptedDataList) {
                encryptedList = obj
                break
            }
        }
        val list = encryptedList
            ?: throw PgpCryptoException("密文里没有找到加密数据包（不是有效的 OpenPGP 消息？）")

        var matchedKey: PGPPrivateKey? = null
        var matchedData: PGPPublicKeyEncryptedData? = null
        for (i in 0 until list.size()) {
            val encrypted = list.get(i)
            if (encrypted !is PGPPublicKeyEncryptedData) continue
            val secretKey = ring.getSecretKey(encrypted.keyIdentifier) ?: continue
            val candidate = extractPrivateKeyOrNull(secretKey, passphrase) ?: continue
            matchedKey = candidate
            matchedData = encrypted
            break
        }
        val key = matchedKey
        val data = matchedData
        if (key == null || data == null) {
            throw PgpCryptoException(
                if (passphrase == null || passphrase.isEmpty()) {
                    "私钥环里没有能解开这份密文的密钥（或私钥带口令但未传 passphrase）"
                } else {
                    "passphrase 不正确，或私钥环里没有匹配的密钥"
                },
            )
        }

        val plainFactory = PGPObjectFactory(
            data.getDataStream(BcPublicKeyDataDecryptorFactory(key)),
            BcKeyFingerprintCalculator(),
        )
        var obj: Any? = plainFactory.nextObject()
        while (obj is PGPCompressedData) {
            obj = PGPObjectFactory(obj.dataStream, BcKeyFingerprintCalculator()).nextObject()
        }
        val literal = obj as? PGPLiteralData
            ?: throw PgpCryptoException("解密后不是字面量数据包（结构异常）")
        return literal.inputStream.readBytes()
    }

    // ================= 解析辅助 =================

    /** armored 私钥块 → 私钥环。 */
    fun parseSecretRing(armoredPrivateKey: String): PGPSecretKeyRing = try {
        PGPSecretKeyRing(
            ArmoredInputStream(armoredPrivateKey.byteInputStream()),
            BcKeyFingerprintCalculator(),
        )
    } catch (e: Exception) {
        throw PgpCryptoException("私钥解析失败（需要 armored 私钥块）: ${e.message}", e)
    }

    /** armored 公钥块 → 公钥环。 */
    fun parsePublicRing(armoredPublicKey: String): PGPPublicKeyRing = try {
        PGPPublicKeyRing(
            ArmoredInputStream(armoredPublicKey.byteInputStream()),
            BcKeyFingerprintCalculator(),
        )
    } catch (e: Exception) {
        throw PgpCryptoException("公钥解析失败（需要 armored 公钥块）: ${e.message}", e)
    }

    /**
     * 验签/加密的入参兼容公钥块与私钥块 —— AI 手里常只有一份私钥文本，
     * 条目里没存 publicKey 字段时也能用（私钥环里本来就含公钥部分）。
     */
    fun resolvePublicRing(armored: String): PGPPublicKeyRing {
        if (armored.contains("BEGIN PGP PUBLIC KEY BLOCK")) return parsePublicRing(armored)
        val secretRing = parseSecretRing(armored)
        val raw = ByteArrayOutputStream()
        ArmoredOutputStream(raw).use { out ->
            val keys = secretRing.secretKeys
            while (keys.hasNext()) {
                keys.next().publicKey.encode(out)
            }
        }
        return try {
            PGPPublicKeyRing(
                ArmoredInputStream(raw.toByteArray().inputStream()),
                BcKeyFingerprintCalculator(),
            )
        } catch (e: Exception) {
            throw PgpCryptoException("从私钥块提取公钥失败: ${e.message}", e)
        }
    }

    /** armored 与二进制两态兼容（gpg 默认输出二进制）。 */
    private fun maybeArmored(bytes: ByteArray): InputStream {
        val headLength = minOf(bytes.size, 64)
        val head = String(bytes, 0, headLength, Charsets.ISO_8859_1)
        return if (head.contains("-----BEGIN")) {
            ArmoredInputStream(bytes.inputStream())
        } else {
            bytes.inputStream()
        }
    }

    private fun extractPrivateKey(secretKey: PGPSecretKey, passphrase: CharArray?): PGPPrivateKey =
        extractPrivateKeyOrNull(secretKey, passphrase)
            ?: throw PgpCryptoException(
                if (passphrase == null || passphrase.isEmpty()) "私钥被口令保护，需要传 passphrase" else "passphrase 不正确",
            )

    private fun extractPrivateKeyOrNull(secretKey: PGPSecretKey, passphrase: CharArray?): PGPPrivateKey? = try {
        secretKey.extractPrivateKey(
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                .build(passphrase ?: CharArray(0)),
        )
    } catch (e: Exception) {
        null
    }

    private inline fun armor(block: (OutputStream) -> Unit): String {
        val raw = ByteArrayOutputStream()
        ArmoredOutputStream(raw).use { armored -> block(armored) }
        return raw.toString("UTF-8")
    }
}
