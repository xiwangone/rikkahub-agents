package me.rerere.rikkahub.costguards

import me.rerere.rikkahub.data.ai.tools.ToolErrors
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.resolveAssistantFor
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * Phase 15 — Cost & loop guards, v1 surface.
 *
 * One LLM tool: [checkTokenUsageTool]. Returns the running token totals for a given
 * conversation (defaults to the assistant's current chat) plus the assistant's soft /
 * hard token caps and a simple budget classification (UNDER_SOFT / WARN / OVER_HARD /
 * NO_BUDGET). The model is expected to self-throttle on WARN and stop on OVER_HARD.
 *
 * Budget classification uses per_message_max — the peak prompt tokens actually sent in one
 * request. It is NOT the model's context-window capacity (this tracker has no window size),
 * and it is not total_tokens (sum which double-counts repeated context across turns).
 *
 * v2 (Phase 15.5) will add the live header pill + GenerationLoop-side auto-stop
 * integration. Ship the data surface first so the LLM can react in the meantime.
 *
 * Stuck-detection on screen-automation flows (the second half of Phase 15 per spec) is
 * its own Phase 15.7 — touches the screen-automation pipeline deeply and shipping a
 * partial version risks breaking the existing tap/swipe/scroll loop. Documented in
 * status.md.
 */

private fun errEnv(
    error: String,
    detail: String,
): List<UIMessagePart> = ToolErrors.partsFor(error, detail)

fun checkTokenUsageTool(
    settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool =
    Tool(
        name = "check_token_usage",
        description =
            """
            Read the running input + output token totals (including cached tokens) for a conversation and compare them
            against the assistant's soft / hard token-budget caps. Budget classification uses per_message_max
            (peak prompt tokens actually sent in one request — NOT the model's context-window capacity; context_window carries the configured capacity when set), not total_tokens (sum which double-counts repeated
            context across turns). Use to self-throttle on a long-running task: WARN means slow down or wrap up;
            OVER_HARD means stop and ask the user before continuing. Returns NO_BUDGET when no caps are configured
            (the defaults). If conversation_id is omitted, reports against the assistant's current chat. Read-only.
            """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put(
                            "conversation_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Conversation UUID; omit to use the assistant's current chat.")
                            },
                        )
                    },
                required = emptyList(),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val rawConvId = params["conversation_id"]?.jsonPrimitive?.contentOrNull
            val settings = settingsStore.settingsFlow.first()
            val explicitConvId = rawConvId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val targetConvId = explicitConvId ?: invocationContext.callerConversationUuid
            val fallbackAssistant = settings.resolveAssistantFor(invocationContext.callerAssistantUuid)
            val conv =
                if (targetConvId != null) {
                    conversationRepo.getConversationById(targetConvId)
                } else {
                    // No conversation specified: pick the most recent for the calling assistant.
                    conversationRepo.getRecentConversations(fallbackAssistant.id, 1).firstOrNull()
                } ?: return@Tool errEnv(
                    "no_conversation",
                    "no conversation found to compute token usage against",
                )
            val assistant = settings.resolveAssistantFor(conv.assistantId)
            val snapshot =
                TokenBudgetTracker.snapshot(
                    conversation = conv,
                    softCap = assistant.tokenBudgetSoftCap,
                    hardCap = assistant.tokenBudgetHardCap,
                )
            val payload =
                buildJsonObject {
                    put("conversation_id", conv.id.toString())
                    put("input_tokens", snapshot.totals.inputTokens)
                    put("output_tokens", snapshot.totals.outputTokens)
                    put("cached_tokens", snapshot.totals.cachedTokens)
                    put("total_tokens", snapshot.totals.totalTokens)
                    put("per_message_max", snapshot.totals.perMessageMax)
                    put("message_count", snapshot.totals.messageCount)
                    if (snapshot.softCap != null) put("soft_cap", snapshot.softCap)
                    if (snapshot.hardCap != null) put("hard_cap", snapshot.hardCap)
                    // 真实窗口容量：只认用户在设置里填的值（单位 K）。没有时显式
                    // 标 unavailable —— per_message_max 只是单次请求体积，不能当容量。
                    val configuredWindow = settings.contextWindowSize.takeIf { it > 0L }
                    if (configuredWindow != null) {
                        put("context_window", configuredWindow)
                        put("context_window_source", "settings")
                        put(
                            "per_message_max_ratio",
                            snapshot.totals.perMessageMax.toDouble() / configuredWindow.toDouble(),
                        )
                    } else {
                        put("context_window_source", "unavailable")
                    }
                    put("status", snapshot.status.name)
                }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
