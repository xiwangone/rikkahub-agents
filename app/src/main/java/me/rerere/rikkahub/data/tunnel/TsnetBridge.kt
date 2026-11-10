package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog
import tsnetlib.Tsnetlib
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 隧道对外状态：**单一来源** —— 由 [TsnetBridge] 与守护循环写回，UI 只读。
 * 页面不再自己拼状态字符串，也不轮询 native（每次查询是一次 LocalAPI 往返）。
 */
sealed interface TunnelState {
    /** 未开启：进程刚起、用户关掉、或启动失败后回落。 */
    data object Off : TunnelState

    /** 正在建连。 */
    data object Connecting : TunnelState

    /** 已连上，[ip] 为本节点 tailnet 地址。 */
    data class Connected(val ip: String) : TunnelState

    /** 后端掉线，守护正按退避重连。 */
    data object Offline : TunnelState

    /** 启动失败，[reason] 为原因。 */
    data class Failed(val reason: String) : TunnelState
}

/**
 * 内嵌 Tailscale（tsnet）隧道的最小封装。
 *
 * 设计边界：
 * - **控制面与认证密钥由调用方传入**，不写死任何服务地址（自建 Headscale / 公共 Tailscale 均可）；
 * - 只做 per-app 代理（把 tailnet 目标映射到 127.0.0.1 端口），**不注册 VpnService**
 *   ⇒ 系统路由与 DNS 完全不动，其他 App 不受影响；
 * - native 库 `libgojni.so` 由 gomobile bind 生成，**不入仓**（见 app/.gitignore）；
 * - Android 上进程枚举不到网络接口，接口列表与默认出口由 [TsnetPathMonitor] 从 Java 层喂给 native。
 *
 * 调用约定：所有方法都是 suspend + Result，失败不抛给 UI 层。
 */
@Suppress("TooManyFunctions")
object TsnetBridge {
    private const val TAG = "TsnetBridge"

    /** native status() 的连接前缀；其余取值（如 "offline"）一律不是可用地址。 */
    private const val CONNECTED_PREFIX = "connected"

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)

    /** 供 UI 订阅的实时状态。 */
    val state: StateFlow<TunnelState> = _state

    /** 状态写回（start / stop 与守护循环用）。 */
    internal fun publish(next: TunnelState) {
        _state.value = next
    }

    /** 隧道当前是否在运行。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 用户是否已开启隧道（本进程存活期内的期望状态）。
     * 与 [isRunning] 区分：掉线自动重连需先关闭旧 native 实例再重新建连，
     * 期间 [isRunning] 暂时为 false，但 [desiredRunning] 仍为 true，守护协程才能继续按退避周期重试。
     */
    @Volatile
    var desiredRunning: Boolean = false
        private set

    /** 本节点在 tailnet 中的 IP（如 100.64.0.5）；未启动时为 null。 */
    @Volatile
    var nodeIp: String? = null
        private set

    /**
     * 是否正在建连握手期间。守护协程据此避让，不把进行中的首次连接误判成掉线重连。
     */
    @Volatile
    var isConnecting: Boolean = false
        private set

    /** 同一目标复用已建的本地代理端口，避免每次连接都新建监听。 */
    private val proxyCache = ConcurrentHashMap<String, Int>()

    /** 串行化 start / stop / resetForReconnect：native 侧是单例，并发进入会互相踩状态。 */
    private val lifecycleMutex = Mutex()

    /** 一次建连所需的全部隧道参数（聚成对象，避免参数列表持续膨胀）。 */
    data class TunnelStartConfig(
        val controlURL: String,
        val authKey: String,
        val hostname: String,
        val acceptRoutes: Boolean = false,
        val exitNode: String = "",
        val advertiseRoutes: String = "",
        val ephemeral: Boolean = false,
        val serve: String = "",
    )

    /**
     * 启动隧道并等待拿到 tailnet IP。
     *
     * @param controlURL 协调服务器地址，如 `https://login.example.com`；空串交给底层默认行为
     * @param authKey preauth key（`tskey-auth-...`）
     * @param hostname 节点在 tailnet 中显示的名字
     * @param acceptRoutes 是否接受对端广播的子网路由（由 native 在启动时应用，tailscale 默认开）
     * @param exitNode 出口节点的 tailnet IP，空串表示不设
     * @param advertiseRoutes 向安全内网发布的网段（逗号分隔 CIDR），空串表示不发布
     * @param ephemeral 临时节点：进程消失后控制面自动移除该设备（首次注册才生效）
     * @param keepDesiredOnFailure 掉线自愈重连时传 true，保留 [desiredRunning] 以便后续退避周期继续重试
     */
    suspend fun start(
        context: Context,
        config: TunnelStartConfig,
        keepDesiredOnFailure: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            desiredRunning = true
            isConnecting = true
            try {
                runCatching {
                    nodeIp?.takeIf { isRunning }?.let { return@runCatching it }
                    publish(TunnelState.Connecting)
                    proxyCache.clear()
                    Tsnetlib.touch() // 触发 native 库加载
                    val stateDir = File(context.filesDir, "tsnet").apply { mkdirs() }.absolutePath
                    // 必须在拉起 server 之前开始喂接口：Android 上 native 侧枚举不到任何接口，
                    // 网络监视要靠这里注册的快照回调才能启动。失败只记日志，不影响启动。
                    runCatching { TsnetPathMonitor.start(context) }
                        .onFailure { AppLog.w(TAG, "path monitor start failed: ${it.message}", it) }
                    // 临时节点标记必须在拉起 server 之前写进 native（tsnet 只在注册时读）。
                    runCatching { Tsnetlib.setEphemeral(config.ephemeral) }
                        .onFailure { AppLog.w(TAG, "setEphemeral(${config.ephemeral}) failed: ${it.message}", it) }
                    // 子网路由与出口节点由 native 在启动阶段写进 prefs：tailscale 的 RouteAll
                    // 默认为开，不能等启动回来再改（那样开隧道瞬间就把对端网段接进来了）。
                    val ip = Tsnetlib.start(
                        config.controlURL,
                        config.authKey,
                        config.hostname,
                        config.acceptRoutes,
                        config.exitNode,
                        config.advertiseRoutes,
                        stateDir,
                    )
                    nodeIp = ip
                    // Serve：把本机回环端口暴露到 tailnet（格式 tailnetPort:localPort）。
                    applyServe(config.serve)
                    isRunning = true
                    publish(TunnelState.Connected(ip))
                    AppLog.i(TAG, "tunnel up: ip=$ip")
                    ip
                }.onFailure { e ->
                    // native 报 "already running" = 上一次 start 已成功、只是重连流程把 isRunning 重置了。
                    // 不能当失败处理：否则 isRunning 永久停在 false，proxyToBlocking() 会一律
                    // `if (!isRunning) return null` 放行直连，tailnet 目标（100.64.0.0/10）必然超时，
                    // 同时 UI 被 publish(Failed) 显示成"未连接"（隧道其实连着）。
                    // 以底层 status 为准恢复状态。
                    if (e.message?.contains("already running", ignoreCase = true) == true) {
                        // status() 在未连上时返回 "offline"（不是 null）——直接 removePrefix 会把
                        // "offline" 当成 IP，从而 publish(Connected("offline"))、isRunning=true，
                        // 代理随后向死隧道拨号。必须要求前缀确实是 connected 且后面还有地址。
                        val rawStatus = runCatching { Tsnetlib.status() }.getOrNull()?.trim()
                        val ip = rawStatus
                            ?.takeIf { it.length > CONNECTED_PREFIX.length && it.startsWith(CONNECTED_PREFIX, ignoreCase = true) }
                            ?.substring(CONNECTED_PREFIX.length)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        if (ip != null) {
                            nodeIp = ip
                            isRunning = true
                            publish(TunnelState.Connected(ip))
                            AppLog.i(TAG, "tunnel already running, state recovered: ip=$ip")
                            return@onFailure
                        }
                    }
                    // 用户主动开启且确实没连上：回落成"未开启"，守护协程不再空转重试；
                    // 掉线自愈路径（keepDesiredOnFailure）保留期望状态，交给退避周期继续重试。
                    if (!isRunning && !keepDesiredOnFailure) {
                        desiredRunning = false
                    }
                    publish(TunnelState.Failed(e.message ?: e::class.simpleName ?: "start failed"))
                    AppLog.w(TAG, "tunnel start failed: ${e.message}", e)
                }
            } finally {
                isConnecting = false
            }
        }
    }

    /** 解析 "tailnetPort:localPort" 并暴露到 tailnet；空/非法则静默跳过（不阻断建连）。 */
    private fun applyServe(spec: String) {
        val parts = spec.trim().split(":")
        if (parts.size != 2) return
        val tailnetPort = parts[0].trim().toIntOrNull() ?: return
        val localPort = parts[1].trim().toIntOrNull() ?: return
        if (tailnetPort <= 0 || localPort <= 0) return
        runCatching { Tsnetlib.listenForward(tailnetPort.toLong(), localPort.toLong()) }
            .onFailure { AppLog.w(TAG, "serve $tailnetPort->$localPort failed: ${it.message}", it) }
    }

    /**
     * 冷启动恢复：仅标记「期望运行」，建连交给守护循环（它带 authKey 解析与退避）。
     * 与 [start] 的区别：这里不碰隧道状态机，避免与并发建连抢 [lifecycleMutex]。
     */
    internal fun markDesiredRunning() {
        desiredRunning = true
    }

    /**
     * 查询 native 侧的真实连接状态。
     *
     * @return `connected <tailnet IP>` / `offline` / null（未运行或查询失败）
     */
    suspend fun status(): String? = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext null
        runCatching { Tsnetlib.status() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 用户主动停止隧道：清空期望状态并释放全部本地代理端口缓存。 */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            desiredRunning = false
            runCatching {
                proxyCache.clear()
                if (!isRunning) {
                    publish(TunnelState.Off)
                    return@runCatching
                }
                // 先停探测，避免回调打到正在关闭的 server 上。
                runCatching { TsnetPathMonitor.stop() }
                    .onFailure { AppLog.w(TAG, "path monitor stop failed: ${it.message}", it) }
                Tsnetlib.stop()
                isRunning = false
                nodeIp = null
                publish(TunnelState.Off)
                AppLog.i(TAG, "tunnel stopped")
            }.onFailure { AppLog.w(TAG, "tunnel stop failed: ${it.message}", it) }
        }
    }

    /**
     * 守护协程掉线重连前的底层复位：保留 [desiredRunning] = true，
     * 仅停止已失效的 native 实例并清空旧本地代理端口缓存。
     */
    internal suspend fun resetForReconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            runCatching {
                proxyCache.clear()
                if (!isRunning) return@runCatching
                runCatching { TsnetPathMonitor.stop() }
                    .onFailure { AppLog.w(TAG, "path monitor stop failed: ${it.message}", it) }
                Tsnetlib.stop()
                isRunning = false
                nodeIp = null
                publish(TunnelState.Offline)
            }.onFailure { AppLog.w(TAG, "tunnel resetForReconnect failed: ${it.message}", it) }
        }
    }

    /**
     * 把 tailnet 内的 [host]:[port] 映射成本地端口。
     * 返回本地端口号，调用方连 `127.0.0.1:<port>` 即可；不进隧道时不要去碰这些目标。
     */
    /** 接受 peer 广播的子网路由（Prefs.RouteAll）；默认关，避免把对端 LAN 拉进 tailnet。 */
    suspend fun setAcceptRoutes(on: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Tsnetlib.setAcceptRoutes(on) }
            .onFailure { AppLog.w(TAG, "setAcceptRoutes($on) failed: ${it.message}", it) }
    }

    /** 临时节点：下次启动时生效（控制面在进程消失后自动移除该设备）。 */
    suspend fun setEphemeral(on: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Tsnetlib.setEphemeral(on) }
            .onFailure { AppLog.w(TAG, "setEphemeral($on) failed: ${it.message}", it) }
    }

    /** 把本机某个回环端口暴露到 tailnet 内（tailnetPort → 127.0.0.1:localPort）。 */
    suspend fun listenForward(tailnetPort: Int, localPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Tsnetlib.listenForward(tailnetPort.toLong(), localPort.toLong()) }
            .onFailure { AppLog.w(TAG, "listenForward($tailnetPort->$localPort) failed: ${it.message}", it) }
    }

    /** 出口节点：传 peer 的 Tailscale IP，空串关闭；LAN 直连保持可用。 */
    suspend fun setExitNode(ip: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Tsnetlib.setExitNode(ip) }
            .onFailure { AppLog.w(TAG, "setExitNode($ip) failed: ${it.message}", it) }
    }

    /** 节点与 peer 列表（JSON）：running / IP / 域名 / peers / acceptRoutes / exitNodeIP。 */
    suspend fun statusJson(): String? = withContext(Dispatchers.IO) {
        runCatching { Tsnetlib.statusJSON() }
            .onFailure { AppLog.w(TAG, "statusJSON failed: ${it.message}", it) }
            .getOrNull()
    }

    /** 退出登录：服务端使该节点失效，随后停止隧道。 */
    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Tsnetlib.logout()
            isRunning = false
            nodeIp = null
            publish(TunnelState.Off)
        }.onFailure { AppLog.w(TAG, "logout failed: ${it.message}", it) }
    }

    suspend fun proxyTo(host: String, port: Int): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(isRunning) { "tunnel not running" }
            val key = "${host.trim().lowercase()}:$port"
            proxyCache[key]?.let { return@runCatching it }
            Tsnetlib.startProxy(host.trim(), port.toLong()).toInt().also { proxyCache[key] = it }
        }.onFailure { AppLog.w(TAG, "proxy $host:$port failed: ${it.message}", it) }
    }

    suspend fun stopProxy(localPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            proxyCache.entries.removeIf { it.value == localPort }
            Tsnetlib.stopProxy(localPort.toLong())
        }.onFailure { AppLog.w(TAG, "stopProxy $localPort failed: ${it.message}", it) }
    }

    /**
     * 目标是否属于 Tailnet 路由范围：
     * - Tailscale IPv4 CGNAT：`100.64.0.0/10`（`100.(64..127).(0..255).(0..255)`）
     * - Tailscale IPv6 ULA：`fd7a:115c:a1e0::/48`
     * - Tailscale MagicDNS 域名：`*.ts.net` 或 `*.beta.tailscale.net`
     */
    fun isTailnetAddress(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (clean.isEmpty()) return false
        if (isTailnetMagicDnsHost(clean)) return true
        if (clean.startsWith("fd7a:115c:a1e0:")) return true
        val parts = clean.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        val c = parts[2].toIntOrNull() ?: return false
        val d = parts[3].toIntOrNull() ?: return false
        return a == 100 && b in 64..127 && c in 0..255 && d in 0..255
    }


    /**
     * 同步版代理（供 JSch 与 OkHttp [TsnetSocketFactory] 调用）：隧道未运行时返回 null。
     */
    fun proxyToBlocking(host: String, port: Int): Int? {
        if (!isRunning) return null
        val cleanHost = host.trim().removeSurrounding("[", "]")
        val key = "${cleanHost.lowercase()}:$port"
        proxyCache[key]?.let { return it }
        return runCatching {
            Tsnetlib.startProxy(cleanHost, port.toLong()).toInt().also { proxyCache[key] = it }
        }.getOrNull()
    }

    /**
     * 把隧道能力接到 workspace 的 SSH 层（App 启动时调一次）。
     * 只有「隧道在跑 + 目标是 tailnet 网段/域名」才接管，其余一律原样放行。
     */
    fun installSshRouter() {
        me.rerere.workspace.ssh.TunnelRouter.install { host, port ->
            if (isRunning && isTailnetAddress(host)) {
                proxyToBlocking(host, port)?.let { local -> "127.0.0.1" to local }
            } else {
                null
            }
        }
    }
}

/** 是否为 Tailnet MagicDNS 域名（供 DNS 层在隧道开启时合成占位地址交给 [TsnetSocketFactory] 拨号）。 */
fun isTailnetMagicDnsHost(host: String): Boolean {
    val clean = host.trim().trimEnd('.').lowercase()
    return clean.endsWith(".ts.net") || clean.endsWith(".beta.tailscale.net")
}
