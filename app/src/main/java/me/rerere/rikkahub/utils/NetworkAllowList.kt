package me.rerere.rikkahub.utils

import java.net.InetAddress

/**
 * 网段白名单（CIDR）判定。Web API 与本地 MCP 服务端共用。
 * 双栈：IPv4 与 IPv6；v4-mapped IPv6（::ffff:a.b.c.d）归一为 IPv4 判定。
 */

/** 来源 IP 是否在允许网段内；[allowedNetworks] 为空 = 不限制 */
fun isRemoteHostAllowed(remoteHost: String, allowedNetworks: String): Boolean {
    val rules =
        allowedNetworks
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (rules.isEmpty()) return true
    // IPv4-mapped IPv6 归一为 IPv4，使 v4 规则可直接命中（dual-stack 监听时的 v4 来源）
    val host = remoteHost.trim().removePrefix("::ffff:").removePrefix("::FFFF:")
    val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return rules.any { rule -> matchesRule(addr, rule) }
}

/** 单条 CIDR 规则匹配：前缀长度缺省 = 全宽（精确匹配）；族不同的规则不命中 */
private fun matchesRule(addr: InetAddress, rule: String): Boolean {
    val slash = rule.indexOf('/')
    val base =
        runCatching {
            InetAddress.getByName(rule.substring(0, if (slash >= 0) slash else rule.length).trim())
        }.getOrNull() ?: return false
    val prefix = if (slash >= 0) rule.substring(slash + 1).trim().toIntOrNull() ?: return false else null
    val addrBytes = addr.address
    val baseBytes = base.address
    if (addrBytes.size != baseBytes.size) return false
    val bits = addrBytes.size * 8
    val p = prefix ?: bits
    if (p !in 0..bits) return false
    var remaining = p
    for (i in addrBytes.indices) {
        if (remaining <= 0) break
        val take = minOf(8, remaining)
        val mask = if (take == 8) 0xFF else (0xFF shl (8 - take)) and 0xFF
        if ((addrBytes[i].toInt() and mask) != (baseBytes[i].toInt() and mask)) return false
        remaining -= take
    }
    return true
}
