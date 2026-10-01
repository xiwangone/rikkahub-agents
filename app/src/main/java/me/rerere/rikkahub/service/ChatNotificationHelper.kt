package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

/**
 * 生成过程的系统通知：后台 Live Update 进度通知与完成通知。
 *
 * 从 [ChatService] 抽出。会话读取与 live 会话集合由调用方注入，
 * 本类只负责通知的组装与发送，不持有会话状态机。
 */
internal class ChatNotificationHelper(
    private val context: Context,
    private val getConversation: (Uuid) -> Conversation,
    private val liveConversations: MutableSet<Uuid>,
) {

    fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
    ) {
        // 先取消 Live Update 通知；完成通知沿用固定 id 直接覆盖，
        // 不再先取消再发送（同一 id 上取消与发送相邻执行时，部分系统会丢掉这一次更新，
        // 通知栏便会停留在更早的内容上）。
        cancelLiveUpdateNotification(conversationId)

        // 内容取最近一条助手回复：列表末条可能是用户消息（例如后台里又收到了下一条），
        // 直接取末条会把用户的话当成回复显示，与页面内容对不上。
        val conversation = getConversation(conversationId)
        val replyText =
            conversation.currentMessages
                .lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.toText()
                ?.trim()
                .orEmpty()
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = getDoneNotificationId(conversationId),
        ) {
            title = senderName
            content = replyText.take(50)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int = conversationId.hashCode() + 10000

    fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId),
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
        liveConversations.add(conversationId)
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态：倒序扫描一次即可拿到各类 part 的最后一项，
        // 原先三次 filterIsInstance 每次都会新建列表，在长回复下被逐块调用开销明显。
        var lastReasoning: UIMessagePart.Reasoning? = null
        var lastTool: UIMessagePart.Tool? = null
        var lastText: UIMessagePart.Text? = null
        for (index in parts.indices.reversed()) {
            when (val part = parts[index]) {
                is UIMessagePart.Reasoning -> if (lastReasoning == null) lastReasoning = part
                is UIMessagePart.Tool -> if (lastTool == null) lastTool = part
                is UIMessagePart.Text -> if (lastText == null) lastText = part
                else -> {}
            }
            if (lastReasoning != null && lastTool != null && lastText != null) break
        }

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                // MCP tools are exposed as `mcp__<serverSlug>_<serverName>__<toolName>`; strip
                // both the prefix and the server segment so the notification shows the bare tool
                // name. Non-MCP tool names (no `mcp__` prefix) fall through unchanged via the
                // missingDelimiterValue, instead of being truncated at an embedded `__`.
                val toolName =
                    lastTool.toolName
                        .removePrefix("mcp__")
                        .substringAfter("__", missingDelimiterValue = lastTool.toolName.removePrefix("mcp__"))
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100),
                )
            }

            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200),
                )
            }

            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200),
                )
            }

            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    "",
                )
            }
        }
    }

    fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveConversations.remove(conversationId)
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    fun cancelAllLiveNotifications() {
        liveConversations.toList().forEach { cancelLiveUpdateNotification(it) }
    }

    private fun getDoneNotificationId(conversationId: Uuid): Int = conversationId.hashCode() + 20000

    fun cancelDoneNotification(conversationId: Uuid) {
        context.cancelNotification(getDoneNotificationId(conversationId))
    }

    private fun getPendingIntent(
        context: Context,
        conversationId: Uuid,
    ): PendingIntent {
        val intent =
            Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId.toString())
            }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
