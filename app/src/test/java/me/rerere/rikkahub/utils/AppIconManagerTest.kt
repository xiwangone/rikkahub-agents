package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconManagerTest {
    @Test
    fun `presets have unique ids, alias class names, and expected order`() {
        val entries = AppIconPreset.entries
        assertEquals(9, entries.size)
        assertEquals(AppIconPreset.YAO_DIAMOND, entries.first())
        assertEquals(
            listOf(
                AppIconPreset.YAO_DIAMOND,
                AppIconPreset.YAO_AURA,
                AppIconPreset.YAO_PRISM,
            ),
            entries.take(3),
        )
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
    fun `fromIdOrNull normalizes case, hyphens, default alias, and rejects retired ids`() {
        assertEquals(AppIconPreset.YAO_DIAMOND, AppIconPreset.fromIdOrNull("default"))
        assertEquals(AppIconPreset.YAO_AURA, AppIconPreset.fromIdOrNull("yao_aura"))
        assertEquals(AppIconPreset.YAO_PRISM, AppIconPreset.fromIdOrNull("YAO-PRISM"))
        assertEquals(AppIconPreset.RIKKAHUB_CLASSIC, AppIconPreset.fromIdOrNull("rikkahub-classic"))
        assertNull(AppIconPreset.fromIdOrNull("flow_yao"))
        assertNull(AppIconPreset.fromIdOrNull("minimal_yao"))
        assertNull(AppIconPreset.fromIdOrNull("obsidian_rabbit"))
        assertNull(AppIconPreset.fromIdOrNull(""))
        assertNull(AppIconPreset.fromIdOrNull("unknown_icon"))
    }

    @Test
    fun `lenient serializer falls back to default for retired preset ids`() {
        assertEquals(AppIconPreset.YAO_DIAMOND, Json.decodeFromString<AppIconPreset>("\"flow_yao\""))
        assertEquals(AppIconPreset.YAO_DIAMOND, Json.decodeFromString<AppIconPreset>("\"minimal_yao\""))
        assertEquals(AppIconPreset.YAO_DIAMOND, Json.decodeFromString<AppIconPreset>("\"obsidian_rabbit\""))
        assertEquals(AppIconPreset.YAO_PRISM, Json.decodeFromString<AppIconPreset>("\"yao_prism\""))
        assertEquals("\"yao_diamond\"", Json.encodeToString(AppIconPreset.YAO_DIAMOND))
    }

    @Test
    fun `resolvePreset handles exact, alias, ambiguous, and unknown queries`() {
        assertEquals(
            AppIconResolution.Found(AppIconPreset.YAO_DIAMOND),
            AppIconManager.resolvePreset("default"),
        )
        assertEquals(
            AppIconResolution.Found(AppIconPreset.YAO_AURA),
            AppIconManager.resolvePreset("b1"),
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
        assertTrue(matches.containsAll(listOf("yao_diamond", "yao_aura", "yao_prism", "legacy_yao")))

        val notFound = AppIconManager.resolvePreset("nonexistent")
        assertTrue(notFound is AppIconResolution.NotFound)
        assertEquals(9, (notFound as AppIconResolution.NotFound).availableIds.size)
    }

    @Test
    fun `terminal and rabbit presets use non-upside-down tailored loading styles`() {
        assertEquals(AppIconLoadingStyle.TERMINAL_CURSOR_BLINK, AppIconPreset.TERMINAL_CLI.loadingStyle)
        assertEquals(AppIconLoadingStyle.RABBIT_HOP_TILT, AppIconPreset.RIKKAHUB_CLASSIC.loadingStyle)
        assertEquals(AppIconLoadingStyle.RABBIT_HOP_TILT, AppIconPreset.RIKKAHUB_OUTLINE.loadingStyle)
        assertEquals(AppIconLoadingStyle.CHAT_FLOAT_ROCK, AppIconPreset.DUAL_CHAT.loadingStyle)
    }
}
