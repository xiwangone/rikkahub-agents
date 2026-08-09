package me.rerere.rikkahub.data.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault 导出包（.vault）——口令加密，供沙箱/ECS 离线解密使用。
 *
 * 协议（见设计文档）：
 * ```
 * {
 *   "format": "rikkahub-vault", "version": 1,
 *   "kdf": {"algo": "PBKDF2-HMAC-SHA256", "iterations": 200000, "salt": "<b64>"},
 *   "entries": {"DEEPSEEK_API_KEY": {"iv": "<b64>", "ct": "<b64>", "desc": "..."}}
 * }
 * ```
 *
 * - 每条凭证独立 AES-256-GCM 加密（随机 IV），GCM tag 校验完整性；
 * - 口令经 PBKDF2-HMAC-SHA256（20 万次迭代）派生密钥；
 * - 导出包不含明文、不含 SSH 私钥。
 */
object VaultExporter {

    private const val FORMAT = "rikkahub-vault"
    private const val VERSION = 1
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_LENGTH = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Serializable
    data class KdfSpec(
        val algo: String = "PBKDF2-HMAC-SHA256",
        val iterations: Int = PBKDF2_ITERATIONS,
        val salt: String,
    )

    @Serializable
    data class EntrySpec(
        val iv: String,
        val ct: String,
        val desc: String = "",
    )

    @Serializable
    data class VaultBundle(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val kdf: KdfSpec,
        val entries: Map<String, EntrySpec>,
    )

    /** 导出：明文条目列表 → .vault JSON 字符串。 */
    fun export(
        password: String,
        entries: List<Triple<String, String, String>>, // name, plaintext, description
    ): String {
        require(password.isNotBlank()) { "口令不能为空" }
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val entryMap = entries.associate { (name, plaintext, desc) ->
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plaintext.encodeToByteArray())
            name to EntrySpec(
                iv = Base64.getEncoder().encodeToString(cipher.iv),
                ct = Base64.getEncoder().encodeToString(ct),
                desc = desc,
            )
        }
        val bundle = VaultBundle(
            kdf = KdfSpec(salt = Base64.getEncoder().encodeToString(salt)),
            entries = entryMap,
        )
        return Json { prettyPrint = true }.encodeToString(bundle)
    }

    /**
     * 导入（离线）：.vault JSON + 口令 → 明文条目列表。
     * 口令错/数据被篡改 → 解密失败抛出异常。
     */
    fun import(
        vaultJson: String,
        password: String,
    ): List<Triple<String, String, String>> {
        val bundle = Json { ignoreUnknownKeys = true }.decodeFromString<VaultBundle>(vaultJson)
        require(bundle.format == FORMAT) { "非 rikkahub-vault 格式" }
        val salt = Base64.getDecoder().decode(bundle.kdf.salt)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return bundle.entries.map { (name, entry) ->
            val iv = Base64.getDecoder().decode(entry.iv)
            val ct = Base64.getDecoder().decode(entry.ct)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
            val plaintext = cipher.doFinal(ct).decodeToString()
            Triple(name, plaintext, entry.desc)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): javax.crypto.SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
