package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog

/**
 * 前台服务通知管理。
 *
 * 从 TelegramBotService 提取：startInForeground / buildForegroundNotification /
 * updateForegroundNotification 三件套。
 */
internal class TelegramForegroundHelper(
    private val service: Service,
) {
    fun startInForeground() {
        val nm = service.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    service.getString(R.string.notification_channel_telegram_bot),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notif = buildNotification()
        // Android 14+ ties the runtime FGS type to the manifest declaration AND to a
        // hard daily-budget cap depending on the type. We previously used DATA_SYNC, which
        // caps at 6 hours/day per app — Telegram bot polling is intended to run
        // indefinitely, so we'd hit ForegroundServiceDidNotStopInTimeException. SPECIAL_USE
        // has no such cap (the manifest declares the subtype "long-running Telegram bot
        // long-poll loop") and is the correct flavor for app-specific long-running work
        // that doesn't fit any predefined Google category.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * Build the foreground notification. If the strict whitelist has rejected at least one
     * sender, surface the most recent rejected sender_id in the body so the user has a way
     * to bootstrap an empty whitelist without spelunking through logcat.
     */
    fun buildNotification(): android.app.Notification {
        val rejected = TelegramBotRegistries.RejectedSenderLog.latest()
        val body =
            if (rejected != null) {
                service.getString(
                    R.string.notification_telegram_rejected_sender_body,
                    rejected.senderId,
                    rejected.chatId,
                )
            } else {
                service.getString(R.string.notification_telegram_routing_body)
            }
        return NotificationCompat
            .Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.notification_telegram_listening_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notif_telegram)
            .setOngoing(true)
            .build()
    }

    /** Re-render the notification in place. Cheap (single NotificationManager call). */
    fun updateNotification() {
        try {
            val nm = service.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: SecurityException) {
            // Notifications can fail in restricted contexts (POST_NOTIFICATIONS revoked,
            // channel blocked); non-fatal, but log so a vanished notification leaves a trace.
            AppLog.w(TAG, "updateForegroundNotification failed", e)
        }
    }

    companion object {
        const val CHANNEL_ID = "rikkahub_telegram_bot"
        const val NOTIF_ID = 0xA1B2
        private const val TAG = "TelegramBotService"
    }
}
