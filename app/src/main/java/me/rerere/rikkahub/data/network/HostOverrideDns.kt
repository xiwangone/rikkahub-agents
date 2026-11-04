package me.rerere.rikkahub.data.network

import java.net.InetAddress
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.isTailnetMagicDnsHost
import okhttp3.Dns

private val IPV4_LITERAL_RE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * Parses [value] as an IP literal, or returns null.
 *
 * Only literals are accepted on purpose: handing an arbitrary string to
 * [InetAddress.getByName] would trigger a DNS lookup, which is exactly what an override is
 * supposed to avoid.
 */
internal fun parseFixedAddress(value: String): InetAddress? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val isLiteral = IPV4_LITERAL_RE.matches(text) || text.contains(':')
    if (!isLiteral) return null
    return runCatching { InetAddress.getByName(text) }.getOrNull()
}

/**
 * 应用全局 OkHttp [Dns] 实现，按以下优先级解析主机名：
 * 1. IP 字面量直接解析（不走任何网络请求）；
 * 2. 用户配置的固定主机映射（`hostAddressOverrides`）；
 * 3. Tailscale 内嵌隧道开启时的 MagicDNS 域名（`*.ts.net`）；
 * 4. 应用内独立 DNS（DoH / DoT / 自定义直连 UDP+TCP DNS，绕过手机系统 DNS 劫持）；
 * 5. 回退到 [delegate]（默认 `Dns.SYSTEM`）。
 */
class HostOverrideDns(
    private val settingsStore: SettingsStore,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim()
        parseFixedAddress(cleanHost)?.let { return listOf(it) }

        val networkSetting = settingsStore.settingsFlow.value.networkSetting
        AppDnsResolver.updateNetworkSetting(networkSetting)
        val fixed = networkSetting.hostAddressOverrides
            .asSequence()
            .filter { it.enabled }
            .filter { it.host.trim().equals(cleanHost, ignoreCase = true) }
            .mapNotNull { entry ->
                parseFixedAddress(entry.address)?.let { addr ->
                    runCatching { InetAddress.getByAddress(cleanHost, addr.address) }.getOrDefault(addr)
                }
            }
            .distinct()
            .toList()
        if (fixed.isNotEmpty()) return fixed

        // Tailscale 隧道开启时，MagicDNS (*.ts.net) 域名合成占位地址交给 TsnetSocketFactory 在拨号阶段走隧道直连
        if (TsnetBridge.isRunning && isTailnetMagicDnsHost(cleanHost)) {
            return listOf(
                InetAddress.getByAddress(cleanHost, byteArrayOf(100, 64, 0, 1)),
            )
        }

        if (networkSetting.dnsMode != NetworkSetting.DNS_MODE_SYSTEM) {
            return AppDnsResolver.resolve(cleanHost, networkSetting, fallbackDns = delegate)
        }

        return delegate.lookup(cleanHost)
    }
}
