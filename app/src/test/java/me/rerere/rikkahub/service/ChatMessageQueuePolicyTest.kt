package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageQueuePolicyTest {
    @Test
    fun idleConversationSendsImmediately() {
        assertFalse(shouldQueueChatMessage(false, false, false))
    }

    @Test
    fun activeGenerationQueuesQuickReply() {
        assertTrue(shouldQueueChatMessage(true, false, false))
    }

    @Test
    fun pendingToolOrAskUserQueuesEvenAfterGenerationJobEnded() {
        assertTrue(shouldQueueChatMessage(false, true, false))
    }

    @Test
    fun pausedQueueRequiresExplicitResume() {
        assertTrue(shouldQueueChatMessage(false, false, true))
    }

    @Test
    fun allBlockingCombinationsKeepReplyQueued() {
        for (mask in 1..7) {
            assertTrue(shouldQueueChatMessage(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0))
        }
    }
}
