package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedAppsTest {
    @Test
    fun `all payment and banking apps are blocked`() {
        listOf(
            "com.eg.android.AlipayGphone", // 支付宝
            "com.unionpay", // 云闪付
            "com.tencent.mm", // 微信
            "com.paypal.android.p2pmobile", // PayPal
            "com.google.android.apps.walletnfcrel", // Google Wallet
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.icbc", // 工商银行
            "com.chinamworld.main", // 建设银行
            "com.android.bankabc", // 农业银行
            "com.chinamworld.bocmbci", // 中国银行
            "com.bankcomm.Bankcomm", // 交通银行
            "cmb.pb", // 招商银行
            "com.yitong.mbank.psbc", // 邮储银行
        ).forEach { packageName ->
            assertTrue(packageName, isBlockedAutomationApp(packageName))
            assertTrue(packageName, packageName in PHONE_USE_BLOCKED_APPS)
        }
        assertEquals(13, PHONE_USE_BLOCKED_APPS.size)
    }

    @Test
    fun `ordinary apps are not blocked`() {
        listOf(
            "com.demo",
            "com.tencent.mobileqq",
            "com.android.chrome",
            "me.rerere.rikkahub",
        ).forEach { packageName ->
            assertFalse(packageName, isBlockedAutomationApp(packageName))
        }
        assertFalse(isBlockedAutomationApp(null))
    }

    @Test
    fun `no foreground envelope when the accessibility service is not connected`() {
        // RikkaAccessibilityService.instance is null on the JVM; the check must not
        // intercept anything and must not touch Android framework code.
        assertNull(blockedForegroundEnvelope())
    }
}
