package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the legacy-backup restore path: an assistant config exported by a build
 * with a different tool set (e.g. a build that had a tool this build no longer defines) must not abort the
 * whole settings restore. [LenientLocalToolListSerializer] drops tool types this build does not
 * define while keeping the known ones, and leaves encoding untouched.
 */
class LenientLocalToolListSerializerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun decode_dropsUnknownToolTypes_keepsKnownOnesInOrder() {
        // `legacy_removed_tool` 在任何 build 里都不存在 —— 用它当“本 build 未定义的工具类型”的样本。
        // （曾用 `screen_time` 当样本，后来本 build 新增了该工具 → 样本变已知，测试就挂了。）
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"time_info"},{"type":"tts"},{"type":"legacy_removed_tool"},{"type":"ask_user"}]""",
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
            """[{"type":"legacy_removed_tool"},{"type":"some_future_tool"}]""",
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

    @Test
    fun `web_extract is dropped while web_fetch survives, rest of the list stays in order`() {
        // web_extract is no longer a defined subtype (the capability lives in the global web
        // settings), while web_fetch is still a per-assistant option. An assistant persisted by
        // an older build may carry both; the restore must not abort, must drop only the unknown
        // one, and must keep the known entries in their original order.
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"time_info"},{"type":"web_fetch"},{"type":"web_extract"},{"type":"ask_user"}]""",
        )

        assertEquals(
            listOf(LocalToolOption.TimeInfo, LocalToolOption.WebFetch, LocalToolOption.AskUser),
            decoded,
        )
    }

    @Test
    fun `settings containing only removed tools decode to an empty list`() {
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"web_extract"},{"type":"some_removed_tool"}]""",
        )

        assertEquals(emptyList<LocalToolOption>(), decoded)
    }
}
