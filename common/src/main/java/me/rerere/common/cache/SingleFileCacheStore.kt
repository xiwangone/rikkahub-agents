package me.rerere.common.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.common.log.AppLogger
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "SingleFileCacheStore"

class SingleFileCacheStore<K : Any, V : Any>(
    private val file: File,
    private val keySerializer: KSerializer<K>,
    private val valueSerializer: KSerializer<V>,
    private val json: Json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
        },
) : CacheStore<K, V> {
    private val lock = ReentrantLock()
    private val entrySerializer = cacheEntrySerializer(valueSerializer)
    private val mapEntrySerializer = MapSerializer(keySerializer, entrySerializer)

    override fun loadEntry(key: K): CacheEntry<V>? =
        lock.withLock {
            val all = safeReadMap()
            all[key]
        }

    override fun saveEntry(
        key: K,
        entry: CacheEntry<V>,
    ) = lock.withLock {
        val all = safeReadMap().toMutableMap()
        all[key] = entry
        safeWriteMap(all)
    }

    override fun remove(key: K) =
        lock.withLock {
            val all = safeReadMap().toMutableMap()
            if (all.remove(key) != null) {
                safeWriteMap(all)
            }
        }

    override fun clear() =
        lock.withLock {
            if (file.exists()) {
                if (!file.delete()) {
                    safeWriteMap(emptyMap())
                }
            }
        }

    override fun loadAllEntries(): Map<K, CacheEntry<V>> = lock.withLock { safeReadMap() }

    override fun keys(): Set<K> = lock.withLock { safeReadMap().keys }

    private fun safeReadMap(): Map<K, CacheEntry<V>> {
        val text =
            try {
                if (!file.exists()) return emptyMap()
                file.readText()
            } catch (ioError: java.io.IOException) {
                AppLogger.w(TAG, "Failed to read cache file, returning empty map: ${file.path}", ioError)
                return emptyMap()
            }
        if (text.isBlank()) return emptyMap()
        return try {
            json.decodeFromString(mapEntrySerializer, text)
        } catch (decodeError: IllegalArgumentException) {
            try {
                val legacyMap = json.decodeFromString(MapSerializer(keySerializer, valueSerializer), text)
                legacyMap.mapValues { CacheEntry(value = it.value, expiresAt = null) }
            } catch (legacyError: IllegalArgumentException) {
                legacyError.addSuppressed(decodeError)
                AppLogger.w(TAG, "Cache file is corrupt, returning empty map: ${file.path}", legacyError)
                emptyMap()
            }
        }
    }

    private fun safeWriteMap(map: Map<K, CacheEntry<V>>) {
        ensureParentDir(file)
        val text = json.encodeToString(mapEntrySerializer, map)
        atomicWrite(file, text)
    }
}
