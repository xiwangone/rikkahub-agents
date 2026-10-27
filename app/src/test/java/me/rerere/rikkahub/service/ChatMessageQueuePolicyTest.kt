package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageQueuePolicyTest {
    @Test
    fun idleConversationSendsImmediately() {
        // 兼作“暂停态不拦新消息”：暂停只作用于自动派发，用户手动发的不入队
        assertFalse(shouldQueueChatMessage(false, false))
    }

    @Test
    fun activeGenerationQueuesQuickReply() {
        assertTrue(shouldQueueChatMessage(true, false))
    }

    @Test
    fun pendingToolOrAskUserQueuesEvenAfterGenerationJobEnded() {
        assertTrue(shouldQueueChatMessage(false, true))
    }

    @Test
    fun allBlockingCombinationsKeepReplyQueued() {
        for (mask in 1..3) {
            assertTrue(shouldQueueChatMessage(mask and 1 != 0, mask and 2 != 0))
        }
    }
}
