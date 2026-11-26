package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconManagerTest {
    @Test
    fun `presets have unique ids, alias class names, and expected order`() {
        val entries = AppIconPreset.entries
        assertEquals(9, entries.size)
        assertEquals(AppIconPreset.FLOW_YAO, entries.first())
        assertEquals(
            listOf(
                AppIconPreset.LEGACY_YAO,
                AppIconPreset.RIKKAHUB_CLASSIC,
                AppIconPreset.RIKKAHUB_OUTLINE,
            ),
            entries.takeLast(3),
        )
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(entries.size, entries.map { it.aliasClassName }.toSet().size)
        assertTrue(entries.all { it.aliasClassName.startsWith(".MainActivity") })
    }

    @Test
    fun `fromIdOrNull normalizes case, hyphens, and default alias`() {
        assertEquals(AppIconPreset.FLOW_YAO, AppIconPreset.fromIdOrNull("default"))
        assertEquals(AppIconPreset.MINIMAL_YAO, AppIconPreset.fromIdOrNull("minimal_yao"))
        assertEquals(AppIconPreset.MINIMAL_YAO, AppIconPreset.fromIdOrNull("MINIMAL-YAO"))
        assertEquals(AppIconPreset.OBSIDIAN_RABBIT, AppIconPreset.fromIdOrNull(" obsidian-rabbit "))
        assertEquals(AppIconPreset.RIKKAHUB_CLASSIC, AppIconPreset.fromIdOrNull("rikkahub-classic"))
        assertNull(AppIconPreset.fromIdOrNull(""))
        assertNull(AppIconPreset.fromIdOrNull("unknown_icon"))
    }

    @Test
    fun `resolvePreset handles exact, alias, ambiguous, and unknown queries`() {
        assertEquals(
            AppIconResolution.Found(AppIconPreset.FLOW_YAO),
            AppIconManager.resolvePreset("default"),
        )
        assertEquals(
            AppIconResolution.Found(AppIconPreset.OBSIDIAN_RABBIT),
            AppIconManager.resolvePreset("obsidian"),
        )
        assertEquals(
            AppIconResolution.Found(AppIconPreset.TERMINAL_CLI),
            AppIconManager.resolvePreset("terminal"),
        )
        assertEquals(
            AppIconResolution.Found(AppIconPreset.AGENT_CORE),
            AppIconManager.resolvePreset(
                "Custom Label",
                localizedLabels = mapOf(AppIconPreset.AGENT_CORE to "Custom Label"),
            ),
        )

        val ambiguous = AppIconManager.resolvePreset("yao")
        assertTrue(ambiguous is AppIconResolution.Ambiguous)
        val matches = (ambiguous as AppIconResolution.Ambiguous).matches
        assertTrue(matches.containsAll(listOf("flow_yao", "minimal_yao", "legacy_yao")))

        val notFound = AppIconManager.resolvePreset("nonexistent")
        assertTrue(notFound is AppIconResolution.NotFound)
        assertEquals(9, (notFound as AppIconResolution.NotFound).availableIds.size)
    }
}
