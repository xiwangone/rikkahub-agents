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
 * 隧道会话守护：**不自动连接**，只在用户手动开启后的存活期内保持/自愈。
 *
 * 边界（按用户口径）：
 * - 连接由开关触发 —— 开 = 建连、关 = 断开；
 * - 进程被划掉 → 隧道随进程消失，**下次打开 App 不会自动接回**（开关显示为关，点一下才连）；
 * - 本进程内用户开过之后，掉线仍自愈：每 [HEALTH_POLL_MS] 查一次 native 状态，
 *   按 5s / 15s / 60s 退避重连，否则掉一次线就得手动关再开两次。
 *
 * ⚠ 为什么不在启动时自动连：冷启动时 `settingsFlow` 首帧是 `Settings.dummy()`，
 * 它代表不了用户配置（旧实现据此判断开关，会白等一个轮询周期才建连）。
 * 连接配置只在需要重连时读取 —— 那时设置早已加载完成。
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
            var retryIndex = 0
            while (true) {
                // 用户没开（或已关、或启动失败回落）：静默待机 —— 不读配置、不建连。
                if (!TsnetBridge.isRunning) {
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
                runCatching { TsnetBridge.stop() }
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
                    hostname = network.tailscaleHostname.ifBlank { "rikkahub" },
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
