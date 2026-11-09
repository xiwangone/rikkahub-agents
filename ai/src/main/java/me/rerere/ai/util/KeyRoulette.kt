package me.rerere.ai.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.common.log.AppLogger

private const val TAG = "KeyRoulette"

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入。
         * 文件内容经 AES-256-GCM 整包加密（Base64(IV + 密文)），不含明文 key。
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

/**
 * provider 密钥「引用解析」钩子。
 *
 * provider 的 apiKey 允许写成 `$$凭证名`，由上层注入的解析器替换为真实值，
 * 使密钥不必以明文散落在配置里。
 *
 * - **未注入解析器时原样返回**，行为与从前完全一致（零风险）。
 * - 所有 provider 取 key 都经 [splitKey]，因此只需在该处解析即可全覆盖。
 * - 引用未命中（名字不存在/上层未提供）时**保留原样**，让配置错误显式暴露，
 *   而不是静默换成空值造成难排查的失败。
 *
 * 注意：`$$` 不是 shell 变量语法的一部分，仅在本层解析；解析结果不会写回配置。
 */
object ProviderKeyRefs {
    /** 引用前缀。 */
    const val PREFIX = "$$"

    /** 解析器：引用名 → 真实值；返回 null 表示未命中。 */
    @Volatile
    var resolve: ((String) -> String?)? = null

    /**
     * 展开通知：成功把 `$$引用` 换成真值时回调**凭证名**（供上层写「凭据取用」审计）。
     *
     * 约定：同步调用、**必须极轻**（只入队，不做 IO/DB）—— 它在 provider 取 key 的热路径上；
     * 未注入时零行为。未命中（名字不存在/无解析器）不回调。
     */
    @Volatile
    var onExpanded: ((String) -> Unit)? = null

    /** 展开单个 token；非引用或未命中原样返回。 */
    fun expand(token: String): String {
        if (!token.startsWith(PREFIX)) return token
        val fn = resolve ?: return token
        val name = token.removePrefix(PREFIX)
        val value = fn(name) ?: return token
        onExpanded?.invoke(name)
        return value
    }
}

private fun splitKey(key: String): List<String> {
    if (key.trimStart().startsWith("enc:v1:")) {
        throw IllegalStateException(
            "Provider API key is in degraded state (ciphertext could not be decrypted; original ciphertext is preserved). Please re-enter the API key in Settings.",
        )
    }
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .onEach { token ->
            if (token.startsWith("enc:v1:")) {
                throw IllegalStateException(
                    "Provider API key is in degraded state (ciphertext could not be decrypted; original ciphertext is preserved). Please re-enter the API key in Settings.",
                )
            }
        }
        // 引用展开（`$$名` → 真实值）：未注入解析器时原样通过
        .map { ProviderKeyRefs.expand(it) }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) {
            throw IllegalStateException("请先填写 API Key")
        }
        return keyList.random()
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 缓存文件内容：Base64(IV(12B) + AES-GCM 密文)，解密后是 JSON：Map<providerId, Map<apiKey, lastUsedTimestamp>>。
// 内存镜像仍以明文 key 为键——next() 必须把选中的明文 key 交给调用方，内存里无法避免；
// 落盘前整体加密，缓存文件里不出现明文 key。

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

private typealias LruCache = Map<String, Map<String, Long>>

// In-memory mirror of the last known persisted state, guarded by LruFileLock. There
// can be one LruKeyRoulette instance per provider (Claude, OpenAI, Google, ...), all
// sharing the same on-disk file, so this has to be shared at file scope — not a field
// on the class — for next() calls on different instances to stay read-after-write
// consistent with each other without re-reading the file on every call.
private var memoryCache: LruCache? = null

// 缓存文件加密参数：AES-256-GCM，密钥存 AndroidKeyStore，密文格式为 Base64(IV(12B) + 密文)。
// 密钥创建属性（用途、分组模式、填充、256 位）与仓内既有凭证加密用法一致；
// 每个调用方用独立 alias 是既有约定，本缓存用自己的 alias。
private const val LRU_KEY_ALIAS = "rikkahub_lru_key_roulette"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_SIZE = 12
private const val GCM_TAG_LENGTH = 128

// AndroidKeyStore 不可用时（例如 JVM 单测环境没有该 provider）的进程内回退密钥：
// 同为 AES-256 密钥，落盘内容同样是密文；密钥只存内存，进程结束即丢弃，
// 用它加密的缓存重启后解不开，只会丢弃轮询状态，不会回退到明文。
// 与 memoryCache 一样放在文件作用域：多个 provider 实例共用同一缓存文件，
// 必须共用同一把回退密钥，否则一个实例写的文件另一个实例解不开。
@Volatile
private var fallbackKey: SecretKey? = null

// next() used to write the LRU cache to disk synchronously on every call, blocking
// whichever thread built the request (streamText's callbackFlow body isn't always on
// Dispatchers.IO). The selection itself must stay synchronous — next() returns the
// chosen key immediately, callers need it right away — but the disk write doesn't need
// to happen before next() returns. Route it through a single-threaded executor so
// writes still land in call order (submission happens inside the synchronized block,
// i.e. in the same order calls to next() were serialized) without blocking next()
// itself on file I/O.
private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "LruKeyRoulette-writer").apply { isDaemon = true }
}

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) {
            throw IllegalStateException("请先填写 API Key")
        }

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = (memoryCache ?: loadCache()).toMutableMap()

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            memoryCache = allCache
            writeExecutor.execute { saveCache(allCache) }
            return selected
        }
    }

    private fun loadCache(): LruCache {
        try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            val content = file.readText().trim()
            if (content.isEmpty()) return emptyMap()
            if (content.startsWith("{")) {
                // 旧版明文 JSON：解析后沿用轮询状态，本次 next() 结束时 saveCache 会以密文重写，完成迁移。
                return try {
                    Json.decodeFromString(content)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "loadCache: legacy plaintext cache is unreadable, starting empty", e)
                    emptyMap()
                }
            }
            val plainJson = decryptCacheJson(content)
            if (plainJson == null) {
                AppLogger.w(TAG, "loadCache: failed to decrypt LRU key cache, starting empty")
                return emptyMap()
            }
            return try {
                Json.decodeFromString(plainJson)
            } catch (e: Exception) {
                AppLogger.w(TAG, "loadCache: decrypted cache is unreadable, starting empty", e)
                emptyMap()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "loadCache: failed to read LRU key cache, starting empty", e)
            return emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(encryptCacheJson(Json.encodeToString(cache)))
        } catch (e: Exception) {
            AppLogger.w(TAG, "saveCache: failed to persist LRU key cache", e)
        }
    }

    /** 取 AES-256-GCM 密钥：优先用 AndroidKeyStore，不可用时回退为进程内现生成的同规格密钥。 */
    private fun getOrCreateLruKey(): SecretKey {
        runCatching { loadOrCreateKeystoreKey() }
            .onFailure { e ->
                AppLogger.w(
                    TAG,
                    "getOrCreateLruKey: AndroidKeyStore unavailable, using ephemeral in-memory key",
                    e,
                )
            }
            .getOrNull()?.let { return it }
        synchronized(LruFileLock) {
            fallbackKey?.let { return it }
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { fallbackKey = it }
        }
    }

    /**
     * 取 AndroidKeyStore 里的 AES 密钥，不存在则按统一属性生成；任一步失败即抛异常，
     * 由调用方回退到进程内密钥。密钥本身不落盘、不进备份。
     */
    private fun loadOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(LRU_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    LRU_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** 把缓存 JSON 加密成 Base64(IV + 密文)；失败时抛异常，由调用方记日志。 */
    private fun encryptCacheJson(plainJson: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateLruKey())
        val encrypted = cipher.doFinal(plainJson.encodeToByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    /** 解密缓存文件内容；内容不是合法密文时返回 null（不抛异常）。 */
    private fun decryptCacheJson(encoded: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > GCM_IV_SIZE)
        val iv = bytes.copyOfRange(0, GCM_IV_SIZE)
        val encrypted = bytes.copyOfRange(GCM_IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateLruKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.doFinal(encrypted).decodeToString()
    }.getOrNull()
}
