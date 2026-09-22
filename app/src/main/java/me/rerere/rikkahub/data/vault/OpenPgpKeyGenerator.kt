package me.rerere.rikkahub.data.vault

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
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
 * 结构（最小可用形态）：**Ed25519 主密钥**（certify + sign 能力）+ 一个 **UID** + 自签名。
 * 不含加密子密钥 —— git 签名不需要它，等有「文件加密」需求时再按需追加子密钥。
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
