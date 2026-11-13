package me.rerere.rikkahub.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AndroidKeyStore 里的 HMAC-SHA256 应用级密钥 —— **统一创建入口**（与 [KeystoreAesKey] 同款做法，
 * 只负责密钥创建、不规定指纹格式）。
 *
 * 用途：凭证值指纹（`value_fp`）的密钥。无盐 SHA-256 指纹可被离线字典反推（低熵口令尤其
 * 如此）；改成设备级 HMAC 后，离线比对必须拿到本设备的密钥——密钥不出 KeyStore，
 * 换设备或清应用数据都会换密钥（旧指纹随之失效；指纹仅用于本地比对，无其他依赖）。
 *
 * alias 由调用方保持原值传入，因此对既有数据完全透明：key 已存在时直接返回，不会重新生成。
 */
internal object KeystoreHmacKey {

    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * 取指定 alias 的 HMAC 密钥；不存在则按统一属性生成。
     *
     * @param alias KeyStore 条目名（各调用方保持历史值，勿改名——改名等于换密钥，旧指纹全废）
     */
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generateKey()
        }
    }
}
