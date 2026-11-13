package me.rerere.rikkahub.ui.components.setting

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.UsageRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页共享校验函数（[webBridgePortValid]、[canTestProxyUrl]、[dohUrlValid]）与
 * 空态 / 计数状态（[cleanDeleteButtonEnabled]、[sshKeyChoiceAvailability]、
 * [usageRangeLabelRes]、[Int.toCountState]）。
 * 各设置页原来散落的页面级校验纯函数收拢于此；新页面级校验函数也必须写进共享模块，
 * 不许写进页面文件（门禁 16-setting-validation 兜底）。
 */
class SettingValidationTest {

    // ---- webBridgePortValid ----

    @Test
    fun `port boundaries pass`() {
        assertTrue(webBridgePortValid("1"))
        assertTrue(webBridgePortValid("22"))
        assertTrue(webBridgePortValid("65535"))
    }

    @Test
    fun `port out of range fails`() {
        assertFalse(webBridgePortValid("0"))
        assertFalse(webBridgePortValid("65536"))
        assertFalse(webBridgePortValid("99999"))
        assertFalse(webBridgePortValid("-1"))
    }

    @Test
    fun `port non-numeric fails`() {
        assertFalse(webBridgePortValid(""))
        assertFalse(webBridgePortValid("abc"))
        assertFalse(webBridgePortValid("22.5"))
        assertFalse(webBridgePortValid(" 22"))
    }

    // ---- canTestProxyUrl ----

    @Test
    fun `parseable proxy urls are testable`() {
        assertTrue(canTestProxyUrl("http://127.0.0.1:7890"))
        assertTrue(canTestProxyUrl("127.0.0.1:7890"))
        assertTrue(canTestProxyUrl("socks5://127.0.0.1:1080"))
    }

    @Test
    fun `blank or unparseable proxy urls are not testable`() {
        assertFalse(canTestProxyUrl(""))
        assertFalse(canTestProxyUrl("   "))
        assertFalse(canTestProxyUrl("not a url"))
    }

    // ---- dohUrlValid ----

    @Test
    fun `https doh url with host is valid`() {
        assertTrue(dohUrlValid("https://dns.example.com/dns-query"))
        assertTrue(dohUrlValid("https://8.8.8.8/dns-query"))
    }

    @Test
    fun `non-https or hostless doh urls are invalid`() {
        assertFalse(dohUrlValid("http://dns.example.com/dns-query"))
        assertFalse(dohUrlValid("https://"))
        assertFalse(dohUrlValid(""))
        assertFalse(dohUrlValid("not a url"))
        assertFalse(dohUrlValid("dns.example.com/dns-query"))
    }

    // ---- cleanDeleteButtonEnabled ----

    @Test
    fun `clean delete button enabled only when count is positive`() {
        assertFalse(cleanDeleteButtonEnabled(0))
        assertTrue(cleanDeleteButtonEnabled(1))
        assertTrue(cleanDeleteButtonEnabled(42))
    }

    // ---- sshKeyChoiceAvailability ----

    @Test
    fun `key choice availability follows both libraries`() {
        val both = sshKeyChoiceAvailability(hasTemplates = true, hasKeys = true)
        assertTrue(both.templateEnabled)
        assertFalse(both.templateHintVisible)
        assertTrue(both.vaultPickEnabled)
        assertFalse(both.vaultHintVisible)

        val neither = sshKeyChoiceAvailability(hasTemplates = false, hasKeys = false)
        assertFalse(neither.templateEnabled)
        assertTrue(neither.templateHintVisible)
        assertFalse(neither.vaultPickEnabled)
        assertTrue(neither.vaultHintVisible)

        val templatesOnly = sshKeyChoiceAvailability(hasTemplates = true, hasKeys = false)
        assertTrue(templatesOnly.templateEnabled)
        assertFalse(templatesOnly.vaultPickEnabled)
        assertTrue(templatesOnly.vaultHintVisible)

        val keysOnly = sshKeyChoiceAvailability(hasTemplates = false, hasKeys = true)
        assertFalse(keysOnly.templateEnabled)
        assertTrue(keysOnly.templateHintVisible)
        assertTrue(keysOnly.vaultPickEnabled)
    }

    // ---- usageRangeLabelRes ----

    @Test
    fun `usage range maps to its label resource`() {
        assertEquals(R.string.usage_stats_range_today, usageRangeLabelRes(UsageRange.TODAY))
        assertEquals(R.string.usage_stats_range_7d, usageRangeLabelRes(UsageRange.LAST_7_DAYS))
        assertEquals(R.string.usage_stats_range_30d, usageRangeLabelRes(UsageRange.LAST_30_DAYS))
        assertEquals(R.string.usage_stats_range_all, usageRangeLabelRes(UsageRange.ALL_TIME))
    }

    // ---- Int?.toCountState ----

    @Test
    fun `null count is loading and numbers are ready`() {
        assertEquals(CountState.Loading, (null as Int?).toCountState())
        assertEquals(CountState.Ready(0), (0 as Int?).toCountState())
        assertEquals(CountState.Ready(7), (7 as Int?).toCountState())
    }
}
