package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V2 migration: assistant preset message parts used to carry fully qualified type names.
 * Nested tool `output` arrays are migrated recursively; anything that is not a well formed
 * assistant array is returned verbatim (migration never destroys user data).
 */
class PreferenceStoreV2MigrationTest {
    private fun partsOf(json: String): JsonArray =
        JsonInstant.parseToJsonElement(json).jsonArray
            .single().jsonObject["presetMessages"]!!.jsonArray
            .single().jsonObject["parts"]!!.jsonArray

    @Test
    fun `fully qualified part types are rewritten to short keys`() {
        val raw = """
            [{"presetMessages":[{"parts":[
              {"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"hi"},
              {"type":"UIMessagePart.Image","url":"a"},
              {"type":"Tool","toolName":"t"}
            ]}]}]
        """.trimIndent()

        val parts = partsOf(migrateAssistantsJson(raw))

        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool", parts[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested tool output parts are migrated recursively`() {
        val raw = """
            [{"presetMessages":[{"parts":[
              {"type":"UIMessagePart.Tool","toolName":"t","output":[
                {"type":"me.rerere.ai.ui.UIMessagePart.Text","text":"nested"}
              ]}
            ]}]}]
        """.trimIndent()

        val tool = partsOf(migrateAssistantsJson(raw)).single().jsonObject
        val nested = tool["output"]!!.jsonArray.single().jsonObject

        assertEquals("tool", tool["type"]!!.jsonPrimitive.content)
        assertEquals("text", nested["type"]!!.jsonPrimitive.content)
        assertEquals("nested", nested["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `part content is preserved while type is rewritten`() {
        val raw = """[{"presetMessages":[{"parts":[{"type":"Text","text":"keep me"}]}]}]"""

        val part = partsOf(migrateAssistantsJson(raw)).single().jsonObject

        assertEquals("text", part["type"]!!.jsonPrimitive.content)
        assertEquals("keep me", part["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `already short types are returned as the original string`() {
        val raw = """[{"presetMessages":[{"parts":[{"type":"text","text":"hi"}]}]}]"""

        assertEquals(raw, migrateAssistantsJson(raw))
    }

    @Test
    fun `assistants without preset messages are returned as the original string`() {
        val raw = """[{"id":"a","name":"assistant"}]"""

        assertEquals(raw, migrateAssistantsJson(raw))
    }

    @Test
    fun `malformed json falls back to the original string`() {
        assertEquals("not-json", migrateAssistantsJson("not-json"))
    }

    @Test
    fun `json object instead of array falls back to the original string`() {
        val raw = """{"presetMessages":[{"parts":[{"type":"Text"}]}]}"""

        assertEquals(raw, migrateAssistantsJson(raw))
    }
}
