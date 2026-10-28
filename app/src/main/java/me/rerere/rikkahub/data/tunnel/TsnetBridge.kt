package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog
import tsnetlib.Tsnetlib
import java.io.File

/**
 * 内嵌 Tailscale（tsnet）隧道的最小封装。
 *
 * 设计边界：
 * - **控制面与认证密钥由调用方传入**，不写死任何服务地址（自建 Headscale / 公共 Tailscale 均可）；
 * - 只做 per-app 代理（把 tailnet 目标映射到 127.0.0.1 端口），**不注册 VpnService**
 *   ⇒ 系统路由与 DNS 完全不动，其他 App 不受影响；
 * - native 库 `libgojni.so` 由 gomobile bind 生成，**不入仓**（见 app/.gitignore）。
 *
 * 调用约定：所有方法都是 suspend + Result，失败不抛给 UI 层。
 */
object TsnetBridge {
    private const val TAG = "TsnetBridge"

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
            Tsnetlib.touch() // 触发 native 库加载
            val stateDir = File(context.filesDir, "tsnet").apply { mkdirs() }.absolutePath
            val ip = Tsnetlib.start(controlURL, authKey, hostname, stateDir)
            nodeIp = ip
            isRunning = true
            AppLog.i(TAG, "tunnel up: ip=$ip")
            ip
        }.onFailure { AppLog.w(TAG, "tunnel start failed: ${it.message}", it) }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isRunning) return@runCatching
            Tsnetlib.stop()
            isRunning = false
            nodeIp = null
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
}
