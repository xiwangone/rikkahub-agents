package me.rerere.rikkahub.service

import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.local.PHONE_USE_TOOL_NAME
import me.rerere.rikkahub.phoneuse.PhoneUseManager
import kotlin.uuid.Uuid

/**
 * Phone Use 期间的悬浮窗门控，从 [ChatFloatingOverlayController] 抽出来：
 * 截图或执行手势时让出屏幕，回复用过 Phone Use 的一轮结束时展开面板。
 * 窗口本身仍由控制器持有，这里只通过回调操作它。
 */
internal class PhoneUseOverlayGate(
    private val scope: CoroutineScope,
    private val chat: ChatService,
    /** Phone Use 开始动作时收起面板 */
    private val collapsePanel: () -> Unit,
    /** 当前悬浮窗的根视图、窗口参数和窗口服务，没有窗口时根视图与参数为 null */
    private val windowState: () -> Triple<FrameLayout?, WindowManager.LayoutParams?, WindowManager?>,
) {
    /** 开始监听 Phone Use 的 acting 状态；返回的 Job 由调用方在关闭时取消 */
    fun start(): Job = scope.launch { PhoneUseManager.acting.collect(::yieldScreen) }

    /** 目标会话一轮结束时，若回复用过 phone_use 则返回 true，调用方展开面板 */
    fun usedPhoneUse(conversationId: Uuid): Boolean {
        val replyId = chat.getFloatingReplyMessageIdFlow(conversationId).value ?: return false
        return chat.getConversationFlow(conversationId).value.messageNodes.any { node ->
            node.messages.any { message ->
                message.id == replyId && message.getTools().any { it.toolName == PHONE_USE_TOOL_NAME }
            }
        }
    }

    /**
     * Phone Use 截图或执行手势时让出屏幕：悬浮窗不能出现在截图里，也不能挡住落在它位置上的点击。
     * 不用 detach（每次重建内容会一直闪），只让窗口透明并且不接收触摸；展开的面板先收起。
     * 透明度要改窗口参数而不是 View：Android 12 起，触摸穿过不透明的悬浮窗落到别的应用上会被系统丢弃，
     * 系统看的是窗口的透明度。
     */
    private fun yieldScreen(yielding: Boolean) {
        if (yielding) collapsePanel()
        val (frame, lp, windowManager) = windowState()
        if (frame == null || lp == null) return
        if (yielding) {
            frame.alpha = 0f
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            frame.alpha = 1f
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager?.updateViewLayout(frame, lp) }
    }
}
