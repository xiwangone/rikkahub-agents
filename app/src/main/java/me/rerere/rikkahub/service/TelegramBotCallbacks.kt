
package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.log.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.telegram.TelegramCallbackQuery
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import me.rerere.rikkahub.service.TelegramBotService.Companion.DOCTOR_FIX_CB_PREFIX
import me.rerere.rikkahub.service.TelegramBotService.Companion.PARSE_MODE_HTML
import kotlin.uuid.Uuid


internal fun TelegramBotService.parseClarifyQuestions(input: String): List<TelegramBotService.ClarifyQuestion> {
        val obj =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(input.ifBlank { "{}" })
                    .jsonObject
            }.getOrNull() ?: return emptyList()
        val arr = obj["questions"]?.jsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val q = (el as? kotlinx.serialization.json.JsonObject) ?: return@mapIndexedNotNull null
            val text =
                q["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
            val id = q["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "q$i"
            val options =
                q["options"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { o -> o.isNotBlank() } }
                    ?: emptyList()
            TelegramBotService.ClarifyQuestion(id, text, options)
        }
    }

    /** Begin an interactive ask_user prompt over Telegram. If the questions can't be parsed,
     *  deny the call with an "ask in plain text" reason so the turn never hangs. */
internal suspend fun TelegramBotService.startClarify(
        chatId: Long,
        convId: Uuid,
        tool: UIMessagePart.Tool,
    ) {
        val questions = parseClarifyQuestions(tool.input)
        if (questions.isEmpty()) {
            chatService.handleToolApproval(
                conversationId = convId,
                toolCallId = tool.toolCallId,
                approved = false,
                reason = "ask_user could not be rendered over Telegram. Ask your question in plain reply text instead.",
                toolName = "ask_user",
            )
            return
        }
        val entry = TelegramBotService.ClarifyPending(chatId, convId, tool.toolCallId, questions)
        clarifyPending[tool.toolCallId] = entry
        sendClarifyQuestion(entry)
    }

internal suspend fun TelegramBotService.sendClarifyQuestion(entry: TelegramBotService.ClarifyPending) {
        val q =
            entry.questions.getOrNull(entry.currentIdx) ?: run {
                finalizeClarify(entry)
                return
            }
        val header =
            if (entry.questions.size > 1) {
                "❓ <b>Question ${entry.currentIdx + 1}/${entry.questions.size}</b>\n\n"
            } else {
                "❓ "
            }
        val body = header + TelegramHtmlRenderer.escape(q.text)
        val res =
            if (q.options.isEmpty()) {
                entry.awaitingText = true
                runCatching {
                    client.sendMessage(
                        entry.chatId,
                        "$body\n\n<i>Reply with your answer.</i>",
                        parseMode = PARSE_MODE_HTML,
                    )
                }.getOrNull()
            } else {
                entry.awaitingText = false
                runCatching {
                    client.sendMessage(
                        entry.chatId,
                        body,
                        parseMode = PARSE_MODE_HTML,
                        replyMarkup = buildClarifyKeyboard(entry.toolCallId, entry.currentIdx, q.options),
                    )
                }.getOrNull()
            }
        entry.questionMessageId = res?.get("message_id")?.jsonPrimitive?.longOrNull
    }

internal suspend fun TelegramBotService.handleClarifyCallback(cq: TelegramCallbackQuery) {
        val parts = cq.data.removePrefix(CLARIFY_CB_PREFIX).split(":", limit = 3)
        if (parts.size != 3) {
            client.answerCallbackQuery(cq.callbackQueryId, "malformed")
            return
        }
        val qIdx = parts[0].toIntOrNull()
        val sel = parts[1]
        val toolCallId = parts[2]
        val entry = clarifyPending[toolCallId]
        if (entry == null) {
            client.answerCallbackQuery(cq.callbackQueryId, "question expired")
            return
        }
        if (qIdx != entry.currentIdx) {
            client.answerCallbackQuery(cq.callbackQueryId, "already answered")
            return
        }
        val q =
            entry.questions.getOrNull(entry.currentIdx)
                ?: run {
                    client.answerCallbackQuery(cq.callbackQueryId, "done")
                    return
                }
        if (sel == "o") {
            entry.awaitingText = true
            client.answerCallbackQuery(cq.callbackQueryId, "Type your answer")
            runCatching {
                client.sendMessage(
                    entry.chatId,
                    "✍️ Type your answer for: <i>${TelegramHtmlRenderer.escape(q.text)}</i>",
                    parseMode = PARSE_MODE_HTML,
                )
            }
            return
        }
        val chosen = sel.toIntOrNull()?.let { q.options.getOrNull(it) }
        if (chosen == null) {
            client.answerCallbackQuery(cq.callbackQueryId, "unknown option")
            return
        }
        client.answerCallbackQuery(cq.callbackQueryId, "✓ ${chosen.take(28)}")
        recordAndAdvance(entry, q, chosen)
    }

    /** Consume a free-form message as the answer to a clarify question awaiting text. Returns
     *  true when consumed — the caller must NOT then treat the message as a new prompt. */
internal suspend fun TelegramBotService.tryResolveClarifyText(
        chatId: Long,
        text: String,
    ): Boolean {
        if (text.isBlank()) return false
        val entry = clarifyPending.values.firstOrNull { it.chatId == chatId && it.awaitingText } ?: return false
        val q = entry.questions.getOrNull(entry.currentIdx) ?: return false
        entry.awaitingText = false
        recordAndAdvance(entry, q, text.trim())
        return true
    }

internal suspend fun TelegramBotService.recordAndAdvance(
        entry: TelegramBotService.ClarifyPending,
        q: TelegramBotService.ClarifyQuestion,
        answer: String,
    ) {
        entry.answers[q.id] = answer
        // Clear the just-answered question's inline keyboard and show what was picked, so the
        // buttons don't linger looking still-tappable. Editing the text without a replyMarkup
        // removes the keyboard.
        entry.questionMessageId?.let { msgId ->
            runCatching {
                client.editMessageText(
                    entry.chatId,
                    msgId,
                    "❓ ${TelegramHtmlRenderer.escape(q.text)}\n→ <b>${TelegramHtmlRenderer.escape(answer)}</b>",
                    parseMode = PARSE_MODE_HTML,
                )
            }
        }
        entry.currentIdx++
        if (entry.currentIdx < entry.questions.size) sendClarifyQuestion(entry) else finalizeClarify(entry)
    }

internal fun TelegramBotService.finalizeClarify(entry: TelegramBotService.ClarifyPending) {
        clarifyPending.remove(entry.toolCallId)
        val answerJson =
            buildJsonObject {
                put(
                    "answers",
                    buildJsonObject {
                        entry.questions.forEach { q ->
                            put(q.id, kotlinx.serialization.json.JsonPrimitive(entry.answers[q.id] ?: ""))
                        }
                    },
                )
            }.toString()
        chatService.handleToolApproval(
            conversationId = entry.convId,
            toolCallId = entry.toolCallId,
            approved = true,
            answer = answerJson,
            toolName = "ask_user",
        )
    }

    /** Drop any pending clarify prompts for a chat — the user moved on or cancelled the turn. */
internal fun TelegramBotService.clearClarifyForChat(chatId: Long) {
        clarifyPending.values.removeAll { it.chatId == chatId }
    }

    @Suppress("CyclomaticComplexMethod") // 审批提示的文案分支多但线性
internal suspend fun TelegramBotService.sendApprovalPrompt(
        chatId: Long,
        tool: UIMessagePart.Tool,
    ) {
        // MCP control tools render their args via a redacting helper so Authorization /
        // X-Api-Key / Cookie values never reach the chat. Workflow_* tools render their
        // approval message in human-readable form via WorkflowApprovalRenderer (with key-name
        // redaction for token / password / private_key etc). Any tool the renderers don't
        // recognise falls back to the generic args display.
        val mcpRendered: String? =
            runCatching {
                val parsed =
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        tool.input.ifBlank { "{}" },
                    ) as? kotlinx.serialization.json.JsonObject
                parsed?.let {
                    me.rerere.rikkahub.data.ai.mcp.control.McpApprovalRenderer
                        .render(tool.toolName, it)
                }
            }.getOrNull()
        // Workflow_* mutators render their args in plain multi-line form ("Create workflow X / When: … / Do: …").
        // The plain variant is then escaped + wrapped in <pre> by the standard path, so the
        // existing approval-card chrome stays consistent across tools.
        val workflowRendered: String? =
            runCatching {
                if (me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer
                        .isWorkflowTool(tool.toolName) &&
                    tool.toolName !in setOf("workflow_list", "workflow_get")
                ) {
                    me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer.renderPlain(
                        tool.toolName,
                        tool.input.ifBlank { "{}" },
                    )
                } else {
                    null
                }
            }.getOrNull()
        val argsPreview =
            workflowRendered
                ?: mcpRendered
                ?: formatArgsForDisplay(tool.input).ifEmpty { "(no args)" }
        val text =
            buildString {
                append("⚠️ <b>Permission required</b>\n\n")
                append("Tool: <code>")
                append(TelegramHtmlRenderer.escape(tool.toolName))
                append("</code>\n")
                append("in: <pre>")
                append(TelegramHtmlRenderer.escape(argsPreview))
                append("</pre>")
                // schedule_job family is special: approving SCHEDULES a future autonomous run, not
                // just one tool. Surface that here so the user knows what they're authorising
                // — every tool the cron prompt invokes will run without further approval.
                // (HARDLINE blocks still apply at fire time, regardless of approval scope.)
                if (tool.toolName in setOf("schedule_job", "schedule_job_direct", "schedule_job_llm")) {
                    append("\n\n<i>⏰ Scheduled jobs run autonomously without per-tool approval. ")
                    append("Approving this lets the job run with full tool access whenever it ")
                    append("fires. Hardline-blocked commands (rm -rf /, mkfs, shutdown, …) still ")
                    append("cannot run.</i>")
                    // Surface mode-specific detail (mirrors the in-app approval card).
                    // New split tools fix the mode in the name; legacy schedule_job carries it in input.
                    val jobInput =
                        runCatching {
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(tool.input.ifBlank { "{}" })
                                .jsonObject
                        }.getOrNull()
                    val mode = jobInput?.get("mode")?.jsonPrimitive?.contentOrNull
                        ?: when (tool.toolName) {
                            "schedule_job_direct" -> "direct"
                            "schedule_job_llm" -> "llm"
                            else -> null
                        }
                    when (mode) {
                        "direct" -> {
                            val actions =
                                runCatching {
                                    (jobInput?.get("actions") as? kotlinx.serialization.json.JsonArray)
                                }.getOrNull()
                            if (actions != null && actions.isNotEmpty()) {
                                append("\n\n<b>Actions:</b>")
                                actions.forEachIndexed { i, el ->
                                    val obj = el as? JsonObject
                                    val toolName = obj?.get("tool")?.jsonPrimitive?.contentOrNull ?: "?"
                                    val args = obj?.get("args")?.toString().orEmpty()
                                    val truncatedArgs = if (args.length > 120) args.take(120) + "…" else args
                                    append("\n  ${i + 1}. <code>")
                                    append(TelegramHtmlRenderer.escape(toolName))
                                    append("</code> ")
                                    append(TelegramHtmlRenderer.escape(truncatedArgs))
                                }
                            }
                        }

                        "llm" -> {
                            val prompt = jobInput?.get("prompt")?.jsonPrimitive?.contentOrNull ?: ""
                            if (prompt.isNotEmpty()) {
                                val truncatedPrompt = if (prompt.length > 200) prompt.take(200) + "…" else prompt
                                append("\n\n<b>Prompt:</b> ")
                                append(TelegramHtmlRenderer.escape(truncatedPrompt))
                            }
                        }
                    }
                }
            }
        val res =
            runCatching {
                client.sendMessage(
                    chatId = chatId,
                    text = text,
                    parseMode = PARSE_MODE_HTML,
                    replyMarkup = buildApprovalKeyboard(tool.toolCallId, tool.toolName),
                )
            }.onFailure { e ->
                AppLog.w(TelegramBotService.TAG, "approval prompt send failed", e)
            }.getOrNull()
        val msgId = res?.get("message_id")?.jsonPrimitive?.longOrNull
        if (msgId != null) {
            TelegramBotRegistries.ApprovalPromptRegistry.register(tool.toolCallId, chatId, msgId)
        }
    }

    /**
     * Handle a /model provider-picker tap (step 1 of the two-step flow). Resolves the
     * token → provider id, then re-renders the message in place as that provider's
     * model list with a "← Back to providers" footer button. PROVIDER_CB_BACK fires
     * a re-render in the other direction.
     *
     * This callback is the second-half of the issue-#1 fix — without it the user can't
     * see any models after picking a provider.
     */
internal suspend fun TelegramBotService.handleProviderPickCallback(cq: TelegramCallbackQuery) {
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val effectiveModelId = assistant.chatModelId ?: s.chatModelId
        val enabledProviders =
            s.providers
                .filter { it.enabled }
                .filter { p -> p.models.any { it.type == me.rerere.ai.provider.ModelType.CHAT } }
        val currentPair =
            enabledProviders
                .flatMap { p -> p.models.map { p to it } }
                .firstOrNull { (_, m) -> m.id == effectiveModelId }
        val currentHeader =
            if (currentPair != null) {
                val name = currentPair.second.displayName.ifBlank { currentPair.second.modelId }
                "🧠 Current model: <b>${TelegramHtmlRenderer.escape(name)}</b> (${TelegramHtmlRenderer.escape(currentPair.first.name)})\n\n"
            } else {
                "🧠 Current model: <i>not set</i>\n\n"
            }

        if (cq.data == PROVIDER_CB_BACK) {
            // Re-render step 1. Drop stale model tokens; keep provider tokens valid so
            // the new keyboard's buttons still resolve.
            ModelPickRegistry.clear()
            client.answerCallbackQuery(cq.callbackQueryId, "")
            runCatching {
                client.editMessageText(
                    cq.chatId,
                    cq.messageId,
                    currentHeader + "Tap a provider to see its models:",
                    parseMode = PARSE_MODE_HTML,
                    replyMarkup = buildProviderKeyboard(enabledProviders, currentPair?.first?.id),
                )
            }.onFailure { e ->
                AppLog.w(TelegramBotService.TAG, "handleProviderPickCallback: back-to-providers edit failed", e)
            }
            return
        }

        // Parse `mdp:<token>[:<page>]`. Page absent → 0 (initial provider tap).
        // Page present → user tapped Prev/Next from the model list.
        val rest = cq.data.removePrefix(PROVIDER_CB_PREFIX)
        val parts = rest.split(":", limit = 2)
        val token = parts[0]
        val requestedPage = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val providerId =
            ProviderPickRegistry.resolve(token) ?: run {
                client.answerCallbackQuery(cq.callbackQueryId, "model picker has expired — send /model again")
                return
            }
        val provider =
            enabledProviders.firstOrNull { it.id.toString() == providerId } ?: run {
                client.answerCallbackQuery(cq.callbackQueryId, "provider no longer available")
                return
            }
        val providerModels =
            provider.models
                .filter { it.type == me.rerere.ai.provider.ModelType.CHAT }
                .map { provider to it }
        if (providerModels.isEmpty()) {
            client.answerCallbackQuery(cq.callbackQueryId, "no chat models in ${provider.name}")
            return
        }
        // Clamp page to valid range — guards against stale Prev/Next callbacks if the
        // model list shrunk between renders (provider rotated keys, etc.).
        val totalPages = maxOf(1, (providerModels.size + MODEL_PICKER_PAGE_SIZE - 1) / MODEL_PICKER_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        // Reset model tokens — every page render mints fresh ones; old tokens from the
        // previous page (or the previous provider) won't resolve.
        ModelPickRegistry.clear()
        client.answerCallbackQuery(cq.callbackQueryId, "")
        val newText =
            buildModelPickerText(
                currentHeader = currentHeader,
                providerName = provider.name,
                modelCount = providerModels.size,
                page = page,
            )
        val keyboard =
            buildModelKeyboard(
                allModels = providerModels,
                page = page,
                providerToken = token,
                currentModelId = effectiveModelId,
                showBackButton = enabledProviders.size >= 2,
            )
        runCatching {
            client.editMessageText(
                cq.chatId,
                cq.messageId,
                newText,
                parseMode = PARSE_MODE_HTML,
                replyMarkup = keyboard,
            )
        }.onFailure { e ->
            AppLog.w(TelegramBotService.TAG, "handleProviderPickCallback: model-list edit failed", e)
        }
    }

    /**
     * Handle a /model picker tap. Looks the short token up in [ModelPickRegistry],
     * switches the current assistant's chatModelId to the resolved model, and rewrites
     * the picker message in place to show the new selection.
     */
internal suspend fun TelegramBotService.handleModelPickCallback(cq: TelegramCallbackQuery) {
        val token = cq.data.removePrefix(MODEL_CB_PREFIX)
        val modelId =
            ModelPickRegistry.resolve(token) ?: run {
                client.answerCallbackQuery(cq.callbackQueryId, "model picker has expired — send /model again")
                return
            }
        val s = settingsStore.settingsFlow.value
        val assistant = s.getCurrentAssistant()
        val match =
            s.providers
                .flatMap { p -> p.models.map { p to it } }
                .firstOrNull { (_, m) -> m.id.toString() == modelId }
        if (match == null) {
            client.answerCallbackQuery(cq.callbackQueryId, "model no longer available")
            return
        }
        val (provider, model) = match
        settingsStore.update { settings ->
            settings.copy(
                assistants =
                    settings.assistants.map {
                        if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                    },
            )
        }
        val name = model.displayName.ifBlank { model.modelId }
        client.answerCallbackQuery(cq.callbackQueryId, "✅ $name")
        runCatching {
            val newText =
                buildString {
                    append("🔄 Switched to <b>")
                    append(TelegramHtmlRenderer.escape(name))
                    append("</b> (")
                    append(TelegramHtmlRenderer.escape(provider.name))
                    append(")")
                }
            client.editMessageText(cq.chatId, cq.messageId, newText, parseMode = PARSE_MODE_HTML)
        }.onFailure { e ->
            AppLog.w(TelegramBotService.TAG, "handleModelPickCallback: switched-confirmation edit failed", e)
        }
    }

    /**
     * Handle a callback_query (inline-keyboard button tap). Whitelisted users only —
     * unauthorised taps are still acked (so Telegram clears the button spinner and stops
     * retrying the delivery) but no work is done. callback_data format:
     * "apv:<scope>:<toolCallId>" / "mdl:<token>" / "mdp:<token>" / "dfix:<id>".
     */
internal suspend fun TelegramBotService.handleCallbackQuery(
        cfg: me.rerere.rikkahub.data.telegram.TelegramBotConfig,
        cq: TelegramCallbackQuery,
    ) {
        val cbStartMs = System.currentTimeMillis()
        AppLog.i(TelegramBotService.TAG, "cb:${cq.callbackQueryId} START data=${cq.data} chat=${cq.chatId}")
        val sender = cq.senderId
        if (sender == null || (sender !in cfg.whitelist && cq.chatId !in cfg.whitelist)) {
            AppLog.w(TelegramBotService.TAG, "handleCallbackQuery: dropping non-whitelisted sender=$sender chat=${cq.chatId}")
            // ALWAYS ack, even when dropping. Spec requires answerCallbackQuery within
            // ~15 s or Telegram resends the tap multiple times — without this ack the
            // bot wastes a getUpdates round-trip on every retry. There is no probe-defence
            // value in withholding the ack: Telegram only routes callback_query updates
            // to the bot that produced the keyboard, so non-whitelisted taps cannot be
            // adversarial scans from outside.
            runCatching { client.answerCallbackQuery(cq.callbackQueryId) }
            return
        }
        // Dispatch by callback_data prefix. New keyboard surfaces (model picker, etc.)
        // get their own prefix so the parsing branches stay small.
        when {
            cq.data.startsWith(MODEL_CB_PREFIX) -> {
                handleModelPickCallback(cq)
                return
            }

            cq.data.startsWith(PROVIDER_CB_PREFIX) -> {
                handleProviderPickCallback(cq)
                return
            }

            cq.data.startsWith(DOCTOR_FIX_CB_PREFIX) -> {
                handleDoctorFixCallback(cq)
                return
            }

            cq.data.startsWith(CLARIFY_CB_PREFIX) -> {
                handleClarifyCallback(cq)
                return
            }

            cq.data.startsWith(APPROVAL_CB_PREFIX) -> {
                handleToolApprovalCallback(cq, cbStartMs)
            }

            else -> {
                client.answerCallbackQuery(cq.callbackQueryId, "unknown action")
            }
        }
    }

private fun resolveApprovalDecision(scopeChar: String): Triple<Boolean, ChatService.ApprovalScope, String>? =
    when (scopeChar) {
        APPROVAL_CB_ONCE -> Triple(true, ChatService.ApprovalScope.Once, "✅ Approved (once)")
        APPROVAL_CB_CHAT -> Triple(true, ChatService.ApprovalScope.ChatScope, "💬 Approved (this chat)")
        APPROVAL_CB_ALWAYS -> Triple(true, ChatService.ApprovalScope.Always, "∞ Approved (always)")
        APPROVAL_CB_DENY -> Triple(false, ChatService.ApprovalScope.Once, "❌ Denied")
        else -> null
    }

private suspend fun TelegramBotService.handleToolApprovalCallback(
    cq: TelegramCallbackQuery,
    cbStartMs: Long,
) {
    val parts = cq.data.split(":", limit = 3)
    if (parts.size != 3) {
        client.answerCallbackQuery(cq.callbackQueryId, "malformed approval callback")
        return
    }
    val scopeChar = parts[1]
    val toolCallId = parts[2]

    val mapping =
        chatRepo.getByChatId(cq.chatId) ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "no active conversation")
            return
        }
    val convId =
        runCatching { Uuid.parse(mapping.conversationId) }.getOrNull() ?: run {
            client.answerCallbackQuery(cq.callbackQueryId, "could not resolve conversation")
            return
        }

    chatService.ensureHydrated(convId)

    val approvalMutex = approvalMutexFor(toolCallId)
    approvalMutex.withLock {
        val tool =
            chatService
                .getConversationFlow(convId)
                .value
                .currentMessages
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
                .firstOrNull { it.toolCallId == toolCallId }
        if (tool == null || !tool.isPending) {
            val msg = if (tool == null) "tool no longer active" else "already resolved"
            client.answerCallbackQuery(cq.callbackQueryId, msg)
            return@withLock
        }
        val decision =
            resolveApprovalDecision(scopeChar) ?: run {
                client.answerCallbackQuery(cq.callbackQueryId, "unknown scope")
                return@withLock
            }
        val (approved, approvalScope, label) = decision
        val toolName = tool.toolName

        val ackStart = System.currentTimeMillis()
        client.answerCallbackQuery(cq.callbackQueryId, label)
        AppLog.i(
            TelegramBotService.TAG,
            "cb:${cq.callbackQueryId} ACKED in ${System.currentTimeMillis() - ackStart} ms (total ${System.currentTimeMillis() - cbStartMs} ms since START)",
        )

        chatService.handleToolApproval(
            conversationId = convId,
            toolCallId = toolCallId,
            approved = approved,
            reason = if (!approved) "Denied by user via Telegram" else "",
            scope = approvalScope,
            toolName = toolName,
        )
        val newText =
            buildString {
                append("<b>")
                append(TelegramHtmlRenderer.escape(label))
                append("</b>\n")
                append("Tool: <code>")
                append(TelegramHtmlRenderer.escape(toolName))
                append("</code>")
            }
        scope.launch {
            runCatching {
                val editStart = System.currentTimeMillis()
                client.editMessageText(cq.chatId, cq.messageId, newText, parseMode = PARSE_MODE_HTML)
                AppLog.i(
                    TelegramBotService.TAG,
                    "cb:${cq.callbackQueryId} EDITED in ${System.currentTimeMillis() - editStart} ms (total ${System.currentTimeMillis() - cbStartMs} ms since START)",
                )
            }.onFailure { e ->
                AppLog.w(
                    TelegramBotService.TAG,
                    "cb:${cq.callbackQueryId} EDIT FAILED: ${e.message ?: e::class.simpleName}",
                    e,
                )
            }
        }
        TelegramBotRegistries.ApprovalPromptRegistry.clear(toolCallId)
        approvalMutexes.remove(toolCallId)
    }
}
