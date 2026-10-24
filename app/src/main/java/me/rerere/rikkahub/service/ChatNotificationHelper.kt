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
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.data.model.generationPhase
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
        replyMessageId: Uuid,
    ) {
        // Resolve only the assistant emitted by this exact turn; never fall back to an older reply.
        val conversation = getConversation(conversationId)
        val replyText =
            conversation.messageNodes
                .asSequence()
                .flatMap { it.messages.asSequence() }
                .firstOrNull { it.id == replyMessageId && it.role == MessageRole.ASSISTANT }
                ?.toText()
                ?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
        sendCompletionNotification(conversationId, senderName, replyText)
    }

    fun sendNeedsApprovalNotification(
        conversationId: Uuid,
        senderName: String,
        replyMessageId: Uuid,
    ) = sendActionNeededNotification(
        conversationId,
        senderName,
        replyMessageId,
        R.string.floating_chat_tool_needs_approval,
    )

    fun sendNeedsAnswerNotification(
        conversationId: Uuid,
        senderName: String,
        replyMessageId: Uuid,
    ) = sendActionNeededNotification(
        conversationId,
        senderName,
        replyMessageId,
        R.string.floating_chat_answer_in_conversation,
    )

    private fun sendActionNeededNotification(
        conversationId: Uuid,
        senderName: String,
        replyMessageId: Uuid,
        labelRes: Int,
    ) {
        // Guard the same turn binding as completed notifications. The content intent opens only
        // this conversation, leaving approval/answer handling to its existing UI.
        val hasCurrentReply =
            getConversation(conversationId)
                .messageNodes
                .asSequence()
                .flatMap { it.messages.asSequence() }
                .any { it.id == replyMessageId && it.role == MessageRole.ASSISTANT }
        if (!hasCurrentReply) return
        sendCompletionNotification(conversationId, senderName, context.getString(labelRes))
    }

    private fun sendCompletionNotification(
        conversationId: Uuid,
        senderName: String,
        contentText: String,
    ) {
        cancelLiveUpdateNotification(conversationId)
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = getDoneNotificationId(conversationId),
        ) {
            title = senderName
            content = contentText.take(50)
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

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> =
        when (val phase = parts.generationPhase()) {
            is GenerationPhase.AwaitingApproval ->
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.floating_chat_tool_needs_approval),
                    phase.toolName,
                )
            is GenerationPhase.AwaitingAnswer ->
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.floating_chat_answer),
                    phase.input.take(100),
                )
            is GenerationPhase.RunningTool ->
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, phase.toolName),
                    phase.input.take(100),
                )
            is GenerationPhase.Thinking ->
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    phase.reasoning.takeLast(200),
                )
            is GenerationPhase.Writing ->
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    phase.text.takeLast(200),
                )
            GenerationPhase.Waiting ->
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    "",
                )
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
