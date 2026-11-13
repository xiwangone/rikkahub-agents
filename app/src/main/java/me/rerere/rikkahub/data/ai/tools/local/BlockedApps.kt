package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.service.RikkaAccessibilityService

/**
 * 不允许 AI 操作的应用：支付和银行类。
 * Phone Use 与散工具（tap、find_node 等）共用同一份名单：这些应用不能打开、不能读屏、
 * 不能操作，只能返回或回到桌面。
 */
internal val PHONE_USE_BLOCKED_APPS = setOf(
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
)

internal fun isBlockedAutomationApp(packageName: String?): Boolean = packageName in PHONE_USE_BLOCKED_APPS

/**
 * 前台应用在禁止列表里时返回拒绝信封，否则返回 null。散工具在执行前调用。
 * 无障碍服务没连接时拿不到前台包名，不拦截，交给各工具自己的服务未开启处理。
 */
internal fun blockedForegroundEnvelope(): JsonObject? {
    val foreground =
        RikkaAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: return null
    if (!isBlockedAutomationApp(foreground)) return null
    return ToolErrors.envelopeFor(
        error = "blocked_app",
        message = "$foreground is blocked for safety: you cannot read or operate anything while it is in the foreground.",
        recovery = "Press back or home to leave $foreground, and tell the user to finish this step themselves.",
    )
}
