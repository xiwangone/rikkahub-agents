package me.rerere.rikkahub.skills

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [parseSkillCatalogJson] and the bundled catalog asset. The Android
 * `loadCatalogFromAssets(Context)` wrapper is exercised on-device.
 */
class SkillCatalogTest {

    private val bundledJson = """
        {
          "version": 1,
          "updated_at": "2026-05-07",
          "skills": [
            {
              "name": "auto-reply",
              "title": "Auto-reply",
              "description": "Reply to a contact's notifications via the messaging app on screen.",
              "category": "automation",
              "license": "Apache-2.0",
              "size_kb": 4,
              "compatibility": "any",
              "is_bundled": true
            },
            {
              "name": "morning-briefing",
              "title": "Morning Briefing",
              "description": "Reads weather, calendar, and unread email at wake-up.",
              "category": "productivity",
              "license": "Apache-2.0",
              "size_kb": 4,
              "compatibility": "any",
              "is_bundled": true
            },
            {
              "name": "smart-forward",
              "title": "Smart Forward",
              "description": "Catch a notification, summarise, forward to another contact.",
              "category": "automation",
              "license": "Apache-2.0",
              "size_kb": 4,
              "compatibility": "any",
              "is_bundled": true
            },
            {
              "name": "notification-summarise-and-act",
              "title": "Notification Summary",
              "description": "Read recent notifications, group by app, summarise, suggest actions.",
              "category": "productivity",
              "license": "Apache-2.0",
              "size_kb": 5,
              "compatibility": "any",
              "is_bundled": true
            }
          ]
        }
    """.trimIndent()

    @Test fun `bundled catalog parses to 4 entries with correct fields`() {
        val catalog = parseSkillCatalogJson(bundledJson)
        assertEquals(1, catalog.version)
        assertEquals("2026-05-07", catalog.updatedAt)
        assertEquals(4, catalog.skills.size)
        val first = catalog.skills.first()
        assertEquals("auto-reply", first.name)
        assertEquals("Auto-reply", first.title)
        assertEquals("automation", first.category)
        assertEquals(4, first.sizeKb)
        assertEquals(true, first.isBundled)
        assertNull("bundled skills should not declare a sourceUrl", first.sourceUrl)
    }

    @Test fun `missing optional fields fall back to defaults`() {
        val sparseJson = """
            {
              "version": 1,
              "skills": [
                {
                  "name": "minimal",
                  "title": "Minimal",
                  "description": "barebones entry"
                }
              ]
            }
        """.trimIndent()
        val catalog = parseSkillCatalogJson(sparseJson)
        assertEquals(1, catalog.skills.size)
        val e = catalog.skills.first()
        assertEquals("minimal", e.name)
        assertEquals("uncategorised", e.category)
        assertEquals("", e.license)
        assertEquals(0, e.sizeKb)
        assertEquals("any", e.compatibility)
        assertEquals(false, e.isBundled)
        // updated_at also has a default ("") since it's missing in the sparse JSON.
        assertEquals("", catalog.updatedAt)
    }

    @Test fun `malformed JSON returns empty catalog`() {
        val malformed = """{ "version": 1, "skills": [ { "name": ] }"""
        val catalog = parseSkillCatalogJson(malformed)
        assertEquals(0, catalog.version)
        assertTrue(catalog.skills.isEmpty())
    }

    @Test fun `entry with non-bundled source url parses`() {
        val withRemote = """
            {
              "version": 1,
              "skills": [
                {
                  "name": "remote-skill",
                  "title": "Remote",
                  "description": "fetched from URL",
                  "category": "demo",
                  "source_url": "https://example.com/SKILL.md"
                }
              ]
            }
        """.trimIndent()
        val catalog = parseSkillCatalogJson(withRemote)
        val e = catalog.skills.first()
        assertNotNull(e.sourceUrl)
        assertEquals("https://example.com/SKILL.md", e.sourceUrl)
        assertEquals(false, e.isBundled)
    }

    @Test fun `unknown fields ignored at top level and entry level`() {
        val withExtras = """
            {
              "version": 1,
              "ignored_top_level": "anything",
              "skills": [
                {
                  "name": "x",
                  "title": "X",
                  "description": "desc",
                  "ignored_entry_level": 42
                }
              ]
            }
        """.trimIndent()
        val catalog = parseSkillCatalogJson(withExtras)
        assertEquals(1, catalog.skills.size)
        assertEquals("x", catalog.skills.first().name)
    }

    @Test fun `bundled asset catalog lists 15 entries including the three core skills`() {
        // Guards catalog/asset drift: every directory under assets/default-skills
        // must have a matching catalog entry, and vice versa for bundled entries.
        // Runs with the module dir as working dir (same pattern as ModelsDevAssetTest).
        val raw = File("src/main/assets/skill-catalog.json").readText()
        val catalog = parseSkillCatalogJson(raw)
        assertEquals(15, catalog.skills.size)
        val byName = catalog.skills.associateBy { it.name }
        assertEquals("no duplicate entry names", catalog.skills.size, byName.size)

        val agentCore = requireNotNull(byName["agent-core"])
        assertEquals("智能体核心", agentCore.title)
        assertEquals("core", agentCore.category)
        assertEquals(100, agentCore.sizeKb)
        assertTrue(agentCore.isBundled)
        assertEquals(false, agentCore.archived)

        val autonomous = requireNotNull(byName["autonomous-agent"])
        assertEquals("自主智能体", autonomous.title)
        assertEquals("core", autonomous.category)
        assertEquals(14, autonomous.sizeKb)
        assertTrue(autonomous.isBundled)
        assertEquals(false, autonomous.archived)

        val converter = requireNotNull(byName["openclaw-converter"])
        assertEquals("OpenClaw 转换", converter.title)
        assertEquals("automation", converter.category)
        assertEquals(4, converter.sizeKb)
        assertTrue(converter.isBundled)
        assertEquals(false, converter.archived)
    }
}
