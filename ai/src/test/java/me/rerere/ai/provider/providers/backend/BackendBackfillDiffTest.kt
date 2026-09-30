package me.rerere.ai.provider.providers.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 断流补拉的差值定位。
 *
 * 关键场景是多 turn 拼接：历史接口给出的是整条消息的完整文本，而本地累计在 turn_done
 * 重置、只覆盖当前 turn —— 前缀判据在此失配并静默不补，表现为"收尾内容缺失"。
 */
class BackendBackfillDiffTest {
    @Test
    fun prefixCaseReturnsMissingTail() {
        assertEquals("def", computeBackfillDiff("abcdef", "abc"))
    }

    @Test
    fun multiTurnTextLocatesLocalTailInsteadOfPrefix() {
        // 完整文本 = 上一 turn 文本 + 当前 turn 文本；本地累计只有当前 turn
        assertEquals(" end", computeBackfillDiff("first turnsecond turn end", "second turn"))
    }

    @Test
    fun noGapReturnsNull() {
        assertNull(computeBackfillDiff("t1t2", "t2"))
    }

    @Test
    fun mismatchedContentReturnsNull() {
        assertNull(computeBackfillDiff("xyz", "abc"))
    }

    @Test
    fun emptyLocalTakesWholeText() {
        assertEquals("abc", computeBackfillDiff("abc", ""))
    }

    @Test
    fun blankFullTextReturnsNull() {
        assertNull(computeBackfillDiff("", "abc"))
        assertNull(computeBackfillDiff("   ", "abc"))
    }
}
