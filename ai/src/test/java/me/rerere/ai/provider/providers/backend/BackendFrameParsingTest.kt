package me.rerere.ai.provider.providers.backend

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * serve 推送帧的字段契约。
 *
 * 帧文本取自接入服务端 /events 的原始 SSE 抓取，
 * 用于固化字段位置：思考正文在 reasoning 帧的 text 字段（不是 reasoning 字段），
 * 完整思考只在收尾的 message 帧的 reasoning 字段出现。
 */
class BackendFrameParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): SseEvent = json.decodeFromString<SseEvent>(raw)

    @Test
    fun reasoningFrameCarriesBodyInTextField() {
        val e = parse("""{"kind":"reasoning","messageId":"m1","seq":16,"status":"in_progress","text":"用户"}""")
        assertEquals("reasoning", e.kind)
        assertEquals("用户", e.text)
        assertNull("reasoning 帧不带 reasoning 字段，正文在 text", e.reasoning)
    }

    @Test
    fun textFrameCarriesBodyInTextField() {
        val e = parse("""{"kind":"text","messageId":"m1","seq":20,"status":"in_progress","text":"ok"}""")
        assertEquals("text", e.kind)
        assertEquals("ok", e.text)
    }

    @Test
    fun messageFrameCarriesFullTextAndFullReasoning() {
        val e =
            parse(
                """{"kind":"message","messageId":"m1","seq":22,"status":"in_progress",""" +
                    """"text":"ok","reasoning":"用户要求回复恰好 \"ok\"。直接回复。"}"""
            )
        assertEquals("ok", e.text)
        assertEquals("用户要求回复恰好 \"ok\"。直接回复。", e.reasoning)
    }

    @Test
    fun phaseIsDeliveredAsTurnPhaseFrame() {
        val e =
            parse(
                """{"kind":"turn_phase","seq":13,"status":"in_progress",""" +
                    """"text":"working","phase":"working"}"""
            )
        assertEquals("turn_phase", e.kind)
        assertEquals("working", e.text)
    }

    @Test
    fun askRequestFrameExposesStructuredQuestions() {
        val e =
            parse(
                """{"kind":"ask_request","promptId":"1","promptKind":"ask","status":"waiting_user",""" +
                    """"ask":{"id":"1","questions":[{"id":"q1","header":"选择",""" +
                    """"prompt":"请选择 A 还是 B？","options":[{"label":"A"},{"label":"B"}]}],""" +
                    """"turnId":"t1"},"itemId":"1"}"""
            )
        assertEquals("ask_request", e.kind)
        val ask = e.ask
        assertEquals("1", ask?.id)
        assertEquals(1, ask?.questions?.size)
        assertEquals("请选择 A 还是 B？", ask?.questions?.first()?.prompt)
        assertEquals(listOf("A", "B"), ask?.questions?.first()?.options?.map { it.label })
    }

    @Test
    fun toolDispatchFrameCarriesNameAndJsonArgs() {
        val e =
            parse(
                """{"kind":"tool_dispatch","seq":37,"status":"in_progress",""" +
                    """"tool":{"runState":"pending","id":"call_1","name":"ask",""" +
                    """"args":"{\"questions\": [{\"header\": \"选择\"}]}"}}"""
            )
        assertEquals("tool_dispatch", e.kind)
        assertEquals("ask", e.tool?.name)
        assertEquals("""{"questions": [{"header": "选择"}]}""", e.tool?.args)
    }
}
