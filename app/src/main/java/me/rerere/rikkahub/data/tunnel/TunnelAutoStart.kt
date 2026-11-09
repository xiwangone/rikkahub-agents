package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

/**
 * 隧道会话守护：**跟随 App 生命周期**。
 *
 * 边界（按用户口径）：
 * - 连接由开关触发 —— 开 = 建连且落盘、关 = 断开且不再自动接；
 * - 进程被划掉 → 隧道随进程消失；下次启动时若开关仍为开，自动接回（本类开头那段）；
 * - 本进程内掉线仍自愈：每 [HEALTH_POLL_MS] 查一次 native 状态，
 *   按 5s / 15s / 60s 退避重连，否则掉一次线就得手动关再开两次。
 * - **不做进程保活**（无前台服务）：归后台被系统回收时断，重进再连。
 *
 * ⚠ 冷启动恢复为什么不能靠首帧：`settingsFlow` 首帧是 `Settings.dummy()`，代表不了
 * 用户配置（旧实现据此判断开关，会白等一个轮询周期才建连）→ 先 `awaitInitialized()`。
 */
object TunnelAutoStart {
    private const val TAG = "TunnelAutoStart"
    private const val HEALTH_POLL_MS = 30_000L
    private val retryDelaysMs = longArrayOf(5_000L, 15_000L, 60_000L)

    fun start(
        context: Context,
        settingsStore: SettingsStore,
        vaultRepository: CredentialVaultRepository,
    ) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val resolver = CredentialResolver(vaultRepository)
            // 冷启动恢复：等设置真正加载后再读开关，否则读到的是 dummy 默认值。
            // 开关为开 → 只标记期望运行，实际建连交给下面带退避的循环。
            runCatching { settingsStore.awaitInitialized() }
                .onFailure { AppLog.w(TAG, "restore skipped: settings not initialized: ${it.message}", it) }
            if (settingsStore.settingsFlow.value.networkSetting.tailscaleEnabled &&
                !TsnetBridge.desiredRunning
            ) {
                AppLog.i(TAG, "restore tunnel on app start (enabled=true)")
                TsnetBridge.markDesiredRunning()
            } else {
                // 留痕：区分「开关没落盘」与「已在连」——两者以前都是静默，无法定位。
                AppLog.i(
                    TAG,
                    "restore skipped: enabled=" +
                        settingsStore.settingsFlow.value.networkSetting.tailscaleEnabled +
                        " desired=" + TsnetBridge.desiredRunning,
                )
            }
            var retryIndex = 0
            while (true) {
                // 用户没开（或已关、或启动失败回落）或正在建连握手：静默待机，不打断正在进行中的连接。
                if (!TsnetBridge.desiredRunning || TsnetBridge.isConnecting) {
                    retryIndex = 0
                    delay(HEALTH_POLL_MS)
                    continue
                }

                val live = TsnetBridge.status()
                if (live != null && live.startsWith("connected")) {
                    retryIndex = 0
                    val ip = live.removePrefix("connected").trim().ifBlank { TsnetBridge.nodeIp.orEmpty() }
                    TsnetBridge.publish(TunnelState.Connected(ip))
                    delay(HEALTH_POLL_MS)
                    continue
                }

                // 掉线或查询失败：先停再按退避重连（native 侧不接受重复 start）。
                TsnetBridge.publish(TunnelState.Offline)
                runCatching { TsnetBridge.resetForReconnect() }
                    .onFailure { AppLog.w(TAG, "reset before reconnect failed: ${it.message}", it) }

                runCatching { settingsStore.awaitInitialized() }
                val network = settingsStore.settingsFlow.value.networkSetting
                val authKeyResult = resolveTunnelAuthKey(resolver, network.tailscaleAuthKey)
                val authKey = authKeyResult.getOrNull()
                if (authKey == null) {
                    // 配置问题：重试也不会成功，等用户去改（下一周期再读）。
                    TsnetBridge.publish(
                        TunnelState.Failed(
                            authKeyResult.exceptionOrNull()?.message ?: "auth key unavailable",
                        ),
                    )
                    AppLog.w(TAG, "tunnel skipped: ${authKeyResult.exceptionOrNull()?.message}")
                    delay(HEALTH_POLL_MS)
                    continue
                }

                val result = TsnetBridge.start(
                    context = appContext,
                    controlURL = network.tailscaleControlUrl.trim(),
                    authKey = authKey,
                    hostname = network.effectiveTunnelHostname,
                    acceptRoutes = network.tailscaleAcceptRoutes,
                    exitNode = network.tailscaleExitNode,
                    advertiseRoutes = network.tailscaleAdvertiseRoutes,
                    keepDesiredOnFailure = true,
                )
                if (result.isSuccess) {
                    retryIndex = 0
                    delay(HEALTH_POLL_MS)
                    continue
                }
                if (retryIndex >= retryDelaysMs.size) {
                    AppLog.w(TAG, "tunnel gave up: ${result.exceptionOrNull()?.message}")
                    retryIndex = 0
                    delay(HEALTH_POLL_MS)
                    continue
                }
                AppLog.w(
                    TAG,
                    "tunnel reconnect failed (try ${retryIndex + 1}), " +
                        "retry in ${retryDelaysMs[retryIndex]}ms: ${result.exceptionOrNull()?.message}",
                )
                delay(retryDelaysMs[retryIndex])
                retryIndex++
            }
        }
    }
}
