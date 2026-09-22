package me.rerere.rikkahub.data.vault

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
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
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.util.encoders.Hex

/**
 * OpenPGP（GnuPG）密钥环生成 + armored 导出。
 *
 * 用途：生成一把可做 **git 提交签名** 的 GPG 密钥 —— 公钥上传到 GitHub/GitLab，
 * 私钥留在凭证库（armored 文本，由 Vault 自身加密存储）。
 *
 * 结构：**Ed25519 主密钥**（certify + sign）+ 一个 **UID** + 自签名
 * + **ECDH (secp256r1) 加密子密钥**（签名/验签/加解密工具需要）。
 * ⚠ 加密子密钥用 secp256r1 而非 cv25519：bcpg 1.86 的 key converter 对 X25519 私钥
 * 有类型转换缺陷（实测 ClassCastException），见 规划/凭证密钥类型扩展-GPG操作-20260922.md §三。
 *
 * ⚠ 私钥**不带 passphrase**：口令保护依赖 §N4（密钥槽/密码槽分离）尚未落地，
 * 当前的保护边界是凭证库自身的加密存储 + Vault 授权。
 */
object OpenPgpKeyGenerator {

    /** 未提供 UID 时使用的默认身份标识。 */
    const val DEFAULT_UID = "RikkaHub Agents <agent@rikkahub-agents>"

    // 密钥用途标志（RFC 4880 §5.2.3.21）：0x01 = certify，0x02 = sign
    private const val KEY_FLAG_CERTIFY = 0x01
    private const val KEY_FLAG_SIGN = 0x02

    // 0x08 = encrypt communications，0x10 = encrypt storage
    private const val KEY_FLAG_ENCRYPT_COMMS = 0x08
    private const val KEY_FLAG_ENCRYPT_STORAGE = 0x10

    data class GeneratedKey(
        /** armored 私钥（`-----BEGIN PGP PRIVATE KEY BLOCK-----`）。 */
        val privateKeyArmored: String,
        /** armored 公钥（`-----BEGIN PGP PUBLIC KEY BLOCK-----`），可直接上传到代码托管平台。 */
        val publicKeyArmored: String,
        /** 16 位十六进制 Key ID（末 8 字节）。 */
        val keyId: String,
        /** 40 位十六进制指纹（v4，大写，无空格）。 */
        val fingerprint: String,
    )

    fun generate(uid: String = DEFAULT_UID): GeneratedKey {
        val normalizedUid = uid.trim().ifBlank { DEFAULT_UID }

        val keyPair = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }.generateKeyPair()
        val pgpKeyPair: PGPKeyPair = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA, keyPair, Date())

        // EdDSA 在 OpenPGP 中要求 SHA-512 作为自签名哈希
        val digestProvider = BcPGPDigestCalculatorProvider()
        val sha1Calculator = digestProvider.get(HashAlgorithmTags.SHA1)

        // 明确声明用途，避免导入后被当成「未指定能力」的密钥
        val hashedSubpackets = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KEY_FLAG_CERTIFY or KEY_FLAG_SIGN)
            // 算法偏好声明：gpg 加密时校验收件人偏好，不声明会报 “not in recipient preferences”
            setPreferredSymmetricAlgorithms(
                false,
                intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128),
            )
            setPreferredHashAlgorithms(
                false,
                intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256),
            )
            setPreferredCompressionAlgorithms(false, intArrayOf(CompressionAlgorithmTags.UNCOMPRESSED))
        }

        val ringGenerator = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            normalizedUid,
            sha1Calculator,
            hashedSubpackets.generate(),
            null,
            BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.EDDSA, HashAlgorithmTags.SHA512),
            // 私钥不加密（无 passphrase）—— 口令保护依赖 N4（密钥槽/密码槽分离），尚未落地
            null,
        )

        // ECDH (secp256r1) 加密子密钥：GPG 加解密用；KDF = SHA-256 + AES-256 密钥包裹
        val ecGenerator = ECKeyPairGenerator().apply {
            init(
                ECKeyGenerationParameters(
                    ECNamedDomainParameters(
                        SECObjectIdentifiers.secp256r1,
                        CustomNamedCurves.getByName("secp256r1"),
                    ),
                    SecureRandom(),
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
            setKeyFlags(false, KEY_FLAG_ENCRYPT_COMMS or KEY_FLAG_ENCRYPT_STORAGE)
        }
        // 3 参版：子密钥绑定签名由主密钥自动完成（4 参自定义 signer 在 1.86 会拿错私钥，见 §三）
        ringGenerator.addSubKey(encryptionKeyPair, encryptionSubpackets.generate(), null)

        val publicRing = ringGenerator.generatePublicKeyRing()
        val secretRing = ringGenerator.generateSecretKeyRing()
        val publicKey = publicRing.publicKey

        return GeneratedKey(
            privateKeyArmored = armor { secretRing.encode(it) },
            publicKeyArmored = armor { publicRing.encode(it) },
            // Key ID 固定 16 位十六进制：直接用 Long.toHexString 会丢掉前导零
            // （约 1/256 的密钥会命中，表现为长度 14 或 15 —— 单元测试会随机失败）
            keyId = "%016X".format(publicKey.keyID),
            fingerprint = Hex.toHexString(publicKey.fingerprint).uppercase(),
        )
    }

    private inline fun armor(block: (OutputStream) -> Unit): String {
        val raw = ByteArrayOutputStream()
        ArmoredOutputStream(raw).use { armored ->
            armored.setHeader("Comment", SshKeyGenerator.DEFAULT_COMMENT)
            block(armored)
        }
        return raw.toString("UTF-8")
    }
}
