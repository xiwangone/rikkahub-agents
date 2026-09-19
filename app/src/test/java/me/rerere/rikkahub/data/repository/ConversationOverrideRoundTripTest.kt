package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 会话级覆盖字段（工具白名单 / 工作区 / 生成步数）的「实体往返」测试。
 *
 * 【为何必须有】2026-09-20 装机实测的缺陷：子代理把这三项设在内存 [Conversation] 上，
 * `insertConversation` 落库后 `initializeConversation(id)` 会**按 id 重新加载**；而
 * ConversationEntity 少了这三列、映射也没带上 → 字段**静默丢失** → 权限收敛退化成
 * 「回显有、实效无」（探针实测：子代理声明 13 项只读集，实际拿到 139 个工具且写工具可用）。
 *
 * 这类 bug 只在真机行为里暴露，跑一遍「转实体再转回来」就能钉死映射完整性。
 */
class ConversationOverrideRoundTripTest {

    private fun conversation(
        toolScope: List<String>? = null,
        workspaceId: Uuid? = null,
        maxSteps: Int? = null,
    ) = Conversation.ofId(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        newConversation = true,
    ).copy(
        toolScopeOverride = toolScope,
        workspaceIdOverride = workspaceId,
        maxToolStepsOverride = maxSteps,
    )

    @Test
    fun sessionOverridesSurviveEntityRoundTrip() {
        val ws = Uuid.random()
        val scope = listOf("workspace_read_file", "web_fetch")
        val back = conversationEntityToConversationImpl(
            conversationToConversationEntityImpl(conversation(scope, ws, 7)),
            emptyList(),
        )
        assertEquals(scope, back.toolScopeOverride)
        assertEquals(ws, back.workspaceIdOverride)
        assertEquals(7, back.maxToolStepsOverride)
    }

    @Test
    fun unsetOverridesRoundTripToNull() {
        val back = conversationEntityToConversationImpl(
            conversationToConversationEntityImpl(conversation()),
            emptyList(),
        )
        assertNull(back.toolScopeOverride)
        assertNull(back.workspaceIdOverride)
        assertNull(back.maxToolStepsOverride)
    }

    @Test
    fun malformedToolScopeJsonDegradesToNull() {
        val entity = conversationToConversationEntityImpl(conversation(listOf("a"))).copy(
            toolScopeOverride = "{not json",
        )
        assertNull(conversationEntityToConversationImpl(entity, emptyList()).toolScopeOverride)
    }

    @Test
    fun nonPositiveMaxStepsMapsToNull() {
        val entity = conversationToConversationEntityImpl(conversation(maxSteps = 3)).copy(
            maxToolStepsOverride = 0,
        )
        assertNull(conversationEntityToConversationImpl(entity, emptyList()).maxToolStepsOverride)
    }
}
