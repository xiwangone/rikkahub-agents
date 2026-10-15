package me.rerere.rikkahub.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [mergeCatalogs] — the store merge rules.
 */
class SkillCatalogMergerTest {

    private fun entry(
        name: String,
        bundled: Boolean = false,
        sourceUrl: String? = null,
    ) = CatalogEntry(name = name, title = name, description = "desc of $name", isBundled = bundled, sourceUrl = sourceUrl)

    private fun source(id: String, name: String = "src-$id") = SkillStoreSource(id = id, name = name, url = "https://$id.example/catalog.json")

    @Test fun `local entries come first and keep null provenance`() {
        val local = SkillCatalog(skills = listOf(entry("a", bundled = true), entry("b", bundled = true)))
        val merged = mergeCatalogs(local, emptyList())
        assertEquals(listOf("a", "b"), merged.entries.map { it.name })
        assertNull(merged.entries[0].sourceId)
        assertNull(merged.entries[0].sourceName)
        assertTrue(merged.sourceStatuses.isEmpty())
    }

    @Test fun `remote entries are appended with provenance stamped`() {
        val local = SkillCatalog(skills = listOf(entry("a", bundled = true)))
        val remote =
            RemoteCatalogResult(
                source = source("s1", "Community"),
                catalog = SkillCatalog(skills = listOf(entry("r1"))),
                status = SkillStoreSourceStatus.OK,
            )
        val merged = mergeCatalogs(local, listOf(remote))
        assertEquals(listOf("a", "r1"), merged.entries.map { it.name })
        val r1 = merged.entries.last()
        assertEquals("s1", r1.sourceId)
        assertEquals("Community", r1.sourceName)
        assertEquals(SkillStoreSourceStatus.OK, merged.statusOf("s1"))
    }

    @Test fun `local wins on name collision`() {
        val local = SkillCatalog(skills = listOf(entry("dup", bundled = true)))
        val remote =
            RemoteCatalogResult(
                source = source("s1"),
                catalog = SkillCatalog(skills = listOf(entry("dup"))),
                status = SkillStoreSourceStatus.OK,
            )
        val merged = mergeCatalogs(local, listOf(remote))
        assertEquals(1, merged.entries.size)
        val only = merged.entries.single()
        assertEquals("dup", only.name)
        assertTrue("local entry must survive", only.isBundled)
        assertNull(only.sourceId)
    }

    @Test fun `first remote wins among remotes on collision`() {
        val local = SkillCatalog()
        val r1 =
            RemoteCatalogResult(
                source = source("s1", "First"),
                catalog = SkillCatalog(skills = listOf(entry("x"))),
                status = SkillStoreSourceStatus.OK,
            )
        val r2 =
            RemoteCatalogResult(
                source = source("s2", "Second"),
                catalog = SkillCatalog(skills = listOf(entry("x"))),
                status = SkillStoreSourceStatus.OK,
            )
        val merged = mergeCatalogs(local, listOf(r1, r2))
        assertEquals(listOf("x"), merged.entries.map { it.name })
        assertEquals("First", merged.entries.single().sourceName)
    }

    @Test fun `remote is_bundled claim is forced false`() {
        val local = SkillCatalog()
        val remote =
            RemoteCatalogResult(
                source = source("s1"),
                catalog = SkillCatalog(skills = listOf(entry("liar", bundled = true))),
                status = SkillStoreSourceStatus.OK,
            )
        val merged = mergeCatalogs(local, listOf(remote))
        val liar = merged.entries.single()
        assertFalse("a remote entry must never claim bundled", liar.isBundled)
    }

    @Test fun `failed source contributes nothing but still reports status`() {
        val local = SkillCatalog(skills = listOf(entry("a", bundled = true)))
        val failed =
            RemoteCatalogResult(source = source("s1"), catalog = null, status = SkillStoreSourceStatus.FAILED)
        val merged = mergeCatalogs(local, listOf(failed))
        assertEquals(listOf("a"), merged.entries.map { it.name })
        assertEquals(SkillStoreSourceStatus.FAILED, merged.statusOf("s1"))
    }

    @Test fun `cached source contributes entries with OK_CACHED status`() {
        val local = SkillCatalog()
        val cached =
            RemoteCatalogResult(
                source = source("s1"),
                catalog = SkillCatalog(skills = listOf(entry("c1"))),
                status = SkillStoreSourceStatus.OK_CACHED,
            )
        val merged = mergeCatalogs(local, listOf(cached))
        assertEquals(listOf("c1"), merged.entries.map { it.name })
        assertEquals(SkillStoreSourceStatus.OK_CACHED, merged.statusOf("s1"))
    }

    @Test fun `local duplicates collapse to first occurrence`() {
        val local = SkillCatalog(skills = listOf(entry("a", bundled = true), entry("a", bundled = true)))
        val merged = mergeCatalogs(local, emptyList())
        assertEquals(1, merged.entries.size)
    }

    @Test fun `statusOf unknown id returns null`() {
        val merged = mergeCatalogs(SkillCatalog(), emptyList())
        assertNull(merged.statusOf("nope"))
    }
}
