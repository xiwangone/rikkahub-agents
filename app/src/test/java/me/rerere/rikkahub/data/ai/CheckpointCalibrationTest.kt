package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩检查点「执行提示」只在请求组装时拼接：必须只作用于头部检查点、幂等（step 循环里
 * messages 会回流再组装）、且不改动消息身份（提示词缓存前缀依赖 id 稳定）。
 */
class CheckpointCalibrationTest {

    private fun checkpoint(text: String) = UIMessage.user(text).copy(isContextCheckpoint = true)

    @Test
    fun `appends the note to the checkpoint head and keeps the tail untouched`() {
        val head = checkpoint("summary body")
        val tail = UIMessage.user("real user message")

        val out = listOf(head, tail).withCheckpointCalibration()

        val headText = out[0].toText()
        assertTrue(headText.startsWith("summary body"))
        assertTrue(headText.contains("执行提示"))
        assertEquals("real user message", out[1].toText())
        // 身份必须不变，否则每次请求前缀都不同，提供方提示词缓存全部失效
        assertEquals(head.id, out[0].id)
    }

    @Test
    fun `re-applying does not stack the note`() {
        val once = listOf(checkpoint("s")).withCheckpointCalibration()
        val twice = once.withCheckpointCalibration()

        assertEquals(once[0].toText(), twice[0].toText())
    }

    @Test
    fun `leaves a non-checkpoint head untouched`() {
        val plain = UIMessage.user("not a checkpoint")

        val out = listOf(plain).withCheckpointCalibration()

        assertEquals("not a checkpoint", out[0].toText())
        assertFalse(out[0].toText().contains("执行提示"))
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(0, emptyList<UIMessage>().withCheckpointCalibration().size)
    }
}
