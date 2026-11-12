package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 桥端口校验（[webBridgePortValid]）：与 SSH 主机页同口径——能解析为整数且在 1–65535 内；
 * 非法输入标红、不写库，连接按钮禁点。
 */
class SettingWebBridgePortTest {

    @Test
    fun `plain valid ports pass`() {
        assertTrue(webBridgePortValid("22"))
        assertTrue(webBridgePortValid("1"))
        assertTrue(webBridgePortValid("65535"))
        assertTrue(webBridgePortValid("8080"))
    }

    @Test
    fun `zero and above range fail`() {
        assertFalse(webBridgePortValid("0"))
        assertFalse(webBridgePortValid("65536"))
        assertFalse(webBridgePortValid("99999"))
    }

    @Test
    fun `non-numeric input fails`() {
        assertFalse(webBridgePortValid(""))
        assertFalse(webBridgePortValid("abc"))
        assertFalse(webBridgePortValid("22.5"))
        assertFalse(webBridgePortValid("-1"))
        assertFalse(webBridgePortValid(" 22"))
    }
}
