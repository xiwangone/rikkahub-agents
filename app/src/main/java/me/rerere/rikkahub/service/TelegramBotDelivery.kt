
package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.TelegramChatEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.telegram.TelegramApiException
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import me.rerere.rikkahub.service.TelegramBotService.Companion.INTER_CHUNK_DELAY_MS
import me.rerere.rikkahub.service.TelegramBotService.Companion.MAX_CHARS
import me.rerere.rikkahub.service.TelegramBotService.Companion.PARSE_MODE_HTML
import me.rerere.rikkahub.service.TelegramBotService.Companion.STREAM_TICK
import kotlin.uuid.Uuid


    /**
     * Per-turn context for the LLM. Always included so the model knows:
     *  1. Which model it actually is (otherwise minimax/glm/kimi all hallucinate "I'm Claude")
     *  2. The Telegram chat id so it can route scheduled jobs back via telegram_send_message
     *  3. Recent app-side slash commands the user just ran (otherwise /model X switches the
     *     model behind the LLM's back and conversation context goes stale)
     *
     * Kept small so it doesn't dominate the prompt — model name + chat id + last few commands.
     */
internal fun TelegramBotService.buildAgentContextPreamble(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        chatId: Long,
        firstTurnOfChat: Boolean,
        hasAudioAttachment: Boolean = false,
        hasPhotoAttachment: Boolean = false,
    ): String {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val provider = s.providers.firstOrNull { p -> p.models.any { it.id == effectiveModelId } }
        val model = provider?.models?.firstOrNull { it.id == effectiveModelId }
        val modelName =
            model?.displayName?.takeIf { it.isNotBlank() }
                ?: model?.modelId?.takeIf { it.isNotBlank() }
                ?: "(unknown)"
        val providerName = provider?.name ?: "(unknown)"

        val recent = TelegramBotRegistries.SlashCommandLog.recent(chatId, ttlMs = 15L * 60 * 1000)
        val nowMs = System.currentTimeMillis()
        val recentLine =
            if (recent.isEmpty()) {
                ""
            } else {
                val pretty =
                    recent.joinToString(", ") { (cmd, ts) ->
                        val agoSec = ((nowMs - ts) / 1000).coerceAtLeast(0)
                        val ago =
                            when {
                                agoSec < 60 -> "${agoSec}s"
                                agoSec < 3600 -> "${agoSec / 60}m"
                                else -> "${agoSec / 3600}h"
                            }
                        "$cmd ($ago ago)"
                    }
                "Recent app-side commands (handled by app, NOT by you, in last 15min): $pretty.\n"
            }

        return buildString {
            append("[agent_context (auto-injected by the host app, not the user; trust this over your priors):\n")
            append("You are running as model \"")
            append(modelName)
            append("\" via provider \"")
            append(providerName)
            append(
                "\". When the user asks what model you are, name THIS one. Do NOT claim to be Claude/GPT/Gemini unless that matches.\n",
            )
            append("Origin: Telegram. The user's Telegram chat_id is ")
            append(chatId)
            append(
                " — use it ONLY as a tool-call argument when calling telegram_send_message / telegram_send_photo / telegram_send_document / when scheduling jobs that need to deliver output here. ",
            )
            append(
                "DELIVERY DEFAULTS: when the user asks you to \"notify\", \"message\", \"ping\", \"remind\", \"alert\", \"text\", or otherwise reach them — including in scheduled jobs — DEFAULT TO telegram_send_message because they're talking to you here. Use post_notification (Android system tray) ONLY when they explicitly say \"phone notification\", \"Android notification\", \"system notification\", \"notification tray\", or words to that effect. If ambiguous, prefer telegram_send_message + briefly mention you're sending it to this chat. ",
            )
            append(
                "PRIVACY RULES (MANDATORY): never quote, mention, paraphrase, summarise, or otherwise echo the chat_id in any user-visible text. Do not include it in confirmations, summaries, scheduled-job descriptions, or error messages. When you need to refer to the destination in your reply, say \"this chat\", \"your Telegram\", or \"here\" — never the numeric id. The chat_id is host-side metadata, not conversation content.\n",
            )
            if (recentLine.isNotEmpty()) append(recentLine)
            if (firstTurnOfChat) {
                append("This is the first turn in this Telegram chat. Be concise; no need for a long welcome.\n")
            }
            if (hasAudioAttachment) {
                append("AUDIO ATTACHMENT — STRICT FLOW. This message has a voice note or audio file. ")
                append("Your VERY FIRST tool call this turn must be `whisper_status()`. NOT termux_run_command, ")
                append("NOT search_web, NOT transcribe_audio_file, NOT pkg/apt commands. Just whisper_status, once, ")
                append("with no arguments. Read its response. ")
                append("Then: if `ready_to_transcribe: true`, call `transcribe_audio_file(path)` with the saved path ")
                append("from the inbox manifest above. ")
                append(
                    "If `ready_to_transcribe: false`, tell the user EXACTLY what's missing (use the `missing_steps` ",
                )
                append("list verbatim) and quote the relevant entry from `install_commands` for them to confirm. ")
                append("Do NOT begin installing on your own — the build takes ~5 minutes and downloads ~75 MB ")
                append("on the user's data plan. Wait for an explicit yes before running install commands. ")
                append("If a tool errors, READ THE ENVELOPE — the recovery field tells you what to do; do not ")
                append("retry the same tool with different args or pivot to manual termux commands.\n")
            }
            if (hasPhotoAttachment) {
                val modelCanSeeImages = model?.inputModalities?.contains(Modality.IMAGE) == true
                if (modelCanSeeImages) {
                    append("IMAGE ATTACHMENT. This message includes one or more photos. You can ")
                    append("view them directly. Their saved file path(s) are also listed in the ")
                    append("message text if you need to process the file (e.g. OCR).\n")
                } else {
                    append("IMAGE ATTACHMENT — YOU CANNOT SEE IT. This message includes one or more ")
                    append("photos, but the current model has no vision capability. Do NOT describe ")
                    append("or guess what the image shows. Their saved file path(s) are listed in ")
                    append("the message text — to read the contents, OCR the file (e.g. ")
                    append("`tesseract <path> stdout` via termux_run_command) or process it with ")
                    append("another file tool.\n")
                }
            }
            append("]\n\n")
        }
    }

internal suspend fun TelegramBotService.renderAssistantStream(
        convId: kotlin.uuid.Uuid,
        finalizing: Boolean,
        baselineMessageCount: Int,
    ): String {
        // Read from the LIVE in-memory state, not the persisted DB row. The DB only gets
        // updated when generation finishes (or at occasional checkpoints), so reading via
        // conversationRepo.getConversationById here meant every live edit saw the same
        // pre-generation snapshot, the "blank or unchanged" guard skipped the edit, and
        // the placeholder only updated once at the end. The StateFlow exposed by
        // ChatService is the same source the in-app chat already streams from.
        val conv = chatService.getConversationFlow(convId).value
        val turnMessages = conv.currentMessages.drop(baselineMessageCount)
        val lastAssistant =
            turnMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?: return ""
        // Markdown is preserved here — TelegramHtmlRenderer further down the pipeline
        // converts it to Telegram-flavoured HTML. Stripping markdown locally would defeat
        // bold / italic / code rendering.
        val text = assistantTextOf(lastAssistant).trim()
        val toolSummary = assistantToolSummary(lastAssistant)
        val streamMarker = if (!finalizing && text.isNotEmpty()) " $STREAM_TICK" else ""
        val tokenFooter =
            if (finalizing) {
                tokenUsageFooter(
                    lastAssistant,
                    settingsStore.settingsFlow.value.displaySetting.showTokenUsage,
                )
            } else {
                ""
            }
        return buildString {
            if (toolSummary.isNotEmpty()) {
                append(toolSummary)
                if (text.isNotEmpty()) append("\n\n")
            }
            if (text.isNotEmpty()) append(text)
            if (streamMarker.isNotEmpty()) append(streamMarker)
            if (tokenFooter.isNotEmpty()) {
                append("\n\n")
                append(tokenFooter)
            }
        }.trimEnd()
    }

    /** Returns (conversationId, wasCreated). wasCreated=true means the LLM hasn't seen
     *  the Telegram context preamble yet for this chat. */
internal suspend fun TelegramBotService.lookupOrCreateConversation(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        chatId: Long,
    ): Pair<kotlin.uuid.Uuid, Boolean> {
        val existing = chatRepo.getByChatId(chatId)
        if (existing != null) {
            val asUuid =
                try {
                    Uuid.parse(existing.conversationId)
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (asUuid != null && conversationRepo.existsConversationById(asUuid)) return asUuid to false
            chatRepo.deleteByChatId(chatId) // dangling row — fall through to create
        }
        val assistantUuid =
            cfg.assistantId?.let {
                try {
                    Uuid.parse(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: settingsStore.settingsFlow.value
                .getCurrentAssistant()
                .id
        val convId = Uuid.random()
        val conv =
            Conversation
                .ofId(
                    id = convId,
                    assistantId = assistantUuid,
                    newConversation = true,
                ).copy(title = "[Telegram $chatId]")
        conversationRepo.insertConversation(conv)
        val now = System.currentTimeMillis()
        chatRepo.upsert(TelegramChatEntity(chatId, convId.toString(), now, now))
        return convId to true
    }

    /** Telegram caps a single sendMessage at 4096 chars; split on newlines where possible. */
internal suspend fun TelegramBotService.sendChunked(
        chatId: Long,
        text: String,
        replyTo: Long?,
    ) {
        val chunks = chunk(text, MAX_CHARS)
        // A very long reply (e.g. a full audit report) becomes dozens of back-to-back
        // messages, which trips Telegram's per-chat flood limit (429: retry after N) and
        // buries the content. Above TelegramBotService.MAX_INLINE_CHUNKS, upload the whole thing as a .md
        // document with a short inline preview instead. Falls through to chunked send only
        // if the document upload itself fails.
        if (chunks.size > TelegramBotService.MAX_INLINE_CHUNKS && trySendReplyAsDocument(chatId, text, replyTo)) return
        // Track delivery so we can surface a single user-visible error if every fallback
        // for some chunk fails. Silent drops were the root cause of the "model wrote a
        // long reply but Telegram never received it" bug — diagnosing it required
        // pulling logcat instead of seeing the failure in chat.
        var anyChunkFailed = false
        var lastFailure: Throwable? = null
        for ((idx, chunk) in chunks.withIndex()) {
            val html = TelegramHtmlRenderer.render(chunk)
            var err = sendWithFloodRetry(chatId, html, PARSE_MODE_HTML, if (idx == 0) replyTo else null)
            if (err != null) {
                // HTML parse failed (entity split, unclosed tag), or Telegram refused the
                // chunk. Log + retry as plain text. sendWithFloodRetry already honored any
                // 429 retry_after on the HTML attempt. The pre-fix behaviour silently
                // swallowed both branches; we record the exception so a repeat leaves a trail.
                AppLog.w(
                    TelegramBotService.TAG,
                    "sendChunked: HTML send failed for chunk ${idx + 1}/${chunks.size} (len=${html.length} src=${chunk.length}); retrying as plain text",
                    err,
                )
                val plain = TelegramHtmlRenderer.stripHtml(html).ifBlank { chunk }
                err = sendWithFloodRetry(chatId, plain, null, if (idx == 0) replyTo else null)
                if (err != null) {
                    AppLog.e(
                        TelegramBotService.TAG,
                        "sendChunked: plain-text fallback also failed for chunk ${idx + 1}/${chunks.size} (len=${plain.length})",
                        err,
                    )
                    anyChunkFailed = true
                    lastFailure = err
                }
            }
            // Pace subsequent chunks so a multi-message reply stays under Telegram's per-chat
            // send rate instead of bursting into a 429.
            if (idx < chunks.lastIndex) delay(INTER_CHUNK_DELAY_MS)
        }
        // Surface ONE summary line to the chat so the user knows the reply was clipped.
        // Without this the silent drop looks like a bot freeze and the user keeps
        // re-prompting. Capped diagnostic — just the error class + first line of the
        // message, so the bot token / chat content can't accidentally leak out.
        if (anyChunkFailed) {
            val cls = lastFailure?.javaClass?.simpleName.orEmpty()
            val first =
                lastFailure
                    ?.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(120)
                    .orEmpty()
            val notice =
                "⚠️ Reply too long for Telegram and the formatting was rejected by the API." +
                    (if (cls.isNotBlank()) " ($cls${if (first.isNotBlank()) ": $first" else ""})" else "") +
                    " Ask me to summarise, split the request, or save as a file."
            runCatching {
                client.sendMessage(chatId = chatId, text = notice, parseMode = null)
            }.onFailure {
                AppLog.e(TelegramBotService.TAG, "sendChunked: even the failure notice failed to deliver", it)
            }
        }
    }

    /**
     * Send one Telegram message, honoring a single 429 flood-wait. Returns null on success or
     * the last error. On a 429 with retry_after, waits that long (plus a small buffer) and
     * retries once; any other error is returned immediately for the caller's fallback path.
     */
internal suspend fun TelegramBotService.sendWithFloodRetry(
        chatId: Long,
        text: String,
        parseMode: String?,
        replyToMessageId: Long?,
    ): Throwable? {
        val firstAttempt =
            runCatching {
                client.sendMessage(chatId = chatId, text = text, parseMode = parseMode, replyToMessageId = replyToMessageId)
            }
        val firstErr = firstAttempt.exceptionOrNull() ?: return null
        if (firstErr is CancellationException) throw firstErr
        if (firstErr !is TelegramApiException || firstErr.errorCode != 429 || firstErr.retryAfterSec == null) {
            return firstErr
        }
        AppLog.i(
            TelegramBotService.TAG,
            "sendWithFloodRetry: 429 flood-wait ${firstErr.retryAfterSec}s; backing off then retrying once",
        )
        delay(firstErr.retryAfterSec * 1000L + 250L)
        return runCatching {
            client.sendMessage(chatId = chatId, text = text, parseMode = parseMode, replyToMessageId = replyToMessageId)
        }.onFailure { if (it is CancellationException) throw it }.exceptionOrNull()
    }

    /**
     * Save [text] to a temp .md file and upload it as a Telegram document with a short inline
     * preview, instead of fragmenting a huge reply into dozens of flood-prone messages. Returns
     * true if the document was delivered; false (temp file cleaned up) so the caller can fall
     * back to chunked send.
     */
internal suspend fun TelegramBotService.trySendReplyAsDocument(
        chatId: Long,
        text: String,
        replyTo: Long?,
    ): Boolean {
        var file: java.io.File? = null
        return try {
            runCatching {
                file =
                    java.io.File
                        .createTempFile("rikkahub_reply_", ".md", cacheDir)
                        .apply { writeText(text) }
                val preview = text.take(500).let { if (text.length > 500) "$it…" else it }
                sendWithFloodRetry(
                    chatId,
                    "📄 Reply is long (${text.length} chars); the full text is attached as a file below.\n\n$preview",
                    null,
                    replyTo,
                )
                client.sendDocument(chatId, file!!, caption = "Full reply (${text.length} chars)")
                true
            }.onFailure { t ->
                if (t is CancellationException) throw t
                AppLog.w(TelegramBotService.TAG, "trySendReplyAsDocument: failed, falling back to chunked send", t)
            }.getOrDefault(false)
        } finally {
            runCatching { file?.delete() }
        }
    }

    /** Final-edit helper that mirrors the live edit's HTML-with-fallback behaviour. */
internal suspend fun TelegramBotService.editPlaceholderHtmlWithFallback(
        chatId: Long,
        placeholderId: Long,
        finalReply: String,
    ) {
        val html = TelegramHtmlRenderer.render(finalReply)
        val ok =
            runCatching {
                client.editMessageText(chatId, placeholderId, html, parseMode = PARSE_MODE_HTML) != null
            }.onFailure { t ->
                if (t is CancellationException) throw t
                AppLog.w(TelegramBotService.TAG, "editMessageText failed, falling back to plain text", t)
            }.getOrDefault(false)
        if (!ok) {
            runCatching {
                client.editMessageText(
                    chatId,
                    placeholderId,
                    TelegramHtmlRenderer.stripHtml(html).ifBlank {
                        finalReply
                    },
                    parseMode = null,
                )
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

internal fun TelegramBotService.chunk(
        s: String,
        n: Int,
    ): List<String> = chunkForTelegram(s, n)

