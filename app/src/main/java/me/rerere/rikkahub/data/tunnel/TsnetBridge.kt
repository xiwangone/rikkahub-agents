package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog
import tsnetlib.Tsnetlib
import java.io.File

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
object TsnetBridge {
    private const val TAG = "TsnetBridge"

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)

    /** 供 UI 订阅的实时状态。 */
    val state: StateFlow<TunnelState> = _state

    /** 状态写回（start / stop 与守护循环用）。 */
    internal fun publish(next: TunnelState) {
        _state.value = next
    }

    /** 隧道是否在运行。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** 本节点在 tailnet 中的 IP（如 100.64.0.5）；未启动时为 null。 */
    @Volatile
    var nodeIp: String? = null
        private set

    /**
     * 启动隧道并等待拿到 tailnet IP。
     *
     * @param controlURL 协调服务器地址，如 `https://login.example.com`；空串交给底层默认行为
     * @param authKey preauth key（`tskey-auth-...`）
     * @param hostname 节点在 tailnet 中显示的名字
     */
    suspend fun start(
        context: Context,
        controlURL: String,
        authKey: String,
        hostname: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            nodeIp?.takeIf { isRunning }?.let { return@runCatching it }
            publish(TunnelState.Connecting)
            Tsnetlib.touch() // 触发 native 库加载
            val stateDir = File(context.filesDir, "tsnet").apply { mkdirs() }.absolutePath
            // 必须在拉起 server 之前开始喂接口：Android 上 native 侧枚举不到任何接口，
            // 网络监视要靠这里注册的快照回调才能启动。失败只记日志，不影响启动。
            runCatching { TsnetPathMonitor.start(context) }
                .onFailure { AppLog.w(TAG, "path monitor start failed: ${it.message}", it) }
            val ip = Tsnetlib.start(controlURL, authKey, hostname, stateDir)
            nodeIp = ip
            isRunning = true
            publish(TunnelState.Connected(ip))
            AppLog.i(TAG, "tunnel up: ip=$ip")
            ip
        }.onFailure {
            publish(TunnelState.Failed(it.message ?: it::class.simpleName ?: "start failed"))
            AppLog.w(TAG, "tunnel start failed: ${it.message}", it)
        }
    }

    /**
     * 查询 native 侧的真实连接状态。
     *
     * 为什么需要：Kotlin 的 [isRunning] 只反映 start/stop 调用，感知不到后端掉线
     * （网络切换、DERP 断连等）。自动恢复循环用它判断“是真连着还是假装连着”。
     *
     * @return `connected <tailnet IP>` / `offline` / null（未运行或查询失败）
     */
    suspend fun status(): String? = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext null
        runCatching { Tsnetlib.status() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isRunning) return@runCatching
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

    /**
     * 把 tailnet 内的 [host]:[port] 映射成本地端口。
     * 返回本地端口号，调用方连 `127.0.0.1:<port>` 即可；不进隧道时不要去碰这些目标。
     */
    suspend fun proxyTo(host: String, port: Int): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(isRunning) { "tunnel not running" }
            Tsnetlib.startProxy(host, port.toLong()).toInt()
        }.onFailure { AppLog.w(TAG, "proxy $host:$port failed: ${it.message}", it) }
    }

    suspend fun stopProxy(localPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Tsnetlib.stopProxy(localPort.toLong()) }
            .onFailure { AppLog.w(TAG, "stopProxy $localPort failed: ${it.message}", it) }
    }

    /** 目标是否落在 tailnet 网段（100.64.0.0/10）。 */
    fun isTailnetAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    /** 同一目标复用已建的本地代理端口，避免每次连接都新建监听。 */
    private val proxyCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 同步版代理（供 JSch 这类阻塞 API 调用）：隧道未运行时返回 null。
     */
    fun proxyToBlocking(host: String, port: Int): Int? {
        if (!isRunning) return null
        val key = "$host:$port"
        proxyCache[key]?.let { return it }
        return runCatching {
            Tsnetlib.startProxy(host, port.toLong()).toInt().also { proxyCache[key] = it }
        }.getOrNull()
    }

    /**
     * 把隧道能力接到 workspace 的 SSH 层（App 启动时调一次）。
     * 只有「隧道在跑 + 目标是 tailnet 网段」才接管，其余一律原样放行。
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
