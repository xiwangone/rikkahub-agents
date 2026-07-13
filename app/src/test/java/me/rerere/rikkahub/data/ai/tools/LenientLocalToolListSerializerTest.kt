package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the upstream-backup restore path: an assistant config exported by a build
 * with a different tool set (e.g. upstream RikkaHub, which has `screen_time`) must not abort the
 * whole settings restore. [LenientLocalToolListSerializer] drops tool types this build does not
 * define while keeping the known ones, and leaves encoding untouched.
 */
class LenientLocalToolListSerializerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun decode_dropsUnknownToolTypes_keepsKnownOnesInOrder() {
        // `screen_time` is an upstream-only tool the fork removed; the rest are fork tools.
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"time_info"},{"type":"tts"},{"type":"screen_time"},{"type":"ask_user"}]""",
        )
        assertEquals(
            listOf(LocalToolOption.TimeInfo, LocalToolOption.Tts, LocalToolOption.AskUser),
            decoded,
        )
    }

    @Test
    fun decode_allUnknown_yieldsEmptyListNotCrash() {
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"screen_time"},{"type":"some_future_tool"}]""",
        )
        assertEquals(emptyList<LocalToolOption>(), decoded)
    }

    @Test
    fun encode_matchesStrictListSerializer() {
        val tools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard)
        val lenient = json.encodeToString(LenientLocalToolListSerializer, tools)
        val strict = json.encodeToString(ListSerializer(LocalToolOption.serializer()), tools)
        assertEquals(strict, lenient)
    }
}
