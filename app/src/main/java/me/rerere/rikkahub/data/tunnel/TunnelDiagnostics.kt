package me.rerere.rikkahub.data.tunnel

import org.json.JSONObject

/**
 * 隧道网络诊断：纯类型与纯判定（零 Android 依赖，本地 kotlinc 可直编）。
 *
 * 检查项（按执行顺序，超时为单项预算）：
 * 1.1 模块状态 / 1.2 本机联网 / 1.3 系统 DNS / 1.4 直连目标站 / 1.5 控制面连通 /
 * 1.6 MagicDNS 记录 / 1.6b MagicDNS 真实解析 / 1.7 对端列表 / 1.8 出口与路由 /
 * 1.9 尾网 TCP / 1.10 尾网 Ping。
 * 1.6 只查 netmap 记录存在（标题写「记录」不写「解析成功」）；真解析是 1.6b 的事，
 * 它复用 AppDnsResolver 的 buildQuery / parseResponse，只自己做 TCP 传输
 * （Socket + 2 字节长度前缀，与 AppDnsResolver.queryDnsTcp 同口径）。
 * 明细文案经 DiagStrings 解析（调用方传入实现），复制文本跟系统语言走。
 */
enum class DiagStatus { PASS, FAIL, SKIP, RUNNING, PENDING }

/**
 * 诊断明细文案 key：与 strings.xml 的 tunnel_diag_detail_* 一一对应。
 * STATUS_* 五项复用既有的 tunnel_diag_status_*。
 */
enum class DiagStringKey {
    MODULE_NOT_STARTED,
    MODULE_RUNNING,
    MODULE_BAD_STATE,
    DEVICE_CONNECTED,
    DEVICE_OFFLINE,
    DNS_OK,
    DNS_FAIL,
    DIRECT_OK,
    DIRECT_FAIL_HTTP,
    DIRECT_FAIL_UNREACHABLE,
    CONTROL_OK,
    CONTROL_FAIL,
    NO_PEER,
    MAGIC_MISSING,
    MAGIC_FOUND,
    RESOLVE_TIMEOUT,
    RESOLVE_NO_RESPONSE,
    RESOLVE_NXDOMAIN,
    RESOLVE_RCODE,
    RESOLVE_NO_A,
    RESOLVE_OUTSIDE,
    RESOLVE_MATCH,
    RESOLVE_MISMATCH,
    PEERS_NONE,
    PEERS_ONLINE,
    PEERS_ALL_OFFLINE,
    ROUTES_EXIT_UNSET,
    ROUTES_EXIT,
    ROUTES_ACCEPT_ON,
    ROUTES_ACCEPT_OFF,
    ROUTES,
    TCP_BANNER,
    TCP_NO_BANNER,
    TCP_FAIL,
    PING_OK,
    PING_FAIL,
    ERROR_SUFFIX,
    PAREN_SUFFIX,
    COPY_HEADER,
    COPY_ITEM,
    SNAPSHOT_FAIL,
    SNAPSHOT_UNKNOWN,
    SNAPSHOT_NOT_RUNNING,
    MAGIC_MISSING_RESOLVE,
    MAGIC_DNS_CUSTOM_SKIP,
    PEER_NO_IP,
    CHECK_ERROR,
    UNKNOWN_ERROR,
    CHECK_TIMEOUT,
    NETWORK_UNREADABLE,
    TRANSPORT_CELLULAR,
    TRANSPORT_ETHERNET,
    TRANSPORT_OTHER,
    CONTROL_INVALID,
    DNS_PROXY_FAIL,
    PROXY_FAIL,
    STATUS_PASS,
    STATUS_FAIL,
    STATUS_SKIP,
    STATUS_RUNNING,
    STATUS_PENDING,
}

/**
 * 明细文案解析器：把 DiagStringKey 与参数格式化成展示文案。
 * data 层不持有 Android 资源，调用方传入实现（页面与执行器用 Context 资源实现，单测用中文表实现）。
 */
fun interface DiagStrings {
    fun get(key: DiagStringKey, vararg args: Any): String
}

/** 超时为单项预算（毫秒）；0 表示纯读快照、不设超时。 */
enum class DiagItemId(val displayNo: String, val timeoutMs: Long) {
    MODULE("1.1", 8_000),
    DEVICE_NETWORK("1.2", 1_000),
    SYSTEM_DNS("1.3", 5_000),
    DIRECT("1.4", 8_000),
    CONTROL_PLANE("1.5", 8_000),
    MAGIC_DNS("1.6", 0),
    MAGIC_DNS_RESOLVE("1.6b", 10_000),
    PEERS("1.7", 0),
    ROUTES("1.8", 0),
    TAILNET_TCP("1.9", 10_000),
    TAILNET_PING("1.10", 10_000),
}

data class DiagItemState(
    val id: DiagItemId,
    val status: DiagStatus,
    val detail: String = "",
)

/** data 层自用的对端快照（设置页的对端类型是 internal，data 层不可见，故另建一份）。 */
data class DiagPeer(
    val hostName: String,
    val dnsName: String,
    val os: String,
    val ips: List<String>,
    val online: Boolean,
    val exitNode: Boolean,
    val active: Boolean,
    val relay: String,
    val curAddr: String,
)

/** data 层自用的节点快照（设置页的状态类型是 internal，data 层不可见，故另建一份）。 */
data class DiagSnapshot(
    val running: Boolean,
    val backendState: String,
    val error: String?,
    val dnsName: String,
    val hostName: String,
    val tailscaleIPs: List<String>,
    val peers: List<DiagPeer>,
    val acceptRoutes: Boolean,
    val exitNodeIP: String,
)

data class DiagSummary(val pass: Int, val fail: Int, val skip: Int)

data class DiagResult(
    val items: List<DiagItemState>,
    val startedAtMs: Long,
    val finishedAtMs: Long,
) {
    val summary: DiagSummary
        get() = DiagSummary(
            pass = items.count { it.status == DiagStatus.PASS },
            fail = items.count { it.status == DiagStatus.FAIL },
            skip = items.count { it.status == DiagStatus.SKIP },
        )
}

/** 是否落在 Tailscale IPv4 CGNAT 段 100.64.0.0/10 内。 */
fun isTailnetIpv4(ip: String): Boolean {
    val parts = ip.trim().split(".")
    if (parts.size != 4) return false
    val nums = parts.map { it.toIntOrNull() ?: return false }
    return nums[0] == 100 && nums[1] in 64..127 && nums[2] in 0..255 && nums[3] in 0..255
}

/** 对端短名：主机名优先，退化到 MagicDNS 名去掉 .ts.net 后缀。 */
fun shortPeerName(peer: DiagPeer): String {
    val host = peer.hostName.trim()
    if (host.isNotEmpty()) return host
    return peer.dnsName.trim().trimEnd('.')
        .removeSuffix(".ts.net").removeSuffix(".beta.tailscale.net")
        .substringBefore('.').ifBlank { peer.dnsName.trim() }
}

/** 对端首个 IPv4 地址；没有则为空串。 */
fun DiagPeer.firstIpv4(): String =
    ips.firstOrNull { it.count { c -> c == '.' } == 3 } ?: ""

/**
 * 探针目标：首个在线且最近活跃的对端；没有活跃的退化到首个在线对端；全离线返回 null。
 * 1.6 / 1.6b / 1.9 / 1.10 共用同一目标，诊断口径一致。
 */
fun pickProbeTarget(peers: List<DiagPeer>): DiagPeer? =
    peers.firstOrNull { it.online && it.active } ?: peers.firstOrNull { it.online }

/**
 * 异常信息进明细/复制文本前截断并去换行：当前错误源不回显密钥，但无截断、无白名单，
 * 若错误源某天在错误串里带上主机/路径/配置片段会直接外泄；snapshot.error 同理。
 */
internal fun errorDetail(error: String): String =
    error.replace('\n', ' ').replace('\r', ' ').trim().take(120)

/** 1.1 模块状态：后端必须是 Running 才算通过。 */
fun decideModule(strings: DiagStrings, running: Boolean, backendState: String, error: String?): Pair<DiagStatus, String> =
    when {
        !running -> DiagStatus.FAIL to strings.get(DiagStringKey.MODULE_NOT_STARTED) +
            (error?.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.ERROR_SUFFIX, errorDetail(it)) }
                ?: backendState.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.PAREN_SUFFIX, it) }
                ?: "")
        backendState == "Running" -> DiagStatus.PASS to strings.get(DiagStringKey.MODULE_RUNNING)
        else -> DiagStatus.FAIL to strings.get(DiagStringKey.MODULE_BAD_STATE, backendState)
    }

/** 1.2 本机联网。 */
fun decideDeviceNetwork(strings: DiagStrings, connected: Boolean, transport: String): Pair<DiagStatus, String> =
    if (connected) DiagStatus.PASS to strings.get(DiagStringKey.DEVICE_CONNECTED, transport)
    else DiagStatus.FAIL to strings.get(DiagStringKey.DEVICE_OFFLINE)

/** 1.3 系统 DNS：解析 example.com。 */
fun decideSystemDns(strings: DiagStrings, ok: Boolean, latencyMs: Long?, error: String?): Pair<DiagStatus, String> =
    if (ok) DiagStatus.PASS to strings.get(DiagStringKey.DNS_OK, latencyMs ?: 0)
    else DiagStatus.FAIL to strings.get(DiagStringKey.DNS_FAIL) +
        (error?.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.ERROR_SUFFIX, errorDetail(it)) } ?: "")

/** 1.4 直连目标站：HTTPS GET example.com。 */
fun decideDirect(strings: DiagStrings, httpStatus: Int?, latencyMs: Long?): Pair<DiagStatus, String> =
    when {
        httpStatus != null && httpStatus in 200..299 ->
            DiagStatus.PASS to strings.get(DiagStringKey.DIRECT_OK, httpStatus, latencyMs ?: 0)
        httpStatus != null -> DiagStatus.FAIL to strings.get(DiagStringKey.DIRECT_FAIL_HTTP, httpStatus)
        else -> DiagStatus.FAIL to strings.get(DiagStringKey.DIRECT_FAIL_UNREACHABLE)
    }

/** 1.5 控制面连通：任意 HTTP 响应都算可达（根路径不保证返回 200）。 */
fun decideControlPlane(strings: DiagStrings, host: String, httpStatus: Int?, latencyMs: Long?, error: String?): Pair<DiagStatus, String> =
    when {
        httpStatus != null -> DiagStatus.PASS to strings.get(DiagStringKey.CONTROL_OK, host, httpStatus, latencyMs ?: 0)
        else -> DiagStatus.FAIL to strings.get(DiagStringKey.CONTROL_FAIL, host) +
            (error?.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.ERROR_SUFFIX, errorDetail(it)) } ?: "")
    }

/** 1.6 MagicDNS 记录：只查 netmap 里有没有该对端的记录，不做真解析。 */
fun decideMagicDns(strings: DiagStrings, target: DiagPeer?): Pair<DiagStatus, String> =
    if (target == null) DiagStatus.SKIP to strings.get(DiagStringKey.NO_PEER)
    else {
        val dns = target.dnsName.trim().trimEnd('.')
        val ip = target.firstIpv4()
        if (dns.isBlank() || ip.isBlank()) DiagStatus.FAIL to strings.get(DiagStringKey.MAGIC_MISSING, shortPeerName(target))
        else DiagStatus.PASS to strings.get(DiagStringKey.MAGIC_FOUND, shortPeerName(target), dns, ip)
    }

/**
 * 1.6b MagicDNS 真实解析：对 1.6 的目标发 A 查询。
 * rcode: null = 无有效响应；3 = NXDOMAIN。addresses 为解析到的地址（hostAddress 串）。
 */
fun decideDnsResolve(
    strings: DiagStrings,
    rcode: Int?,
    addresses: List<String>,
    timedOut: Boolean,
    netmapIp: String,
    domain: String,
): Pair<DiagStatus, String> = when {
    timedOut -> DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_TIMEOUT)
    rcode == null -> DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_NO_RESPONSE)
    rcode == 3 -> DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_NXDOMAIN)
    rcode != 0 -> DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_RCODE, rcode)
    addresses.isEmpty() -> DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_NO_A)
    else -> {
        val inRange = addresses.firstOrNull { isTailnetIpv4(it) }
        if (inRange == null) DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_OUTSIDE, domain, addresses.first())
        else if (inRange == netmapIp) DiagStatus.PASS to strings.get(DiagStringKey.RESOLVE_MATCH, domain, inRange)
        // 解析到尾网段内、但与 netmap 记录不一致的地址：DNS 答案是错的（过期/配错），
        // 按它拨号会连到错误的对端，不能判 PASS。
        else DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_MISMATCH, domain, inRange, netmapIp)
    }
}

/** 1.7 对端列表。 */
fun decidePeers(strings: DiagStrings, peers: List<DiagPeer>): Pair<DiagStatus, String> =
    when {
        peers.isEmpty() -> DiagStatus.FAIL to strings.get(DiagStringKey.PEERS_NONE)
        peers.any { it.online } -> {
            val online = peers.count { it.online }
            DiagStatus.PASS to strings.get(DiagStringKey.PEERS_ONLINE, online, peers.size)
        }
        else -> DiagStatus.FAIL to strings.get(DiagStringKey.PEERS_ALL_OFFLINE, peers.size)
    }

/** 1.8 出口与路由：回读守护进程的生效设置（只展示，不做价值判断）。 */
fun decideRoutes(strings: DiagStrings, peers: List<DiagPeer>, acceptRoutes: Boolean, exitNodeIP: String): Pair<DiagStatus, String> {
    val ip = exitNodeIP.trim().takeIf { it.isNotBlank() && it != "<nil>" && it != "invalid IP" }
    val exitPart = if (ip == null) strings.get(DiagStringKey.ROUTES_EXIT_UNSET)
    else {
        val peer = peers.firstOrNull { p -> p.ips.any { it == ip } }
        val name = peer?.hostName?.takeIf { it.isNotBlank() }
        strings.get(DiagStringKey.ROUTES_EXIT, name ?: ip)
    }
    val routesPart = if (acceptRoutes) strings.get(DiagStringKey.ROUTES_ACCEPT_ON) else strings.get(DiagStringKey.ROUTES_ACCEPT_OFF)
    return DiagStatus.PASS to strings.get(DiagStringKey.ROUTES, exitPart, routesPart)
}

/** 1.9 尾网 TCP：必须读到 HTTP 横幅才算通过（StartProxy 是懒拨号，连上不代表通）。 */
fun decideTailnetTcp(strings: DiagStrings, ok: Boolean, banner: String?, error: String?): Pair<DiagStatus, String> =
    when {
        ok && banner != null -> DiagStatus.PASS to strings.get(DiagStringKey.TCP_BANNER, banner.take(40))
        // 连上就算通：对面不是 HTTP 服务（读不到响应）不影响「尾网 TCP 可达」这个结论。
        ok -> DiagStatus.PASS to strings.get(DiagStringKey.TCP_NO_BANNER)
        else -> DiagStatus.FAIL to strings.get(DiagStringKey.TCP_FAIL) +
            (error?.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.ERROR_SUFFIX, errorDetail(it)) } ?: "")
    }

/** 1.10 尾网 Ping（TSMP）。 */
fun decideTailnetPing(strings: DiagStrings, ok: Boolean, latencyMs: Long?, error: String?): Pair<DiagStatus, String> =
    if (ok) DiagStatus.PASS to strings.get(DiagStringKey.PING_OK, latencyMs ?: 0)
    else DiagStatus.FAIL to strings.get(DiagStringKey.PING_FAIL) +
        (error?.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.ERROR_SUFFIX, errorDetail(it)) } ?: "")

/** 解析 Go 侧 StatusJSON；字段缺失取默认值，解析失败返回 null。 */
fun parseDiagStatusJson(raw: String?): DiagSnapshot? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(raw)
        val peers = mutableListOf<DiagPeer>()
        o.optJSONArray("peers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val ips = mutableListOf<String>()
                p.optJSONArray("ips")?.let { a -> for (j in 0 until a.length()) ips += a.optString(j) }
                peers += DiagPeer(
                    hostName = p.optString("hostName"),
                    dnsName = p.optString("dnsName"),
                    os = p.optString("os"),
                    ips = ips,
                    online = p.optBoolean("online"),
                    exitNode = p.optBoolean("exitNode"),
                    active = p.optBoolean("active", true),
                    relay = p.optString("relay"),
                    curAddr = p.optString("curAddr"),
                )
            }
        }
        val selfIps = mutableListOf<String>()
        o.optJSONArray("tailscaleIPs")?.let { a -> for (j in 0 until a.length()) selfIps += a.optString(j) }
        DiagSnapshot(
            running = o.optBoolean("running"),
            backendState = o.optString("backendState"),
            error = o.optString("error").takeIf { it.isNotBlank() },
            dnsName = o.optString("dnsName"),
            hostName = o.optString("hostName"),
            tailscaleIPs = selfIps,
            peers = peers,
            acceptRoutes = o.optBoolean("acceptRoutes"),
            exitNodeIP = o.optString("exitNodeIP"),
        )
    }.getOrNull()
}

private fun statusWord(strings: DiagStrings, status: DiagStatus): String = when (status) {
    DiagStatus.PASS -> strings.get(DiagStringKey.STATUS_PASS)
    DiagStatus.FAIL -> strings.get(DiagStringKey.STATUS_FAIL)
    DiagStatus.SKIP -> strings.get(DiagStringKey.STATUS_SKIP)
    DiagStatus.RUNNING -> strings.get(DiagStringKey.STATUS_RUNNING)
    DiagStatus.PENDING -> strings.get(DiagStringKey.STATUS_PENDING)
}

/**
 * 复制文本：标题取调用方传入的本地化标题（titles 缺失时退化到英文 id 名）；
 * 只写对端短名 + 尾网 IP；控制面只出现 host，不含完整 URL / path / query / 密钥
 * （CONTROL_INVALID 的地址已在 controlPlane() 里只取 host，解析不出 host 时
 * 截断 64 字符并去 query/fragment）；不写 auth key 与凭证名。
 */
fun buildCopyText(
    strings: DiagStrings,
    items: List<DiagItemState>,
    titles: Map<DiagItemId, String>,
    startedAtMs: Long,
    finishedAtMs: Long,
): String {
    val summary = DiagSummary(
        pass = items.count { it.status == DiagStatus.PASS },
        fail = items.count { it.status == DiagStatus.FAIL },
        skip = items.count { it.status == DiagStatus.SKIP },
    )
    val elapsedSec = ((finishedAtMs - startedAtMs).coerceAtLeast(0L) + 500L) / 1000L
    val date = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.time.Instant.ofEpochMilli(finishedAtMs).atZone(java.time.ZoneId.systemDefault()))
    return buildString {
        appendLine(strings.get(DiagStringKey.COPY_HEADER, date, elapsedSec, summary.pass, summary.fail, summary.skip))
        for (item in items) {
            val title = titles[item.id] ?: item.id.name
            val detail = item.detail.takeIf { it.isNotBlank() }?.let { strings.get(DiagStringKey.PAREN_SUFFIX, it) } ?: ""
            appendLine(strings.get(DiagStringKey.COPY_ITEM, item.id.displayNo, title, statusWord(strings, item.status), detail))
        }
    }.trimEnd()
}
