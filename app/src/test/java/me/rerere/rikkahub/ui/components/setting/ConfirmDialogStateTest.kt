package me.rerere.rikkahub.ui.components.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认弹窗共享状态机（[ConfirmDialogState]）：只持有「待确认的目标」，不渲染对话框。
 * 各页原来散落的 `pendingDelete: T?` / `pendingRevoke: String?` / `pendingXxx: Boolean`
 * 改调本类后，一套参数化用例覆盖 5 处（搜索删除、撤销授权、文件清理 ALL、清空密码等）。
 */
class ConfirmDialogStateTest {

    @Test
    fun `initial state is not showing`() {
        val state = ConfirmDialogState<String>()
        assertFalse(state.isShowing)
        assertNull(state.pending)
        assertNull(state.confirm())
    }

    @Test
    fun `request sets pending and shows`() {
        val state = ConfirmDialogState<String>()
        state.request("tool-a")
        assertTrue(state.isShowing)
        assertEquals("tool-a", state.pending)
    }

    @Test
    fun `confirm returns target and clears`() {
        val state = ConfirmDialogState<String>()
        state.request("tool-a")
        assertEquals("tool-a", state.confirm())
        assertFalse(state.isShowing)
        assertNull(state.pending)
        assertNull(state.confirm())
    }

    @Test
    fun `dismiss clears without returning target`() {
        val state = ConfirmDialogState<String>()
        state.request("tool-a")
        state.dismiss()
        assertFalse(state.isShowing)
        assertNull(state.pending)
        assertNull(state.confirm())
    }

    @Test
    fun `re-request overwrites pending target`() {
        val state = ConfirmDialogState<String>()
        state.request("tool-a")
        state.request("tool-b")
        assertEquals("tool-b", state.confirm())
    }

    @Test
    fun `unit request shows boolean confirm`() {
        val state = ConfirmDialogState<Unit>()
        state.request()
        assertTrue(state.isShowing)
        assertEquals(Unit, state.confirm())
        assertFalse(state.isShowing)
    }
}
