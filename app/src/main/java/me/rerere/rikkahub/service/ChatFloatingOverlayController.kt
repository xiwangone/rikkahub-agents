package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

private data class FloatingChatSnapshot(
    val conversation: Conversation,
    val generating: Boolean,
    val processingStatus: String?,
    val queue: MessageQueueState,
    val replyMessage: UIMessage?,
    val startedAt: Long?,
)

/**
 * Owns one optional background-only overlay. It deliberately uses WindowManager instead of
 * replacing the repository's existing FloatingX 2.3.7 callers. Permission and setting failures
 * only detach the window; they never cancel or mutate a generation session.
 */
internal class ChatFloatingOverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val chat: ChatService,
) {
    private val foreground = MutableStateFlow(true)
    private val hasOverlayPermission = MutableStateFlow(runCatching { Settings.canDrawOverlays(context.applicationContext) }.getOrDefault(false))
    private var policy = FloatingOverlayPolicyState()
    private var priorEnabled = false
    private var priorForeground = true
    private var previousJobs: Set<String> = emptySet()
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var targetId: Uuid? = null
    private var expanded = false
    private var referenceHeld: Uuid? = null
    private var snapshotJob: Job? = null
    private val observerJobs = mutableListOf<Job>()
    private var latestSnapshot: FloatingChatSnapshot? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var timelineView: TextView? = null
    private var approvals: LinearLayout? = null
    private var replyInput: EditText? = null
    private var queuedView: TextView? = null
    private val overlayContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val onLifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_START -> foreground.value = true
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> foreground.value = false
            else -> Unit
        }
    }

    fun start() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(onLifecycle)
        observerJobs += scope.launch {
            // Overlay access may be revoked from Android settings while our process remains alive.
            while (true) {
                hasOverlayPermission.value = runCatching { Settings.canDrawOverlays(overlayContext) }.getOrDefault(false)
                delay(750)
            }
        }
        observerJobs += scope.launch {
            combine(
                combine(
                    settingsStore.settingsFlow.map { it.displaySetting.enableFloatingChatWindow }.distinctUntilChanged(),
                    foreground,
                    chat.getConversationJobs(),
                ) { enabled, isForeground, jobs -> Triple(enabled, isForeground, jobs.keys.map { it.toString() }.toSet()) },
                hasOverlayPermission,
            ) { inputs, permission -> inputs to permission }
                .collect { (inputs, permission) ->
                    refresh(inputs.first, inputs.second, inputs.third, permission)
                }
        }
    }

    fun close() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(onLifecycle)
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()
        snapshotJob?.cancel()
        changeTarget(null)
        detachWindow()
    }

    private fun refresh(enabled: Boolean, isForeground: Boolean, running: Set<String>, permission: Boolean) {
        if (enabled != priorEnabled) policy = policy.updateEnabled(enabled)
        if (isForeground != priorForeground) policy = policy.updateForeground(isForeground)
        policy = policy.updatePermission(permission)
        policy = policy.updateRunning(running)
        priorEnabled = enabled
        priorForeground = isForeground
        previousJobs = running
        val nextId = policy.targetConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (nextId != targetId) changeTarget(nextId)
        if (!policy.shouldShowWindow) {
            detachWindow()
            snapshotJob?.cancel()
            snapshotJob = null
            referenceHeld?.let(chat::removeConversationReference)
            referenceHeld = null
            return
        }
        targetId?.let(::trackTarget)
        if (root == null && !attachWindow()) return
        applyExpanded(policy.expanded)
        latestSnapshot?.let(::render)
    }

    private fun changeTarget(id: Uuid?) {
        if (targetId == id) return
        snapshotJob?.cancel()
        latestSnapshot = null
        referenceHeld?.let(chat::removeConversationReference)
        targetId = id
        if (id == null) {
            detachWindow()
            return
        }
        trackTarget(id)
    }

    private fun trackTarget(id: Uuid) {
        if (referenceHeld == id && snapshotJob?.isActive == true) return
        referenceHeld?.let(chat::removeConversationReference)
        snapshotJob?.cancel()
        referenceHeld = id
        chat.addConversationReference(id)
        snapshotJob = scope.launch {
            // combine has typed overloads only up to five flows; nest pairs to retain type safety.
            combine(
                combine(
                    chat.getConversationFlow(id),
                    chat.getGenerationJobStateFlow(id),
                    chat.getProcessingStatusFlow(id),
                ) { conversation, job, processing -> Triple(conversation, job, processing) },
                combine(
                    chat.messageQueueState(id),
                    chat.getFloatingReplyMessageIdFlow(id),
                    chat.getFloatingGenerationStartedAtFlow(id),
                ) { queue, replyId, startedAt -> Triple(queue, replyId, startedAt) },
            ) { a, b ->
                val (conversation, job, processing) = a
                val (queue, replyId, startedAt) = b
                val reply = replyId?.let { wanted ->
                    conversation.messageNodes.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == wanted }
                }
                FloatingChatSnapshot(conversation, job?.isActive == true, processing, queue, reply, startedAt)
            }.collect { snapshot ->
                latestSnapshot = snapshot
                render(snapshot)
            }
        }
    }

    private fun attachWindow(): Boolean {
        if (!Settings.canDrawOverlays(overlayContext)) return false
        val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val content = buildContent()
        val initial = WindowManager.LayoutParams(
            dp(56), dp(56),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(180)
        }
        return try {
            wm.addView(content, initial)
            windowManager = wm
            root = content
            params = initial
            applyExpanded(policy.expanded)
            true
        } catch (_: SecurityException) {
            // Overlay permission can be revoked while a window is attached.
            detachWindow()
            false
        } catch (_: WindowManager.BadTokenException) {
            detachWindow()
            false
        } catch (_: IllegalStateException) {
            detachWindow()
            false
        }
    }

    private fun detachWindow() {
        val attached = root
        if (attached != null) {
            runCatching { windowManager?.removeViewImmediate(attached) }
        }
        root = null
        params = null
        windowManager = null
        bubble = null
        panel = null
        titleView = null
        statusView = null
        timelineView = null
        approvals = null
        replyInput = null
        queuedView = null
    }

    private fun buildContent(): FrameLayout {
        val frame = FrameLayout(overlayContext)
        val bubbleText = TextView(overlayContext).apply {
            text = "R"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(73, 90, 180), dp(28))
            contentDescription = overlayContext.getString(R.string.floating_chat_open)
        }
        bubble = bubbleText
        val card = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(250, 250, 255), dp(18))
            elevation = dp(12).toFloat()
        }
        panel = card
        val header = LinearLayout(overlayContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = TextView(overlayContext).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(25, 28, 38)); maxLines = 1 }
        titleView = heading
        header.addView(heading, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(smallButton(overlayContext.getString(R.string.floating_chat_minimize)) { policy = policy.collapse(); applyExpanded(false) })
        header.addView(smallButton(overlayContext.getString(R.string.floating_chat_close)) { policy = policy.dismiss(); detachWindow(); changeTarget(null) })
        card.addView(header)
        val status = TextView(overlayContext).apply { textSize = 13f; setTextColor(Color.rgb(77, 82, 99)); setPadding(0, dp(4), 0, dp(8)) }
        statusView = status
        card.addView(status)
        val scroll = ScrollView(overlayContext).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val details = LinearLayout(overlayContext).apply { orientation = LinearLayout.VERTICAL }
        val timeline = TextView(overlayContext).apply { textSize = 14f; setTextColor(Color.rgb(35, 38, 48)); setTextIsSelectable(true); setPadding(0, 0, 0, dp(8)) }
        timelineView = timeline
        details.addView(timeline)
        val approvalRows = LinearLayout(overlayContext).apply { orientation = LinearLayout.VERTICAL }
        approvals = approvalRows
        details.addView(approvalRows)
        scroll.addView(details)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val queued = TextView(overlayContext).apply { textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(4), 0, dp(4)) }
        queuedView = queued
        card.addView(queued)
        val compose = LinearLayout(overlayContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(overlayContext).apply {
            hint = overlayContext.getString(R.string.floating_chat_reply_hint)
            setSingleLine(false)
            maxLines = 3
            textSize = 14f
        }
        replyInput = input
        compose.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        compose.addView(smallButton(overlayContext.getString(R.string.floating_chat_send)) { sendQuickReply() })
        card.addView(compose)
        card.addView(smallButton(overlayContext.getString(R.string.floating_chat_cancel_generation)) {
            val id = targetId ?: return@smallButton
            scope.launch { runCatching { chat.stopGeneration(id) } }
        })
        frame.addView(bubbleText, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM))
        frame.addView(card, FrameLayout.LayoutParams(dp(340), dp(470), Gravity.END or Gravity.BOTTOM))
        bubbleText.setOnClickListener { policy = policy.expand(); applyExpanded(true) }
        installDragHandler(bubbleText)
        card.visibility = View.GONE
        bubbleText.visibility = View.VISIBLE
        return frame
    }

    private fun render(snapshot: FloatingChatSnapshot) {
        latestSnapshot = snapshot
        val reply = snapshot.replyMessage
        titleView?.text = snapshot.conversation.title.ifBlank { overlayContext.getString(R.string.app_name) }
        val phase = when {
            snapshot.processingStatus?.isNotBlank() == true -> snapshot.processingStatus
            !snapshot.generating -> overlayContext.getString(R.string.floating_chat_ready)
            reply == null -> overlayContext.getString(R.string.floating_chat_starting)
            else -> phaseText(reply)
        }
        statusView?.text = phase
        timelineView?.text = buildTimeline(reply, snapshot.startedAt)
        queuedView?.text = overlayContext.getString(
            if (snapshot.queue.paused) R.string.floating_chat_queue_paused else R.string.floating_chat_queue_count,
            snapshot.queue.messages.size,
        )
        val pending = reply?.parts?.filterIsInstance<UIMessagePart.Tool>()?.filter { it.isPending }.orEmpty()
        val holder = approvals ?: return
        holder.removeAllViews()
        if (snapshot.queue.paused && snapshot.queue.messages.isNotEmpty()) {
            holder.addView(smallButton(overlayContext.getString(R.string.floating_chat_resume_queue)) {
                targetId?.let(chat::resumeMessageQueue)
            })
        }
        if (previousJobs.size > 1) {
            holder.addView(smallButton(overlayContext.getString(R.string.floating_chat_switch_session)) {
                val ids = previousJobs.toList()
                val current = targetId?.toString()
                val next = ids[(ids.indexOf(current).let { if (it < 0) -1 else it } + 1) % ids.size]
                policy = policy.selectConversation(next)
                changeTarget(runCatching { Uuid.parse(next) }.getOrNull())
            })
        }
        holder.addView(smallButton(overlayContext.getString(R.string.floating_chat_open_session)) {
            targetId?.let { id ->
                runCatching {
                    overlayContext.startActivity(Intent(overlayContext, me.rerere.rikkahub.RouteActivity::class.java)
                        .putExtra("conversationId", id.toString())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
            }
        })
        pending.forEach { tool ->
            val isAsk = tool.toolName == "ask_user"
            val prompt = TextView(overlayContext).apply {
                text = if (isAsk) overlayContext.getString(R.string.floating_chat_question, tool.input) else overlayContext.getString(R.string.floating_chat_tool_request, tool.toolName, tool.input)
                textSize = 13f
                setTextColor(Color.rgb(48, 50, 58))
                setPadding(0, dp(6), 0, dp(4))
            }
            holder.addView(prompt)
            if (isAsk) {
                val answer = EditText(overlayContext).apply { hint = overlayContext.getString(R.string.floating_chat_answer_hint); maxLines = 3 }
                holder.addView(answer)
                holder.addView(smallButton(overlayContext.getString(R.string.floating_chat_answer)) {
                    val value = answer.text?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty()) chat.handleToolApproval(targetId ?: return@smallButton, tool.toolCallId, approved = false, answer = value, toolName = tool.toolName)
                })
            } else {
                val actions = LinearLayout(overlayContext).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(smallButton(overlayContext.getString(R.string.floating_chat_approve_once)) {
                    chat.handleToolApproval(targetId ?: return@smallButton, tool.toolCallId, approved = true, scope = ChatService.ApprovalScope.Once, toolName = tool.toolName)
                })
                actions.addView(smallButton(overlayContext.getString(R.string.floating_chat_deny)) {
                    chat.handleToolApproval(targetId ?: return@smallButton, tool.toolCallId, approved = false, reason = overlayContext.getString(R.string.floating_chat_denied_reason), toolName = tool.toolName)
                })
                holder.addView(actions)
            }
        }
    }

    private fun phaseText(message: UIMessage): String {
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        if (tool != null && !tool.isExecuted) return overlayContext.getString(R.string.floating_chat_tool_running, tool.toolName)
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        if (reasoning != null && reasoning.finishedAt == null) return overlayContext.getString(R.string.floating_chat_thinking)
        if (message.parts.any { it is UIMessagePart.Text }) return overlayContext.getString(R.string.floating_chat_writing)
        return overlayContext.getString(R.string.floating_chat_waiting)
    }

    private fun buildTimeline(message: UIMessage?, startedAt: Long?): String {
        if (message == null) return overlayContext.getString(R.string.floating_chat_no_reply)
        val lines = mutableListOf<String>()
        message.parts.takeLast(8).forEach { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.takeIf(String::isNotBlank)?.let { lines += it }
                is UIMessagePart.Reasoning -> part.reasoning.takeIf(String::isNotBlank)?.let { lines += "${overlayContext.getString(R.string.floating_chat_thinking)}\n$it" }
                is UIMessagePart.Tool -> {
                    val label = if (part.isPending) overlayContext.getString(R.string.floating_chat_tool_needs_approval) else overlayContext.getString(R.string.floating_chat_tool_step)
                    lines += "$label: ${part.toolName}\n${part.output.ifBlank { part.input }.take(400)}"
                }
                else -> Unit
            }
        }
        val elapsed = startedAt?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0) / 1000).toInt() }
        if (elapsed != null) lines.add(0, overlayContext.getString(R.string.floating_chat_elapsed, elapsed))
        return lines.joinToString("\n\n").ifBlank { overlayContext.getString(R.string.floating_chat_no_reply) }
    }

    private fun sendQuickReply() {
        val id = targetId ?: return
        val text = replyInput?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        replyInput?.text?.clear()
        chat.sendMessage(id, listOf(UIMessagePart.Text(text = text)), answer = true)
    }

    private fun applyExpanded(value: Boolean) {
        expanded = value
        val frame = root ?: return
        bubble?.visibility = if (value) View.GONE else View.VISIBLE
        panel?.visibility = if (value) View.VISIBLE else View.GONE
        val lp = params ?: return
        if (value) {
            lp.width = dp(340)
            lp.height = dp(470)
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            lp.width = dp(56)
            lp.height = dp(56)
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        runCatching { windowManager?.updateViewLayout(frame, lp) }.onFailure { detachWindow() }
    }

    private fun installDragHandler(view: View) {
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = lp.x; initialY = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) dragged = true
                    if (dragged) { lp.x = (initialX - dx).coerceAtLeast(0); lp.y = (initialY + dy).coerceAtLeast(0); runCatching { windowManager?.updateViewLayout(root, lp) } }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                else -> false
            }
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(overlayContext).apply {
        text = label
        textSize = 12f
        minHeight = dp(42)
        minWidth = dp(44)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun dp(value: Int): Int = (value * density).toInt()
}
