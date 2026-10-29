package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

private const val TAG = "GenerationFgs"

/**
 * 生成回复期间把进程留在前台。
 *
 * 没有前台服务时，界面被隐藏后进程会被系统当作缓存进程冻结，流式连接随之停摆，
 * 表现为「回复说到一半就断了」。这里只负责前台服务的生命周期，生成本身仍由
 * [ChatService] 持有，本服务不读取任何消息内容。
 */
@Suppress("TooGenericExceptionCaught") // 前台服务启动/停止边界：底层异常类型不可枚举，统一按「进入前台失败」处理并清理状态
class GenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.GENERATION_RELEASE"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val NOTIFICATION_ID = 2004

        /** 有一轮生成开始时调用；已在前台则只累加计数。 */
        fun acquire(context: Context, conversationId: String): Boolean {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start generation foreground service", it)
            }.getOrDefault(false)
        }

        /** 一轮生成结束（含取消/失败）时调用；没有活跃生成后服务自行退出。 */
        fun release(context: Context, conversationId: String) {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            runCatching { context.startService(intent) }.onFailure {
                Log.e(TAG, "Unable to release generation foreground service", it)
            }
        }
    }

    private val activeConversations = linkedSetOf<String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                if (conversationId == null) {
                    finishForeground()
                } else {
                    activeConversations += conversationId
                    updateForegroundNotification()
                }
            }

            ACTION_RELEASE -> {
                intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let(activeConversations::remove)
                if (activeConversations.isEmpty()) finishForeground() else updateForegroundNotification()
            }

            else -> finishForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeConversations.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 系统回收前台服务不等于生成失败：进程还在就继续跑，用户回到界面仍能看到结果。
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        activeConversations.clear()
        finishForeground()
    }

    private fun updateForegroundNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeConversations.clear()
            stopSelf()
        }
    }

    /** 退出前台并结束自身（命名避开 Context.stopService(Intent)，免得跨文件同名误判）。 */
    private fun finishForeground() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, GENERATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_telegram)
            .setContentTitle(getString(R.string.generation_foreground_title))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        // 会话 id 的跳转参数由界面层各自解释，这里只负责把 App 带回前台。
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(contentIntent)
        }
        return builder.build()
    }
}
