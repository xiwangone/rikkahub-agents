package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.server.LocalMcpServerManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.android.ext.android.inject

private const val TAG = "LocalMcpServerService"

/**
 * 承载本地 MCP Server 的前台服务：App 退到后台仍保持 Backend 可用的工具源。
 *
 * 开关状态由设置（[SettingsStore.localMcpServerEnabled]）维护，本服务只负责生命周期：
 * ACTION_START → 前台通知 + [LocalMcpServerManager].start()；ACTION_STOP → stop()。
 * 管理器的 state 流驱动前台通知内容与自动 stopSelf。
 */
class LocalMcpServerService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.MCP_SERVER_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.MCP_SERVER_STOP"
        const val NOTIFICATION_ID = 2007
        const val CHANNEL_ID = "mcp_server"
    }

    private val manager: LocalMcpServerManager by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startObservingState()
                startWithActiveProfile()
            }

            ACTION_STOP -> {
                manager.stop()
                // 开关由设置页/启动链维护；服务只负责生命周期，停止后由状态流触发 stopSelf
            }

            null -> {
                // 兜底：intent 为 null（系统重启重投）时按设置决定
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (settings.localMcpServerEnabled) {
                        startObservingState()
                        startWithActiveProfile()
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startWithActiveProfile() {
        val profile =
            runBlocking {
                val s = settingsStore.settingsFlowRaw.first()
                s.activeLocalMcpProfileId?.let { id -> s.localMcpProfiles.firstOrNull { it.id == id } }
            }
        if (profile != null) manager.start(profile) else manager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat(): Boolean =
        try {
            ensureNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildStartingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildStartingNotification())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }

    private fun startObservingState() {
        if (stateObserverJob?.isActive == true) return
        stateObserverJob =
            serviceScope.launch {
                var wasRunning = false
                manager.state.collect { state ->
                    when {
                        state.isRunning -> {
                            wasRunning = true
                            updateNotification(buildRunningNotification(state))
                        }

                        state.error != null && !state.isRunning -> {
                            // 启动失败（如端口占用）：停下服务，避免挂着无意义的常驻通知
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }

                        wasRunning && !state.isRunning -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
    }

    private fun updateNotification(notification: android.app.Notification) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("本地 MCP Server")
                .build()
        )
    }

    private fun buildLaunchPendingIntent() =
        PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildStartingNotification() =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("本地 MCP Server 启动中")
            .setContentText("Backend 设备工具源")
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun buildRunningNotification(state: me.rerere.rikkahub.data.ai.mcp.server.LocalMcpServerState): android.app.Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("本地 MCP Server 运行中")
            .setContentText("127.0.0.1:${state.port} · ${state.toolCount} 个工具")
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}