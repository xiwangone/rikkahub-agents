package me.rerere.rikkahub.skills

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID

/**
 * Phase 19E — a user-configured remote skill-store source.
 *
 * A source is just a URL pointing at a catalog JSON document in the same shape as the
 * bundled `assets/skill-catalog.json`. Sources are typed by hand in Settings, persisted
 * in a dedicated DataStore so they survive reinstalls and never collide with the main
 * settings file.
 */
@Serializable
data class SkillStoreSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

/** Dedicated small DataStore: only the user's skill-store source list. */
private val Context.skillStoreDataStore by preferencesDataStore(name = "skill_store_sources")

/**
 * CRUD + persistence for [SkillStoreSource]. Mirrors the `RemotePlatformStore` pattern:
 * a JSON-encoded list under a single preference key, read-modify-write on every edit.
 *
 * URL normalisation lives in [normalizeUrl] as a pure function so it can be JVM-tested
 * without an Android Context.
 */
class SkillStoreSourceStore(
    private val appContext: Context,
) {
    private val dataStore = appContext.skillStoreDataStore

    /** All sources, in insertion order. Never null — empty list when nothing is saved. */
    val sources: Flow<List<SkillStoreSource>> =
        dataStore.data.map { prefs ->
            parseSources(prefs[KEY_SOURCES])
        }

    /**
     * Add a source. Returns the entry that now represents this URL, or null when the
     * URL is unusable (see [normalizeUrl]).
     *
     * Dedup by normalised URL: re-adding an existing (possibly disabled) source
     * re-enables it in place instead of creating a duplicate entry.
     */
    suspend fun add(
        name: String,
        url: String,
    ): SkillStoreSource? {
        val normalized = normalizeUrl(url) ?: return null
        val label = name.trim().ifBlank { deriveName(normalized) }
        runCatching {
            dataStore.edit { prefs ->
                val current = parseSources(prefs[KEY_SOURCES])
                val existingIdx = current.indexOfFirst { normalizeUrl(it.url) == normalized }
                val updated =
                    when {
                        existingIdx >= 0 ->
                            current.toMutableList().also {
                                it[existingIdx] = it[existingIdx].copy(enabled = true)
                            }

                        current.size >= MAX_SOURCES -> return@edit

                        else -> current + SkillStoreSource(name = label, url = normalized)
                    }
                prefs[KEY_SOURCES] = encodeSources(updated)
            }
        }.onFailure { return null }
        return parseSources(dataStore.data.first()[KEY_SOURCES])
            .firstOrNull { normalizeUrl(it.url) == normalized }
    }

    /** Drop a source by id. No-op when the id is unknown. */
    suspend fun remove(id: String) {
        runCatching {
            dataStore.edit { prefs ->
                val updated = parseSources(prefs[KEY_SOURCES]).filterNot { it.id == id }
                prefs[KEY_SOURCES] = encodeSources(updated)
            }
        }
    }

    /** Toggle a source. Unknown id is a no-op. */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        runCatching {
            dataStore.edit { prefs ->
                val updated =
                    parseSources(prefs[KEY_SOURCES]).map {
                        if (it.id == id) it.copy(enabled = enabled) else it
                    }
                prefs[KEY_SOURCES] = encodeSources(updated)
            }
        }
    }

    companion object {
        private val KEY_SOURCES = stringPreferencesKey("sources")
        private const val MAX_SOURCES = 20

        /**
         * Normalise a user-typed store URL. Accepts bare hosts (`example.com/catalog.json`
         * → `https://example.com/catalog.json`); rejects anything that is not http(s)
         * after normalisation. Returns null when unusable.
         */
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(trimmed)?.groupValues?.get(1)
            return when {
                // Bare host — assume https.
                scheme == null -> "https://$trimmed"
                scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) -> trimmed
                // Any other scheme (ftp:, file:, javascript: …) is rejected outright.
                else -> null
            }
        }

        /** Derive a display name from the URL host (scheme / userinfo / port / path stripped). */
        fun deriveName(url: String): String =
            url
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringAfterLast('@') // strip userinfo if present
                .substringBefore(':') // strip port
                .ifBlank { "store" }

        /** Encode the source list for DataStore. Empty list encodes as `[]`. */
        fun encodeSources(sources: List<SkillStoreSource>): String =
            JsonInstant.encodeToString(kotlinx.serialization.builtins.ListSerializer(SkillStoreSource.serializer()), sources)

        /** Parse the persisted list; empty on any malformed payload (never throws). */
        fun parseSources(raw: String?): List<SkillStoreSource> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                JsonInstant.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(SkillStoreSource.serializer()),
                    raw,
                )
            }.getOrDefault(emptyList())
        }
    }
}
