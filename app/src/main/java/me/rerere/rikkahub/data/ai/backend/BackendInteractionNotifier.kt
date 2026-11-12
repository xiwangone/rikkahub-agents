package me.rerere.rikkahub.data.ai.backend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.backend.BackendApi
import me.rerere.ai.provider.providers.backend.BackendInteractionHandler
import me.rerere.ai.ui.AskQuestion
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog

/**
 * Backend 富事件的交互桥：把 serve 的 approval_request / ask_request 转成系统通知，
 * 用户在通知栏批准/拒绝（或文本回答）后，通过 [BackendApi.approve] / [BackendApi.answer]
 * 把决策回传 serve，使 Ask/Approval 在 Backend 直连路径上形成完整闭环。
 *
 * 与对话卡片中的展示态渲染（UIMessageAnnotation）互补：卡片负责展示，本桥负责交互。
 * 应答状态存在进程内存（进程被杀则交互失效），与 MCP 审批桥同一模式。
 */
class BackendInteractionNotifier(private val context: Context) : BackendInteractionHandler {

    private companion object {
        const val TAG = "BackendInteraction"
        const val CHANNEL_ID = "backend_interactions"
        const val ACTION_APPROVE = "me.rerere.rikkahub.action.BACKEND_APPROVE"
        const val ACTION_DENY = "me.rerere.rikkahub.action.BACKEND_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_APPROVED = "approved"
        const val KEY_REPLY = "reply_text"
    }

    private data class PendingApproval(
        val setting: ProviderSetting.Backend,
        val tool: String,
        val subject: String? = null,
    )

    private data class PendingAsk(
        val setting: ProviderSetting.Backend,
        val questions: List<AskQuestion>,
    )

    /**
     * 决策投递结果：已送达 / 请求已不在待处理（别处已处理或进程重启）/
     * 投递失败（请求已放回待处理，调用方可提示用户重试）。
     */
    enum class InteractionDelivery {
        DELIVERED,
        NOT_PENDING,
        FAILED,
    }

    private val lock = Any()
    private val pendingApprovals = mutableMapOf<String, PendingApproval>()
    private val pendingAsks = mutableMapOf<String, PendingAsk>()

    /** 当前仍待应答的请求 id 集合（供对话卡片与通知栏状态同步）。 */
    private val _pendingIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _pendingIds.asStateFlow()

    /** 重新计算待应答集合（任何增删后调用）。 */
    private fun syncPendingIds() {
        _pendingIds.value = synchronized(lock) { pendingApprovals.keys.toSet() + pendingAsks.keys.toSet() }
    }
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
            when (intent.action) {
                ACTION_APPROVE, ACTION_DENY -> {
                    val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
                    val p = synchronized(lock) { pendingApprovals.remove(requestId) } ?: return
                    syncPendingIds()
                    cancelNotification(requestId)
                    ioScope.launch {
                        runCatching {
                            api(p.setting).approve(id = requestId, allow = approved)
                        }.onFailure {
                            AppLog.e(TAG, "approve($requestId, $approved) failed", it)
                            // 投递失败不丢决策：放回待处理并重发通知，用户可重试。
                            synchronized(lock) { pendingApprovals[requestId] = p }
                            syncPendingIds()
                            postApprovalNotification(requestId, p.tool, p.subject)
                        }
                    }
                }

                else -> {
                    val reply = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                    val p = synchronized(lock) { pendingAsks.remove(requestId) } ?: return
                    syncPendingIds()
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
                            AppLog.e(TAG, "answer($requestId) failed", it)
                            // 投递失败不丢回答：放回待处理并重发通知，用户可重试。
                            synchronized(lock) { pendingAsks[requestId] = p }
                            syncPendingIds()
                            postAskNotification(requestId, p.questions)
                        }
                    }
                }
            }
        }
    }

    override fun onApprovalRequest(
        setting: ProviderSetting.Backend,
        id: String,
        tool: String,
        subject: String?,
    ) {
        ensureReceiverRegistered()
        synchronized(lock) { pendingApprovals[id] = PendingApproval(setting, tool, subject) }
        syncPendingIds()
        postApprovalNotification(id, tool, subject)
    }

    /** 发（或重发）审批通知：投递失败后重发即为重试入口。 */
    private fun postApprovalNotification(id: String, tool: String, subject: String?) {
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
        val body = subject?.takeIf { it.isNotBlank() } ?: context.getString(R.string.backend_notif_tool_request, tool)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_yao)
                .setContentTitle(context.getString(R.string.backend_notif_pending_approve, tool))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.approve), approvePi)
                .addAction(0, context.getString(R.string.deny), denyPi)
                .build()
        notify(id, notification)
    }

    /**
     * 由对话内嵌卡片调用：按 requestId 回应审批（与通知栏按钮同一通道）。
     * 四档决策映射到 serve 的 session / persist 参数（本会话允许 / 持久允许）。
     * 调用方按返回值区分：已送达 / 已不在待处理 / 投递失败（请求已放回待处理）。
     */
    suspend fun approveById(
        requestId: String,
        approved: Boolean,
        session: Boolean = false,
        persist: Boolean = false,
        scope: String = "",
    ): InteractionDelivery {
        val p = synchronized(lock) { pendingApprovals.remove(requestId) }
            ?: return InteractionDelivery.NOT_PENDING
        syncPendingIds()
        cancelNotification(requestId)
        return try {
            api(p.setting).approve(
                id = requestId,
                allow = approved,
                session = session,
                persist = persist,
                scope = scope,
            )
            InteractionDelivery.DELIVERED
        } catch (cancelled: CancellationException) {
            // 取消不算投递失败：放回待处理并向上传播。
            synchronized(lock) { pendingApprovals[requestId] = p }
            syncPendingIds()
            throw cancelled
        } catch (failure: Exception) {
            AppLog.e(TAG, "approve($requestId, $approved) failed", failure)
            // 投递失败不丢决策：放回待处理，调用方提示用户重试。
            synchronized(lock) { pendingApprovals[requestId] = p }
            syncPendingIds()
            InteractionDelivery.FAILED
        }
    }

    /** 由对话内嵌卡片调用：回答提问（answers: questionId → 文本）。 */
    suspend fun answerById(requestId: String, answer: String): InteractionDelivery {
        val p = synchronized(lock) { pendingAsks.remove(requestId) }
            ?: return InteractionDelivery.NOT_PENDING
        syncPendingIds()
        cancelNotification(requestId)
        if (answer.isBlank()) return InteractionDelivery.DELIVERED
        return try {
            api(p.setting).answer(
                id = requestId,
                answers =
                    p.questions.map { q ->
                        buildJsonObject {
                            put("id", JsonPrimitive(q.id))
                            put("answers", JsonPrimitive(answer))
                        }
                    },
            )
            InteractionDelivery.DELIVERED
        } catch (cancelled: CancellationException) {
            // 取消不算投递失败：放回待处理并向上传播。
            synchronized(lock) { pendingAsks[requestId] = p }
            syncPendingIds()
            throw cancelled
        } catch (failure: Exception) {
            AppLog.e(TAG, "answer($requestId) failed", failure)
            // 投递失败不丢回答：放回待处理，调用方提示用户重试。
            synchronized(lock) { pendingAsks[requestId] = p }
            syncPendingIds()
            InteractionDelivery.FAILED
        }
    }

    override fun onAskRequest(
        setting: ProviderSetting.Backend,
        id: String,
        questions: List<AskQuestion>,
    ) {
        ensureReceiverRegistered()
        synchronized(lock) { pendingAsks[id] = PendingAsk(setting, questions) }
        syncPendingIds()
        postAskNotification(id, questions)
    }

    /** 发（或重发）提问通知：投递失败后重发即为重试入口。 */
    private fun postAskNotification(id: String, questions: List<AskQuestion>) {
        ensureChannel()
        val body =
            questions.joinToString("\n\n") { q ->
                val options =
                    q.options.joinToString("\n") { opt ->
                        "• ${opt.label}${opt.description?.let { context.getString(R.string.backend_notif_desc_paren, it) } ?: ""}"
                    }
                context.getString(R.string.backend_notif_ask_body, q.prompt, if (q.multi) context.getString(R.string.backend_notif_multi) else context.getString(R.string.backend_notif_single), options)
            }
        val replyIntent =
            Intent().apply {
                action = "me.rerere.rikkahub.action.BACKEND_ASK_REPLY"
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
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.backend_notif_reply_hint)).build()
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_yao)
                .setContentTitle(context.getString(R.string.backend_notif_ask_title))
                .setContentText(questions.firstOrNull()?.prompt ?: context.getString(R.string.backend_notif_answer_hint))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_send,
                        context.getString(R.string.answer),
                        replyPi,
                    ).addRemoteInput(remoteInput).build(),
                )
                .build()
        notify(id, notification)
    }

    private fun api(setting: ProviderSetting.Backend) =
        BackendApi(
            baseUrl = setting.baseUrl,
            username = setting.username,
            // 与 token 一致：支持 `$$凭证名` 引用（未命中保持原样，便于暴露配置错误）
            password = me.rerere.rikkahub.data.vault.VaultProviderKeyRefs.resolveValue(setting.password),
            // 支持 `$$凭证名` 引用（未命中保持原样，便于暴露配置错误）
            token = me.rerere.rikkahub.data.vault.VaultProviderKeyRefs.resolveValue(setting.token),
        )

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        synchronized(lock) {
            if (receiverRegistered) return
            val filter =
                IntentFilter().apply {
                    addAction(ACTION_APPROVE)
                    addAction(ACTION_DENY)
                    addAction("me.rerere.rikkahub.action.BACKEND_ASK_REPLY")
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
                    context.getString(R.string.backend_notif_channel_name),
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
            AppLog.i(TAG, "POST_NOTIFICATIONS not granted; interaction stays card-only")
            return
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(id.hashCode() and 0x7fffffff, notification)
        }.onFailure {
            AppLog.w(TAG, "Failed to post interaction notification", it)
        }
    }

    private fun cancelNotification(id: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(id.hashCode() and 0x7fffffff)
        }
    }
}