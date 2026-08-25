package me.rerere.rikkahub.data.ai.mcp.server

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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP 工具执行前的审批桥：`tool.needsApproval(input) == true` 时挂起执行，
 * 通过系统通知（批准 / 拒绝 action）把决策交还用户，超时自动拒绝。
 *
 * 与对话消息流中的审批 UI 相互独立——MCP 调用由 Reasonix serve 发起，App 内没有
 * 对应的 UIMessage 上下文，因此用通知 + 动态广播接收器作为审批通道。
 */
class LocalApprovalBridge(private val context: Context) {

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
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
            val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
            val deferred = synchronized(lock) { pending.remove(requestId) }
            deferred?.complete(approved)
            intent?.getStringExtra(EXTRA_TOOL_NAME)?.let { toolName ->
                NotificationManagerCompat.from(context).cancel(toolName.hashCode())
            }
        }
    }

    /**
     * 返回 true 表示可以直接执行。needsApproval 为 false 的只读/低风险工具直接放行。
     */
    suspend fun requireApproval(tool: Tool, input: JsonElement, requestId: String): Boolean {
        if (!tool.needsApproval(input)) return true
        ensureReceiverRegistered()
        val deferred = CompletableDeferred<Boolean>()
        synchronized(lock) { pending[requestId] = deferred }
        // 先登记再发通知，避免用户极快点击时 onReceive 先于登记执行而丢失决策。
        postApprovalNotification(tool, input, requestId)
        return withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
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
                "MCP 工具审批",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Reasonix 通过本地 MCP 调用设备工具时的批准/拒绝"
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
            Log.w(TAG, "POST_NOTIFICATIONS not granted; approval gated on timeout-reject")
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
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("设备工具待批准：${tool.name}")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "批准", approvePi)
            .addAction(0, "拒绝", denyPi)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(tool.name.hashCode(), notification)
        }.onFailure {
            Log.w(TAG, "Failed to post approval notification", it)
        }
    }

    private fun summarizeInput(tool: Tool, input: JsonElement): String {
        val args = try {
            (input as? JsonObject)?.entries?.take(4)?.joinToString(", ") { (k, v) ->
                val raw = v.jsonPrimitive.contentOrNull ?: v.toString()
                "$k=${raw.take(40)}"
            } ?: input.toString().take(200)
        } catch (e: Exception) {
            input.toString().take(200)
        }
        return "Reasonix 请求调用设备工具 ${tool.name}。\n参数：$args\n\n批准后将在本机执行。"
    }
}