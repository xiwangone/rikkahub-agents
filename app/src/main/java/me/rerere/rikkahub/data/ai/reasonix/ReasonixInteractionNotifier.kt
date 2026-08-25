package me.rerere.rikkahub.data.ai.reasonix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.reasonix.ReasonixApi
import me.rerere.ai.provider.providers.reasonix.ReasonixInteractionHandler
import me.rerere.ai.ui.AskQuestion
import me.rerere.rikkahub.R

/**
 * Reasonix 富事件的交互桥：把 serve 的 approval_request / ask_request 转成系统通知，
 * 用户在通知栏批准/拒绝（或文本回答）后，通过 [ReasonixApi.approve] / [ReasonixApi.answer]
 * 把决策回传 serve，使 Ask/Approval 在 Reasonix 直连路径上形成完整闭环。
 *
 * 与对话卡片中的展示态渲染（UIMessageAnnotation）互补：卡片负责展示，本桥负责交互。
 * 应答状态存在进程内存（进程被杀则交互失效），与 MCP 审批桥同一模式。
 */
class ReasonixInteractionNotifier(private val context: Context) : ReasonixInteractionHandler {

    private companion object {
        const val TAG = "ReasonixInteraction"
        const val CHANNEL_ID = "reasonix_interactions"
        const val CHANNEL_NAME = "Reasonix 提问与审批"
        const val ACTION_APPROVE = "me.rerere.rikkahub.action.REASONIX_APPROVE"
        const val ACTION_DENY = "me.rerere.rikkahub.action.REASONIX_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_APPROVED = "approved"
        const val KEY_REPLY = "reply_text"
    }

    private data class PendingApproval(
        val setting: ProviderSetting.Reasonix,
        val tool: String,
    )

    private data class PendingAsk(
        val setting: ProviderSetting.Reasonix,
        val questions: List<AskQuestion>,
    )

    private val lock = Any()
    private val pendingApprovals = mutableMapOf<String, PendingApproval>()
    private val pendingAsks = mutableMapOf<String, PendingAsk>()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
            when (intent.action) {
                ACTION_APPROVE, ACTION_DENY -> {
                    val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
                    val p = synchronized(lock) { pendingApprovals.remove(requestId) } ?: return
                    cancelNotification(requestId)
                    ioScope.launch {
                        runCatching {
                            api(p.setting).approve(id = requestId, allow = approved)
                        }.onFailure {
                            Log.w(TAG, "approve($requestId, $approved) failed", it)
                        }
                    }
                }

                else -> {
                    val reply = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                    val p = synchronized(lock) { pendingAsks.remove(requestId) } ?: return
                    cancelNotification(requestId)
                    if (reply.isEmpty()) return
                    ioScope.launch {
                        runCatching {
                            api(p.setting).answer(
                                id = requestId,
                                answers =
                                    p.questions.map { q ->
                                        buildJsonObject {
                                            put("id", JsonPrimitive(q.id))
                                            put("answers", JsonPrimitive(reply))
                                        }
                                    },
                            )
                        }.onFailure {
                            Log.w(TAG, "answer($requestId) failed", it)
                        }
                    }
                }
            }
        }
    }

    override fun onApprovalRequest(
        setting: ProviderSetting.Reasonix,
        id: String,
        tool: String,
        subject: String?,
    ) {
        ensureReceiverRegistered()
        synchronized(lock) { pendingApprovals[id] = PendingApproval(setting, tool) }
        ensureChannel()
        val approveIntent =
            Intent(ACTION_APPROVE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_REQUEST_ID, id)
                putExtra(EXTRA_APPROVED, true)
            }
        val denyIntent =
            Intent(ACTION_DENY).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_REQUEST_ID, id)
                putExtra(EXTRA_APPROVED, false)
            }
        val approvePi =
            PendingIntent.getBroadcast(
                context,
                id.hashCode() and 0x7fffffff,
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val denyPi =
            PendingIntent.getBroadcast(
                context,
                (id.hashCode() and 0x7fffffff) + 1,
                denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val body = subject?.takeIf { it.isNotBlank() } ?: "Reasonix 请求执行工具 $tool"
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle("Reasonix 工具待批准：$tool")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .addAction(0, "批准", approvePi)
                .addAction(0, "拒绝", denyPi)
                .build()
        notify(id, notification)
    }

    override fun onAskRequest(
        setting: ProviderSetting.Reasonix,
        id: String,
        questions: List<AskQuestion>,
    ) {
        ensureReceiverRegistered()
        synchronized(lock) { pendingAsks[id] = PendingAsk(setting, questions) }
        ensureChannel()
        val body =
            questions.joinToString("\n\n") { q ->
                val options =
                    q.options.joinToString("\n") { opt ->
                        "• ${opt.label}${opt.description?.let { "（$it）" } ?: ""}"
                    }
                "问：${q.prompt}${if (q.multi) "（多选）" else "（单选）"}\n$options"
            }
        val replyIntent =
            Intent().apply {
                action = "me.rerere.rikkahub.action.REASONIX_ASK_REPLY"
                setPackage(context.packageName)
                putExtra(EXTRA_REQUEST_ID, id)
            }
        val replyPi =
            PendingIntent.getBroadcast(
                context,
                (id.hashCode() and 0x7fffffff) + 2,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("输入答案/选项").build()
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle("Reasonix 提问")
                .setContentText(questions.firstOrNull()?.prompt ?: "请回答")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_send,
                        "回答",
                        replyPi,
                    ).addRemoteInput(remoteInput).build(),
                )
                .build()
        notify(id, notification)
    }

    private fun api(setting: ProviderSetting.Reasonix) =
        ReasonixApi(
            baseUrl = setting.baseUrl,
            username = setting.username,
            password = setting.password,
            token = setting.token,
        )

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        synchronized(lock) {
            if (receiverRegistered) return
            val filter =
                IntentFilter().apply {
                    addAction(ACTION_APPROVE)
                    addAction(ACTION_DENY)
                    addAction("me.rerere.rikkahub.action.REASONIX_ASK_REPLY")
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun notify(id: String, notification: android.app.Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; interaction stays card-only")
            return
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(id.hashCode() and 0x7fffffff, notification)
        }.onFailure {
            Log.w(TAG, "Failed to post interaction notification", it)
        }
    }

    private fun cancelNotification(id: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(id.hashCode() and 0x7fffffff)
        }
    }
}