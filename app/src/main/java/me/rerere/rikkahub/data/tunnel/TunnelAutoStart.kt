package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.common.log.ThrottleGate
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

    // auth key 缺失是配置问题：重试也不会成功，每 30 秒 1 条 w 会刷满缓冲。
    // 首条立即记，其后 1 小时窗口内只计数，放行时在汇总行里带上抑制条数。
    // 本类是 object，门随进程常驻；服务重启不丢节流状态。
    private val authKeyMissingThrottle = ThrottleGate(3_600_000L)

    /**
     * 冷启动恢复：等设置真正加载后再读开关（否则读到 dummy 默认值）。
     * 开关为开且尚未标记时只标记期望运行，实际建连交给 start() 里的退避循环。
     */
    private suspend fun restoreDesiredRunning(settingsStore: SettingsStore) {
        runCatching { settingsStore.awaitInitialized() }
            .onFailure { AppLog.w(TAG, "restore skipped: settings not initialized: ${it.message}", it) }
        val enabled = settingsStore.settingsFlow.value.networkSetting.tailscaleEnabled
        if (enabled && !TsnetBridge.desiredRunning) {
            AppLog.i(TAG, "restore tunnel on app start (enabled=true)")
            TsnetBridge.markDesiredRunning()
        } else {
            // 留痕：区分「开关没落盘」与「已在连」——两者以前都是静默，无法定位。
            AppLog.i(TAG, "restore skipped: enabled=$enabled desired=${TsnetBridge.desiredRunning}")
        }
    }

    /** auth key 缺失属配置问题：重试不会成功，只按节流记一条，由调用方进入下一周期。 */
    private suspend fun publishAuthKeyMissing(result: Result<String>) {
        TsnetBridge.publish(
            TunnelState.Failed(result.exceptionOrNull()?.message ?: "auth key unavailable"),
        )
        if (authKeyMissingThrottle.shouldLog()) {
            val suppressed = authKeyMissingThrottle.takeSuppressedCount()
            AppLog.w(
                TAG,
                "tunnel skipped: ${result.exceptionOrNull()?.message}" +
                    if (suppressed > 0) " (+$suppressed suppressed in the last hour)" else "",
            )
        }
    }

    fun start(
        context: Context,
        settingsStore: SettingsStore,
        vaultRepository: CredentialVaultRepository,
    ) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val resolver = CredentialResolver(vaultRepository)
            restoreDesiredRunning(settingsStore)
            var retryIndex = 0
            // 本退避周期内的失败计数：首条失败记 1 条 w，其后抑制；周期耗尽或
            // 重连成功时各记 1 条汇总/恢复行。随 retryIndex 一起复位。
            var cycleFailures = 0
            var lastFailureMessage: String? = null
            fun resetCycle() {
                cycleFailures = 0
                lastFailureMessage = null
            }
            // 隧道恢复：本周期有过失败时记 1 条恢复行（无失败时不记）。
            fun noteReconnected() {
                if (cycleFailures > 0) {
                    AppLog.i(
                        TAG,
                        "tunnel reconnected after $cycleFailures failed attempt(s) " +
                            "(last error: $lastFailureMessage)",
                    )
                }
                resetCycle()
            }
            while (true) {
                // 用户没开（或已关、或启动失败回落）或正在建连握手：静默待机，不打断正在进行中的连接。
                if (!TsnetBridge.desiredRunning || TsnetBridge.isConnecting) {
                    retryIndex = 0
                    resetCycle()
                    delay(HEALTH_POLL_MS)
                    continue
                }

                val live = TsnetBridge.status()
                if (live != null && live.startsWith("connected")) {
                    retryIndex = 0
                    noteReconnected()
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
                    publishAuthKeyMissing(authKeyResult)
                    delay(HEALTH_POLL_MS)
                    continue
                }

                val result = TsnetBridge.start(
                    context = appContext,
                    config = TunnelStartConfig(
                        controlURL = network.tailscaleControlUrl.trim(),
                        authKey = authKey,
                        hostname = network.effectiveTunnelHostname,
                        acceptRoutes = network.tailscaleAcceptRoutes,
                        exitNode = network.tailscaleExitNode,
                        advertiseRoutes = network.tailscaleAdvertiseRoutes,
                        ephemeral = network.tailscaleEphemeral,
                        serve = network.tailscaleServe,
                    ),
                    keepDesiredOnFailure = true,
                )
                if (result.isSuccess) {
                    retryIndex = 0
                    noteReconnected()
                    delay(HEALTH_POLL_MS)
                    continue
                }
                // 先计数：本次失败也要进周期汇总（周期耗尽行说「after N attempts」时
                // N 是本周期实际失败次数；放耗尽分支之后会把触发耗尽的那次漏掉）。
                cycleFailures++
                lastFailureMessage = result.exceptionOrNull()?.message
                if (retryIndex >= retryDelaysMs.size) {
                    AppLog.w(
                        TAG,
                        "tunnel reconnect backoff cycle exhausted after $cycleFailures attempts " +
                            "(last error: $lastFailureMessage), starting a new cycle",
                    )
                    retryIndex = 0
                    resetCycle()
                    delay(HEALTH_POLL_MS)
                    continue
                }
                // 只记本周期首条失败，其后抑制（退避表固定 5s/15s/60s，不用每步都记）；
                // 周期耗尽与重连成功时各有 1 条汇总/恢复行。
                if (cycleFailures == 1) {
                    AppLog.w(
                        TAG,
                        "tunnel reconnect failed, " +
                            "retry in ${retryDelaysMs[retryIndex]}ms: $lastFailureMessage",
                    )
                }
                delay(retryDelaysMs[retryIndex])
                retryIndex++
            }
        }
    }
}
