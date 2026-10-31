package me.rerere.rikkahub.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AndroidKeyStore 里的 AES-256-GCM 应用级密钥 —— **统一创建入口**。
 *
 * 为什么集中：Codex / Grok / Gemini / Provider / Backup / SkillSecrets 六处原先各自复制了
 * 同一段 [KeyGenParameterSpec]（KEYSTORE + getKey + PURPOSE_ENCRYPT|DECRYPT + GCM + NO_PADDING
 * + 256 位）。将来调整密钥属性（如 `setUserAuthenticationRequired` / `setUnlockedDeviceRequired`
 * / 改 key size）必须改六处，漏一处就出现"同一个库里两套密钥策略"。
 *
 * ⚠ **只统一密钥创建，不要统一密文格式**：各处格式本来就不同
 * （`Base64(IV+ct)` 字符串 / 裸 `IV+ct` 字节 / 密文与 IV 分开存），改格式会让存量密文解不开。
 *
 * alias 由调用方保持原值传入，因此对既有数据完全透明：key 已存在时直接返回，不会重新生成。
 */
internal object KeystoreAesKey {

    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * 取指定 alias 的 AES 密钥；不存在则按统一属性生成。
     *
     * @param alias KeyStore 条目名（各调用方保持历史值，勿改名——改名等于换密钥，旧密文全废）
     */
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}
