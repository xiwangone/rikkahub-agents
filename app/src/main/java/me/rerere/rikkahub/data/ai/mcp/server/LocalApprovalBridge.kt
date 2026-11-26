package me.rerere.rikkahub.data.ai.mcp.server

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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.log.AppLog

/**
 * MCP 工具执行前的审批桥：需要审批时挂起执行，通过系统通知（批准 / 拒绝 action）
 * 把决策交还用户，超时自动拒绝。
 *
 * 与对话消息流中的审批 UI 相互独立——MCP 调用由 Backend serve 发起，App 内没有
 * 对应的 UIMessage 上下文，因此用通知 + 动态广播接收器作为审批通道。但审批
 * 决策与对话路径统一（见 [requireApproval]）：NO_ALWAYS_ALLOW 的工具一律通知
 * 确认；其余先看 YOLO / 持久“总是允许”，再看工具声明。入站调用没有会话上下文，
 * 会话级 💬 不适用。
 */
class LocalApprovalBridge(
    private val context: Context,
    /**
     * 入站调用的自动批准判定（与对话路径同一套：YOLO 开，或工具在持久“总是允许”
     * 里）。null = 不自动批准，一律按工具声明走通知。由调用方注入，与
     * GenerationAgentRun.isToolAutoApproved 同一模式。
     */
    private val isAutoApproved: (suspend (String) -> Boolean)? = null,
) {

    private companion object {
        const val TAG = "LocalApprovalBridge"
        const val CHANNEL_ID = "mcp_approval_requests"
        const val ACTION_APPROVE = "me.rerere.rikkahub.action.MCP_APPROVE"
        const val ACTION_DENY = "me.rerere.rikkahub.action.MCP_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_APPROVED = "approved"
        const val EXTRA_TOOL_NAME = "tool_name"
        /** 审批等待上限：超时视为拒绝（自动化任务无人应答时不永久挂起）。 */
        const val APPROVAL_TIMEOUT_MS = 120_000L
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
    @Volatile private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
            val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
            val deferred = synchronized(lock) { pending.remove(requestId) }
            deferred?.complete(approved)
            requestId.hashCode().let { notificationId ->
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
        }
    }

    /**
     * 返回 true 表示可以直接执行。决策与对话路径统一：
     *  1. NO_ALWAYS_ALLOW 的工具一律通知确认（YOLO / 持久授权越不过；入站调用
     *     没有会话上下文，会话级 💬 不适用）；
     *  2. 其余工具：YOLO 开或在持久“总是允许”里 → 直接执行；
     *  3. 否则按工具声明 `needsApproval(input)` 决定是否通知。
     */
    suspend fun requireApproval(tool: Tool, input: JsonElement, requestId: String): Boolean {
        if (isDirectlyExecutable(tool, input)) return true
        ensureReceiverRegistered()
        val deferred = CompletableDeferred<Boolean>()
        synchronized(lock) { pending[requestId] = deferred }
        // 先登记再发通知，避免用户极快点击时 onReceive 先于登记执行而丢失决策。
        postApprovalNotification(tool, input, requestId)
        return withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
    }

    private suspend fun isDirectlyExecutable(tool: Tool, input: JsonElement): Boolean {
        // NO_ALWAYS_ALLOW：YOLO / 持久授权越不过，入站又没有会话级 💬 → 一律通知确认。
        if (!ToolApprovalDefaults.isAutoApprovable(tool.name)) return false
        if (isAutoApproved?.invoke(tool.name) == true) return true
        return !tool.needsApproval(input)
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        synchronized(lock) {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(ACTION_APPROVE)
                addAction(ACTION_DENY)
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

    private fun ensureNotificationChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.mcp_approval_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.mcp_approval_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postApprovalNotification(tool: Tool, input: JsonElement, requestId: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(TAG, "POST_NOTIFICATIONS not granted; approval gated on timeout-reject")
            return
        }
        ensureNotificationChannel()
        val summary = summarizeInput(tool, input)
        val approveIntent = Intent(ACTION_APPROVE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_APPROVED, true)
            putExtra(EXTRA_TOOL_NAME, tool.name)
        }
        val denyIntent = Intent(ACTION_DENY).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_APPROVED, false)
            putExtra(EXTRA_TOOL_NAME, tool.name)
        }
        val approvePi = PendingIntent.getBroadcast(
            context,
            requestId.hashCode() and 0x7fffffff,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val denyPi = PendingIntent.getBroadcast(
            context,
            (requestId.hashCode() and 0x7fffffff) + 1,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(me.rerere.rikkahub.utils.AppIconManager.currentStatIconRes)
            .setContentTitle(context.getString(R.string.mcp_approval_title, tool.name))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            // 审批通知内容是工具入参摘要（锁屏可见即泄露）：必须 PRIVATE，随系统
            // “锁屏隐藏敏感内容”遮蔽；全仓唯一需要改的 PUBLIC，另一处是仅含
            // 标题/艺人的媒体播放通知（良性）。
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.approve), approvePi)
            .addAction(0, context.getString(R.string.deny), denyPi)
            .build()
        runCatching {
            // 通知 id 按 requestId：同工具并发审批各占一条通知，互不覆盖；
            // onReceive 取消时按同一 id 对齐。
            NotificationManagerCompat.from(context).notify(requestId.hashCode(), notification)
        }.onFailure {
            AppLog.w(TAG, "Failed to post approval notification", it)
        }
    }

    private fun summarizeInput(tool: Tool, input: JsonElement): String {
        val args = try {
            (input as? JsonObject)?.entries?.take(4)?.joinToString(", ") { (k, v) ->
                val raw = v.jsonPrimitive.contentOrNull ?: v.toString()
                "$k=${raw.take(40)}"
            } ?: input.toString().take(200)
        } catch (_: IllegalArgumentException) {
            // jsonPrimitive 对非 JsonPrimitive 元素抛 IllegalArgumentException，是 try 块唯一异常源
            input.toString().take(200)
        }
        return context.getString(R.string.mcp_approval_body, tool.name, args)
    }
}