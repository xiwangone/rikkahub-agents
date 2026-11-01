package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.log.AppLog

import me.rerere.rikkahub.data.vault.message
import me.rerere.rikkahub.data.vault.ensureTrailingNewline
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.ui.pages.setting.doctor.DnsProbe
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Properties

private const val TAG_SSH = "SshTool"

/**
 * Resolves [host] to an IPv4 address string. JSch's `Socket(addr, port)` does NOT implement
 * Happy Eyeballs — it sits on the IPv6 SYN until the connect timeout fires, even when the
 * server only listens on IPv4. Termux's OpenSSH races both stacks in parallel and never
 * sees this failure mode, which is why `ssh user@host` from Termux works while our direct
 * JSch path on the same Pixel times out.
 *
 * Returns null if [host] is already a literal IP, if no IPv4 record exists, or if DNS fails.
 * Caller falls back to the original [host] string in that case.
 *
 * Successful resolutions are memoised in [sshDnsCache] (60s TTL) so a workflow that SSHes to
 * the same host on a tight interval doesn't pay a DNS round-trip every connect. A literal IP
 * input bypasses the cache entirely — it's not a name, there's nothing to resolve or stale.
 * Failures are never cached, so a transient DNS hiccup can't poison the next 60s of connects.
 * The 60s TTL is kept unchanged: an independently-resolved real IP is cached like any other
 * success (so a hijacked-network retry stays fast), while a fake-IP answer is treated as a
 * failure and never stored — a fake mapping can flip to a different host at any time.
 *
 * When the system resolver hands back an address from a reserved fake-IP block (a proxy/VPN
 * that owns DNS), the value is reported in [SshResolution.fakeIp] and [resolveViaPublicDns]
 * is tried once to recover the real address. On a normally-resolving network nothing here
 * changes: the fake-IP branch is unreachable, so behaviour is byte-for-byte the pre-change one.
 */
private fun resolveToIPv4(host: String, context: Context? = null): SshResolution {
    sshDnsCache.get(host)?.let { cached ->
        AppLog.i(TAG_SSH, "resolveToIPv4: $host -> $cached (dns cache hit)")
        return SshResolution(cached)
    }
    return runCatching {
        val addrs = InetAddress.getAllByName(host)
        // Already a literal IP (v4 or v6)? getAllByName returns it as-is and the printed
        // hostAddress equals the input (modulo case). Don't try to "resolve" it further.
        if (addrs.isNotEmpty() && addrs[0].hostAddress.equals(host, ignoreCase = true)) return SshResolution(null)
        val v4 = addrs.firstOrNull { it is Inet4Address } ?: return SshResolution(null)
        val ip = v4.hostAddress ?: return SshResolution(null)
        if (isFakeIpRange(ip)) {
            AppLog.w(TAG_SSH, "resolveToIPv4: $host -> $ip is a reserved fake-IP block address, retrying via public DNS")
            val real = resolveViaPublicDns(host, context)
            if (real != null) {
                sshDnsCache.put(host, real)
                return@runCatching SshResolution(real, fakeIp = ip)
            }
            return@runCatching SshResolution(null, fakeIp = ip)
        }
        sshDnsCache.put(host, ip)
        AppLog.i(TAG_SSH, "resolveToIPv4: $host -> $ip (skipping ${addrs.size - 1} other records)")
        SshResolution(ip)
    }.getOrElse { t ->
        AppLog.w(TAG_SSH, "resolveToIPv4: $host failed", t)
        SshResolution(null)
    }
}

/**
 * What [resolveToIPv4] produced. [ip] = address to dial, null = dial the original host string
 * instead (literal IP, no A record, or DNS failure — exactly the cases that returned null
 * before this type existed). [fakeIp] = reserved-block address the system resolver returned,
 * non-null only when DNS was hijacked.
 */
internal data class SshResolution(val ip: String?, val fakeIp: String? = null)

/**
 * Reserved fake-IP blocks that DNS-hijacking proxies (clash / sing-box `fake-ip` mode) hand out
 * for domains routed through them. 198.18.0.0/15 spans 198.18.0.0–198.19.255.255 and covers
 * both blocks seen in the field. Such an address is only reachable *inside* the VPN's own
 * stack; our SSH sockets are deliberately bound to WiFi/Ethernet/Cellular transports, so the
 * handshake can never complete.
 */
internal fun isFakeIpRange(ip: String): Boolean {
    val octets = ip.split('.')
    if (octets.size != 4) return false
    val first = octets[0].toIntOrNull() ?: return false
    val second = octets[1].toIntOrNull() ?: return false
    if (first !in 1..254 || second !in 0..255) return false
    return first == 198 && (second == 18 || second == 19)
}

/**
 * Resolve [host] without touching the system resolver: raw UDP A queries straight at
 * well-known public resolvers. The system resolver is the component a VPN replaces, so going
 * around it is the only way to see the real address while DNS is hijacked.
 *
 * Servers = the active network's own DNS (when we can read one and it is not itself inside a
 * reserved block) followed by [DnsProbe.PUBLIC_SERVERS]. Queries are sequential and bounded:
 * at most [INDEPENDENT_DNS_SERVER_LIMIT] servers × [INDEPENDENT_DNS_TIMEOUT_MS], and this path
 * only runs in the already-broken fake-IP case. Returns the first non-fake IPv4 answer, else
 * null (caller then surfaces [FAKE_IP_ERROR] rather than silently dialing the fake address).
 */
private fun resolveViaPublicDns(host: String, context: Context?): String? {
    val linkServers = context?.let { ctx ->
        runCatching { DnsProbe.activeServers(ctx) }.getOrNull().orEmpty()
    }.orEmpty()
    val servers = (linkServers + DnsProbe.PUBLIC_SERVERS)
        .filterNot { it.isBlank() || isFakeIpRange(it) }
        .distinct()
        .take(INDEPENDENT_DNS_SERVER_LIMIT)
    for (server in servers) {
        val answer = runCatching { DnsProbe.probe(server, host, INDEPENDENT_DNS_TIMEOUT_MS).answerIp }
            .getOrNull()
        if (!answer.isNullOrEmpty() && !isFakeIpRange(answer)) {
            AppLog.i(TAG_SSH, "resolveViaPublicDns: $host -> $answer (via $server)")
            return answer
        }
    }
    AppLog.w(TAG_SSH, "resolveViaPublicDns: $host got no real address from ${servers.size} resolver(s)")
    return null
}

/** Thrown when DNS came back hijacked and no public resolver could supply the real address. */
internal class FakeIpResolutionException(
    val host: String,
    val fakeIp: String,
) : IOException(
    "dns_hijacked: $host resolved to the reserved fake-IP block address $fakeIp, " +
        "which is only reachable through the VPN that minted it",
)

/**
 * Returns every Network the device has, ordered for the SSH probe. Each entry is a
 * (label, network-or-null) pair. The null entry means "do not bind — let Android pick the
 * default route." All WiFi networks the device exposes are included individually (a phone
 * may have a saved-but-inactive hotspot AND the active WiFi visible at the same time;
 * filtering down to one would skip the working network on adaptive-routing devices).
 *
 * WiFi networks are sorted with VALIDATED-INTERNET ones first so the probe doesn't waste
 * its first slot on a captive-portal WiFi when a real one is available. With the parallel
 * probe in [probeReachability], having more candidates costs no extra latency anyway.
 */
private fun enumerateCandidateNetworks(ctx: Context): List<Pair<String, Network?>> {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return listOf("default" to null)
    val out = mutableListOf<Pair<String, Network?>>()
    // allNetworks deprecated at API 31; the recommended NetworkCallback path is async and
    // doesn't fit our synchronous enumeration here. Behaviour-equivalent and still functional.
    @Suppress("DEPRECATION")
    val all = try { cm.allNetworks.toList() } catch (_: Throwable) { emptyList() }
    fun caps(n: Network) = try { cm.getNetworkCapabilities(n) } catch (_: Throwable) { null }

    val wifis = all.filter { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        .sortedByDescending {
            val c = caps(it)
            val validated = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val internet = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            (if (validated) 2 else 0) + (if (internet) 1 else 0)
        }
    wifis.forEachIndexed { i, n ->
        out += (if (wifis.size == 1) "wifi" else "wifi$i") to n
    }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ?.let { out += "ethernet" to it }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
        ?.let { out += "cellular" to it }
    out += "default" to null
    return out
}

/**
 * Auth bundle. Either [password] or [privateKey] must be non-blank for connect to succeed.
 */
internal data class SshAuth(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)

internal fun SshAuth.isUsable() = !password.isNullOrBlank() || !privateKey.isNullOrBlank()

/** 跳板主机描述：连上它之后经它直达目标。由 SshHostsTool 从 jumpHost 名解析而来。 */
internal data class JumpSpec(
    val host: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
)

/**
 * Construct a JSch instance pre-loaded with the app's persistent known_hosts file. New host
 * keys are automatically appended ("StrictHostKeyChecking=accept-new"); changed keys cause
 * the connect to fail (MITM protection).
 */
internal fun newJSch(context: Context): JSch {
    // 兼容老 SSH 服务端（如小米路由 dropbear 2017.75 只提供 ssh-rsa host key）：
    // 把 ssh-rsa 追加到 server_host_key 候选列表末尾，新算法优先、老设备也能连。
    // JSch.setConfig 是全局配置，对所有后续 newJSch() 实例生效。
    JSch.setConfig(
        "server_host_key",
        "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
            "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
    )
    val jsch = JSch()
    val knownHosts = knownHostsFile(context)
    if (!knownHosts.exists()) {
        try { knownHosts.createNewFile() } catch (_: Throwable) {}
    }
    try { jsch.setKnownHosts(knownHosts.absolutePath) } catch (_: Throwable) {}
    return jsch
}

internal fun knownHostsFile(context: Context): File = File(context.filesDir, "known_hosts")

/**
 * Open a connected SSH session. Caller MUST call [Session.disconnect] in a finally block.
 * Throws on auth/connect failure (caller wraps in try/catch and surfaces a JSON error).
 */
internal fun openSshSession(
    jsch: JSch,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
    network: Network? = null,
    /** 自定义 ssh 选项文本（每行 "键 值"），覆盖默认 setConfig 属性 */
    extraOptions: String? = null,
): Session {
    if (!auth.privateKey.isNullOrBlank()) {
        val keyBytes = auth.privateKey.ensureTrailingNewline().toByteArray(Charsets.UTF_8)
        val passBytes = auth.passphrase?.toByteArray(Charsets.UTF_8)
        jsch.addIdentity("rikkahub-ssh-key-${System.nanoTime()}", keyBytes, null, passBytes)
    }
    // Force IPv4 resolution before handing the address to JSch. Solves the "ssh works in
    // Termux but times out in our app" Pixel-only failure: Android's DnsResolver returns
    // AAAA first and JSch sits on the IPv6 SYN for the full timeout instead of falling
    // back to v4. setHostKeyAlias keeps known_hosts comparisons keyed by the human-readable
    // hostname so trust-on-first-use still works the same.
    val resolution = resolveToIPv4(host)
    if (resolution.ip == null && resolution.fakeIp != null) {
        // Dialing the fake address (or the raw hostname, which the system resolver would map
        // to the same fake address) can never succeed from this process — say so precisely
        // instead of letting JSch time out into a generic connect_failed.
        throw FakeIpResolutionException(host, resolution.fakeIp)
    }
    val ipv4 = resolution.ip
    val effectiveHost = ipv4 ?: host
    val session = jsch.getSession(user, effectiveHost, port)
    if (ipv4 != null && ipv4 != host) {
        session.setHostKeyAlias(host)
    }
    if (!auth.password.isNullOrBlank()) session.setPassword(auth.password)
    session.setConfig(Properties().apply {
        // accept-new: trust on first use, fail if a known host's key changes
        setProperty("StrictHostKeyChecking", "accept-new")
        setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
        // 自定义选项最后应用，可覆盖默认值（每行 "键 值"，# 开头为注释）
        extraOptions?.lineSequence()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val sp = trimmed.indexOf(' ')
            if (sp > 0) {
                setProperty(trimmed.substring(0, sp), trimmed.substring(sp + 1).trim())
            }
        }
    })
    // Always install our custom socket factory so JSch's internal sockets get a bounded
    // connect timeout. When [network] is non-null we ALSO bind sockets to that specific
    // Network so SSH traffic stays on the chosen transport, bypassing Android's adaptive
    // routing that would otherwise re-route app traffic away from the chosen WiFi LAN.
    session.setSocketFactory(NetworkBoundSocketFactory(network, SOCKET_CONNECT_TIMEOUT_MS))
    // Keep-alive: send a server-alive probe every 30s of channel idle, give up after 3
    // unanswered. Without this an intermediate NAT/firewall silently drops the connection's
    // state entry during a long-running command and the session black-holes. This is the
    // single shared session-build path for ssh_exec, ssh_exec_saved, and SFTP
    // upload/download, so setting it here covers every JSch session in the app.
    session.serverAliveInterval = sshOptionInt(extraOptions, "ServerAliveInterval") ?: SERVER_ALIVE_INTERVAL_MS
    session.serverAliveCountMax = sshOptionInt(extraOptions, "ServerAliveCountMax") ?: SERVER_ALIVE_COUNT_MAX
    session.connect(timeoutMs)
    return session
}

/**
 * 跳板（bastion/jump host）隧道。经一个已连的 JSch 会话（如 PC）开
 * direct-tcpip 通道直达目标 host:port，把通道的流包装成 JSch [Proxy] 喂给
 * 目标 Session——目标 SSH 握手的所有字节都走这条隧道，对上层完全透明。
 * 纯 JSch 级联，无需本地 ssh 二进制（Android 无 ssh 也可用）。
 *
 * 生命周期：跳板 Session 与 Channel 由调用方持有，用毕必须 [close]。
 * [Proxy.connect] 由 JSch 在目标 session.connect() 时调用一次。
 */
internal class JumpTunnelProxy(
    private val jumpSession: Session,
    private val targetHost: String,
    private val targetPort: Int,
) : Proxy {
    private var channel: ChannelDirectTCPIP? = null
    private var input: java.io.InputStream? = null
    private var jumpOutStream: java.io.OutputStream? = null

    /** JSch 在目标 session.connect() 时调用。socketFactory/host/port 由 Proxy 语义传入，
     *  但隧道已固定到 targetHost:targetPort，故忽略形参，只开 direct-tcpip 通道。 */
    override fun connect(socketFactory: com.jcraft.jsch.SocketFactory?, host: String, port: Int, connectTimeout: Int) {
        val ch = jumpSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost(targetHost)
        ch.setPort(targetPort)
        ch.connect(connectTimeout.coerceAtLeast(10_000))
        channel = ch
        input = ch.inputStream
        jumpOutStream = ch.outputStream
    }

    override fun getInputStream(): java.io.InputStream = input ?: error("jump proxy not connected")
    override fun getOutputStream(): java.io.OutputStream = jumpOutStream ?: error("jump proxy not connected")
    // JSch 允许返回 null：Session.connect() 里 `socket = proxy.getSocket()` 之后紧跟
    // `if (connectTimeout > 0 && socket != null)` 判空，握手只靠 getInputStream/getOutputStream
    // 的两个流；标准源码注释里的 ProxyCommand 示例同样是 `return null`。
    // （此前这里 direct error() → 跳板握手必定失败："jump proxy has no raw socket"）
    override fun getSocket(): java.net.Socket? = null

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { jumpSession.disconnect() }
    }
}

/**
 * 重试前的重新选网：网络可能已切换（WiFi↔蜂窝），首次探测选出的 Network 可能已失效，
 * 复用旧 Network 会让重试也失败。故重试前重新跑一次可达性探测（跳板场景探测跳板主机）。
 */
internal fun probeNetworkForRetry(context: Context, jump: JumpSpec?, host: String, port: Int): Network? =
    kotlinx.coroutines.runBlocking {
        if (jump != null) probeReachability(context, jump.host, jump.port).winningNetwork
        else probeReachability(context, host, port).winningNetwork
    }

/**
 * 建立到跳板主机的会话（用于 JumpTunnelProxy）。跳板自身的认证与 keepalive
 * 走与直连相同的 openSshSession 逻辑，network 绑定同一传输。
 */
internal fun openJumpSession(
    jsch: JSch,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
    network: Network? = null,
): Session = openSshSession(jsch, host, port, user, auth, timeoutMs, network)

/**
 * JSch SocketFactory with two responsibilities:
 *  1. Optionally bind every newly-created socket to a specific Android [Network], to keep
 *     SSH traffic on a chosen transport when the OS's default-network selection would
 *     route it elsewhere.
 *  2. Always pass an explicit connect timeout to Socket.connect() so a stalled SYN can't
 *     hang for the kernel default (~75s) — JSch's session.connect(timeout) controls only
 *     the handshake reads, NOT this socket's TCP connect.
 */
private class NetworkBoundSocketFactory(
    private val network: Network?,
    private val connectTimeoutMs: Int,
) : com.jcraft.jsch.SocketFactory {
    override fun createSocket(host: String, port: Int): java.net.Socket {
        val s = java.net.Socket()
        if (network != null) {
            try { network.bindSocket(s) } catch (_: Throwable) { /* best-effort */ }
        }
        s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        return s
    }

    override fun getInputStream(socket: java.net.Socket): java.io.InputStream =
        socket.getInputStream()

    override fun getOutputStream(socket: java.net.Socket): java.io.OutputStream =
        socket.getOutputStream()
}

/**
 * Outcome of a reachability probe. [winningNetwork] is the Network that successfully
 * completed the TCP handshake (null if the unbound default-route attempt was the winner);
 * pass it to [openSshSession] so JSch's handshake follows the same route. [failures] lists
 * the per-network failure reasons when no probe succeeded.
 */
internal data class ProbeOutcome(
    val winningNetwork: Network?,
    val winningLabel: String?,
    val failures: List<Pair<String, String>>,
    val resolvedIp: String,
    val totalMs: Long,
    /** Non-null when the system resolver returned a reserved fake-IP block address. */
    val fakeIp: String? = null,
)

/**
 * Race a TCP handshake to (host, port) across every available transport in parallel. The
 * sequential predecessor (5s timeout × 4 networks = up to 20s) ate most of the user's
 * 30s budget on misconfigured devices; the parallel version takes ~2.5s in the worst case
 * and immediately surfaces the right network for the JSch handshake to follow.
 */
internal suspend fun probeReachability(context: Context, host: String, port: Int): ProbeOutcome {
    val probeStart = System.currentTimeMillis()
    // withContext(IO): the fake-IP branch may issue several blocking UDP queries.
    val resolution = withContext(Dispatchers.IO) { resolveToIPv4(host, context) }
    val resolvedIp = resolution.ip ?: host
    val attempts = enumerateCandidateNetworks(context)
    // Race all candidates in parallel; awaitAll caps total time at one probe-timeout.
    val results = withContext(Dispatchers.IO) {
        coroutineScope {
            attempts.map { (label, candidate) ->
                async {
                    val s = java.net.Socket()
                    try {
                        runCatching {
                            if (candidate != null) {
                                runCatching { candidate.bindSocket(s) }.onFailure { t ->
                                    AppLog.w(TAG_SSH, "bindSocket to $label failed", t)
                                }
                            }
                            s.connect(java.net.InetSocketAddress(resolvedIp, port), PROBE_PER_NETWORK_TIMEOUT_MS)
                            Triple(label, candidate, null as String?)
                        }.getOrElse { e ->
                            Triple(label, candidate, "${e::class.java.simpleName}: ${e.message ?: "unknown"}")
                        }
                    } finally {
                        try { s.close() } catch (_: Throwable) {}
                    }
                }
            }.awaitAll()
        }
    }
    val winner = results.firstOrNull { it.third == null }
    val failures = results.filter { it.third != null }.map { it.first to (it.third ?: "unknown") }
    val totalMs = System.currentTimeMillis() - probeStart
    if (winner != null) {
        AppLog.i(TAG_SSH, "tcp probe ok via ${winner.first} in ${totalMs}ms")
        return ProbeOutcome(winner.second, winner.first, failures, resolvedIp, totalMs, resolution.fakeIp)
    }
    return ProbeOutcome(null, null, failures, resolvedIp, totalMs, resolution.fakeIp)
}

/**
 * Standard envelope for "we couldn't reach the host on any transport". Surfaces the per-
 * network failure reasons so the LLM can quote them back to the user when explaining what
 * went wrong, plus the recovery hint for the most common Android routing pitfalls.
 */
internal fun unreachableEnvelope(host: String, port: Int, outcome: ProbeOutcome): JsonObject {
    val fakeIp = outcome.fakeIp
    if (fakeIp != null) {
        // Distinct from the generic "unreachable" case: the address itself is a virtual one
        // minted by a DNS-hijacking proxy, so no transport available to this process can
        // route to it. Re-telling the user to check background data would send them down the
        // wrong path — name the cause and the two ways out.
        return ToolErrors.envelopeFor(
            error = FAKE_IP_ERROR,
            message = FAKE_IP_ERROR,
            hint = "$host resolved to $fakeIp, an address from the reserved fake-IP range " +
                "198.18.0.0/15 — a proxy/VPN on this device is answering DNS for it, and SSH " +
                "connections from this app are not bound to that VPN, so the virtual address " +
                "is unreachable here. Use the server's real IP directly, or change the proxy's " +
                "split-routing rules (or its fake-ip mode) so this domain resolves normally.",
            extra = ToolErrors.extraOf(
                "host" to host,
                "ip" to fakeIp,
                "port" to port,
                "resolution" to "fake_ip_block",
            ),
        )
    }
    return ToolErrors.envelopeFor(error = "tcp_unreachable", message = "tcp_unreachable", hint = "Direct TCP to ${outcome.resolvedIp}:$port failed across every available network (${outcome.totalMs}ms total). If Termux ssh from the same device reaches this host, RikkaHub Agents's process is being filtered. Check Settings → Network → Private DNS (try Off), any active VPN's per-app routing, and Settings → Apps → RikkaHub Agents → Mobile data & Wi-Fi (enable Background data and Unrestricted data usage).", extra = ToolErrors.extraOf("host" to host, "ip" to outcome.resolvedIp, "port" to port, "attempts" to buildJsonObject {
            outcome.failures.forEach { (label, reason) -> put(label, reason) }
        }))
}

