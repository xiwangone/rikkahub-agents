package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * 折叠语义回归覆盖：[isConversationListItemVisible]。
 *
 * 背景：列表链若在生成分组标题之前按折叠态过滤，被折叠分组内无存活项 →
 * 标题不生成 → 整组连标题一起消失，且没有可点击的标题（折叠不可逆、会话被永久隐藏）。
 * 因此这里固定两条不变量：**分组标题恒保留**、**置顶会话不参与日期折叠**。
 */
class ConversationListItemVisibilityTest {

    private val today = LocalDate.of(2026, 9, 20)
    private val yesterday = LocalDate.of(2026, 9, 19)
    private val todayKey = today.toString()
    private val yesterdayKey = yesterday.toString()

    private fun conversation(
        date: LocalDate,
        pinned: Boolean = false,
        subAgentRun: Boolean = false,
    ) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "t",
        messageNodes = emptyList(),
        isPinned = pinned,
        updateAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        isSubAgentRun = subAgentRun,
    )

    private fun item(
        date: LocalDate,
        pinned: Boolean = false,
        subAgentRun: Boolean = false,
    ) = ConversationListItem.Item(conversation(date, pinned, subAgentRun))

    private fun visible(
        entry: ConversationListItem,
        subAgentExpanded: Boolean = false,
        collapsedDates: Set<String> = emptySet(),
    ) = isConversationListItemVisible(entry, subAgentExpanded, collapsedDates)

    // ---- 折叠日期分组：组内会话隐藏，标题必须留下 ----

    /** 标题消失 = 折叠不可逆，故即使该日期已被折叠，标题也必须可见。 */
    @Test
    fun dateHeaderSurvivesItsGroupBeingCollapsed() {
        assertTrue(
            visible(ConversationListItem.DateHeader(today, "今天"), collapsedDates = setOf(todayKey)),
        )
    }

    @Test
    fun conversationsOfACollapsedDateAreHidden() {
        assertFalse(visible(item(today), collapsedDates = setOf(todayKey)))
    }

    @Test
    fun conversationsOfAnExpandedDateStayVisible() {
        // 折叠的是 19 号，20 号的会话不受影响。
        assertTrue(visible(item(today), collapsedDates = setOf(yesterdayKey)))
        // 折叠的是 20 号，19 号同样保留（互不串扰）。
        assertTrue(visible(item(yesterday), collapsedDates = setOf(todayKey)))
    }

    // ---- 子代理分组：标题必须留下，组内会话跟随展开态 ----

    @Test
    fun subAgentHeaderIsAlwaysVisible() {
        assertTrue(visible(ConversationListItem.SubAgentHeader))
        assertTrue(visible(ConversationListItem.SubAgentHeader, subAgentExpanded = true))
    }

    @Test
    fun subAgentRunsFollowTheGroupExpansionState() {
        assertFalse(visible(item(today, subAgentRun = true)))
        assertTrue(visible(item(today, subAgentRun = true), subAgentExpanded = true))
    }

    /** 子代理会话由自己的分组折叠态决定，不受日期折叠影响。 */
    @Test
    fun subAgentRunsIgnoreDateCollapsing() {
        assertTrue(
            visible(
                item(today, subAgentRun = true),
                subAgentExpanded = true,
                collapsedDates = setOf(todayKey),
            ),
        )
    }

    // ---- 置顶：标题是 PinnedHeader（不可点击），会话不能被日期折叠吃掉 ----

    @Test
    fun pinnedHeaderIsAlwaysVisible() {
        assertTrue(visible(ConversationListItem.PinnedHeader))
    }

    @Test
    fun pinnedConversationsIgnoreDateCollapsing() {
        assertTrue(visible(item(today, pinned = true), collapsedDates = setOf(todayKey)))
    }
}
