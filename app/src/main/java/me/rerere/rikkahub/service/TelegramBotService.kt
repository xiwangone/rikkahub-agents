package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.log.AppLog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.TelegramChatRepository
import me.rerere.rikkahub.data.telegram.TelegramApiException
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.data.telegram.TelegramMyChatMember
import me.rerere.rikkahub.data.telegram.parseCallbackQuery
import me.rerere.rikkahub.data.telegram.parseIncoming
import me.rerere.rikkahub.data.telegram.parseMyChatMember
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

/**
 * Foreground service that long-polls Telegram for incoming messages and routes them into the
 * existing chat pipeline so the LLM can reply, with full tool-calling.
 *
 * Outbound: TelegramTool (LLM-callable) + this service's outbound helpers exposed via the
 * shared singleton client.
 *
 * Lifecycle: started by TelegramTool's enable_telegram_bot, stopped by disable_telegram_bot,
 * also re-started after device boot via CronBootReceiver if config.enabled was true.
 */
/**
 * Telegram 机器人后台长连接服务：消息轮询/Webhook 接收、指令分发与会话代理。
 *
 * 本文件很长（2300+ 行）。阅读与维护时请按下面的段落名检索符号，不要依赖行号：
 *  - 服务生命周期：[onCreate] / [onStartCommand] / [onDestroy]
 *  - 轮询与网络通信：长轮询连接、心跳与异常断线退避重试
 *  - 消息解析与分发：文本指令、图片输入、语音识别桥接
 *  - 会话执行与回调：调用 [me.rerere.rikkahub.service.ChatService] 发起推理与流式回复
 *
 * 相关文件：后台会话服务 [me.rerere.rikkahub.service.ChatService]。
 */
class TelegramBotService : Service() {
    // The DI-injected dependencies are `internal` (not `private`) because the command
    // handler extension functions in TelegramCommandHandlers.kt access them. Module-private
    // is the same practical scope — only :app classes can resolve TelegramBotService, and
    // it's Koin-managed there.
    internal val prefs: TelegramBotPreferences by inject()
    internal val client: TelegramBotClient by inject()
    internal val chatService: ChatService by inject()
    internal val conversationRepo: ConversationRepository by inject()
    internal val chatRepo: TelegramChatRepository by inject()
    internal val settingsStore: SettingsStore by inject()
    internal val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks by inject()
    internal val agentRunRepo: me.rerere.rikkahub.data.agentrun.AgentRunRepository by inject()

    // Phase 24 — shared long-poll stall tracker. The poll loop stamps it on every
    // getUpdates; the stall checker reads it; DoctorChecks reads it.
    private val pollStallTracker: me.rerere.rikkahub.data.telegram.TelegramPollStallTracker by inject()

    // Uncaught exceptions in scope.launch children would otherwise hit the default handler,
    // which logs and kills the process (utils/CrashHandler) — swallow + log here instead so a
    // single bad message task cannot take down the long-poll service.
    internal val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> AppLog.e(TAG, "uncaught error in bot scope", e) }
        )
    private var pollJob: Job? = null
    private var externalGenPumpJob: Job? = null
    internal val foregroundHelper by lazy { TelegramForegroundHelper(this) }

    // Phase 24 — the periodic poll-stall checker coroutine. Launched alongside the poll
    // loop in onStartCommand, cancelled with the service scope.
    private var pollStallCheckerJob: Job? = null

    /**
     * Per-chat serialization for non-built-in messages. The poll loop launches each
     * inbound message in its own coroutine (so it can return to getUpdates immediately
     * and pick up /stop while a long generation is in flight), but two LLM round-trips
     * for the same chat must NOT interleave. Built-in slash commands skip this mutex
     * entirely so /stop and /new run the moment they arrive.
     */
    private val chatMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    internal fun mutexFor(chatId: Long): Mutex = chatMutexes.getOrPut(chatId) { Mutex() }

    /**
     * Per-toolCallId mutex used to serialise inline-keyboard tap callbacks for the SAME
     * approval prompt. Without this, two whitelisted users tapping different scope
     * buttons on the same prompt within ~50ms each pass the `tool.isPending` check
     * (a snapshot read) and both call handleToolApproval; the second cancel()
     * interrupts the first's resume coroutine and the recorded scope can flip silently.
     */
    internal val approvalMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    internal fun approvalMutexFor(toolCallId: String): Mutex = approvalMutexes.getOrPut(toolCallId) { Mutex() }

    /**
     * Tracks the active handleLlmTurn coroutine per chat so /stop and /new can cancel it
     * directly. Without this, a stop/reset cancels the ChatService generation job but
     * the handleLlmTurn loop stays parked on `getGenerationJobStateFlow(...).first { it != null }`
     * waiting forever for a generation that won't come — holding the per-chat mutex —
     * so every subsequent message bounces off `tryLock` with "previous turn waiting".
     * Recovery used to require force-stopping the app.
     */
    internal val turnJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /**
     * Conversations whose generation pump is currently being driven by an active
     * [handleIncoming] (i.e. a real Telegram-incoming-message turn). Used by the external
     * generation-done listener to AVOID double-pumping: when handleIncoming finishes a
     * turn, it streams the final text to Telegram itself, so the listener must skip.
     *
     * The listener fires for the OTHER case: a generation that wasn't kicked off by an
     * inbound Telegram message — e.g. the [SubAgentEngine.postRunResultToParent] wake
     * message, where a sub-agent finished and posted a synthetic user message into the
     * parent's conversation. Without this listener, the parent's LLM responds silently
     * to the wake — its reply lands in the in-app chat history but never reaches Telegram.
     */
    internal val activeHandleIncomingConvs: java.util.concurrent.ConcurrentHashMap.KeySetView<Uuid, Boolean> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<Uuid>()

    /**
     * Deferred-removal jobs for [activeHandleIncomingConvs]. When [handleIncoming] exits,
     * the set entry stays for [ACTIVE_INCOMING_LINGER_MS] so the external generation-done
     * pump still sees the conv as "Telegram-pumped right now" and skips it.
     *
     * Why the linger: [chatService.sendMessage]'s launch block emits to
     * [chatService.generationDoneFlow] BEFORE the launch block exits, but the pump's
     * `collect { }` lambda runs on a separate coroutine. There is a tiny window where
     * [handleIncoming] can return + run its finally (remove from activeHandleIncomingConvs)
     * BEFORE the pump's lambda processes the emit — typically observed on very fast
     * generations (sub-second), where handleIncoming's final Telegram edit + cleanup
     * race past the pump's scheduling latency. Without the linger the pump then sees
     * the conv as not-actively-pumped and sends a duplicate copy of the assistant text
     * to Telegram (visible to the user as the same reply landing twice in a row).
     *
     * A new handleIncoming for the same chat cancels any pending removal so the entry
     * doesn't get dropped mid-turn.
     */
    internal val pendingActiveIncomingRemovals: java.util.concurrent.ConcurrentHashMap<Uuid, Job> =
        java.util.concurrent.ConcurrentHashMap<Uuid, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Do NOT call startForeground here. Android 12+ ForegroundServiceStartNotAllowedException
        // will fire on any auto-revive (START_STICKY / process restart / etc.) because the
        // foreground "ticket" only exists when the service was explicitly started via
        // startForegroundService() from a user-engaged context. We promote in onStartCommand.
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        try {
            foregroundHelper.startInForeground()
        } catch (e: Throwable) {
            // Most likely on Android 12+ ForegroundServiceStartNotAllowedException after
            // process revive without a fresh foreground ticket, OR SecurityException on
            // Android 14+ if the FOREGROUND_SERVICE_<TYPE> permission isn't declared.
            // Either way: log loudly so we don't fail silently again, then stop.
            AppLog.e("TelegramBotService", "startForeground failed; service will not run", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        // Phase 24 — launch the poll-stall checker as a child of the service scope (so it
        // is auto-cancelled on FGS teardown). It periodically asks the tracker whether
        // getUpdates has gone silent inside a still-live FGS; on a stall it recycles JUST
        // the poll loop (not the service). GUARDED like externalGenPumpJob below — every
        // onStartCommand would otherwise spawn a duplicate checker.
        if (pollStallCheckerJob?.isActive != true) {
            pollStallTracker.markUpdate() // fresh baseline so the checker doesn't fire on stale state
            val checker =
                TelegramPollStallChecker(
                    tracker = pollStallTracker,
                    restartPollLoop = ::restartPollLoop,
                    onEscalate = ::escalatePollStallToFgsRestart,
                )
            pollStallCheckerJob = scope.launch { checker.monitor() }
        }
        // Refresh the slash-command menu Telegram shows users every time the bot starts
        // (and any time the TelegramBotRegistries.BUILT_IN_COMMANDS list changes between releases). Idempotent
        // and cheap; failures are logged but not fatal.
        scope.launch { registerBuiltInCommandsWithTelegram() }
        // External generation-done pump: when a chat-mapped conversation finishes a
        // generation that wasn't kicked off by handleIncoming (e.g. a sub-agent wake), push
        // the assistant's reply text to Telegram so the user sees it without polling.
        // GUARDED — onStartCommand runs many times in a session (service revive, APK
        // reinstall during dev, bot re-toggle). Without this guard, every call spawns a
        // fresh listener; each generationDoneFlow emit triggers ALL listeners; the chat
        // gets N duplicate replies for one sub-agent wake.
        if (externalGenPumpJob?.isActive != true) {
            externalGenPumpJob = scope.launch { listenForExternalGenerationCompletions() }
        }
        isRunning = true
        // START_NOT_STICKY: don't auto-revive; revival without a fresh foreground ticket would
        // crash with the same exception. The boot receiver, RikkaHubApp.onCreate, and the user
        // re-enable cover the legitimate restart paths.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }


    /**
     * Long-poll Telegram, dispatch messages, advance offset. Acquires a partial WakeLock
     * around each getUpdates request so the CPU and network stay alive during Doze
     * maintenance windows on OEM-aggressive ROMs (Xiaomi/OPPO/OnePlus/Vivo). Without this,
     * the long-poll connection sits idle on a sleeping CPU and updates only arrive during
     * the device's brief maintenance bursts.
     */
    private suspend fun pollLoop() {
        AppLog.i(TAG, "pollLoop: starting")
        val pm = applicationContext.getSystemService(android.os.PowerManager::class.java)
        // Persisted offset survives process death: a cold start replays no more than
        // the updates that arrived in the gap. Without this, an OEM kill -> next boot
        // re-processes up to 24 h of cached updates (Telegram retains unconfirmed
        // server-side) and the bot replies to ancient messages.
        var offset = runCatching { prefs.lastOffset() }.getOrDefault(0L)
        AppLog.i(TAG, "pollLoop: starting at offset=$offset")
        var cycle = 0L
        // Exponential backoff state. Bumped on every transient error, reset on every
        // successful cycle. Without this, a brief network blip used to spin every 5s
        // forever; now we back off to a max of 2 minutes between retries.
        var consecutiveErrors = 0
        // Bot-token tracking: if `telegram_set_token` writes a new value mid-loop the
        // offset must reset (different bot, different update_id space). Without this,
        // the new bot starts at offset = old-bot's-last + 1 which is always large
        // enough that legitimate inbound updates get skipped.
        var lastTokenSeen: String? = null
        while (true) {
            val cfg =
                try {
                    prefs.current()
                } catch (e: Throwable) {
                    AppLog.e(TAG, "pollLoop: prefs.current() failed", e)
                    null
                }
            if (cfg == null || !cfg.isUsable) {
                AppLog.w(
                    TAG,
                    "pollLoop: cfg unusable (token_set=${cfg?.token?.isNotBlank()} enabled=${cfg?.enabled}); stopping",
                )
                stopSelf()
                return
            }
            if (lastTokenSeen != null && lastTokenSeen != cfg.token) {
                AppLog.i(TAG, "pollLoop: token changed; resetting offset")
                offset = 0L
                runCatching { prefs.setLastOffset(0L) }
                consecutiveErrors = 0
            }
            lastTokenSeen = cfg.token
            // Held only for the duration of one long-poll cycle, and only when the screen
            // is off - while the device is interactive the CPU stays awake on its own, so
            // the lock is pure battery waste (see shouldHoldPollWakeLock). Released in
            // finally so a crash during getUpdates does not leak the wakelock.
            val wakeLock =
                if (shouldHoldPollWakeLock(pm?.isInteractive)) {
                    pm
                        ?.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK,
                            "rikkahub:telegram_long_poll",
                        )?.also { it.setReferenceCounted(false) }
                } else {
                    null
                }
            try {
                cycle++
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                val updates = client.getUpdates(offset, 30)
                // Phase 24 — stamp the stall tracker on every successful getUpdates round-
                // trip (success OR empty result). A long-poll that returns at all — even
                // with zero updates — proves the loop is alive; the stall checker only
                // fires when this stamp goes stale for >90s.
                pollStallTracker.markUpdate()
                if (cycle <= 2 || updates.isNotEmpty()) {
                    AppLog.i(TAG, "pollLoop: cycle=$cycle offset=$offset updates=${updates.size}")
                }
                val offsetBefore = offset
                for (u in updates.map { it as kotlinx.serialization.json.JsonObject }) {
                    // Bump the offset BEFORE deciding whether to dispatch this update.
                    // Without this, any update parseIncoming returns null for (callback_query,
                    // edited_message, content-less message, etc.) keeps its update_id, so
                    // Telegram returns the same update on the next getUpdates and the loop
                    // re-processes it forever. This caused an infinite re-poll the first
                    // time a non-message update slipped past the allowed_updates filter.
                    val updateId = u["update_id"]?.jsonPrimitive?.longOrNull
                    if (updateId != null && updateId >= offset) offset = updateId + 1
                    val incoming = parseIncoming(u)
                    if (incoming != null) {
                        AppLog.i(
                            TAG,
                            "pollLoop: dispatching message ${incoming.messageId} from chat=${incoming.chatId} sender=${incoming.senderId}",
                        )
                        scope.launch {
                            try {
                                handleIncoming(cfg, incoming)
                            } catch (
                                e: Throwable,
                            ) {
                                AppLog.e(TAG, "handleIncoming threw for message ${incoming.messageId}", e)
                            }
                        }
                        continue
                    }
                    val cq = parseCallbackQuery(u)
                    if (cq != null) {
                        AppLog.i(
                            TAG,
                            "pollLoop: dispatching callback_query ${cq.callbackQueryId} from chat=${cq.chatId} sender=${cq.senderId}",
                        )
                        scope.launch {
                            try {
                                handleCallbackQuery(cfg, cq)
                            } catch (
                                e: Throwable,
                            ) {
                                AppLog.e(TAG, "handleCallbackQuery threw for ${cq.callbackQueryId}", e)
                            }
                        }
                        continue
                    }
                    val mcm = parseMyChatMember(u)
                    if (mcm != null) {
                        AppLog.i(
                            TAG,
                            "pollLoop: my_chat_member chat=${mcm.chatId} newStatus=${mcm.newStatus}",
                        )
                        scope.launch {
                            try {
                                handleMyChatMember(mcm)
                            } catch (
                                e: Throwable,
                            ) {
                                AppLog.e(TAG, "handleMyChatMember threw for chat=${mcm.chatId}", e)
                            }
                        }
                        continue
                    }
                    // Unknown update type — offset already bumped, just drop it.
                }
                // Persist the high-water offset once per cycle (cheap async DataStore write).
                // Only when it actually advanced — idle cycles don't need the disk hit.
                if (offset != offsetBefore) {
                    runCatching { prefs.setLastOffset(offset) }
                }
                // Successful cycle: reset the backoff counter so a transient blip doesn't
                // permanently elevate the retry delay.
                consecutiveErrors = 0
            } catch (e: TelegramApiException) {
                AppLog.e(TAG, "pollLoop: telegram api error ${e.errorCode}: ${e.description}", e)
                if (e.errorCode == 401 || e.errorCode == 404) {
                    // Token revoked / wrong / bot deleted. Spinning every 5s forever burns
                    // battery + Telegram quota for no recovery — only the user can fix this
                    // by setting a new token. Disable the bot, surface a notification, and
                    // stop the service cleanly.
                    AppLog.w(TAG, "pollLoop: bailing out; bot token rejected (${e.errorCode})")
                    runCatching { prefs.update { it.copy(enabled = false) } }
                    postTokenInvalidNotification(e.errorCode, e.description)
                    stopSelf()
                    return
                }
                consecutiveErrors++
                // Honour Telegram's flood-control retry_after (429) so we don't hammer the
                // API during a flood-wait; otherwise fall back to exponential backoff.
                val retryAfterMs = e.retryAfterSec?.let { it * 1000L + 1000L } ?: 0L
                delay(maxOf(computeBackoffMs(consecutiveErrors), retryAfterMs))
            } catch (e: Throwable) {
                AppLog.e(TAG, "pollLoop: unexpected error in cycle=$cycle", e)
                consecutiveErrors++
                delay(computeBackoffMs(consecutiveErrors))
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (t: Throwable) {
                    AppLog.w(TAG, "pollLoop: wakeLock release failed", t)
                }
            }
        }
    }

    /**
     * Phase 24 — recycle ONLY the long-poll coroutine, leaving the FGS alive. Called by
     * [TelegramPollStallChecker] when getUpdates has gone silent. Cancelling and
     * relaunching the poll job forces a fresh OkHttp connection + DNS lookup, which clears
     * the common "stuck socket after a background network transition" stall without the
     * cost of a full service restart (notification flash, OkHttp re-warm).
     */
    private fun restartPollLoop() {
        AppLog.w(TAG, "restartPollLoop: recycling the long-poll coroutine")
        runCatching { pollJob?.cancel() }
        pollJob = scope.launch { pollLoop() }
    }

    /**
     * Phase 24 — flapping-defence escalation. When the poll loop has been recycled past the
     * flap ceiling within the flap window, a local recycle clearly isn't fixing it (network
     * down, token issue, OEM freeze). Hand off to a full service restart via
     * [TelegramBotHealthWorker]'s next pass — it re-arms the FGS from scratch. Silent
     * recovery: no user notification (per spec).
     */
    private fun escalatePollStallToFgsRestart() {
        AppLog.w(TAG, "escalatePollStallToFgsRestart: poll loop flapping — requesting FGS restart")
        runCatching {
            // The health worker is idempotent (ExistingPeriodicWorkPolicy.KEEP) and its
            // next pass re-starts the service if it finds it unhealthy. Re-scheduling here
            // ensures the worker is armed even if it had been cancelled.
            TelegramBotHealthWorker.schedule(applicationContext)
            // Also stop ourselves so the worker's next pass brings up a clean instance —
            // START_NOT_STICKY means we won't auto-revive without a fresh foreground ticket,
            // which the health worker provides via startForegroundService.
            stopSelf()
        }.onFailure { AppLog.w(TAG, "escalatePollStallToFgsRestart failed", it) }
    }

    /** Capped exponential backoff: 5s, 10s, 20s, 40s, 80s, 120s (capped). */
    /**
     * Long-lived listener for generations completing on chat-mapped conversations that
     * AREN'T currently being pumped by [handleIncoming]. Without this, sub-agent wake
     * messages — posted via [SubAgentEngine.postRunResultToParent] →
     * `chatService.sendMessage(parentConvId, …)` — kick off a generation in the parent's
     * Telegram conversation, but its assistant reply never reaches Telegram because
     * handleIncoming isn't running for that turn.
     *
     * The listener stays alive for the lifetime of the foreground service. Errors are
     * logged but do not break the loop — a single bad emit shouldn't kill our pump.
     */
    private suspend fun listenForExternalGenerationCompletions() {
        chatService.generationDoneFlow.collect { convId ->
            // Skip if handleIncoming is driving this turn — it streams + finalises the
            // assistant text to Telegram on its own.
            if (convId in activeHandleIncomingConvs) return@collect
            // Find the chat this conv is bound to. If none, the conv isn't a Telegram
            // conv; nothing to do.
            val mapping =
                runCatching { chatRepo.getByConversationId(convId.toString()) }
                    .getOrNull() ?: return@collect

            // Harvest the most-recent assistant text. Mirrors SubAgentEngine.harvestFinalText
            // but pulls from the live in-memory state when available so we get whatever the
            // last gen actually produced (DB write may lag a few hundred ms behind).
            val text =
                runCatching {
                    val conv = chatService.getConversationFlow(convId).value
                    val selected = conv.messageNodes.mapNotNull { it.messages.getOrNull(it.selectIndex) }
                    val lastAssistant = selected.lastOrNull { it.role.name.equals("assistant", ignoreCase = true) }
                    lastAssistant
                        ?.parts
                        ?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                        ?.joinToString("\n") { it.text }
                        ?.trim()
                        .orEmpty()
                }.getOrDefault("")

            if (text.isBlank()) {
                AppLog.i(TAG, "external-gen-pump: convId=$convId → empty assistant text, skipping")
                return@collect
            }
            runCatching {
                sendChunked(mapping.chatId, text, replyTo = null)
                AppLog.i(
                    TAG,
                    "external-gen-pump: convId=$convId → pushed ${text.length} chars to Telegram chat ${mapping.chatId}",
                )
            }.onFailure {
                AppLog.w(TAG, "external-gen-pump: send failed for convId=$convId", it)
            }
        }
    }

    /**
     * Handle a `my_chat_member` update: the bot's membership in [TelegramMyChatMember.chatId]
     * just changed. We only care about the terminal states — `kicked` (user blocked the bot
     * in a private chat) and `left` (bot was removed from a group). On either, drop the
     * chat -> conversation mapping so subsequent outbound paths can't keep posting into a
     * dead chat, then cancel any parked turn for the chat. We don't try to send a goodbye
     * message — Telegram would 403 the send for `kicked` chats anyway.
     */
    private suspend fun handleMyChatMember(update: TelegramMyChatMember) {
        val terminal = update.newStatus == "kicked" || update.newStatus == "left"
        if (!terminal) return
        // Cancel any parked turn for this chat so the per-chat mutex releases.
        turnJobs.remove(update.chatId)?.let { runCatching { it.cancelAndJoin() } }
        // Drop the chat -> conversation mapping. Conversation rows stay in the DB so the
        // user can still browse history in-app; only the Telegram routing breaks.
        runCatching { chatRepo.deleteByChatId(update.chatId) }
        // Stale approval keyboards in this chat are now orphan; their messageIds belong to
        // a chat we can no longer edit. Drop them from the registry so no future cancellation
        // sweep tries (and fails) to edit them.
        runCatching { TelegramBotRegistries.ApprovalPromptRegistry.clearChat(update.chatId) }
    }

    /**
     * Surface a notification when the bot bails out due to an invalid token. The user has
     * no other way to discover that we stopped: the foreground service is gone, the next
     * inbound message would hit a dead bot, and the auto-revive paths only retry when the
     * bot is `enabled=true` (which we've just flipped to false).
     */
    private fun postTokenInvalidNotification(
        errorCode: Int,
        description: String,
    ) {
        try {
            val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "rikkahub_telegram_token_invalid"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId,
                        getString(R.string.notification_channel_telegram_bot_errors),
                        android.app.NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val builder =
                androidx.core.app.NotificationCompat
                    .Builder(applicationContext, channelId)
                    .setContentTitle(getString(R.string.notification_telegram_disabled_title))
                    .setContentText(
                            getString(R.string.notification_telegram_token_rejected_text, errorCode),
                        )
                    .setStyle(
                        androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                            getString(
                                R.string.notification_telegram_token_rejected_bigtext,
                                errorCode,
                                description,
                            ),
                        ),
                    ).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
            androidx.core.app.NotificationManagerCompat
                .from(applicationContext)
                .notify(101, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silent failure is fine
        } catch (e: Throwable) {
            AppLog.w(TAG, "postTokenInvalidNotification failed", e)
        }
    }

    // free-form replies via the message intercept in handleIncoming, then feed the collected
    // answers back through handleToolApproval(answer=…) — the same hook the in-app card uses.
    // Modelled on the Hermes clarify_gateway pattern; questions are asked one at a time.

    internal data class ClarifyQuestion(
        val id: String,
        val text: String,
        val options: List<String>,
    )

    internal class ClarifyPending(
        val chatId: Long,
        val convId: Uuid,
        val toolCallId: String,
        val questions: List<ClarifyQuestion>,
    ) {
        val answers = LinkedHashMap<String, String>()
        var currentIdx = 0
        var awaitingText = false

        /** message_id of the question currently on screen, so we can clear its keyboard once answered. */
        var questionMessageId: Long? = null
    }

    /** Pending clarify prompts keyed by toolCallId; resolved by a button tap or a text reply. */
    internal val clarifyPending = java.util.concurrent.ConcurrentHashMap<String, ClarifyPending>()


    companion object {
        const val TAG = "TelegramBotService"

        // Telegram's HARD per-message limit is 4096. We chunk at 3500 (was 4000) because
        // chunks are markdown-rendered to HTML downstream and the rendered HTML can be
        // LONGER than the source: `**foo**` (7) → `<b>foo</b>` (10), a single `[text](url)`
        // can swell by 12+ chars per occurrence, and there's no upper bound on
        // markdown→HTML expansion in pathological cases. A 4000-char chunk packed with
        // bold + headers (e.g. a 25-paragraph story with `**Paragraph N:**` lines) easily
        // pushed Telegram over the 4096 cap and got 400 MESSAGE_TOO_LONG, then the
        // try-catches silently swallowed both the HTML and stripped-text retries leaving
        // the user with no message at all. 3500 leaves ~600 bytes of headroom — enough
        // for typical markdown-heavy text without making chunks needlessly small.
        const val MAX_CHARS = 3500

        /** Above this many chunks a reply is uploaded as a .md document instead of dozens of
         *  back-to-back messages (which trip Telegram's per-chat flood limit, 429 retry_after). */
        const val MAX_INLINE_CHUNKS = 6

        /** Pause between chunked messages so a multi-part reply stays under Telegram's per-chat
         *  send rate and doesn't flood into a 429. */
        const val INTER_CHUNK_DELAY_MS: Long = 400L

        /** How long an activeHandleIncomingConvs entry lingers after handleIncoming exits.
         *  The external generation-done pump can land its collect lambda AFTER the finally
         *  has removed the marker, on fast (sub-second) generations. Keeping the marker
         *  for an extra 5 seconds covers any realistic pump scheduling latency without
         *  blocking legitimate sub-agent wake messages from going through after that. */
        const val ACTIVE_INCOMING_LINGER_MS: Long = 5_000L

        /** Long-poll request can take ~50s server-side + a few seconds for the client to
         *  handle inbound updates and dispatch them. 75s is comfortable headroom; the wake
         *  lock is auto-released in finally before each next cycle so a longer hang cannot
         *  leak it. */
        const val WAKELOCK_TIMEOUT_MS: Long = 75_000L

        /**
         * Whether the long-poll cycle needs to hold a partial wake lock. The lock only
         * matters when the CPU would otherwise suspend mid-poll, i.e. when the screen is
         * off. While the device is interactive the CPU is already awake, so holding it
         * then is pure waste - profiling showed the unconditional acquire kept the lock
         * held ~100% of the time, which was the app's entire wake-lock budget. Gating on
         * the non-interactive state preserves Doze survival (screen-off always gets the
         * lock) while dropping the screen-on waste.
         *
         * @param isInteractive `PowerManager.isInteractive`, or `null` when no
         *   PowerManager is available - in which case we default to holding the lock
         *   (the safe choice: never weaker than the old unconditional behaviour).
         */
        fun shouldHoldPollWakeLock(isInteractive: Boolean?): Boolean = isInteractive != true

        /** Initial placeholder text the bot posts before streaming begins. */
        const val STREAM_PLACEHOLDER = "..."

        /** Trailing tick the live edit appends so the user can tell the bot is mid-stream
         *  versus finished. The final edit drops it. */
        const val STREAM_TICK = "▌"

        /** Timer-driven cadence for live edits. 600ms feels close to typing without
         *  tripping Telegram's edit limiter when paired with the gap floor below. */
        const val STREAM_EDIT_INTERVAL_MS: Long = 600L

        /** Hard floor between any two edits to the same placeholder, regardless of why
         *  the edit was triggered (timer or burst). Telegram returns 429 if you go faster. */
        const val STREAM_EDIT_MIN_GAP_MS: Long = 400L

        /** When the rendered text grows by at least this many characters since the last
         *  edit, fire an edit immediately instead of waiting for the next timer tick.
         *  Makes long token bursts feel instant. */
        const val STREAM_EDIT_BURST_THRESHOLD_CHARS: Int = 80

        /** parse_mode value for outbound LLM-generated messages. We render through
         *  TelegramHtmlRenderer first so the body uses Telegram's tiny HTML subset. */
        const val PARSE_MODE_HTML: String = "HTML"

        /** Inline-keyboard prefix for the /doctor "Run fix" buttons. callback_data is
         *  "dfix:<check_id>" where check_id is the DoctorCheck.id (e.g. "db.integrity").
         *  Check ids are short kebab/dot-cased identifiers, comfortably under the 64-byte cap. */
        const val DOCTOR_FIX_CB_PREFIX: String = "dfix:"

        // Other inline-keyboard prefixes, registries, and builders live in
        // TelegramKeyboards.kt (approval cards + the two-step /model picker).
        //
        // No approval timeout / auto-deny — the user explicitly asked for "no timeout
        // because the user is busy and might take long to answer". The streaming jobs
        // are torn down between iterations of handleLlmTurn (see the per-iteration
        // typing/edit launch + cancelAndJoin) so a long wait doesn't burn battery or
        // Telegram quota. /stop is still effective via the built-in fast-path.

        /** Set whenever the service is alive AND its long-poll loop is running. */
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TelegramBotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramBotService::class.java))
        }
    }
}
