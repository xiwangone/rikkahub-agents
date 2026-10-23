package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.generationPhase
import me.rerere.rikkahub.ui.floating.FloatingChatWindow
import me.rerere.rikkahub.ui.floating.FloatingToolAction
import me.rerere.rikkahub.ui.floating.FloatingWindowState
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.uuid.Uuid

private data class FloatingChatSnapshot(
    val conversation: Conversation,
    val generating: Boolean,
    val processingStatus: String?,
    val queue: MessageQueueState,
    val replyMessage: UIMessage?,
    val startedAt: Long?,
    val errors: List<ChatError>,
)

/** Background-only overlay. Permission/window failures detach UI only; generation is independent. */
internal class ChatFloatingOverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val chat: ChatService,
) {
    private val overlayContext = context.applicationContext
    private val foreground = MutableStateFlow(true)
    private val hasOverlayPermission = MutableStateFlow(runCatching { Settings.canDrawOverlays(overlayContext) }.getOrDefault(false))
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
    private var bubble: ImageView? = null
    private var panel: ComposeView? = null
    private val uiState = androidx.compose.runtime.mutableStateOf(
        FloatingWindowState("", "", null, null, emptyList(), "", false, false, null, emptyList()),
    )
    private val uiSettings = androidx.compose.runtime.mutableStateOf(settingsStore.settingsFlow.value)
    private val onLifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_START -> foreground.value = true
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> foreground.value = false
            else -> Unit
        }
    }

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(onLifecycle)
        observerJobs += scope.launch {
            while (true) {
                hasOverlayPermission.value = runCatching { Settings.canDrawOverlays(overlayContext) }.getOrDefault(false)
                delay(750)
            }
        }
        observerJobs += scope.launch {
            settingsStore.settingsFlow.collect { uiSettings.value = it }
        }
        observerJobs += scope.launch {
            combine(
                combine(
                    settingsStore.settingsFlow.map { it.displaySetting.enableFloatingChatWindow }.distinctUntilChanged(),
                    foreground,
                    chat.getConversationJobs(),
                ) { enabled, isForeground, jobs -> Triple(enabled, isForeground, jobs.keys.map { it.toString() }.toSet() },
                hasOverlayPermission,
            ) { inputs, permission -> inputs to permission }
                .collect { (inputs, permission) -> refresh(inputs.first, inputs.second, inputs.third, permission) }
        }
    }

    fun close() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(onLifecycle)
        observerJobs.forEach(Job::cancel)
        observerJobs.clear()
        snapshotJob?.cancel()
        snapshotJob = null
        changeTarget(null)
        detachWindow()
        releaseReference()
    }

    private fun refresh(enabled: Boolean, isForeground: Boolean, running: Set<String>, permission: Boolean) {
        if (enabled != priorEnabled) policy = policy.updateEnabled(enabled)
        if (isForeground != priorForeground) policy = policy.updateForeground(isForeground)
        policy = policy.updatePermission(permission).updateRunning(running)
        priorEnabled = enabled
        priorForeground = isForeground
        previousJobs = running
        val nextId = policy.targetConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (nextId != targetId) changeTarget(nextId)
        if (!policy.shouldShowWindow) {
            detachWindow()
            snapshotJob?.cancel()
            snapshotJob = null
            releaseReference()
            return
        }
        if (root == null && !attachWindow()) {
            snapshotJob?.cancel()
            snapshotJob = null
            releaseReference()
            return
        }
        targetId?.let(::trackTarget)
        latestSnapshot?.let(::render)
    }

    private fun changeTarget(id: Uuid?) {
        if (targetId == id) return
        snapshotJob?.cancel()
        snapshotJob = null
        latestSnapshot = null
        releaseReference()
        targetId = id
        if (id == null) {
            detachWindow()
            return
        }
        if (root != null) trackTarget(id)
    }

    private fun releaseReference() {
        referenceHeld?.let(chat::removeConversationReference)
        referenceHeld = null
    }

    private fun trackTarget(id: Uuid) {
        if (root == null || (referenceHeld == id && snapshotJob?.isActive == true)) return
        releaseReference()
        snapshotJob?.cancel()
        referenceHeld = id
        chat.addConversationReference(id)
        snapshotJob = scope.launch {
            chat.hydrateFloatingConversation(id)
            val retainedSnapshot = latestSnapshot?.takeIf { it.conversation.id == id }
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
                    chat.errors,
                ) { queue, replyId, startedAt, errors -> listOf(queue, replyId, startedAt, errors) },
            ) { a, b ->
                val (conversation, job, processing) = a
                @Suppress("UNCHECKED_CAST")
                val queue = b[0] as MessageQueueState
                val replyId = b[1] as? Uuid
                val startedAt = b[2] as? Long
                @Suppress("UNCHECKED_CAST")
                val errors = b[3] as List<ChatError>
                val reply = replyId?.let { wanted -> conversation.messageNodes.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == wanted } }
                val restoredReply = reply ?: if (startedAt == null) retainedSnapshot?.replyMessage else null
                val restoredStartedAt = startedAt ?: if (replyId == null) retainedSnapshot?.startedAt else null
                FloatingChatSnapshot(conversation, job?.isActive == true, processing, queue, restoredReply, restoredStartedAt, errors)
            }.collect { snapshot ->
                if (root != null && targetId == id) {
                    latestSnapshot = snapshot
                    render(snapshot)
                }
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
            targetId?.let(::trackTarget)
            applyExpanded(policy.expanded)
            true
        } catch (_: SecurityException) {
            detachWindow(); false
        } catch (_: WindowManager.BadTokenException) {
            detachWindow(); false
        } catch (_: IllegalStateException) {
            detachWindow(); false
        }
    }

    private fun detachWindow() {
        val attached = root
        if (attached != null) runCatching { windowManager?.removeViewImmediate(attached) }
        root = null
        params = null
        windowManager = null
        bubble = null
        panel = null
    }

    private fun buildContent(): FrameLayout {
        val frame = FrameLayout(overlayContext)
        val icon = ImageView(overlayContext).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = overlayContext.getString(R.string.floating_chat_open)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            setOnClickListener { policy = policy.expand(); applyExpanded(true) }
        }
        bubble = icon
        val compose = ComposeView(overlayContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            ViewTreeLifecycleOwner.set(this, ProcessLifecycleOwner.get())
            setContent {
                val state = uiState.value
                FloatingChatWindow(
                    state = state,
                    settings = uiSettings.value,
                    onMinimize = { policy = policy.collapse(); applyExpanded(false) },
                    onClose = { policy = policy.dismiss(); detachWindow(); changeTarget(null) },
                    onOpen = { openSession() },
                    onSwitch = { switchSession() },
                    onResumeQueue = { targetId?.let(chat::resumeMessageQueue) },
                    onSend = { sendQuickReply(it) },
                    onCancel = { targetId?.let { id -> scope.launch { runCatching { chat.stopGeneration(id) } } } },
                    onApprove = { tool -> targetId?.let { chat.handleToolApproval(it, tool.callId, approved = true, scope = ChatService.ApprovalScope.Once, toolName = tool.name) } },
                    onDeny = { tool -> targetId?.let { chat.handleToolApproval(it, tool.callId, approved = false, reason = overlayContext.getString(R.string.floating_chat_denied_reason), toolName = tool.name) } },
                )
            }
        }
        panel = compose
        frame.addView(icon, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM))
        frame.addView(compose, FrameLayout.LayoutParams(dp(panelWidth()), dp(panelHeight()), Gravity.END or Gravity.BOTTOM))
        installDragHandler(icon)
        compose.visibility = View.GONE
        return frame
    }

    private fun render(snapshot: FloatingChatSnapshot) {
        latestSnapshot = snapshot
        val failure = snapshot.startedAt?.let { started ->
            snapshot.errors.filter { it.conversationId == targetId && it.timestamp >= started }.maxByOrNull { it.timestamp }
        }
        val reply = snapshot.replyMessage
        val phase = reply?.generationPhase()
        val status = when {
            failure != null -> overlayContext.getString(R.string.floating_chat_failed)
            snapshot.processingStatus?.isNotBlank() == true -> snapshot.processingStatus
            !snapshot.generating -> overlayContext.getString(R.string.floating_chat_ready)
            phase is me.rerere.rikkahub.data.model.GenerationPhase.AwaitingApproval -> overlayContext.getString(R.string.floating_chat_tool_needs_approval)
            phase is me.rerere.rikkahub.data.model.GenerationPhase.AwaitingAnswer -> overlayContext.getString(R.string.floating_chat_needs_answer)
            phase is me.rerere.rikkahub.data.model.GenerationPhase.RunningTool -> overlayContext.getString(R.string.floating_chat_tool_running, phase.toolName)
            phase is me.rerere.rikkahub.data.model.GenerationPhase.Thinking -> overlayContext.getString(R.string.floating_chat_thinking)
            phase is me.rerere.rikkahub.data.model.GenerationPhase.Writing -> overlayContext.getString(R.string.floating_chat_writing)
            phase == null -> overlayContext.getString(R.string.floating_chat_starting)
            else -> overlayContext.getString(R.string.floating_chat_waiting)
        }
        val elapsed = snapshot.startedAt?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0) / 1000).toInt() }
        val steps = reply?.parts?.filter { it is UIMessagePart.Tool || it is UIMessagePart.Reasoning }?.takeLast(8).orEmpty()
        val tools = reply?.parts?.filterIsInstance<UIMessagePart.Tool>()?.filter { it.isPending }?.map {
            FloatingToolAction(it.toolCallId, it.toolName, it.input, it.toolName == "ask_user")
        }.orEmpty()
        uiState.value = FloatingWindowState(
            title = snapshot.conversation.title.ifBlank { overlayContext.getString(R.string.app_name) },
            status = status,
            reply = reply,
            elapsedLabel = elapsed?.let { overlayContext.getString(R.string.floating_chat_elapsed, it) },
            steps = steps,
            queuedLabel = overlayContext.getString(if (snapshot.queue.paused) R.string.floating_chat_queue_paused else R.string.floating_chat_queue_count, snapshot.queue.messages.size),
            queuePaused = snapshot.queue.paused && snapshot.queue.messages.isNotEmpty(),
            multiSession = previousJobs.size > 1,
            failure = failure?.let { it.title?.takeIf(String::isNotBlank) ?: it.error.message ?: overlayContext.getString(R.string.floating_chat_failed) },
            tools = tools,
        )
    }

    private fun sendQuickReply(text: String) {
        val id = targetId ?: return
        if (text.isBlank()) return
        chat.sendMessage(id, listOf(UIMessagePart.Text(text = text)), answer = true)
    }

    private fun openSession() {
        targetId?.let { id -> runCatching {
            overlayContext.startActivity(Intent(overlayContext, me.rerere.rikkahub.RouteActivity::class.java)
                .putExtra("conversationId", id.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } }
    }

    private fun switchSession() {
        val ids = previousJobs.toList()
        if (ids.size < 2) return
        val current = targetId?.toString()
        val next = ids[(ids.indexOf(current).let { if (it < 0) -1 else it } + 1) % ids.size]
        policy = policy.selectConversation(next)
        changeTarget(runCatching { Uuid.parse(next) }.getOrNull())
    }

    private fun applyExpanded(value: Boolean) {
        expanded = value
        val frame = root ?: return
        bubble?.visibility = if (value) View.GONE else View.VISIBLE
        panel?.visibility = if (value) View.VISIBLE else View.GONE
        val lp = params ?: return
        if (value) {
            lp.width = dp(panelWidth())
            lp.height = dp(panelHeight())
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            lp.width = dp(56)
            lp.height = dp(56)
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        runCatching { windowManager?.updateViewLayout(frame, lp) }.onFailure { detachWindow(); releaseReference() }
    }

    private fun panelWidth(): Int = minOf(380, ((overlayContext.resources.displayMetrics.widthPixels / density()) - 24).coerceAtLeast(160))
    private fun panelHeight(): Int {
        val screenHeight = overlayContext.resources.displayMetrics.heightPixels / density()
        val safeAvailable = (screenHeight - 80).coerceAtLeast(180f)
        return minOf(620, safeAvailable.toInt(), (screenHeight * 0.8f).toInt()).coerceAtLeast(180)
    }
    private fun density(): Float = overlayContext.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density()).toInt()

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
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) dragged = true
                    if (dragged) {
                        lp.x = (initialX - dx).coerceIn(0, (overlayContext.resources.displayMetrics.widthPixels - lp.width).coerceAtLeast(0))
                        lp.y = (initialY + dy).coerceIn(dp(24), (overlayContext.resources.displayMetrics.heightPixels - lp.height - dp(48)).coerceAtLeast(dp(24)))
                        runCatching { windowManager?.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                else -> false
            }
        }
    }
}
