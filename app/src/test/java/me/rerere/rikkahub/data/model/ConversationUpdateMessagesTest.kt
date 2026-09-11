package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 覆盖 [Conversation.updateCurrentMessages] 的节点归属。
 *
 * 回归背景：请求链路上的 transformer（工作区提醒 / 时间提醒 / 提示词注入 / OCR）会在
 * 消息列表**头部插入合成消息**（均为新 id），使回写列表的下标与 messageNodes 的下标
 * 错位。若按 index 对应，同一节点的消息会被写到别的节点并被当作新消息追加，
 * 表现为同一节点内出现多余分支（UI 上的「2/2」）。
 *
 * 约定：回写时优先按消息 id 定位所属节点。
 */
class ConversationUpdateMessagesTest {

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessage(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `inflight updates keep one message per node`() {
        val first = userMessage("第一条")
        val second = userMessage("第二条")
        val conversation =
            Conversation(
                id = Uuid.random(),
                assistantId = Uuid.random(),
                messageNodes = listOf(first.toMessageNode(), second.toMessageNode()),
                newConversation = true,
            )

        // 模拟：回写列表里这两条消息仍在，但没有额外的合成消息 → 每节点保持 1 条
        val updated = conversation.updateCurrentMessages(listOf(first, second))

        assertEquals(2, updated.messageNodes.size)
        updated.messageNodes.forEach { node ->
            assertEquals("节点内不应出现重复分支", 1, node.messages.size)
        }
    }

    @Test
    fun `messages already present are matched by id not by index`() {
        val first = userMessage("第一条")
        val second = userMessage("第二条")
        val conversation =
            Conversation(
                id = Uuid.random(),
                assistantId = Uuid.random(),
                messageNodes = listOf(first.toMessageNode(), second.toMessageNode()),
                newConversation = true,
            )

        // 只回写「第二条」（模拟下标错位：它的 index=0，但应落到它自己的节点）
        val updated = conversation.updateCurrentMessages(listOf(second))

        // 仍应只有 2 个节点，且第一条节点未被污染（不含第一条的重复）
        assertEquals(2, updated.messageNodes.size)
        assertEquals(1, updated.messageNodes[0].messages.size)
        assertEquals(1, updated.messageNodes[1].messages.size)
        assertTrue(
            "第二条应仍在自己的节点内",
            updated.messageNodes[1].messages.any { it.id == second.id },
        )
    }

    @Test
    fun `new assistant message is appended to its own node`() {
        val first = userMessage("第一条")
        val reply = assistantMessage("回复")
        val conversation =
            Conversation(
                id = Uuid.random(),
                assistantId = Uuid.random(),
                messageNodes = listOf(first.toMessageNode()),
                newConversation = true,
            )

        // 回写列表比历史多一条（新回复）→ 追加为新节点
        val updated = conversation.updateCurrentMessages(listOf(first, reply))

        assertEquals(2, updated.messageNodes.size)
        updated.messageNodes.forEach { node -> assertEquals(1, node.messages.size) }
    }
}
