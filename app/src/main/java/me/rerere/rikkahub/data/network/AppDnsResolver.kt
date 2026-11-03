package me.rerere.rikkahub.data.network

import me.rerere.rikkahub.data.datastore.NetworkSetting
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

private const val QCLASS_IN = 1
private const val MIN_TTL_MS = 30_000L
private const val MAX_TTL_MS = 600_000L
private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

/** 内置 DoH / DoT 域名引导 IP：确保加密 DNS 握手前不依赖系统 DNS。 */
private val WELL_KNOWN_BOOTSTRAP_IPS: Map<String, List<String>> = mapOf(
    "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
    "doh.pub" to listOf("1.12.12.12", "120.53.53.53", "119.29.29.29"),
    "dot.pub" to listOf("1.12.12.12", "120.53.53.53", "119.29.29.29"),
    "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
    "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"),
    "1dot1dot1dot1.cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
    "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
    "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
    "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
)

@Volatile
private var cachedDohClient: Pair<List<String>, OkHttpClient>? = null

/**
 * 应用内独立 DNS 解析器：支持 DoH (RFC 8484)、DoT (RFC 7858) 与自定义直连 UDP/TCP DNS (RFC 1035)。
 */
object AppDnsResolver {

    const val QTYPE_A = 1
    const val QTYPE_AAAA = 28

    data class DnsPreset(
        val id: String,
        val label: String,
        val dohUrl: String,
        val dotHost: String,
        val bootstrapIps: String,
    )

    val PRESETS: List<DnsPreset> = listOf(
        DnsPreset(
            id = "alidns",
            label = "AliDNS (阿里)",
            dohUrl = "https://dns.alidns.com/dns-query",
            dotHost = "dns.alidns.com",
            bootstrapIps = "223.5.5.5, 223.6.6.6",
        ),
        DnsPreset(
            id = "dnspod",
            label = "DNSPod (腾讯)",
            dohUrl = "https://doh.pub/dns-query",
            dotHost = "dot.pub",
            bootstrapIps = "119.29.29.29, 1.12.12.12, 120.53.53.53",
        ),
        DnsPreset(
            id = "cloudflare",
            label = "Cloudflare (1.1.1.1)",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            dotHost = "one.one.one.one",
            bootstrapIps = "1.1.1.1, 1.0.0.1",
        ),
        DnsPreset(
            id = "google",
            label = "Google (8.8.8.8)",
            dohUrl = "https://dns.google/dns-query",
            dotHost = "dns.google",
            bootstrapIps = "8.8.8.8, 8.8.4.4",
        ),
        DnsPreset(
            id = "quad9",
            label = "Quad9 (9.9.9.9)",
            dohUrl = "https://dns.quad9.net/dns-query",
            dotHost = "dns.quad9.net",
            bootstrapIps = "9.9.9.9, 149.112.112.112",
        ),
        DnsPreset(
            id = "adguard",
            label = "AdGuard DNS",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            dotHost = "dns.adguard-dns.com",
            bootstrapIps = "94.140.14.14, 94.140.15.15",
        ),
    )

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
    )

    data class DnsParsedPacket(
        val rcode: Int,
        val truncated: Boolean,
        val addresses: List<InetAddress>,
        val minTtlSeconds: Long,
    )

    data class DnsTestOutcome(
        val mode: String,
        val endpoint: String,
        val domain: String,
        val elapsedMs: Long,
        val addresses: List<String>,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var lastConfigKey: String = ""

    fun invalidateCache() {
        cache.clear()
    }

    fun parseServerList(raw: String): List<String> =
        raw.split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { parseFixedAddress(it) != null }
            .distinct()

    fun resolve(
        hostname: String,
        setting: NetworkSetting,
        fallbackDns: Dns = Dns.SYSTEM,
    ): List<InetAddress> {
        val cleanHost = hostname.trim()
        if (cleanHost.isEmpty()) throw UnknownHostException("empty hostname")
        parseFixedAddress(cleanHost)?.let { return listOf(it) }

        val cfgKey = dnsConfigKey(setting)
        if (cfgKey != lastConfigKey) {
            lastConfigKey = cfgKey
            cache.clear()
        }

        val cacheKey = "$cfgKey|${cleanHost.lowercase()}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { entry ->
            if (now < entry.expiresAtMs && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
            cache.remove(cacheKey, entry)
        }

        val bootstrapServers = parseServerList(setting.customDnsServers)
            .ifEmpty { listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8") }
        val qtypes = if (setting.dnsEnableIpv6) listOf(QTYPE_A, QTYPE_AAAA) else listOf(QTYPE_A)

        val resolved = runCatching {
            resolveByMode(setting, bootstrapServers, cleanHost, qtypes)
        }.getOrNull()

        if (resolved != null && resolved.first.isNotEmpty()) {
            val ttlMs = (resolved.second * 1_000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
            cache[cacheKey] = CacheEntry(resolved.first, now + ttlMs)
            return resolved.first
        }

        if (setting.dnsFallbackToSystem || setting.dnsMode == NetworkSetting.DNS_MODE_SYSTEM) {
            return fallbackDns.lookup(cleanHost)
        }
        throw UnknownHostException("App independent DNS (${setting.dnsMode}) failed to resolve $cleanHost")
    }

    /** 供网络设置页一键测试当前 DNS 配置并返回延迟与解析结果。 */
    fun testResolve(
        domain: String,
        setting: NetworkSetting,
    ): DnsTestOutcome {
        invalidateCache()
        val target = domain.trim().ifEmpty { "www.baidu.com" }
        val start = System.nanoTime()
        val bootstrapServers = parseServerList(setting.customDnsServers)
            .ifEmpty { listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8") }
        val qtypes = if (setting.dnsEnableIpv6) listOf(QTYPE_A, QTYPE_AAAA) else listOf(QTYPE_A)
        val endpoint = when (setting.dnsMode) {
            NetworkSetting.DNS_MODE_DOH -> setting.dohUrl.trim().ifEmpty { NetworkSetting.DEFAULT_DOH_URL }
            NetworkSetting.DNS_MODE_DOT -> setting.dotHost.trim().ifEmpty { NetworkSetting.DEFAULT_DOT_HOST }
            NetworkSetting.DNS_MODE_CUSTOM -> bootstrapServers.joinToString(", ")
            else -> "System DNS"
        }
        val addresses = resolveByMode(setting, bootstrapServers, target, qtypes)?.first
            ?: Dns.SYSTEM.lookup(target)
        if (addresses.isEmpty()) {
            throw UnknownHostException("No A/AAAA record returned for $target via $endpoint")
        }
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        return DnsTestOutcome(
            mode = setting.dnsMode,
            endpoint = endpoint,
            domain = target,
            elapsedMs = elapsedMs,
            addresses = addresses.mapNotNull { it.hostAddress }.distinct(),
        )
    }

    internal fun resolveBootstrapAddresses(
        host: String,
        bootstrapServers: List<String>,
    ): List<InetAddress> = resolveDnsBootstrapAddresses(host, bootstrapServers)

    internal fun parseHostPort(spec: String, defaultPort: Int): Pair<String, Int> =
        parseDnsHostPort(spec, defaultPort)

    fun buildQuery(id: Int, domain: String, qtype: Int = QTYPE_A): ByteArray =
        buildDnsWireQuery(id, domain, qtype)

    fun parseResponse(
        expectedId: Int?,
        buf: ByteArray,
        length: Int = buf.size,
        requestedHost: String = "",
    ): DnsParsedPacket? = parseDnsWireResponse(expectedId, buf, length, requestedHost)
}

private fun dnsConfigKey(setting: NetworkSetting): String =
    "${setting.dnsMode}|${setting.dohUrl.trim()}|${setting.dotHost.trim()}|${setting.customDnsServers.trim()}|${setting.dnsEnableIpv6}"

private fun resolveByMode(
    setting: NetworkSetting,
    bootstrapServers: List<String>,
    hostname: String,
    qtypes: List<Int>,
): Pair<List<InetAddress>, Long>? =
    when (setting.dnsMode) {
        NetworkSetting.DNS_MODE_DOH -> resolveViaDoh(
            dohUrl = setting.dohUrl.trim().ifEmpty { NetworkSetting.DEFAULT_DOH_URL },
            bootstrapServers = bootstrapServers,
            hostname = hostname,
            qtypes = qtypes,
        )
        NetworkSetting.DNS_MODE_DOT -> resolveViaDot(
            dotHostSpec = setting.dotHost.trim().ifEmpty { NetworkSetting.DEFAULT_DOT_HOST },
            bootstrapServers = bootstrapServers,
            hostname = hostname,
            qtypes = qtypes,
        )
        NetworkSetting.DNS_MODE_CUSTOM -> resolveViaCustomServers(
            servers = bootstrapServers,
            hostname = hostname,
            qtypes = qtypes,
        )
        else -> null
    }

private fun resolveViaCustomServers(
    servers: List<String>,
    hostname: String,
    qtypes: List<Int>,
): Pair<List<InetAddress>, Long> {
    val allAddresses = mutableListOf<InetAddress>()
    var minTtl = 300L
    for (server in servers) {
        var serverSucceeded = false
        for (qtype in qtypes) {
            val parsed = runCatching { queryDnsUdpOrTcp(server, 53, hostname, qtype) }.getOrNull()
            if (parsed != null && parsed.addresses.isNotEmpty()) {
                allAddresses.addAll(parsed.addresses)
                minTtl = minOf(minTtl, parsed.minTtlSeconds)
                serverSucceeded = true
            }
        }
        if (serverSucceeded && allAddresses.isNotEmpty()) break
    }
    return allAddresses.distinct() to minTtl
}

private fun resolveViaDot(
    dotHostSpec: String,
    bootstrapServers: List<String>,
    hostname: String,
    qtypes: List<Int>,
): Pair<List<InetAddress>, Long> {
    val (dotHost, dotPort) = parseDnsHostPort(dotHostSpec, defaultPort = 853)
    val dotAddresses = resolveDnsBootstrapAddresses(dotHost, bootstrapServers)
    if (dotAddresses.isEmpty()) {
        throw UnknownHostException("Cannot bootstrap DoT host: $dotHost")
    }
    val allAddresses = mutableListOf<InetAddress>()
    var minTtl = 300L
    for (dotAddr in dotAddresses) {
        val ok = runCatching {
            Socket().use { rawSocket ->
                rawSocket.soTimeout = 4_000
                rawSocket.connect(InetSocketAddress(dotAddr, dotPort), 4_000)
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (sslFactory.createSocket(rawSocket, dotHost, dotPort, true) as SSLSocket).use { sslSocket ->
                    sslSocket.soTimeout = 4_000
                    if (parseFixedAddress(dotHost) == null) {
                        val params = sslSocket.sslParameters
                        params.serverNames = listOf(SNIHostName(dotHost))
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        sslSocket.sslParameters = params
                    }
                    sslSocket.startHandshake()
                    val out = DataOutputStream(sslSocket.outputStream)
                    val input = DataInputStream(sslSocket.inputStream)
                    for (qtype in qtypes) {
                        val txid = Random.nextInt(1, 0xFFFF)
                        val query = buildDnsWireQuery(txid, hostname, qtype)
                        out.writeShort(query.size)
                        out.write(query)
                        out.flush()
                        val respLen = input.readUnsignedShort()
                        if (respLen in 12..65535) {
                            val respBuf = ByteArray(respLen)
                            input.readFully(respBuf)
                            val parsed = parseDnsWireResponse(txid, respBuf, respLen, hostname)
                            if (parsed != null && parsed.addresses.isNotEmpty()) {
                                allAddresses.addAll(parsed.addresses)
                                minTtl = minOf(minTtl, parsed.minTtlSeconds)
                            }
                        }
                    }
                }
            }
        }.isSuccess
        if (ok && allAddresses.isNotEmpty()) break
    }
    return allAddresses.distinct() to minTtl
}

private fun resolveViaDoh(
    dohUrl: String,
    bootstrapServers: List<String>,
    hostname: String,
    qtypes: List<Int>,
): Pair<List<InetAddress>, Long> {
    val client = buildDohClient(bootstrapServers)
    val allAddresses = mutableListOf<InetAddress>()
    var minTtl = 300L
    for (qtype in qtypes) {
        val wireQuery = buildDnsWireQuery(0, hostname, qtype)
        val request = Request.Builder()
            .url(dohUrl)
            .header("Accept", "application/dns-message")
            .post(wireQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
            .build()
        val parsed = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("DoH HTTP ${response.code}")
                }
                val bytes = response.body?.bytes() ?: return@use null
                parseDnsWireResponse(expectedId = null, buf = bytes, length = bytes.size, requestedHost = hostname)
            }
        }.getOrNull()
        if (parsed != null && parsed.addresses.isNotEmpty()) {
            allAddresses.addAll(parsed.addresses)
            minTtl = minOf(minTtl, parsed.minTtlSeconds)
        }
    }
    return allAddresses.distinct() to minTtl
}

private fun buildDohClient(bootstrapServers: List<String>): OkHttpClient {
    cachedDohClient?.let { (servers, client) ->
        if (servers == bootstrapServers) return client
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .dns { dohHost ->
            val list = resolveDnsBootstrapAddresses(dohHost, bootstrapServers)
            if (list.isEmpty()) throw UnknownHostException("Cannot bootstrap DoH host: $dohHost")
            list
        }
        .build()
    cachedDohClient = bootstrapServers to client
    return client
}

private fun resolveDnsBootstrapAddresses(
    host: String,
    bootstrapServers: List<String>,
): List<InetAddress> {
    val clean = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
    parseFixedAddress(clean)?.let { addr ->
        return listOf(InetAddress.getByAddress(clean, addr.address))
    }
    WELL_KNOWN_BOOTSTRAP_IPS[clean]?.let { ips ->
        val resolved = ips.mapNotNull { ip ->
            parseFixedAddress(ip)?.let { InetAddress.getByAddress(clean, it.address) }
        }
        if (resolved.isNotEmpty()) return resolved
    }
    for (server in bootstrapServers) {
        val parsed = runCatching { queryDnsUdpOrTcp(server, 53, clean, AppDnsResolver.QTYPE_A) }.getOrNull()
        if (parsed != null && parsed.addresses.isNotEmpty()) {
            return parsed.addresses
        }
    }
    return emptyList()
}

private fun parseDnsHostPort(spec: String, defaultPort: Int): Pair<String, Int> {
    val trimmed = spec.trim()
    if (trimmed.contains("://")) {
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri != null && !uri.host.isNullOrBlank()) {
            val port = if (uri.port in 1..65535) uri.port else defaultPort
            return uri.host.removeSurrounding("[", "]") to port
        }
    }
    if (trimmed.startsWith("[")) {
        val closeIdx = trimmed.indexOf(']')
        if (closeIdx > 0) {
            val host = trimmed.substring(1, closeIdx)
            val rest = trimmed.substring(closeIdx + 1)
            val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: defaultPort else defaultPort
            return host to port
        }
    }
    val colonCount = trimmed.count { it == ':' }
    if (colonCount == 1) {
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':').toIntOrNull() ?: defaultPort
        return host to port
    }
    return trimmed to defaultPort
}

private fun queryDnsUdpOrTcp(
    serverIp: String,
    port: Int = 53,
    domain: String,
    qtype: Int = AppDnsResolver.QTYPE_A,
    timeoutMs: Int = 2_500,
): AppDnsResolver.DnsParsedPacket? {
    val serverAddr = parseFixedAddress(serverIp) ?: return null
    val txid = Random.nextInt(1, 0xFFFF)
    val query = buildDnsWireQuery(txid, domain, qtype)
    val udpResult = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(serverAddr, port)
            socket.send(DatagramPacket(query, query.size))
            val buf = ByteArray(4_096)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            parseDnsWireResponse(txid, buf, packet.length, domain)
        }
    }.getOrNull()
    if (udpResult != null && !udpResult.truncated) {
        return udpResult
    }
    return runCatching {
        queryDnsTcp(serverAddr, port, txid, query, domain, timeoutMs)
    }.getOrNull() ?: udpResult
}

private fun queryDnsTcp(
    serverAddr: InetAddress,
    port: Int,
    txid: Int,
    query: ByteArray,
    domain: String,
    timeoutMs: Int,
): AppDnsResolver.DnsParsedPacket? {
    Socket().use { socket ->
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(serverAddr, port), timeoutMs)
        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        out.writeShort(query.size)
        out.write(query)
        out.flush()
        val respLen = input.readUnsignedShort()
        if (respLen !in 12..65535) return null
        val buf = ByteArray(respLen)
        input.readFully(buf)
        return parseDnsWireResponse(txid, buf, respLen, domain)
    }
}

private fun buildDnsWireQuery(
    id: Int,
    domain: String,
    qtype: Int,
): ByteArray {
    val asciiDomain = runCatching {
        IDN.toASCII(domain.trim().trimEnd('.'))
    }.getOrDefault(domain.trim().trimEnd('.'))
    val out = ByteArrayOutputStream(64)
    out.write((id shr 8) and 0xFF)
    out.write(id and 0xFF)
    out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    for (label in asciiDomain.split('.')) {
        if (label.isEmpty()) continue
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 63) { "DNS label too long: $label" }
        out.write(bytes.size)
        out.write(bytes)
    }
    out.write(0)
    out.write((qtype shr 8) and 0xFF)
    out.write(qtype and 0xFF)
    out.write((QCLASS_IN shr 8) and 0xFF)
    out.write(QCLASS_IN and 0xFF)
    return out.toByteArray()
}

private fun parseDnsWireResponse(
    expectedId: Int?,
    buf: ByteArray,
    length: Int,
    requestedHost: String,
): AppDnsResolver.DnsParsedPacket? {
    if (length < 12 || length > buf.size) return null
    val txid = readU16(buf, 0)
    if (expectedId != null && txid != expectedId) return null
    val flags = readU16(buf, 2)
    if (flags and 0x8000 == 0) return null
    val qdCount = readU16(buf, 4)
    val anCount = readU16(buf, 6)

    val answerStart = skipQuestionSection(buf, qdCount, length)
    if (answerStart < 0) return null

    val addresses = mutableListOf<InetAddress>()
    var minTtl = Long.MAX_VALUE
    val hostForAddress = requestedHost.trim().takeIf { it.isNotEmpty() }
    var offset = answerStart

    repeat(anCount) {
        val nextOffset = decodeAnswerRecord(buf, offset, length, hostForAddress, addresses) { ttl ->
            if (ttl < minTtl) minTtl = ttl
        }
        if (nextOffset < 0) return null
        offset = nextOffset
    }

    return AppDnsResolver.DnsParsedPacket(
        rcode = flags and 0x000F,
        truncated = (flags and 0x0200) != 0,
        addresses = addresses,
        minTtlSeconds = if (minTtl == Long.MAX_VALUE) 120L else minTtl.coerceIn(30L, 600L),
    )
}

private fun skipQuestionSection(buf: ByteArray, qdCount: Int, length: Int): Int {
    var offset = 12
    repeat(qdCount) {
        offset = skipDnsName(buf, offset, length)
        if (offset < 0 || offset + 4 > length) return -1
        offset += 4
    }
    return offset
}

private inline fun decodeAnswerRecord(
    buf: ByteArray,
    startOffset: Int,
    length: Int,
    hostForAddress: String?,
    outAddresses: MutableList<InetAddress>,
    onTtl: (Long) -> Unit,
): Int {
    val offset = skipDnsName(buf, startOffset, length)
    if (offset < 0 || offset + 10 > length) return -1
    val rrType = readU16(buf, offset)
    val rrClass = readU16(buf, offset + 2)
    val ttl = readU32(buf, offset + 4)
    val rdLength = readU16(buf, offset + 8)
    val rdataOffset = offset + 10
    if (rdataOffset + rdLength > length) return -1
    val isIpRecord = (rrType == AppDnsResolver.QTYPE_A && rdLength == 4) ||
        (rrType == AppDnsResolver.QTYPE_AAAA && rdLength == 16)
    if (rrClass == QCLASS_IN && isIpRecord) {
        val rawBytes = buf.copyOfRange(rdataOffset, rdataOffset + rdLength)
        runCatching {
            if (hostForAddress != null) {
                InetAddress.getByAddress(hostForAddress, rawBytes)
            } else {
                InetAddress.getByAddress(rawBytes)
            }
        }.getOrNull()?.let { addr ->
            outAddresses.add(addr)
            onTtl(ttl)
        }
    }
    return rdataOffset + rdLength
}

private fun skipDnsName(buf: ByteArray, startOffset: Int, length: Int): Int {
    var i = startOffset
    var steps = 0
    while (i < length) {
        if (++steps > 128) return -1
        val b = buf[i].toInt() and 0xFF
        when {
            b == 0 -> return i + 1
            (b and 0xC0) == 0xC0 -> return if (i + 1 < length) i + 2 else -1
            (b and 0xC0) != 0 -> return -1
            else -> i += 1 + b
        }
    }
    return -1
}

private fun readU16(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

private fun readU32(buf: ByteArray, offset: Int): Long =
    ((buf[offset].toLong() and 0xFFL) shl 24) or
        ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
        ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
        (buf[offset + 3].toLong() and 0xFFL)
