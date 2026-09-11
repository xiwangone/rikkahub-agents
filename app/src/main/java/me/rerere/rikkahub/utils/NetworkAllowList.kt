package me.rerere.rikkahub.utils

/**
 * 网段白名单（CIDR）判定。Web API 与本地 MCP 服务端共用。
 */

/** 来源 IP 是否在允许网段内；[allowedNetworks] 为空 = 不限制 */
fun isRemoteHostAllowed(remoteHost: String, allowedNetworks: String): Boolean {
    val rules =
        allowedNetworks
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (rules.isEmpty()) return true
    val addr = parseIpv4(remoteHost) ?: return false
    return rules.any { ipv4InCidr(addr, it) }
}

private fun parseIpv4(host: String): Long? {
    val parts = host.trim().removePrefix("::ffff:").split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        value = (value shl 8) or n.toLong()
    }
    return value
}

private fun ipv4InCidr(addr: Long, cidr: String): Boolean {
    val raw = cidr.trim()
    if (raw.isEmpty()) return false
    val slash = raw.indexOf('/')
    val ipPart = if (slash >= 0) raw.substring(0, slash) else raw
    val base = parseIpv4(ipPart) ?: return false
    if (slash < 0) return base == addr
    val prefix = raw.substring(slash + 1).toIntOrNull()?.takeIf { it in 0..32 } ?: return false
    val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
    return (addr and mask) == (base and mask)
}
