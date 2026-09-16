package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 压缩模型的解析规则：跳过「Auto」占位与被禁用的 provider，逐级回退；
 * 全不可用时返回 null（由调用方提示用户去设置里改）。
 */
class ResolveCompressionModelTest {

    @Test
    fun `returns null when no provider is configured`() {
        assertNull(resolveCompressionModel(Settings()))
    }
}
