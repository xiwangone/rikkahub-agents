package me.rerere.rikkahub.data.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.network.AppDnsResolver
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.random.Random

/**
 * 隧道网络诊断执行器：按 1.1–1.10 顺序跑，每项独立超时、独立失败，不互相拖垮。
 * 取消只认 CancellationException（重抛），其余异常收敛为该项 FAIL。
 * DNS 真解析（1.6b）复用 AppDnsResolver.buildQuery / parseResponse，只自己做
 * TCP 传输（与 AppDnsResolver.queryDnsTcp 同口径：2 字节长度前缀）。
 * 探针连完即关并 stopProxy，不留监听。
 */
class TunnelDiagnosticsRunner(
    private val bridge: TsnetBridge,
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val strings: DiagStrings,
) {
    suspend fun runAll(onItem: (DiagItemState) -> Unit): DiagResult {
        val startedAtMs = System.currentTimeMillis()
        val collected = mutableListOf<DiagItemState>()
        // 只收最终态进汇总；RUNNING 只回调界面。
        val emit: (DiagItemState) -> Unit = { state ->
            if (state.status != DiagStatus.RUNNING) collected += state
            onItem(state)
        }
        val snapshot = runCatching {
            withTimeoutOrNull(8_000) { parseDiagStatusJson(bridge.statusJson()) }
        }.throwIfCancelled().getOrNull()
        val target = snapshot?.takeIf { it.running }?.let { pickProbeTarget(it.peers) }

        runItem(DiagItemId.MODULE, emit) { moduleDecision(snapshot) }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.DEVICE_NETWORK, emit) { deviceNetwork() }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.SYSTEM_DNS, emit) { systemDns() }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.DIRECT, emit) {
            val (status, ms, _) = probeHttp("https://example.com")
            decideDirect(strings, status, ms)
        }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.CONTROL_PLANE, emit) { controlPlane() }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.MAGIC_DNS, emit) { magicDnsDecision(snapshot, target) }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.MAGIC_DNS_RESOLVE, emit) { magicDnsResolveDecision(snapshot, target) }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.PEERS, emit) { peersDecision(snapshot) }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.ROUTES, emit) { routesDecision(snapshot) }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.TAILNET_TCP, emit) { tailnetTcpDecision(snapshot, target) }
        currentCoroutineContext().ensureActive()

        runItem(DiagItemId.TAILNET_PING, emit) { tailnetPingDecision(snapshot, target) }
        return DiagResult(
            items = collected,
            startedAtMs = startedAtMs,
            finishedAtMs = System.currentTimeMillis(),
        )
    }

    private fun moduleDecision(snapshot: DiagSnapshot?): Pair<DiagStatus, String> =
        if (snapshot == null) DiagStatus.FAIL to strings.get(DiagStringKey.SNAPSHOT_FAIL)
        else decideModule(strings, snapshot.running, snapshot.backendState, snapshot.error)

    private fun magicDnsDecision(snapshot: DiagSnapshot?, target: DiagPeer?): Pair<DiagStatus, String> =
        when {
            snapshot == null -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_UNKNOWN)
            !snapshot.running -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_NOT_RUNNING)
            else -> decideMagicDns(strings, target)
        }

    /**
     * 是否官方 Tailscale 控制面：控制面 URL 留空即用默认官方地址，填了就是自建控制面。
     * 内置 DNS（100.100.100.100）只有官方提供，自建控制面一律没有。
     */
    private fun usesOfficialControlPlane(): Boolean =
        settingsStore.settingsFlow.value.networkSetting.tailscaleControlUrl.isBlank()

    private suspend fun magicDnsResolveDecision(
        snapshot: DiagSnapshot?,
        target: DiagPeer?,
    ): Pair<DiagStatus, String> = when {
        snapshot == null -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_UNKNOWN)
        !snapshot.running -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_NOT_RUNNING)
        // 内置 DNS（100.100.100.100）只在官方 Tailscale 提供；自建控制面不下发，探测必然超时。
        !usesOfficialControlPlane() -> DiagStatus.SKIP to strings.get(DiagStringKey.MAGIC_DNS_CUSTOM_SKIP)
        target == null -> DiagStatus.SKIP to strings.get(DiagStringKey.NO_PEER)
        target.dnsName.isBlank() || target.firstIpv4().isBlank() ->
            DiagStatus.SKIP to strings.get(DiagStringKey.MAGIC_MISSING_RESOLVE)
        else -> probeMagicDnsResolve(target.dnsName.trim().trimEnd('.'), target.firstIpv4())
    }

    private fun peersDecision(snapshot: DiagSnapshot?): Pair<DiagStatus, String> =
        if (snapshot == null) DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_UNKNOWN)
        else decidePeers(strings, snapshot.peers)

    private fun routesDecision(snapshot: DiagSnapshot?): Pair<DiagStatus, String> =
        if (snapshot == null) DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_UNKNOWN)
        else decideRoutes(strings, snapshot.peers, snapshot.acceptRoutes, snapshot.exitNodeIP)

    private suspend fun tailnetTcpDecision(
        snapshot: DiagSnapshot?,
        target: DiagPeer?,
    ): Pair<DiagStatus, String> = when {
        snapshot == null -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_UNKNOWN)
        !snapshot.running -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_NOT_RUNNING)
        target == null -> DiagStatus.SKIP to strings.get(DiagStringKey.NO_PEER)
        target.firstIpv4().isBlank() -> DiagStatus.SKIP to strings.get(DiagStringKey.PEER_NO_IP)
        else -> probeTailnetTcp(target.firstIpv4())
    }

    private suspend fun tailnetPingDecision(
        snapshot: DiagSnapshot?,
        target: DiagPeer?,
    ): Pair<DiagStatus, String> = when {
        snapshot == null -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_UNKNOWN)
        !snapshot.running -> DiagStatus.SKIP to strings.get(DiagStringKey.SNAPSHOT_NOT_RUNNING)
        target == null -> DiagStatus.SKIP to strings.get(DiagStringKey.NO_PEER)
        target.firstIpv4().isBlank() -> DiagStatus.SKIP to strings.get(DiagStringKey.PEER_NO_IP)
        else -> {
            val ping = bridge.pingPeerBlocking(target.firstIpv4())
            if (ping.isSuccess) decideTailnetPing(strings, true, ping.getOrNull(), null)
            else decideTailnetPing(strings, false, null, ping.exceptionOrNull()?.message)
        }
    }

    private suspend fun runItem(
        id: DiagItemId,
        onItem: (DiagItemState) -> Unit,
        block: suspend () -> Pair<DiagStatus, String>,
    ): DiagItemState {
        onItem(DiagItemState(id, DiagStatus.RUNNING))
        val result = runCatching {
            if (id.timeoutMs > 0) withTimeoutOrNull(id.timeoutMs) { block() } else block()
        }.throwIfCancelled()
        val pair = result.getOrNull()
        val state = when {
            pair != null -> DiagItemState(id, pair.first, pair.second)
            result.exceptionOrNull() != null ->
                DiagItemState(
                    id,
                    DiagStatus.FAIL,
                    strings.get(
                        DiagStringKey.CHECK_ERROR,
                        result.exceptionOrNull()?.message ?: strings.get(DiagStringKey.UNKNOWN_ERROR),
                    ),
                )
            else -> DiagItemState(id, DiagStatus.FAIL, strings.get(DiagStringKey.CHECK_TIMEOUT, id.timeoutMs / 1000))
        }
        onItem(state)
        return state
    }

    private fun deviceNetwork(): Pair<DiagStatus, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DiagStatus.FAIL to strings.get(DiagStringKey.NETWORK_UNREADABLE)
        val network = cm.activeNetwork ?: return DiagStatus.FAIL to strings.get(DiagStringKey.DEVICE_OFFLINE)
        val caps = cm.getNetworkCapabilities(network) ?: return DiagStatus.FAIL to strings.get(DiagStringKey.DEVICE_OFFLINE)
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return DiagStatus.FAIL to strings.get(DiagStringKey.DEVICE_OFFLINE)
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> strings.get(DiagStringKey.TRANSPORT_CELLULAR)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> strings.get(DiagStringKey.TRANSPORT_ETHERNET)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> strings.get(DiagStringKey.TRANSPORT_OTHER)
        }
        return decideDeviceNetwork(strings, true, transport)
    }

    private suspend fun systemDns(): Pair<DiagStatus, String> = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val result = runCatching { InetAddress.getByName("example.com") }
        val ms = (System.nanoTime() - start) / 1_000_000L
        if (result.isSuccess) decideSystemDns(strings, true, ms, null)
        else decideSystemDns(strings, false, null, result.exceptionOrNull()?.message)
    }

    private suspend fun probeHttp(url: String, timeoutMs: Int = 5_000): Triple<Int?, Long?, String?> =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            val result = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    try {
                        responseCode
                    } finally {
                        disconnect()
                    }
                }
            }
            Triple(result.getOrNull(), (System.nanoTime() - start) / 1_000_000L, result.exceptionOrNull()?.message)
        }

    private suspend fun controlPlane(): Pair<DiagStatus, String> {
        val raw = settingsStore.settingsFlow.value.networkSetting.tailscaleControlUrl
            .ifBlank { NetworkSetting.DEFAULT_TUNNEL_CONTROL_URL }
        val url = runCatching { URL(raw) }.getOrNull()
            ?: return DiagStatus.FAIL to strings.get(DiagStringKey.CONTROL_INVALID, raw)
        val host = url.host.takeIf { it.isNotBlank() }
            ?: return DiagStatus.FAIL to strings.get(DiagStringKey.CONTROL_INVALID, raw)
        val displayHost = if (url.port != -1 && url.port != url.defaultPort) "$host:${url.port}" else host
        val probeUrl = URL(url.protocol, host, url.port, "/").toString()
        val (status, ms, error) = probeHttp(probeUrl)
        return decideControlPlane(strings, displayHost, status, ms, error)
    }

    /**
     * 1.6b：经 100.100.100.100:53 的本地代理端口发 TCP DNS A 查询。
     * 编解码复用 AppDnsResolver；传输失败（非超时）直接报传输错误。
     */
    private suspend fun probeMagicDnsResolve(domain: String, netmapIp: String): Pair<DiagStatus, String> =
        withContext(Dispatchers.IO) {
            val proxy = bridge.proxyTo("100.100.100.100", 53)
            val localPort = proxy.getOrNull()
                ?: return@withContext DiagStatus.FAIL to
                    strings.get(DiagStringKey.DNS_PROXY_FAIL) +
                        (proxy.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                            ?.let { strings.get(DiagStringKey.ERROR_SUFFIX, it) } ?: "")
            try {
                val txid = Random.nextInt(1, 0xFFFF)
                val query = AppDnsResolver.buildQuery(txid, domain, AppDnsResolver.QTYPE_A)
                val outcome = runCatching { queryDnsTcp("127.0.0.1", localPort, txid, query, domain) }
                    .throwIfCancelled()
                val parsed = outcome.getOrNull()
                val timedOut = outcome.exceptionOrNull() is SocketTimeoutException
                if (parsed == null && !timedOut) {
                    val msg = outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        ?.let { strings.get(DiagStringKey.ERROR_SUFFIX, it) } ?: ""
                    return@withContext DiagStatus.FAIL to strings.get(DiagStringKey.RESOLVE_TRANSPORT_ERROR, msg)
                }
                val rcode = parsed?.rcode
                val addresses = parsed?.addresses?.mapNotNull { it.hostAddress } ?: emptyList()
                decideDnsResolve(strings, rcode, addresses, timedOut, netmapIp, domain)
            } finally {
                bridge.stopProxy(localPort)
            }
        }

    /** 与 AppDnsResolver.queryDnsTcp 同口径的 TCP DNS 传输（该函数是 private，无法复用）。 */
    private fun queryDnsTcp(
        host: String,
        port: Int,
        txid: Int,
        query: ByteArray,
        domain: String,
        timeoutMs: Int = 5_000,
    ): AppDnsResolver.DnsParsedPacket? {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.writeShort(query.size)
            out.write(query)
            out.flush()
            val respLen = input.readUnsignedShort()
            if (respLen !in 12..65535) return null
            val buf = ByteArray(respLen)
            input.readFully(buf)
            return AppDnsResolver.parseResponse(txid, buf, respLen, domain)
        }
    }

    /** 1.9：经对端 :80 的本地代理端口发 HTTP 请求，必须读到横幅才算通过。 */
    private suspend fun probeTailnetTcp(targetIp: String): Pair<DiagStatus, String> =
        withContext(Dispatchers.IO) {
            val proxy = bridge.proxyTo(targetIp, 80)
            val localPort = proxy.getOrNull()
                ?: return@withContext DiagStatus.FAIL to
                    strings.get(DiagStringKey.PROXY_FAIL) +
                        (proxy.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                            ?.let { strings.get(DiagStringKey.ERROR_SUFFIX, it) } ?: "")
            try {
                val read = runCatching {
                    Socket().use { socket ->
                        socket.soTimeout = 5_000
                        socket.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                        val out = socket.getOutputStream()
                        out.write("GET / HTTP/1.0\r\nHost: $targetIp\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                        val buf = ByteArray(512)
                        val n = socket.getInputStream().read(buf)
                        if (n <= 0) null
                        else String(buf, 0, n, Charsets.US_ASCII).lineSequence().firstOrNull()
                            ?.trim()?.takeIf { it.startsWith("HTTP/") }
                    }
                }.throwIfCancelled()
                if (read.isSuccess) decideTailnetTcp(strings, true, read.getOrNull(), null)
                else decideTailnetTcp(strings, false, null, read.exceptionOrNull()?.message)
            } finally {
                bridge.stopProxy(localPort)
            }
        }
}

private fun <T> Result<T>.throwIfCancelled(): Result<T> {
    val e = exceptionOrNull()
    if (e is CancellationException) throw e
    return this
}
