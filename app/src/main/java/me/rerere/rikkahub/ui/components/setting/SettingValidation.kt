package me.rerere.rikkahub.ui.components.setting

import me.rerere.rikkahub.data.network.toProxyOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 设置页共享校验函数。
 *
 * 各设置页原来散落的页面级校验纯函数收拢于此；新页面级校验函数也必须写进本文件，
 * 不许写进页面文件（门禁 16-setting-validation 兜底）。
 * 校验口径以各页原有行为为准，收拢只搬位置、不改语义。
 */

/** Web 桥端口校验：能解析为整数且在 1–65535 内（与 SSH 主机页同口径）。非法输入标红、不写库，连接按钮禁点。 */
internal fun webBridgePortValid(text: String): Boolean {
    val port = text.toIntOrNull() ?: return false
    return port in 1..65535
}

/**
 * 代理草稿可测试判定：能解析出有效代理地址才允许发起测试。
 * 页内测试按钮与弹窗内测试按钮共用同一口径。
 */
internal fun canTestProxyUrl(draftUrl: String): Boolean = draftUrl.toProxyOrNull() != null

/**
 * DoH URL 有效性：必须是可解析的 https URL 且带 host。
 * 只驱动 DNS 弹窗 DoH 字段的 isError（空值表示走默认、不标红）；测试按钮本身不拦截，
 * 无效 URL 走测试请求失败弹 Toast 的原有路径——与 AppDnsResolver 经 OkHttp 使用该 URL 的
 * 实际消费口径一致。
 */
internal fun dohUrlValid(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" && parsed.host.isNotBlank()
}
