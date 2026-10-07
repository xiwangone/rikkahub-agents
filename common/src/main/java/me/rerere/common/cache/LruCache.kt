package me.rerere.common.cache

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LruCache<K, V>(
    private val capacity: Int,
    private val store: CacheStore<K, V>,
    private val deleteOnEvict: Boolean = false,
    preloadFromStore: Boolean = false,
    private val expireAfterWriteMillis: Long? = null,
    /**
     * 存储层操作失败（加载 / 写 / 删）时的回调。
     *
     * 失败不抛是**有意设计**（缓存问题不该拖垮主流程），但不能全静默 —— 否则
     * 内存与持久化不一致无从感知。默认只累加计数（见 [storeFailureCount]），
     * 调用方可注入日志。
     */
    private val onStoreFailure: (op: String, cause: Exception) -> Unit = { _, _ -> },
) where K : Any {
    private val lock = ReentrantLock()

    private val storeFailures = AtomicLong()

    /** 存储层失败累计次数（配合 [onStoreFailure] 观察一致性风险）。 */
    fun storeFailureCount(): Long = storeFailures.get()

    private fun noteStoreFailure(
        op: String,
        cause: Exception,
    ) {
        storeFailures.incrementAndGet()
        onStoreFailure(op, cause)
    }

    private val map =
        object : LinkedHashMap<K, CacheEntry<V>>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>): Boolean {
                val shouldEvict = size > capacity
                if (shouldEvict) {
                    if (deleteOnEvict) {
                        try {
                            store.remove(eldest.key)
                        } catch (e: Exception) {
                            noteStoreFailure("evict", e)
                        }
                    }
                }
                return shouldEvict
            }
        }

    init {
        if (preloadFromStore) {
            try {
                val all = store.loadAllEntries()
                lock.withLock {
                    val now = now()
                    for ((k, entry) in all) {
                        if (!entry.isExpired(now)) {
                            map[k] = entry
                        } else {
                            runCatching { store.remove(k) }
                        }
                        if (map.size >= capacity) break
                    }
                }
            } catch (e: Exception) {
                noteStoreFailure("preload", e)
            }
        }
    }

    fun get(key: K): V? {
        lock.withLock {
            map[key]?.let { entry ->
                if (!entry.isExpired(now())) return entry.value
                map.remove(key)
            }
        }
        val entry = store.loadEntry(key)
        if (entry != null) {
            return if (!entry.isExpired(now())) {
                lock.withLock { map[key] = entry }
                entry.value
            } else {
                runCatching { store.remove(key) }
                null
            }
        }
        return null
    }

    fun put(
        key: K,
        value: V,
    ) = put(key, value, expireAfterWriteMillis)

    fun put(
        key: K,
        value: V,
        ttlMillis: Long?,
    ) {
        val entry = CacheEntry(value = value, expiresAt = ttlMillis?.let { now() + it })
        lock.withLock { map[key] = entry }
        try {
            store.saveEntry(key, entry)
        } catch (e: Exception) {
            noteStoreFailure("put", e)
        }
    }

    fun remove(key: K) {
        lock.withLock { map.remove(key) }
        try {
            store.remove(key)
        } catch (e: Exception) {
            noteStoreFailure("remove", e)
        }
    }

    fun clear() {
        lock.withLock { map.clear() }
        try {
            store.clear()
        } catch (e: Exception) {
            noteStoreFailure("clear", e)
        }
    }

    fun containsKey(key: K): Boolean {
        val inMem = lock.withLock { map[key]?.let { !it.isExpired(now()) } ?: false }
        if (inMem) return true
        val entry = store.loadEntry(key)
        if (entry != null && !entry.isExpired(now())) return true
        if (entry != null) runCatching { store.remove(key) }
        return false
    }

    fun size(): Int = lock.withLock { map.size }

    fun keysInMemory(): Set<K> = lock.withLock { map.filterValues { !it.isExpired(now()) }.keys.toSet() }
}

private fun now(): Long = System.currentTimeMillis()
