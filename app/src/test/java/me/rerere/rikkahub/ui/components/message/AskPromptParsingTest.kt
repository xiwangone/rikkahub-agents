package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.AskOption
import me.rerere.ai.ui.AskQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提问卡的解析层：两个来源（本地工具参数 / 服务端提问帧）都要归一到同一中性模型，
 * 渲染层只认模型 —— 这是「接入与原生拆分、抽公共基建」的契约面，用单测钉住。
 */
class AskPromptParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── 本地工具参数 ──

    @Test
    fun parsesToolArgsWithOptionsAndSelectionType() {
        val args =
            json.parseToJsonElement(
                """{"questions":[{"id":"q1","question":"选哪个？",""" +
                    """"options":["A","B"],"selection_type":"single"}]}"""
            )
        val result = parseAskToolQuestions(args)
        assertEquals(1, result.size)
        assertEquals("q1", result[0].id)
        assertEquals("选哪个？", result[0].question)
        assertEquals(listOf("A", "B"), result[0].options)
        assertEquals("single", result[0].selectionType)
    }

    @Test
    fun toolArgsWithoutSelectionTypeDefaultsToText() {
        val args = json.parseToJsonElement("""{"questions":[{"id":"q1","question":"说点什么"}]}""")
        assertEquals("text", parseAskToolQuestions(args)[0].selectionType)
    }

    @Test
    fun malformedToolArgsYieldsEmpty() {
        assertTrue(parseAskToolQuestions(json.parseToJsonElement("""{"other":1}""")).isEmpty())
        assertTrue(parseAskToolQuestions(json.parseToJsonElement("\"not an object\"")).isEmpty())
    }

    // ── 服务端提问帧 ──

    @Test
    fun parsesBackendQuestionWithOptionsAsSingle() {
        val result =
            parseBackendAskQuestions(
                listOf(
                    AskQuestion(
                        id = "q1",
                        prompt = "请选择 A 还是 B？",
                        options = listOf(AskOption("A"), AskOption("B")),
                    )
                )
            )
        assertEquals("q1", result[0].id)
        assertEquals("请选择 A 还是 B？", result[0].question)
        assertEquals(listOf("A", "B"), result[0].options)
        assertEquals("single", result[0].selectionType)
    }

    @Test
    fun backendQuestionWithoutOptionsIsTextAndMultiFlagWins() {
        assertEquals(
            "text",
            parseBackendAskQuestions(listOf(AskQuestion(id = "q1", prompt = "自由回答")))
                .first()
                .selectionType,
        )
        assertEquals(
            "multi",
            parseBackendAskQuestions(
                    listOf(
                        AskQuestion(
                            id = "q1",
                            prompt = "多选",
                            multi = true,
                            options = listOf(AskOption("A"), AskOption("B")),
                        )
                    )
                )
                .first()
                .selectionType,
        )
    }

    // ── 作答汇总 ──

    @Test
    fun buildsAnswersPayloadWithQuestionIds() {
        val payload = buildAskAnswersJson(mapOf("q1" to "A", "q2" to "自由文本"))
        val obj = json.parseToJsonElement(payload)
        assertEquals(payload, obj.toString())
        assertTrue(payload.contains("\"answers\""))
        assertTrue(payload.contains("\"q1\""))
        assertTrue(payload.contains("\"A\""))
    }

    @Test
    fun emptyAnswersStillBuildsValidPayload() {
        assertEquals("""{"answers":{}}""", buildAskAnswersJson(emptyMap()))
    }
}
