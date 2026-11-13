package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代理草稿可测试判定（[canTestProxyUrl]）：能解析出有效代理地址才允许发起测试；
 * 页内与弹窗内测试按钮共用同一口径。
 */
class SettingNetworkProxyTest {

    @Test
    fun `valid proxy urls are testable`() {
        assertTrue(canTestProxyUrl("http://127.0.0.1:7890"))
        assertTrue(canTestProxyUrl("127.0.0.1:7890"))
        assertTrue(canTestProxyUrl("socks5://127.0.0.1:1080"))
        assertTrue(canTestProxyUrl("https://proxy.example.com:8080"))
    }

    @Test
    fun `blank url is not testable`() {
        assertFalse(canTestProxyUrl(""))
        assertFalse(canTestProxyUrl("   "))
    }

    @Test
    fun `unparseable url is not testable`() {
        assertFalse(canTestProxyUrl("not a url"))
        assertFalse(canTestProxyUrl("http://127.0.0.1"))
        assertFalse(canTestProxyUrl("ftp://127.0.0.1:21"))
        assertFalse(canTestProxyUrl("http://127.0.0.1:99999"))
    }
}
