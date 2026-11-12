package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清空 Web 服务访问密码是否需要先确认（[passwordClearNeedsConfirm]）：
 * 只有“新值为空且身份验证当前开着”时才需要——此时保存会联动关掉身份验证，不能悄悄做。
 */
class SettingWebServerPageTest {

    @Test
    fun `clearing password while jwt on needs confirm`() {
        assertTrue(passwordClearNeedsConfirm("", jwtEnabled = true))
        assertTrue(passwordClearNeedsConfirm("   ", jwtEnabled = true))
    }

    @Test
    fun `clearing password while jwt off needs no confirm`() {
        assertFalse(passwordClearNeedsConfirm("", jwtEnabled = false))
    }

    @Test
    fun `non-blank password needs no confirm`() {
        assertFalse(passwordClearNeedsConfirm("secret", jwtEnabled = true))
        assertFalse(passwordClearNeedsConfirm("secret", jwtEnabled = false))
    }
}
