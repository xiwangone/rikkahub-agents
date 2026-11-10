package me.rerere.search.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// This intentionally parallels me.rerere.rikkahub.data.ai.net.GuardedDns in the app module:
// :search cannot depend on :app, so the scrape() egress guard for SearXNG/Bing is duplicated
// here rather than shared.

/**
 * True when this address must never be a tool-driven HTTP target: loopback, link-local,
 * site-local (RFC1918), any-local, multicast, CGNAT (100.64.0.0/10), or IPv6 unique-local
 * (fc00::/7).
 *
 * 169.254.169.254, the cloud metadata address, falls out of the link-local check.
 */
internal fun InetAddress.isBlockedTarget(): Boolean {
    val isLocalAddress = isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress
    val isSiteOrMulticast = isSiteLocalAddress || isMulticastAddress
    if (isLocalAddress || isSiteOrMulticast) {
        return true
    }
    // 100.64.0.0/10 is not covered by isSiteLocalAddress.
    if (this is Inet4Address) {
        val b = address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        if (first == 100 && second in 64..127) return true
    }
    // fc00::/7 (unique-local) is not covered by isSiteLocalAddress, which only matches
    // the deprecated fec0::/10 for Inet6Address.
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return true
    }
    return false
}

/**
 * An OkHttp [Dns] that refuses to resolve a hostname to any private or otherwise
 * non-routable address.
 *
 * Guarding here rather than by inspecting the URL string is deliberate: OkHttp resolves
 * every redirect hop through this same [Dns], so a public URL that 302s to
 * `http://127.0.0.1:8080` is blocked without any extra redirect-tracking logic.
 *
 * The entire answer set is rejected if any address in it is unsafe: accepting the safe
 * subset would let a hostile resolver smuggle a private address alongside a public one.
 */
class GuardedDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowPrivate: Boolean = false,
) : Dns {

    private fun isMagicDnsPlaceholder(hostname: String, address: InetAddress): Boolean {
        val clean = hostname.trim().trimEnd('.').lowercase()
        if (!clean.endsWith(".ts.net") && !clean.endsWith(".beta.tailscale.net")) return false
        val bytes = address.address
        return bytes.size == 4 && bytes[0].toInt() and 0xFF == 100 &&
            bytes[1].toInt() and 0xFF == 64 && bytes[2].toInt() and 0xFF == 0 &&
            bytes[3].toInt() and 0xFF == 1
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (allowPrivate) return addresses

        // The delegate may be the app DNS, which synthesises the MagicDNS placeholder
        // address for a Tailnet MagicDNS name (*.ts.net): that address is only a placeholder
        // the socket factory intercepts by hostname and routes through the tunnel, so it never
        // reaches the carrier-grade NAT network. Any other CGNAT-range address, and a
        // literal placeholder host, stay blocked (see isBlockedTarget).
        val blocked = addresses.firstOrNull {
            it.isBlockedTarget() && !isMagicDnsPlaceholder(hostname, it)
        }
        if (blocked != null) {
            throw UnknownHostException(
                "blocked_private_address: $hostname resolves to ${blocked.hostAddress}",
            )
        }
        return addresses
    }
}


/**
 * Parse [host] as an IPv4 literal in any of the numeric forms a resolver accepts
 * (the `inet_aton` forms): a single number, `a.b`, `a.b.c`, or `a.b.c.d`, where each
 * part may be decimal, `0`-prefixed octal, or `0x`-prefixed hexadecimal. The standard
 * dotted-decimal form is only one of these: `2130706433`, `127.1`, `0x7f000001` and
 * `0177.0.0.1` all denote 127.0.0.1, and a proxy-side resolver interprets them the
 * same way, so the literal check must recognise them too. Returns the parsed address,
 * or null when [host] is not such a literal. No DNS lookup happens here: a hostname
 * is never passed to [InetAddress.getByName].
 */
/**
 * Parse one numeric IPv4 part (`inet_aton` form): `0x`-prefixed hex,
 * `0`-prefixed octal, or plain decimal. Returns null when the part is
 * empty or not a valid number in its form.
 */
internal fun parseIpv4Part(part: String): Long? {
    if (part.isEmpty()) return null
    return when {
        part.startsWith("0x", ignoreCase = true) -> {
            if (part.length == 2) return null
            part.substring(2).toLongOrNull(16)
        }
        part.length > 1 && part[0] == '0' -> {
            if (!part.all { it in '0'..'7' }) return null
            part.toLongOrNull(8)
        }
        part.all { it.isDigit() } -> part.toLongOrNull(10)
        else -> null
    }
}

internal fun parseNumericIpv4Literal(host: String): InetAddress? {
    if (host.contains(':')) return null
    val parts = host.split('.')
    if (parts.size !in 1..4) return null
    val values = LongArray(parts.size)
    for ((index, part) in parts.withIndex()) {
        values[index] = parseIpv4Part(part) ?: return null
    }
    val combined: Long = when (parts.size) {
        1 -> values[0].takeIf { it <= 0xFFFFFFFFL } ?: return null
        2 -> if (values[0] > 0xFF || values[1] > 0xFFFFFF) return null
            else (values[0] shl 24) or values[1]
        3 -> if (values[0] > 0xFF || values[1] > 0xFF || values[2] > 0xFFFF) return null
            else (values[0] shl 24) or (values[1] shl 16) or values[2]
        else -> {
            if (values.any { it > 0xFF }) return null
            (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
        }
    }
    val bytes = byteArrayOf(
        ((combined shr 24) and 0xFF).toByte(),
        ((combined shr 16) and 0xFF).toByte(),
        ((combined shr 8) and 0xFF).toByte(),
        (combined and 0xFF).toByte(),
    )
    return InetAddress.getByAddress(bytes)
}

private val NUMERIC_IPV4_ATTEMPT_RE =
    Regex("""^(0[xX][0-9a-fA-F]+|[0-9]+)(\.(0[xX][0-9a-fA-F]+|[0-9]+)){0,3}$""")

/**
 * True when [host] is an IP literal that [isBlockedTarget] refuses. OkHttp routes a
 * literal-IP host straight to the socket without consulting the custom [Dns] (its route
 * selector short-circuits `canParseAsIpAddress`), so [GuardedDns] never sees it; a caller
 * uses this to reject such a host before dialing.
 *
 * A host counts as a literal when it parses as an IPv4 literal in any numeric form
 * (see [parseNumericIpv4Literal]) or contains ':' (an OkHttp `HttpUrl.host` for IPv6 is
 * bracket-free, and a real hostname never contains ':'). Only literals are inspected,
 * so no DNS query happens here; a hostname returns false. A null or blank host also
 * returns false: it is not a literal, and such a host only arises from an unparseable
 * URL that never dials. A numeric-looking host that does not parse as a valid literal
 * (for example `999.999.999.999`) is refused (fail closed) rather than handed to a
 * resolver that might interpret it differently.
 */
@Suppress("SwallowedException") // fail-closed：解析失败的字面量直接按“被禁”处理，返回值本身就是处理结果；UnknownHostException 已是具体类型，无法再收窄。
internal fun hostIsBlockedLiteral(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    if (host.contains(':')) {
        return try {
            // No DNS lookup happens for an IP literal; getByName just parses it.
            InetAddress.getByName(host).isBlockedTarget()
        } catch (e: UnknownHostException) {
            true
        }
    }
    parseNumericIpv4Literal(host)?.let { return it.isBlockedTarget() }
    return NUMERIC_IPV4_ATTEMPT_RE.matches(host)
}


/**
 * Wrap [this] client so tool-driven requests cannot reach a private target by either name or
 * literal. The [GuardedDns] wrapper covers hostname resolution on every hop; the network
 * interceptor covers literal-IP hosts, which OkHttp routes without consulting [Dns]. A
 * caller-side [hostIsBlockedLiteral] pre-check on the initial URL makes that case
 * deterministic, while this interceptor still guards literal-IP redirect hops.
 *
 * Also sets a 30 s [OkHttpClient.Builder.callTimeout] bounding the entire call, including
 * trickling reads: a coroutine `withTimeoutOrNull` around a blocking `execute()` has no
 * suspension point to interrupt, so it cannot enforce a caller-side timeout on its own.
 */
internal fun OkHttpClient.withEgressGuard(allowPrivate: Boolean = false): OkHttpClient =
    newBuilder()
        .dns(GuardedDns(this.dns, allowPrivate))
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val host = chain.request().url.host
            if (!allowPrivate && hostIsBlockedLiteral(host)) {
                throw UnknownHostException("blocked_private_address: $host")
            }
            chain.proceed(chain.request())
        }
        .build()
