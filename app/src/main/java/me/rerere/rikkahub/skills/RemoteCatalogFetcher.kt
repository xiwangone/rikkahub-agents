package me.rerere.rikkahub.skills

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 19E — fetch one remote skill-store catalog.
 *
 * Guard policy is deliberately looser than [me.rerere.rikkahub.skills.SkillUrlImporter]'s:
 * only the scheme is checked (http/https). Loopback / private-range hosts ARE allowed
 * because a self-hosted catalog on the LAN is a first-class use case — the user typed
 * the URL themselves in Settings, unlike a skill body URL the LLM might compose.
 *
 * Caching: the last good JSON body is written to `cacheDir/skill-store-catalog/<id>.json`.
 * On a fetch or parse failure the cache is replayed as [SkillStoreSourceStatus.OK_CACHED]
 * so the store still shows entries when the network is down; only a cold source with no
 * cache degrades to [SkillStoreSourceStatus.FAILED].
 */
class RemoteCatalogFetcher(
    private val appContext: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private companion object {
        const val TAG = "RemoteCatalogFetcher"
        const val MAX_BODY_BYTES = 1L * 1024 * 1024 // 1 MB cap — a catalog is metadata only
        const val CACHE_DIR = "skill-store-catalog"

        /** Top-level-ish default client. Must live here (not on the instance) so the
         *  constructor's default-parameter expression can call it before <this> exists. */
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
    }

    private val cacheDir: File
        get() = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }

    /**
     * Fetch + parse the catalog for [source]. Never throws — failures are reported via
     * the returned [RemoteCatalogResult.status].
     */
    @Suppress("TooGenericExceptionCaught") // 有意兜底：单个源取不到只影响自己，不应打断整表刷新
    suspend fun fetch(source: SkillStoreSource): RemoteCatalogResult =
        withContext(Dispatchers.IO) {
            val normalized = SkillStoreSourceStore.normalizeUrl(source.url)
            if (normalized == null) {
                AppLog.w(TAG, "source ${source.id} has an unusable URL: ${source.url}")
                return@withContext RemoteCatalogResult(source, null, SkillStoreSourceStatus.FAILED)
            }
            val body =
                try {
                    download(normalized)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "fetch failed for $normalized", t)
                    null
                }
            if (body != null) {
                val catalog = parseSkillCatalogJson(body)
                if (catalog.skills.isNotEmpty()) {
                    writeCache(source.id, body)
                    return@withContext RemoteCatalogResult(source, catalog, SkillStoreSourceStatus.OK)
                }
                // A well-formed but empty catalog is a legitimate (if useless) source;
                // do not poison the cache with it, and do not treat it as a failure.
                AppLog.w(TAG, "catalog at $normalized parsed but contains no skills")
                return@withContext RemoteCatalogResult(source, catalog, SkillStoreSourceStatus.OK)
            }
            val cached = readCache(source.id)
            if (cached != null) {
                val catalog = parseSkillCatalogJson(cached)
                if (catalog.skills.isNotEmpty()) {
                    return@withContext RemoteCatalogResult(source, catalog, SkillStoreSourceStatus.OK_CACHED)
                }
            }
            RemoteCatalogResult(source, null, SkillStoreSourceStatus.FAILED)
        }

    /** Drop a source's cached body (called when the user removes a source). */
    fun invalidateCache(sourceId: String) {
        runCatching { File(cacheDir, "$sourceId.json").delete() }
    }

    private fun download(url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w(TAG, "non-200 for $url: ${response.code}")
                return null
            }
            val body = response.body ?: return null
            val source = body.source()
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) {
                AppLog.w(TAG, "catalog at $url exceeds the $MAX_BODY_BYTES byte cap")
                return null
            }
            return source.buffer.readUtf8()
        }
    }

    private fun writeCache(
        sourceId: String,
        body: String,
    ) {
        runCatching { File(cacheDir, "$sourceId.json").writeText(body) }
            .onFailure { AppLog.w(TAG, "cache write failed for $sourceId", it) }
    }

    private fun readCache(sourceId: String): String? {
        val file = File(cacheDir, "$sourceId.json")
        if (!file.isFile) return null
        return runCatching { file.readText() }
            .getOrElse {
                AppLog.w(TAG, "cache read failed for $sourceId", it)
                null
            }
    }
}
