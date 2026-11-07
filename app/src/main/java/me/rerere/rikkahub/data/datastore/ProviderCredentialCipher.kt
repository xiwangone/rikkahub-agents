package me.rerere.rikkahub.data.datastore

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.data.crypto.KeystoreAesKey

/**
 * P0 凭证加密：为 PreferencesStore 的敏感配置与 `providers` 中的敏感字段（含 LLM provider apiKey、
 * Google Vertex AI 服务账号私钥、Backend 密码/令牌、自定义请求头值等）提供 AES-256-GCM 加解密。
 *
 * 复用仓库既有 AndroidKeyStore 基建（密钥创建统一在 [KeystoreAesKey]，与 Codex 等共用同一套
 * AES-256-GCM 属性）：密钥仅存于 AndroidKeyStore（应用级，
 * TEE/StrongBox 可用时硬件背书），密钥本身不落盘、不进备份，因此密文即使随备份
 * 泄漏也无法在其它设备解密。
 *
 * 密文格式：
 * - 整包密文（历史及尚未拆分字段的配置项）：`Base64(IV(12B) + AES-GCM ciphertext)`
 * - 字段级密文（`providers` 内敏感字段）：`enc:v1:Base64(IV(12B) + AES-GCM ciphertext)`
 */
// 加密入口与 degraded 状态管理天然聚合（同一份缓存/同一把密钥），拆到别的对象只会把状态散到多处；
// 函数数保持在 11 个已是这条链路的自然下限，故对 TooManyFunctions 显式豁免。
@Suppress("TooManyFunctions")
internal object ProviderCredentialCipher {

    const val ENC_V1_PREFIX = "enc:v1:"
    private const val VAULT_REF_PREFIX = "\$\$"
    private const val KEY_ALIAS = "rikkahub_providers_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_LENGTH = 128
    private const val MAX_CACHE_ENTRIES = 256

    // 按 (keyIdentity, ciphertext) 缓存已验证通过的明文，避免每次设置项变更时重复进入 TEE 解密未改动的密文字段。
    private val verifiedPlaintextByCipher = ConcurrentHashMap<String, String>()

    // 按 (keyIdentity, plaintext) 缓存最近一次验证通过的密文体（不含 enc:v1: 前缀），
    // 使未改动的敏感字段在写回时复用原密文，保持迁移与重复保存幂等并避免全量字段进 TEE。
    private val verifiedCipherByPlaintext = ConcurrentHashMap<String, String>()

    // 记录内层 enc:v1: 解密失败的字段（key = "$providerId|$fieldPath" -> 原始 "enc:v1:..." 密文），
    // 确保单字段解不开时标记 degraded 且禁止空串覆盖磁盘原密文。
    private val degradedFieldsBySlot = ConcurrentHashMap<String, String>()

    private fun cacheKey(key: SecretKey, token: String): String =
        "${System.identityHashCode(key)}:$token"

    private fun slotKey(ownerId: String, fieldPath: String): String =
        "$ownerId|$fieldPath"

    private fun putCache(key: SecretKey, rawCipher: String, plaintext: String) {
        if (verifiedPlaintextByCipher.size >= MAX_CACHE_ENTRIES) {
            verifiedPlaintextByCipher.clear()
        }
        if (verifiedCipherByPlaintext.size >= MAX_CACHE_ENTRIES) {
            verifiedCipherByPlaintext.clear()
        }
        verifiedPlaintextByCipher[cacheKey(key, rawCipher)] = plaintext
        verifiedCipherByPlaintext[cacheKey(key, plaintext)] = rawCipher
    }

    /** 加密明文，返回 Base64(IV + ciphertext)。 */
    fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        val result = Base64.getEncoder().encodeToString(cipher.iv + encrypted)
        putCache(key, result, plaintext)
        return result
    }

    /**
     * 解密密文（兼容带或不带 `enc:v1:` 前缀）。失败返回 null（不抛异常）。
     */
    fun decrypt(ciphertext: String): String? = runCatching {
        val cleanCipher = ciphertext.removePrefix(ENC_V1_PREFIX)
        val key = getOrCreateKey()
        val cacheKeyStr = cacheKey(key, cleanCipher)
        verifiedPlaintextByCipher[cacheKeyStr]?.let { return@runCatching it }
        val bytes = Base64.getDecoder().decode(cleanCipher)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val plaintext = cipher.doFinal(encrypted).decodeToString()
        putCache(key, cleanCipher, plaintext)
        plaintext
    }.onFailure { e ->
        // 解密失败属高价值排查线索, 必须留痕并区分失败类别
        android.util.Log.w(
            "ProviderCredentialCipher",
            "provider 凭证解密失败(${e.javaClass.simpleName}): ${e.message}",
        )
    }.getOrNull()

    /**
     * 字段级加密（返回 `enc:v1:<Base64>`）：
     * - 已带 `enc:v1:` 前缀（含 degraded 原密文）：原样返回，禁止二次加密或覆盖；
     * - 空串：若该槽位存在未修复的 degraded 原密文，保留原密文禁止回填空串；否则返回空串；
     * - `$$` 凭证引用名：非秘密引用名，原样明文保存；
     * - 普通明文：若命中当前密钥的幂等缓存则复用原密文，否则执行 AES-GCM 加密并加 `enc:v1:` 前缀。
     */
    fun encryptField(
        plaintext: String,
        ownerId: String = "",
        fieldPath: String = "",
    ): String {
        if (plaintext.startsWith(ENC_V1_PREFIX)) {
            return plaintext
        }
        val slot = if (ownerId.isNotEmpty() && fieldPath.isNotEmpty()) slotKey(ownerId, fieldPath) else null
        if (plaintext.isEmpty()) {
            if (slot != null) {
                degradedFieldsBySlot[slot]?.let { lockedCipher ->
                    return lockedCipher
                }
            }
            return ""
        }
        if (plaintext.startsWith(VAULT_REF_PREFIX)) {
            if (slot != null) degradedFieldsBySlot.remove(slot)
            return plaintext
        }
        if (slot != null) degradedFieldsBySlot.remove(slot)
        val cachedCipher = runCatching {
            val key = getOrCreateKey()
            verifiedCipherByPlaintext[cacheKey(key, plaintext)]
        }.getOrNull()
        if (cachedCipher != null) {
            return ENC_V1_PREFIX + cachedCipher
        }
        return ENC_V1_PREFIX + encrypt(plaintext)
    }

    /**
     * 字段级解密：
     * - 无 `enc:v1:` 前缀（历史明文或 `$$` 引用）：原样返回；
     * - 带 `enc:v1:` 前缀且解密成功：清除 degraded 记录并返回明文；
     * - 带 `enc:v1:` 前缀但解密失败：记录 degraded 状态并**保留原始 `enc:v1:...` 密文**，
     *   严禁静默回退为空串，防止后续保存时把磁盘原密文覆盖丢失。
     */
    fun decryptField(
        raw: String,
        ownerId: String = "",
        fieldPath: String = "",
    ): String {
        val slot = if (ownerId.isNotEmpty() && fieldPath.isNotEmpty()) slotKey(ownerId, fieldPath) else null
        if (!raw.startsWith(ENC_V1_PREFIX)) {
            if (slot != null) degradedFieldsBySlot.remove(slot)
            return raw
        }
        val body = raw.removePrefix(ENC_V1_PREFIX)
        val decrypted = if (body.isNotEmpty()) decrypt(body) else null
        if (decrypted != null) {
            if (slot != null) degradedFieldsBySlot.remove(slot)
            return decrypted
        }
        if (slot != null) {
            degradedFieldsBySlot[slot] = raw
        }
        android.util.Log.w(
            "ProviderCredentialCipher",
            "provider($ownerId) 字段 $fieldPath 解密失败，已标记 degraded 并保留原密文禁止覆盖",
        )
        return raw
    }

    fun recordDegradedSlot(ownerId: String, fieldPath: String, rawCiphertext: String) {
        if (ownerId.isNotEmpty() && fieldPath.isNotEmpty() && rawCiphertext.startsWith(ENC_V1_PREFIX)) {
            degradedFieldsBySlot[slotKey(ownerId, fieldPath)] = rawCiphertext
        }
    }


    fun clearDegradedStateForTest() {
        degradedFieldsBySlot.clear()
    }

    /** 判断存量字符串是否已是当前有效密钥加密的密文（命中缓存时 O(1) 无密码学开销）。 */
    fun isValidCiphertext(raw: String?): Boolean =
        !raw.isNullOrBlank() && decrypt(raw) != null

    private fun getOrCreateKey(): SecretKey = KeystoreAesKey.getOrCreate(KEY_ALIAS)
}
