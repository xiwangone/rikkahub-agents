package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingOverlayPolicyTest {
    @Test fun disabledByDefaultAndGatedByBackgroundAndPermission() {
        var state = FloatingOverlayPolicyState()
        state = state.updateRunning(setOf("chat-a"))
        assertEquals(null, state.targetConversationId)
        state = state.updateEnabled(true).updateForeground(false).updateRunning(setOf("chat-a"))
        assertFalse(state.shouldShowWindow)
        state = state.updatePermission(true)
        assertTrue(state.shouldShowWindow)
        assertEquals("chat-a", state.targetConversationId)
    }

    @Test fun foregroundHidesAndReenteringBackgroundCanTrackActiveGeneration() {
        var state = FloatingOverlayPolicyState(enabled = true, foreground = false, hasOverlayPermission = true)
        state = state.updateRunning(setOf("first"))
        assertTrue(state.shouldShowWindow)
        state = state.updateForeground(true)
        assertFalse(state.shouldShowWindow)
        assertEquals(null, state.targetConversationId)
        state = state.updateRunning(setOf("first")).updateForeground(false).updateRunning(setOf("first"))
        assertEquals("first", state.targetConversationId)
        assertTrue(state.shouldShowWindow)
    }

    @Test fun dismissalWaitsForANewConversationToStartAndDoesNotStopGeneration() {
        var state = FloatingOverlayPolicyState(enabled = true, foreground = false, hasOverlayPermission = true)
            .updateRunning(setOf("first"))
            .expand()
            .dismiss()
        assertEquals(null, state.targetConversationId)
        assertFalse(state.expanded)
        state = state.updateRunning(setOf("first"))
        assertEquals(null, state.targetConversationId)
        state = state.updateRunning(setOf("first", "second"))
        assertEquals("second", state.targetConversationId)
        assertTrue(state.shouldShowWindow)
    }

    @Test fun revokingPermissionHidesWithoutDiscardingTargetOrRunningState() {
        var state = FloatingOverlayPolicyState(enabled = true, foreground = false, hasOverlayPermission = true)
            .updateRunning(setOf("first"))
        state = state.updatePermission(false)
        assertFalse(state.shouldShowWindow)
        assertEquals(setOf("first"), state.runningConversationIds)
        assertEquals("first", state.targetConversationId)
        state = state.updatePermission(true)
        assertTrue(state.shouldShowWindow)
    }

    @Test fun activeSessionSwitchIsRestrictedToRunningSessions() {
        var state = FloatingOverlayPolicyState(enabled = true, foreground = false, hasOverlayPermission = true)
            .updateRunning(setOf("first", "second"))
        state = state.selectConversation("first")
        assertEquals("first", state.targetConversationId)
        state = state.selectConversation("not-running")
        assertEquals("first", state.targetConversationId)
    }

    @Test fun revocationCannotShowOverlayEvenWhileEnabledAndBackgrounded() {
        val state = FloatingOverlayPolicyState(enabled = true, foreground = false, hasOverlayPermission = true)
            .updateRunning(setOf("conversation"))
            .updatePermission(false)
        assertFalse(state.shouldShowWindow)
        assertEquals("conversation", state.targetConversationId)
    }
}
