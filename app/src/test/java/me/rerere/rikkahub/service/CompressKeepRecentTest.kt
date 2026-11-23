package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 压缩保留条数的钳制规则：保留数不得超过"总数 - 1"（至少留一条可压缩），
 * 否则 splitForCompression 会抛错——手动/自动压缩 2/2、1/1 卡死正是
 * keepRecent=32 撞上 6 条 / 11 条消息的短对话。
 */
class CompressKeepRecentTest {

    @Test
    fun `short conversation clamps to total minus one`() {
        assertEquals(5, effectiveKeepRecent(totalMessages = 6, requested = 32))
        assertEquals(10, effectiveKeepRecent(totalMessages = 11, requested = 32))
    }

    @Test
    fun `long conversation keeps requested value`() {
        assertEquals(32, effectiveKeepRecent(totalMessages = 100, requested = 32))
    }

    @Test
    fun `single message clamps to zero so it can still be compressed`() {
        assertEquals(0, effectiveKeepRecent(totalMessages = 1, requested = 32))
    }

    @Test
    fun `empty conversation clamps to zero`() {
        assertEquals(0, effectiveKeepRecent(totalMessages = 0, requested = 32))
    }

    @Test
    fun `requested below the cap passes through`() {
        assertEquals(3, effectiveKeepRecent(totalMessages = 10, requested = 3))
    }

    @Test
    fun `negative requested clamps to zero`() {
        assertEquals(0, effectiveKeepRecent(totalMessages = 10, requested = -5))
    }
}
