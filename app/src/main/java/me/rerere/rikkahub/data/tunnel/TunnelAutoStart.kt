package me.rerere.rikkahub.data.tunnel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

/**
 * App 进程启动时恢复隧道。
 *
 * 边界（按用户口径）：**App 存活就保持、划掉就断** —— 不注册前台服务、不做开机自启，
 * 进程被划掉后隧道随进程一起消失；用户再次打开 App 时由这里把上次开启的隧道接回来。
 *
 * 失败策略：
 * - 配置问题（开关关、密钥为空 / 引用无效）不重试，只记日志（重试也不会成功）；
 * - 启动失败按 5s / 15s / 60s 退避重试 3 次，之后放弃并记日志（不打扰界面）。
 */
object TunnelAutoStart {
    private const val TAG = "TunnelAutoStart"
    private val retryDelaysMs = longArrayOf(5_000L, 15_000L, 60_000L)

    fun start(
        context: Context,
        settingsStore: SettingsStore,
        vaultRepository: CredentialVaultRepository,
    ) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val network = runCatching { settingsStore.settingsFlow.first().networkSetting }.getOrNull()
                ?: return@launch
            if (network.tunnelType != NetworkSetting.TUNNEL_TAILSCALE) return@launch

            // 开关关着：顺手把进程内可能还在跑的隧道停掉（避免与界面状态不一致）。
            if (!network.tunnelEnabled) {
                if (TsnetBridge.isRunning) {
                    runCatching { TsnetBridge.stop() }
                        .onFailure { AppLog.w(TAG, "stop stale tunnel failed: ${it.message}", it) }
                }
                return@launch
            }
            if (TsnetBridge.isRunning) return@launch

            val resolver = CredentialResolver(vaultRepository)
            val authKey = resolveTunnelAuthKey(resolver, network.tailscaleAuthKey).getOrElse { error ->
                AppLog.w(TAG, "tunnel restore skipped: ${error.message}")
                return@launch
            }
            val controlUrl = network.tailscaleControlUrl.trim()
            val hostname = network.tailscaleHostname.ifBlank { "rikkahub" }

            var attempt = 0
            while (true) {
                if (TsnetBridge.isRunning) return@launch
                val result = TsnetBridge.start(
                    context = appContext,
                    controlURL = controlUrl,
                    authKey = authKey,
                    hostname = hostname,
                )
                val ip = result.getOrNull()
                if (ip != null) {
                    AppLog.i(TAG, "tunnel restored on app launch: $ip")
                    return@launch
                }
                if (attempt >= retryDelaysMs.size) {
                    AppLog.w(TAG, "tunnel restore gave up: ${result.exceptionOrNull()?.message}")
                    return@launch
                }
                AppLog.w(
                    TAG,
                    "tunnel restore failed (try ${attempt + 1}), retry in ${retryDelaysMs[attempt]}ms: " +
                        result.exceptionOrNull()?.message,
                )
                delay(retryDelaysMs[attempt])
                attempt++
            }
        }
    }
}
