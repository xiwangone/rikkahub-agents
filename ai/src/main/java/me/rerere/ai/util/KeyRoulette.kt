package me.rerere.ai.util

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "KeyRoulette"

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
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

    /** 展开单个 token；非引用或未命中原样返回。 */
    fun expand(token: String): String {
        if (!token.startsWith(PREFIX)) return token
        val fn = resolve ?: return token
        return fn(token.removePrefix(PREFIX)) ?: token
    }
}

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        // 引用展开（`$$名` → 真实值）：未注入解析器时原样通过
        .map { ProviderKeyRefs.expand(it) }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

// In-memory mirror of the last known persisted state, guarded by LruFileLock. There
// can be one LruKeyRoulette instance per provider (Claude, OpenAI, Google, ...), all
// sharing the same on-disk file, so this has to be shared at file scope — not a field
// on the class — for next() calls on different instances to stay read-after-write
// consistent with each other without re-reading the file on every call.
private var memoryCache: LruCache? = null

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
        if (keyList.isEmpty()) return keys

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
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "loadCache: failed to read LRU key cache, starting empty", e)
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (e: Exception) {
            Log.w(TAG, "saveCache: failed to persist LRU key cache", e)
        }
    }
}
