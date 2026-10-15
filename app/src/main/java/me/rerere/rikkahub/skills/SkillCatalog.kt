package me.rerere.rikkahub.skills

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.log.AppLog

/**
 * Phase 19D — bundled "featured skills" catalog.
 *
 * The catalog is shipped as `assets/skill-catalog.json` and loaded once when the user
 * opens the catalog sheet. Parse errors are non-fatal: the catalog returns empty so the
 * UI still renders cleanly with a "no featured skills available" empty state.
 *
 * Phase 19E — the bundled catalog is now the default store source. User-configured
 * remote sources are fetched by `RemoteCatalogFetcher` and merged on top of it by
 * `mergeCatalogs` (local entries win on name collision).
 */
@Serializable
data class SkillCatalog(
    val version: Int = 0,
    @SerialName("updated_at")
    val updatedAt: String = "",
    val skills: List<CatalogEntry> = emptyList(),
)

/**
 * One entry in the featured catalog. [sourceUrl] is null for bundled-with-the-APK skills
 * (those in `assets/default-skills/`); installing a bundled entry re-seeds the pristine
 * copy from assets ("restore built-in version"). Non-null `sourceUrl` triggers a fetch
 * through [SkillUrlImporter.importFromUrl] when the user taps Install.
 *
 * Phase 19E — entries merged from a remote source carry [sourceId]/[sourceName] (null for
 * local/bundled entries), and a remote entry's `is_bundled` flag is always forced false at
 * merge time so a copied-over "bundled" claim can never redirect an install to a missing
 * asset. [archived] marks local entries whose files ship in the APK but are NOT seeded
 * automatically — the user installs them on demand from the store.
 */
@Serializable
data class CatalogEntry(
    val name: String,
    val title: String,
    val description: String,
    val category: String = "uncategorised",
    val license: String = "",
    @SerialName("size_kb")
    val sizeKb: Int = 0,
    val compatibility: String = "any",
    @SerialName("source_url")
    val sourceUrl: String? = null,
    @SerialName("is_bundled")
    val isBundled: Boolean = false,
    @SerialName("archived")
    val archived: Boolean = false,
    // Merge-time provenance; never present in on-disk JSON.
    val sourceId: String? = null,
    val sourceName: String? = null,
)

/**
 * Read + parse `assets/skill-catalog.json`. Empty + warn-logged on any failure.
 */
fun loadCatalogFromAssets(context: Context): SkillCatalog {
    val raw = runCatching {
        context.assets.open("skill-catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull() ?: run {
        AppLog.w("SkillCatalog", "skill-catalog.json missing from assets — returning empty catalog")
        return SkillCatalog()
    }
    return parseSkillCatalogJson(raw)
}

private val skillCatalogJson = Json { ignoreUnknownKeys = true }

/**
 * Pure-string parser. Public for JVM-test coverage that doesn't need an Android Context.
 * Returns an empty catalog on any malformed-JSON / unknown-shape input.
 */
fun parseSkillCatalogJson(raw: String): SkillCatalog {
    return runCatching {
        skillCatalogJson.decodeFromString(SkillCatalog.serializer(), raw)
    }.getOrElse { t ->
        runCatching { AppLog.w("SkillCatalog", "skill-catalog.json failed to parse", t) }
        SkillCatalog()
    }
}
