package me.rerere.rikkahub.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SkillStoreSourceStore]'s companion helpers (URL normalisation,
 * display-name derivation, list serialisation). The DataStore glue itself needs an
 * Android Context and is covered on-device.
 */
class SkillStoreSourceTest {

    @Test fun `bare host gets https scheme`() {
        assertEquals("https://example.com/catalog.json", SkillStoreSourceStore.normalizeUrl("example.com/catalog.json"))
    }

    @Test fun `explicit http scheme is preserved`() {
        assertEquals("http://lan.local/catalog.json", SkillStoreSourceStore.normalizeUrl("http://lan.local/catalog.json"))
    }

    @Test fun `whitespace is trimmed`() {
        assertEquals("https://a.example/c.json", SkillStoreSourceStore.normalizeUrl("  a.example/c.json  "))
    }

    @Test fun `blank url is rejected`() {
        assertNull(SkillStoreSourceStore.normalizeUrl("   "))
        assertNull(SkillStoreSourceStore.normalizeUrl(""))
    }

    @Test fun `non-http scheme is rejected`() {
        assertNull(SkillStoreSourceStore.normalizeUrl("ftp://example.com/catalog.json"))
        assertNull(SkillStoreSourceStore.normalizeUrl("file:///etc/catalog.json"))
    }

    @Test fun `deriveName takes the host`() {
        assertEquals("example.com", SkillStoreSourceStore.deriveName("https://example.com/catalog.json"))
        assertEquals("lan.local", SkillStoreSourceStore.deriveName("http://lan.local:8080/c.json"))
    }

    @Test fun `deriveName strips userinfo`() {
        assertEquals("example.com", SkillStoreSourceStore.deriveName("https://user:pw@example.com/catalog.json"))
    }

    @Test fun `encode and parse round-trip`() {
        val sources =
            listOf(
                SkillStoreSource(id = "id-1", name = "One", url = "https://one.example/c.json", enabled = false),
                SkillStoreSource(id = "id-2", name = "Two", url = "https://two.example/c.json"),
            )
        val raw = SkillStoreSourceStore.encodeSources(sources)
        assertEquals(sources, SkillStoreSourceStore.parseSources(raw))
    }

    @Test fun `parse of malformed payload returns empty list`() {
        assertTrue(SkillStoreSourceStore.parseSources("{ not json").isEmpty())
        assertTrue(SkillStoreSourceStore.parseSources(null).isEmpty())
        assertTrue(SkillStoreSourceStore.parseSources("").isEmpty())
    }

    @Test fun `parse of wrong-shape payload returns empty list`() {
        // A JSON object instead of an array — decode fails, must not throw.
        assertTrue(SkillStoreSourceStore.parseSources("""{"id":"x","name":"n","url":"u"}""").isEmpty())
    }
}
