package me.rerere.rikkahub.data.ai

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger as JSchLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.service.WebServerService

/**
 * Reasonix Web 桥 — 把 RikkaHub 手机端 Web 服务反向隧道到 ECS，供 reasonix serve/run 访问。
 *
 * 原理：手机主动出站 SSH 到 ECS（阿里云公网可达），建立反向隧道
 *   `ssh -R <remotePort>:localhost:<localPort> root@<ECS>`，
 *   ECS 上 reasonix 即可通过 `http://127.0.0.1:<remotePort>` 访问手机 Web API。
 *
 * 生命周期：
 * - [start]：启动 Web 服务 + 建立反向隧道（前台服务持有，防止被杀）
 * - [stop]：断开隧道 + 停止 Web 服务
 * - 状态通过 [state] 暴露（用于配置页展示）
 */
class ReasonixWebBridge(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sshSession: Session? = null
    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    data class BridgeState(
        val webServerRunning: Boolean = false,
        val tunnelConnected: Boolean = false,
        val message: String = "",
    )

    /**
     * 启动 Web 桥：
     * 1. 启动手机 Web 服务（复用 WebServerService，端口取设置，默认 8080）
     * 2. 建立 SSH 反向隧道（JSch 保持连接）
     */
    suspend fun start(
        ecsHost: String,
        ecsPort: Int = 22,
        ecsUser: String = "root",
        remoteTunnelPort: Int = 8080,
        localWebPort: Int = 8080,
        privateKeyPath: String = "",
        password: String = "",
    ): Boolean = withContext(Dispatchers.Default) {
        AppLog.d(TAG, "start: host=$ecsHost remote=$remoteTunnelPort local=$localWebPort key=${if (privateKeyPath.isNotBlank()) "path" else "none"}")
        // 1. 启动 Web 服务（前台服务，通知常驻）
        runCatching {
            val intent =
                android.content.Intent(context, WebServerService::class.java)
                    .setAction(WebServerService.ACTION_START)
                    .putExtra(WebServerService.EXTRA_PORT, localWebPort)
                    .putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, true)
            context.startForegroundService(intent)
        }.onSuccess {
            _state.value = _state.value.copy(webServerRunning = true)
        }.onFailure { e ->
            AppLog.e(TAG, "Failed to start web server", e)
            _state.value = _state.value.copy(message = "Web 服务启动失败: ${e.message}")
        }

        // 2. 建立 SSH 反向隧道
        val ok = connectTunnel(
            ecsHost = ecsHost,
            ecsPort = ecsPort,
            ecsUser = ecsUser,
            remoteTunnelPort = remoteTunnelPort,
            localWebPort = localWebPort,
            privateKeyPath = privateKeyPath,
            password = password,
        )
        // 成功时清空上次失败的 message（避免「✅已连接 + 红字残留」矛盾显示）
        _state.value = _state.value.copy(tunnelConnected = ok, message = if (ok) "" else _state.value.message)
        AppLog.d(TAG, "start 完成: tunnelConnected=$ok")
        ok
    }

    /** 建立反向隧道：本地 Web 端口 → ECS 的 remoteTunnelPort */
    private suspend fun connectTunnel(
        ecsHost: String,
        ecsPort: Int,
        ecsUser: String,
        remoteTunnelPort: Int,
        localWebPort: Int,
        privateKeyPath: String,
        password: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            if (privateKeyPath.isNotBlank()) {
                val keyFile = java.io.File(privateKeyPath)
                if (!keyFile.exists()) {
                    AppLog.e(TAG, "SSH private key file not found: $privateKeyPath")
                    _state.value = _state.value.copy(message = "私钥文件不存在: $privateKeyPath（请点「生成 SSH 密钥」自动填写路径）")
                    return@withContext false
                }
                if (keyFile.length() == 0L) {
                    AppLog.e(TAG, "SSH private key file is empty: $privateKeyPath")
                    _state.value = _state.value.copy(message = "私钥文件为空: $privateKeyPath（请重新生成 SSH 密钥）")
                    return@withContext false
                }
                jsch.addIdentity(privateKeyPath)
                AppLog.i(TAG, "Loaded SSH private key: $privateKeyPath (${keyFile.length()} bytes)")
            }
            // JSch 握手/认证过程接到 App 日志（AppLog），开启「设置→日志→应用层日志」即可查看。
            // 只开 INFO 以上，避免 DEBUG 逐包刷屏撑爆日志 buffer
            JSch.setLogger(object : JSchLogger {
                override fun isEnabled(level: Int): Boolean = level >= JSchLogger.INFO
                override fun log(level: Int, message: String) {
                    AppLog.d(TAG, "[jsch] $message")
                }
            })
            val session: Session = jsch.getSession(ecsUser, ecsHost, ecsPort)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }
            // 非交互：接受 host key（首次连接；生产应校验指纹）
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("ServerAliveInterval", "30")
            session.setConfig("ServerAliveCountMax", "3")
            // RSA-2048 私钥走 rsa-sha2-256/512 签名（SshKeyGenerator 默认输出，
            // mwiede/jsch 0.2.x 与 OpenSSH 8.8+ 均默认支持），无需额外算法配置。
            session.connect(15_000)

            // 反向隧道：ECS 的 remoteTunnelPort → 手机的 localhost:localWebPort
            // 4 参数重载：setPortForwardingR(bind_address, bind_port, host, port)
            // bind_address 留空 = 监听 ECS 所有接口（reasonix 在本机访问 127.0.0.1 也可）
            sshSession = session
            session.setPortForwardingR("", remoteTunnelPort, "127.0.0.1", localWebPort)
            AppLog.i(TAG, "SSH reverse tunnel established: ECS:$remoteTunnelPort -> local:$localWebPort")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "SSH tunnel failed", e)
            _state.value = _state.value.copy(message = "隧道建立失败: ${e.message}")
            false
        }
    }

    /** 断开隧道 + 停止 Web 服务 */
    fun stop() {
        scope.launch {
            runCatching {
                sshSession?.disconnect()
                sshSession = null
            }.onSuccess {
                _state.value = _state.value.copy(tunnelConnected = false)
            }
            runCatching {
                val intent =
                    android.content.Intent(context, WebServerService::class.java)
                        .setAction(WebServerService.ACTION_STOP)
                        .putExtra(WebServerService.EXTRA_STOP_FROM_BRIDGE, true)
                context.startService(intent)
            }.onSuccess {
                _state.value = _state.value.copy(webServerRunning = false)
            }
        }
    }

    fun release() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "ReasonixWebBridge"
    }
}
