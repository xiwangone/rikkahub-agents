package me.rerere.rikkahub.skills

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.log.AppLog

/**
 * Phase 19E — orchestrates store loading: read enabled sources, fetch each one
 * concurrently, merge on top of the bundled catalog.
 *
 * The merge is a pure function of ([SkillCatalogMerger.mergeCatalogs]), so all the
 * interesting rules are unit-tested there; this class only wires IO.
 */
class SkillStoreRepository(
    private val sourceStore: SkillStoreSourceStore,
    private val fetcher: RemoteCatalogFetcher,
) {
    /**
     * Load the merged store view. Network failures never throw — failed sources simply
     * contribute no entries (their status is in [MergedSkillCatalog.sourceStatuses]).
     *
     * @param localCatalog the bundled catalog (parsed once from assets by the caller)
     */
    suspend fun load(localCatalog: SkillCatalog): MergedSkillCatalog {
        val sources = sourceStore.sources.first().filter { it.enabled }
        AppLog.i("SkillStoreRepository", "load: ${sources.size} enabled source(s): ${sources.map { it.url }}")
        val results =
            coroutineScope {
                sources
                    .map { source -> async { fetcher.fetch(source) } }
                    .awaitAll()
            }
        return mergeCatalogs(localCatalog, results)
    }
}
