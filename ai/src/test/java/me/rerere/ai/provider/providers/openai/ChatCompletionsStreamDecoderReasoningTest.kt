package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * XML tool_call 文本兜底与 reasoning 通道的交互（纯 JVM，不依赖 Android / Robolectric）。
 *
 * 兜底只应接管 content：同一 chunk 的 reasoning_content 必须照常下发。
 * 标签名一律拼接而成，与 XmlToolCallParser 的约定一致（源码里不留下可匹配的完整标签）。
 */
class ChatCompletionsStreamDecoderReasoningTest {
    private val openTag = "<" + "tool_" + "call"
    private val closeTag = "<" + "/" + "tool_" + "call" + ">"

    /** 用一个 SSE 行喂解码器：content 传 [JsonNull] 表示字段存在但为 null，传 null 表示字段缺失 */
    private fun delta(content: JsonElement?, reasoning: String? = null): String {
        val message = buildJsonObject {
            reasoning?.let { put("reasoning_content", it) }
            content?.let { put("content", it) }
        }
        return """{"id":"resp-1","choices":[{"index":0,"delta":$message}]}"""
    }

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private fun decode(lines: List<String>): List<StreamChunk> {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = lines.flatMap { decoder.accept(SseEvent(data = it)).chunks }
        return chunks + decoder.onClosed()
    }

    private fun List<StreamChunk>.reasoningText(): String =
        filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text }

    private fun List<StreamChunk>.bodyText(): String =
        filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

    @Test
    fun `reasoning survives the xml fallback once pendingXml has residue`() {
        val chunks = decode(
            listOf(
                delta(text(""), "让我"),
                delta(text("<t")),                       // 最小触发：< + tool_call 前缀 -> 残留 2 字符
                delta(text(""), "配置项"),                 // 残留期内，修复前整块 reasoning 被吞
                delta(null, "已生效"),                    // content 字段缺失 -> 仍走原路径
            ),
        )

        assertEquals("让我配置项已生效", chunks.reasoningText())
        assertEquals("<t", chunks.bodyText())
    }

    @Test
    fun `reasoning is not swallowed while an unclosed tool call block stays pending`() {
        val unclosed = openTag + "\n" + """{"name":"f"}"""
        val chunks = decode(
            listOf(
                delta(text(unclosed)),
                delta(text(""), "整段思考内容大约二十个字符"),
                delta(text(""), "，一直吞到流结束"),
                delta(text("，正文收尾")),
            ),
        )

        // 开标签命中但未闭合时 remainder 从标签起无上限，修复前这段 reasoning 全灭
        assertEquals("整段思考内容大约二十个字符，一直吞到流结束", chunks.reasoningText())
        assertEquals(unclosed + "，正文收尾", chunks.bodyText())
    }

    @Test
    fun `xml tool call parsing is unchanged by the reasoning fix`() {
        val block = "先看 " + openTag + "\n" + """{"name":"f","input":{}}""" + closeTag
        val chunks = decode(listOf(delta(text(block), "顺带的思考")))

        assertEquals("f", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().toolName)
        assertEquals("{}", chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta)
        assertEquals("先看 ", chunks.bodyText())
        assertEquals("顺带的思考", chunks.reasoningText())
    }

    @Test
    fun `merged reasoning part keeps its head after the fallback has been armed`() {
        val handler = StreamChunkHandler(Model(modelId = "fixture-model"))
        var messages = listOf(UIMessage.user("fixture input"))
        val decoder = ChatCompletionsStreamDecoder()

        listOf(
            delta(text(""), "让我想想"),
            delta(text("<t")),
            delta(text(""), "这段开头不能丢"),
            delta(null, "以及后续"),
        ).forEach { line ->
            decoder.accept(SseEvent(data = line)).chunks.forEach { messages = handler.handle(messages, it) }
        }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }

        val reasoning = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(1, reasoning.size)
        // 落库形态：修复前该 part 是 "让我想想以及后续"
        assertEquals("让我想想这段开头不能丢以及后续", reasoning.single().reasoning)
        assertEquals("<t", messages.last().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }

    @Test
    fun `content tail still waits for the next delta and is flushed exactly once`() {
        val chunks = decode(
            listOf(
                delta(text("<t")),
                delta(text("ool 部署完成")),
            ),
        )

        // remainder 语义未变：正文末尾最多 8 字符延迟，下一次或流末尾刷出，不丢不重
        assertEquals("<tool 部署完成", chunks.bodyText())
    }

    @Test
    fun `explicit null content field does not arm the fallback and keeps reasoning flowing`() {
        val chunks = decode(
            listOf(
                delta(text("<t")),
                delta(JsonNull, "null 字段不该被吞"),
                delta(null, "字段缺失也不该被吞"),
            ),
        )

        assertEquals("null 字段不该被吞字段缺失也不该被吞", chunks.reasoningText())
        assertEquals("<t", chunks.bodyText())
    }

    @Test
    fun `plain deltas without any tag prefix keep the original path untouched`() {
        val chunks = decode(
            listOf(
                delta(text(""), "思考"),
                delta(text("正文")),
                delta(text("收尾")),
            ),
        )

        assertEquals("思考", chunks.reasoningText())
        assertEquals("正文收尾", chunks.bodyText())
        assertEquals(1, chunks.count { it is StreamChunk.Finish })
    }
}
