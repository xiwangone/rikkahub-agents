package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

/**
 * 隧道守护：App 启动时恢复 + 存活期间保持连接。
 *
 * 边界（按用户口径）：**App 存活就保持、划掉就断** —— 不注册前台服务、不做开机自启；
 * 进程被划掉后隧道随进程消失，用户再次打开 App 时由这里接回来。
 *
 * 每 [HEALTH_POLL_MS] 轮询一次 native 侧状态（[TsnetBridge.status]）：
 * - `connected` → 什么都不做；
 * - `offline`（后端掉线）→ 先停再重连；
 * - null（未运行）→ 解析密钥后启动；
 * 连接失败按 5s / 15s / 60s 退避，成功后重置退避。
 *
 * 配置类失败（开关关、密钥为空 / 引用无效）不重试，只在一个轮询周期后重新读配置。
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
                val network = settingsStore.settingsFlow.value.networkSetting
                if (network.tunnelType != NetworkSetting.TUNNEL_TAILSCALE) {
                    delay(HEALTH_POLL_MS)
                    continue
                }
                // 开关关着：停掉进程内可能还在跑的隧道，然后进入待机轮询。
                if (!network.tunnelEnabled) {
                    if (TsnetBridge.isRunning) {
                        runCatching { TsnetBridge.stop() }
                            .onFailure { AppLog.w(TAG, "stop stale tunnel failed: ${it.message}", it) }
                    }
                    retryIndex = 0
                    delay(HEALTH_POLL_MS)
                    continue
                }

                val state = TsnetBridge.status()
                if (state != null && state.startsWith("connected")) {
                    retryIndex = 0
                    delay(HEALTH_POLL_MS)
                    continue
                }

                // 需要建立或重建连接：offline 时先停，避免 native 侧返回 "already running"。
                if (TsnetBridge.isRunning) {
                    runCatching { TsnetBridge.stop() }
                        .onFailure { AppLog.w(TAG, "reset before reconnect failed: ${it.message}", it) }
                }

                val authKeyResult = resolveTunnelAuthKey(resolver, network.tailscaleAuthKey)
                val authKey = authKeyResult.getOrNull()
                if (authKey == null) {
                    // 配置问题：重试也不会成功，等下一轮（用户可能去改设置）。
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
                val ip = result.getOrNull()
                if (ip != null) {
                    AppLog.i(TAG, "tunnel up: $ip")
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
                    "tunnel start failed (try ${retryIndex + 1}), retry in ${retryDelaysMs[retryIndex]}ms: " +
                        result.exceptionOrNull()?.message,
                )
                delay(retryDelaysMs[retryIndex])
                retryIndex++
            }
        }
    }
}
