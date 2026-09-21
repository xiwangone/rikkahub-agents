package me.rerere.rikkahub.data.vault

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * 凭证库「生成密钥」的统一类型清单（UI 选择项与 `vault_gen_key` 的 type 参数同源）。
 *
 * 分两类：
 * - **SSH 类**（ED25519 / RSA / ECDSA）→ 交给 [SshKeyGenerator]，公钥是 OpenSSH 一行格式；
 * - **X25519 类**（WIREGUARD / AGE）→ 由本文件生成，公钥是各工具认的文本格式。
 *
 * label 采用技术名（不含自然语言），因此新增类型不需要任何本地化文案。
 */
enum class VaultKeyType(val label: String) {
    ED25519("Ed25519（推荐）"),
    RSA2048("RSA-2048"),
    RSA4096("RSA-4096"),
    ECDSA256("ECDSA (nistp256)"),
    ECDSA384("ECDSA (nistp384)"),
    ECDSA521("ECDSA (nistp521)"),
    WIREGUARD("WireGuard (X25519)"),
    AGE("age (X25519)"),
    OPENPGP("OpenPGP (Ed25519)"),
    X509_CERT("X.509 self-signed"),
    X509_CSR("PKCS#10 CSR"),
}

/**
 * 统一的密钥材料生成入口。
 *
 * 返回值语义按类型分为「私钥文本 / 公钥文本」：
 * - SSH 类：私钥 = PKCS#8 PEM（Ed25519 为 OpenSSH 格式），公钥 = OpenSSH 单行
 * - WireGuard：两侧均为标准 base64（44 字符），与 `wg genkey` / `wg pubkey` 一致
 * - age：私钥 `AGE-SECRET-KEY-1…`（bech32，大写），公钥 `age1…`（bech32，小写）
 *
 * ⚠ 一律用 Bouncy Castle 软件实现，**不用 AndroidKeyStore**（其私钥不可导出，无法配置到外部）。
 */
object VaultKeyGenerator {

    private const val AGE_SECRET_HRP = "age-secret-key-"
    private const val AGE_PUBLIC_HRP = "age"

    data class GeneratedKey(
        val privateText: String,
        val publicText: String,
    )

    fun generate(
        type: VaultKeyType,
        comment: String = SshKeyGenerator.DEFAULT_COMMENT,
        uid: String = "",
    ): GeneratedKey = when (type) {
        VaultKeyType.ED25519 -> ssh(SshKeyGenerator.KeyType.ED25519, comment)
        VaultKeyType.RSA2048 -> ssh(SshKeyGenerator.KeyType.RSA, comment)
        VaultKeyType.RSA4096 -> ssh(SshKeyGenerator.KeyType.RSA4096, comment)
        VaultKeyType.ECDSA256 -> ssh(SshKeyGenerator.KeyType.ECDSA, comment)
        VaultKeyType.ECDSA384 -> ssh(SshKeyGenerator.KeyType.ECDSA384, comment)
        VaultKeyType.ECDSA521 -> ssh(SshKeyGenerator.KeyType.ECDSA521, comment)
        VaultKeyType.WIREGUARD -> wireGuard()
        VaultKeyType.AGE -> age()
        VaultKeyType.OPENPGP -> openPgp(uid.ifBlank { OpenPgpKeyGenerator.DEFAULT_UID })
        VaultKeyType.X509_CERT -> certificateKey(X509KeyGenerator.Kind.SELF_SIGNED_CERTIFICATE, uid)
        VaultKeyType.X509_CSR -> certificateKey(X509KeyGenerator.Kind.CSR, uid)
    }

    /** 该公钥是否有 OpenSSH 风格指纹可展示（非 SSH 类型没有，返回 null）。 */
    fun fingerprintOf(publicText: String): String? = SshKeyGenerator.fingerprint(publicText)

    private fun ssh(type: SshKeyGenerator.KeyType, comment: String): GeneratedKey {
        val pair = SshKeyGenerator.generate(type, comment)
        return GeneratedKey(privateText = pair.privateKeyPem, publicText = pair.publicKeyLine)
    }

    /** OpenPGP 没有「公钥一行 + 注释」的概念，两侧都是 armored 文本块。 */
    private fun openPgp(uid: String): GeneratedKey {
        val key = OpenPgpKeyGenerator.generate(uid)
        return GeneratedKey(privateText = key.privateKeyArmored, publicText = key.publicKeyArmored)
    }

    // ================= X25519 =================

    private fun x25519Keys(): Pair<ByteArray, ByteArray> {
        val generator = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(SecureRandom()))
        }
        val keyPair = generator.generateKeyPair()
        val private = (keyPair.private as X25519PrivateKeyParameters).encoded
        val public = (keyPair.public as X25519PublicKeyParameters).encoded
        return private to public
    }

    /** WireGuard 密钥对：两侧都是 32 字节 X25519 材料的标准 base64。 */
    private fun wireGuard(): GeneratedKey {
        val (private, public) = x25519Keys()
        return GeneratedKey(
            privateText = Base64.getEncoder().encodeToString(private),
            publicText = Base64.getEncoder().encodeToString(public),
        )
    }

    /**
     * age 密钥对：X25519 材料按 age 规范的 bech32 文本编码 ——
     * 私钥 `AGE-SECRET-KEY-1…`（HRP `age-secret-key-`，大写），公钥 `age1…`（HRP `age`）。
     */
    private fun age(): GeneratedKey {
        val (private, public) = x25519Keys()
        return GeneratedKey(
            privateText = Bech32.encode(AGE_SECRET_HRP, private).uppercase(),
            publicText = Bech32.encode(AGE_PUBLIC_HRP, public),
        )
    }

    /** X.509 家族用 uid 参数承载 Subject（`CN=host, O=Org`；只写一个名字时按 CN 处理）。 */
    private fun certificateKey(kind: X509KeyGenerator.Kind, subject: String): GeneratedKey {
        val key = X509KeyGenerator.generate(kind, subject.ifBlank { X509KeyGenerator.DEFAULT_SUBJECT })
        return GeneratedKey(privateText = key.privateKeyPem, publicText = key.publicText)
    }
}
