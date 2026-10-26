package me.rerere.rikkahub.data.network

import java.net.InetAddress
import me.rerere.rikkahub.data.datastore.SettingsStore
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
 * An OkHttp [Dns] that answers for hosts pinned in the network settings and delegates
 * everything else to [delegate].
 *
 * Some endpoints sit behind a shared anycast front where the address the system resolver
 * returns can be a slow or lossy route, while a different address of the same service is
 * fast and stable. Pinning the host keeps every request on the chosen address without
 * touching system-wide DNS or needing root.
 *
 * Several entries for the same host are allowed and act as an ordered fallback: the
 * addresses are returned in the configured order, and OkHttp dials them one by one,
 * moving to the next address when a connect attempt fails.
 */
class HostOverrideDns(
    private val settingsStore: SettingsStore,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val fixed = settingsStore.settingsFlow.value.networkSetting.hostAddressOverrides
            .asSequence()
            .filter { it.enabled }
            .filter { it.host.trim().equals(hostname, ignoreCase = true) }
            .mapNotNull { parseFixedAddress(it.address) }
            .distinct()
            .toList()
        if (fixed.isNotEmpty()) return fixed
        return delegate.lookup(hostname)
    }
}
