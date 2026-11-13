package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 凭证库审计条目计数后缀（[auditCountSuffix]）：同一调用点多次出现时只在文案后追加 ` ×N`。
 * 渲染行为由 UI 层负责；这里只验证「0/1 不显示、>1 显示次数」的映射。
 */
class VaultPageAuditCountSuffixTest {

    @Test
    fun `zero and one show no suffix`() {
        assertEquals("", auditCountSuffix(0))
        assertEquals("", auditCountSuffix(1))
    }

    @Test
    fun `more than one shows the count`() {
        assertEquals(" ×2", auditCountSuffix(2))
        assertEquals(" ×10", auditCountSuffix(10))
    }

    @Test
    fun `negative count shows no suffix`() {
        assertEquals("", auditCountSuffix(-1))
    }
}
