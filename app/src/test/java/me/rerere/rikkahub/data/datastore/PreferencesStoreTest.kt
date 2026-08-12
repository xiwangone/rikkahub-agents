package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesStoreTest {
    @Test
    fun `compression target defaults to one percent of the active context`() {
        val settings = Settings()

        assertEquals(3_720, settings.getContextCompactionTargetTokens(372_000))
    }

    @Test
    fun `compression target uses a safe fallback without model metadata`() {
        val settings = Settings(contextCompactionTargetTokensK = null)

        assertEquals(2_000, settings.getContextCompactionTargetTokens(null))
        assertEquals(2_000, settings.getContextCompactionTargetTokens(0))
    }

    @Test
    fun `token threshold supplies context metadata for the one percent default`() {
        val settings = Settings(
            contextCompactionTargetTokensK = null,
            autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(372_000, settings.getCompactionContextLength(null))
        assertEquals(3_720, settings.getContextCompactionTargetTokens(
            settings.getCompactionContextLength(null),
        ))
    }

    @Test
    fun `explicit compression target overrides the fixed default`() {
        val settings = Settings(contextCompactionTargetTokensK = 30)

        assertEquals(30_000, settings.getContextCompactionTargetTokens(372_000))
    }

    /**
     * #36: `subAgents` is a new field on [Settings]. An install that predates it wrote
     * JSON with no "subAgents" key at all - reproduced here by stripping the key from a fresh
     * encode - and it MUST still decode, defaulting to an empty list, the same way the
     * `mcpServers` / `assistants` list fields already do.
     */
    @Test
    fun `settings decodes when the subAgents key is absent, as in a pre-existing install`() {
        val fullJson = JsonInstant.encodeToString(Settings())
        val withoutSubAgents = JsonObject(
            JsonInstant.parseToJsonElement(fullJson).jsonObject.filterKeys { it != "subAgents" }
        )

        val decoded = JsonInstant.decodeFromString<Settings>(withoutSubAgents.toString())

        assertTrue(decoded.subAgents.isEmpty())
    }
}
