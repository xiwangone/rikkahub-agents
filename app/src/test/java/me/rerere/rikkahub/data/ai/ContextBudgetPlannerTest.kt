package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContextBudgetPlannerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `reported usage is used as baseline for trailing messages`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("prior response")),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
            UIMessage.user("x".repeat(300)),
        )

        assertEquals(6_108, ContextBudgetPlanner.estimateInputTokens(messages))
    }

    @Test
    fun `tool output appended after provider usage is included in the next request estimate`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "read_file",
                        input = "{\"path\":\"notes.txt\"}",
                        output = listOf(UIMessagePart.Text("x".repeat(3_000))),
                    ),
                ),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
        )

        assertEquals(7_000, ContextBudgetPlanner.estimateInputTokens(messages))
    }

    @Test
    fun `context estimate ignores pre compaction provider usage`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("x".repeat(300))),
                usage = TokenUsage(promptTokens = 10_000, completionTokens = 100, totalTokens = 10_100),
            ),
        )

        assertEquals(108, ContextBudgetPlanner.estimateContextTokens(messages))
    }

    @Test
    fun `context estimate conservatively counts CJK tool output`() {
        val messages = listOf(UIMessage.user("文".repeat(300)))

        assertEquals(308, ContextBudgetPlanner.estimateContextTokens(messages))
    }

    @Test
    fun `plan triggers at configured percentage`() {
        val below = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
            UIMessage.user("x".repeat(300)),
        )
        val above = below.dropLast(1) + UIMessage.user("x".repeat(1_200))

        assertFalse(ContextBudgetPlanner.plan(below, 8_000, 80).shouldCompact)
        assertTrue(ContextBudgetPlanner.plan(above, 8_000, 80).shouldCompact)
        assertEquals(6_400, ContextBudgetPlanner.plan(above, 8_000, 80).triggerTokens)
        assertEquals(400, ContextBudgetPlanner.plan(above, 8_000, 5).triggerTokens)
    }

    @Test
    fun `plan supports a manual token threshold in thousands`() {
        val messages = listOf(UIMessage.user("x".repeat(36_000)))

        val plan = ContextBudgetPlanner.plan(
            messages = messages,
            contextLength = 128_000,
            thresholdPercent = 80,
            thresholdTokensK = 12,
        )

        assertEquals(12_000, plan.triggerTokens)
        assertTrue(plan.shouldCompact)
    }

    @Test
    fun `plan reserves output and prompt budget before triggering`() {
        val plan = ContextBudgetPlanner.plan(
            messages = listOf(UIMessage.user("x".repeat(24_000))),
            contextLength = 10_000,
            thresholdPercent = 95,
            reservedTokens = 3_000,
        )

        assertEquals(7_000, plan.triggerTokens)
        assertTrue(plan.shouldCompact)
    }

    @Test
    fun `tail selection keeps minimum recent messages`() {
        val messages = List(6) { UIMessage.user("x".repeat(300)) }

        assertEquals(
            2,
            ContextBudgetPlanner.chooseTailStartIndex(
                messages = messages,
                targetTokens = 250,
                minimumRecentMessages = 4,
            ),
        )
    }

    // ---- Document 正文估算（附件解耦：预算与实际发送对齐）----

    private fun documentOf(file: File) = UIMessagePart.Document(
        url = "file://" + file.absolutePath,
        fileName = "same-name.txt",
        mime = "text/plain",
    )

    private fun textFileOfSize(name: String, bytes: Int): File =
        tempFolder.newFile(name).apply { writeText("x".repeat(bytes)) }

    @Test
    fun `document body estimate grows monotonically with file size, not with the file name`() {
        val small = textFileOfSize("doc-small.txt", 300)
        val medium = textFileOfSize("doc-medium.txt", 3_000)
        val large = textFileOfSize("doc-large.txt", 30_000)

        fun contextEstimate(file: File): Int = ContextBudgetPlanner.estimateContextTokens(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(documentOf(file)),
                ),
            ),
        )

        val smallTokens = contextEstimate(small)
        val mediumTokens = contextEstimate(medium)
        val largeTokens = contextEstimate(large)

        // 三个附件同名（same-name.txt）：旧口径下估算应完全相同；新口径必须随大小单调上升。
        assertTrue("$smallTokens < $mediumTokens", smallTokens < mediumTokens)
        assertTrue("$mediumTokens < $largeTokens", mediumTokens < largeTokens)
        // 300B/3 = 100 token 正文；30_000B/3 = 10_000 token 但被封顶到默认 8_000。
        // 另含：消息开销 8 + MEDIA 1_024 + 文件名 "same-name.txt"(13 ASCII => ceil(13/3) = 5)。
        assertEquals(8 + 1_024 + 5 + 100, smallTokens)
        assertEquals(8 + 1_024 + 5 + 8_000, largeTokens)
    }

    @Test
    fun `document body estimate is capped by the inline threshold`() {
        val file = textFileOfSize("doc-cap.txt", 30_000)
        val document = documentOf(file)

        // 30_000 字节 / 3 = 10_000 token，封顶到 8_000（= DisplaySetting 默认内联阈值）
        assertEquals(8_000L, ContextBudgetPlanner.estimateDocumentBodyTokens(document, 8_000))
        // 阈值放大后恢复按文件大小估
        assertEquals(10_000L, ContextBudgetPlanner.estimateDocumentBodyTokens(document, 50_000))
        // 阈值 0/负值不崩（按 >=0 收敛）
        assertEquals(0L, ContextBudgetPlanner.estimateDocumentBodyTokens(document, 0))
    }

    @Test
    fun `document without a readable local file contributes no body tokens`() {
        assertEquals(
            0L,
            ContextBudgetPlanner.estimateDocumentBodyTokens(
                UIMessagePart.Document(url = "file:///nonexistent/upload/missing.txt", fileName = "missing.txt"),
                8_000,
            ),
        )
        assertEquals(
            0L,
            ContextBudgetPlanner.estimateDocumentBodyTokens(
                UIMessagePart.Document(url = "content://media/123", fileName = "picked.txt"),
                8_000,
            ),
        )
    }

    @Test
    fun `document body tokens flow into the message estimate`() {
        val file = textFileOfSize("doc-flow.txt", 9_000)
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(documentOf(file)),
        )

        // 9_000 字节 / 3 = 3_000 token 正文；+ MEDIA 1_024 + 文件名 "same-name.txt"(13 ASCII => 5)
        // + 消息开销 8 = 4_037。
        assertEquals(4_037L, ContextBudgetPlanner.estimateMessageTokens(message))
    }
}
