package me.rerere.rikkahub.skills

/**
 * Phase 19E — pure merge logic for the skill store.
 *
 * The store shows one flat list assembled from the bundled (local) catalog plus every
 * enabled remote source. Rules, all enforced here so they are JVM-testable:
 *
 *  1. **Local wins.** On a name collision the local entry is kept; the remote one is
 *     dropped (first-seen wins among remotes, in source order).
 *  2. **Remote `is_bundled` is forced false.** A remote catalog that copies our format
 *     and flags entries as bundled must not redirect installs to a missing local asset.
 *  3. **Provenance is stamped** on remote entries (`sourceId`/`sourceName`) so the UI can
 *     show where each entry came from; local entries keep null provenance.
 *  4. **Failed sources contribute nothing** — their entries simply do not appear, and
 *     their status is surfaced via [MergedSkillCatalog.sourceStatuses].
 */

/** Fetch outcome for one remote source. */
enum class SkillStoreSourceStatus {
    /** Fresh fetch + parse succeeded. */
    OK,

    /** Fetch failed but a cached copy parsed — entries shown, staleness signalled. */
    OK_CACHED,

    /** Fetch failed and no usable cache — source contributes no entries. */
    FAILED,
}

/** One remote source's fetch result, fed into [mergeCatalogs]. */
data class RemoteCatalogResult(
    val source: SkillStoreSource,
    val catalog: SkillCatalog?,
    val status: SkillStoreSourceStatus,
)

/** The merged store view: flat entry list + per-source fetch status. */
data class MergedSkillCatalog(
    val entries: List<CatalogEntry> = emptyList(),
    val sourceStatuses: Map<String, SkillStoreSourceStatus> = emptyMap(),
) {
    /** Status of a remote source by id; null for unknown ids. */
    fun statusOf(sourceId: String): SkillStoreSourceStatus? = sourceStatuses[sourceId]
}

/**
 * Merge the local catalog with remote fetch results. Pure function — no IO, no Android.
 *
 * @param local the bundled catalog (may be empty on a parse failure)
 * @param remoteResults one result per enabled source, in display order
 */
fun mergeCatalogs(
    local: SkillCatalog,
    remoteResults: List<RemoteCatalogResult>,
): MergedSkillCatalog {
    val entries = mutableListOf<CatalogEntry>()
    val seen = mutableSetOf<String>()
    for (entry in local.skills) {
        if (seen.add(entry.name)) {
            entries += entry
        }
    }
    val statuses = LinkedHashMap<String, SkillStoreSourceStatus>()
    for (result in remoteResults) {
        statuses[result.source.id] = result.status
        val catalog = result.catalog ?: continue
        for (entry in catalog.skills) {
            if (!seen.add(entry.name)) continue
            entries +=
                entry.copy(
                    isBundled = false,
                    sourceId = result.source.id,
                    sourceName = result.source.name,
                )
        }
    }
    return MergedSkillCatalog(entries = entries, sourceStatuses = statuses)
}
