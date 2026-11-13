package me.rerere.rikkahub.ui.components.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿 + 取消回滚共享状态机（[DraftState]）：持有「已保存值」与「编辑中草稿」两份状态。
 * 各页原来手写的 loadXxxDrafts / saveXxx / 取消重载三件套改调本类后，一套参数化用例
 * 覆盖 5 处（主机覆写取消、DNS 弹窗取消与测试分离、代理弹窗取消与测试分离等）。
 */
class DraftStateTest {

    @Test
    fun `initial saved equals draft and is clean`() {
        val state = DraftState("saved")
        assertEquals("saved", state.saved)
        assertEquals("saved", state.draft)
        assertFalse(state.isDirty)
    }

    @Test
    fun `edit marks dirty without touching saved`() {
        val state = DraftState("saved")
        state.edit("draft")
        assertTrue(state.isDirty)
        assertEquals("saved", state.saved)
        assertEquals("draft", state.draft)
    }

    @Test
    fun `edit transform applies to draft only`() {
        val state = DraftState(listOf("a"))
        state.edit { it + "b" }
        assertEquals(listOf("a", "b"), state.draft)
        assertEquals(listOf("a"), state.saved)
        assertTrue(state.isDirty)
    }

    @Test
    fun `discard rolls draft back to saved`() {
        val state = DraftState("saved")
        state.edit("draft")
        state.discard()
        assertEquals("saved", state.draft)
        assertEquals("saved", state.saved)
        assertFalse(state.isDirty)
    }

    @Test
    fun `save commits draft and returns it`() {
        val state = DraftState("saved")
        state.edit("draft")
        assertEquals("draft", state.save())
        assertEquals("draft", state.saved)
        assertEquals("draft", state.draft)
        assertFalse(state.isDirty)
    }

    @Test
    fun `load replaces both saved and draft`() {
        val state = DraftState("old")
        state.edit("dirty")
        state.load("new")
        assertEquals("new", state.saved)
        assertEquals("new", state.draft)
        assertFalse(state.isDirty)
    }

    @Test
    fun `discard after save keeps saved value`() {
        val state = DraftState("v1")
        state.edit("v2")
        state.save()
        state.edit("v3")
        state.discard()
        assertEquals("v2", state.draft)
        assertEquals("v2", state.saved)
    }
}
