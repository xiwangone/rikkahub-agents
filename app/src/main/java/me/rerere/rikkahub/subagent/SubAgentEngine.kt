package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "SubAgentEngine"

/**
 * Turn a wait-for-completion outcome into a stop decision, stopping the still-running
 * generation via [stop] when the wait timed out. Returns true on timeout, false on
 * natural completion. The generation itself is NOT cancelled by withTimeoutOrNull — that
 * only abandons the wait, leaving the LLM call running in ChatService's own session job.
 * Left uncalled, a timed-out sub-agent keeps burning tokens (and, if it later succeeds,
 * races a duplicate parallel run against whatever the parent does next). [stop] is
 * responsible for its own failure handling (see the runCatching wrapper around
 * chatService.stopGeneration at the call site in [SubAgentEngine.executeRun]) — kept out
 * of this pure function so it stays testable without touching android.util.Log, which
 * isn't mocked in this module's plain-JVM unit tests. Split out the same way
 * CronJobWorker.finishRunLlm is, so a JVM test can pin the stop-on-timeout contract
 * without a live ChatService.
 */
internal suspend fun finishSubAgentWait(completed: Boolean, stop: suspend () -> Unit): Boolean {
    if (!completed) {
        stop()
        return true
    }
    return false
}

/**
 * Resolves subagent_dispatch's `model_id` (uuid, provider model id, or display name) against
 * the CHAT-type models of ENABLED providers. #28: `model_id` was parsed, stored and
 * echoed back but never used to pick a model - the sub-agent silently inherited the parent's.
 * That silent fallback is the bug; this resolver fails loudly instead.
 */
internal object SubAgentModelResolver {
    sealed class Result {
        data object Inherit : Result()
        data class Resolved(val modelId: Uuid) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * [modelIdInput] null/blank -> [Result.Inherit] (today's behavior unchanged). Otherwise
     * tried in order - uuid exact match, then case-insensitive exact match on [Model.modelId],
     * then case-insensitive exact match on [Model.displayName] - stopping at the first step
     * with any match. Exactly one match at a step resolves; more than one is ambiguous;
     * falling through all three with nothing is unknown. Both failure cases list the
     * candidates as "displayName (providerName) -> uuid" so the caller can retry unambiguously.
     */
    fun resolve(modelIdInput: String?, providers: List<ProviderSetting>): Result {
        if (modelIdInput.isNullOrBlank()) return Result.Inherit

        val chatModels: List<Pair<ProviderSetting, Model>> = providers
            .filter { it.enabled }
            .flatMap { provider -> provider.models.filter { it.type == ModelType.CHAT }.map { provider to it } }

        val asUuid = runCatching { Uuid.parse(modelIdInput) }.getOrNull()
        if (asUuid != null) {
            chatModels.firstOrNull { (_, model) -> model.id == asUuid }
                ?.let { (_, model) -> return Result.Resolved(model.id) }
        }

        val byModelId = chatModels.filter { (_, model) -> model.modelId.equals(modelIdInput, ignoreCase = true) }
        if (byModelId.size == 1) return Result.Resolved(byModelId[0].second.id)
        if (byModelId.size > 1) return Result.Failed(ambiguousMessage(modelIdInput, byModelId))

        val byDisplayName = chatModels.filter { (_, model) -> model.displayName.equals(modelIdInput, ignoreCase = true) }
        if (byDisplayName.size == 1) return Result.Resolved(byDisplayName[0].second.id)
        if (byDisplayName.size > 1) return Result.Failed(ambiguousMessage(modelIdInput, byDisplayName))

        return Result.Failed(unknownMessage(modelIdInput, chatModels))
    }

    private fun candidateLine(candidate: Pair<ProviderSetting, Model>): String {
        val (provider, model) = candidate
        return "${model.displayName} (${provider.name}) -> ${model.id}"
    }

    private fun ambiguousMessage(input: String, matches: List<Pair<ProviderSetting, Model>>): String =
        "model_id \"$input\" matches multiple models - retry with one of these uuids:\n" +
            matches.joinToString("\n") { candidateLine(it) }

    private fun unknownMessage(input: String, available: List<Pair<ProviderSetting, Model>>): String =
        if (available.isEmpty()) {
            "model_id \"$input\" did not match any model, and no chat models are available from enabled providers"
        } else {
            "model_id \"$input\" did not match any model. Available models:\n" +
                available.joinToString("\n") { candidateLine(it) }
        }
}

/**
 * #36: resolves subagent_dispatch's `agent` (a [SubAgentProfile] name) against the
 * user's configured profiles. Mirrors [SubAgentModelResolver]'s contract: no input is not an
 * error (nothing was requested), an unknown or disabled name fails loudly with the list of
 * valid names rather than silently falling back to the parent's model - the same
 * silent-inheritance bug `model_id` had before #28 fixed it.
 */
internal object SubAgentProfileResolver {
    sealed class Result {
        data object NotRequested : Result()
        data class Resolved(val profile: SubAgentProfile) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Profiles eligible for dispatch or for listing in `subagent_dispatch`'s description - a
     * disabled profile is neither resolvable nor discoverable, so it behaves exactly as if it
     * didn't exist. Shared by [resolve] and the tool description built in SubAgentTools.kt so
     * there's a single definition of "eligible" to test.
     */
    fun enabledProfiles(profiles: List<SubAgentProfile>): List<SubAgentProfile> =
        profiles.filter { it.enabled }

    /**
     * [agentName] null/blank -> [Result.NotRequested]. Otherwise matched case-insensitively by
     * [SubAgentProfile.name] against only the ENABLED profiles. More than one enabled profile
     * sharing a name (case-insensitively) is ambiguous and fails loudly naming the duplicate,
     * mirroring [SubAgentModelResolver]'s ambiguous-`model_id` handling - a sub-agent must never
     * silently run on whichever of two same-named profiles happened to come first.
     */
    fun resolve(agentName: String?, profiles: List<SubAgentProfile>): Result {
        if (agentName.isNullOrBlank()) return Result.NotRequested

        val enabled = enabledProfiles(profiles)
        val matches = enabled.filter { it.name.equals(agentName, ignoreCase = true) }
        if (matches.size > 1) {
            return Result.Failed(
                "agent \"$agentName\" matches multiple sub-agent profiles - rename one of these " +
                    "duplicates: " + matches.joinToString(", ") { it.name }
            )
        }
        val match = matches.firstOrNull()
        if (match != null) return Result.Resolved(match)

        return Result.Failed(
            if (enabled.isEmpty()) {
                "agent \"$agentName\" did not match any sub-agent profile, and no profiles are configured"
            } else {
                "agent \"$agentName\" did not match any enabled sub-agent profile. Available: " +
                    enabled.joinToString(", ") { it.name }
            }
        )
    }
}

/**
 * #36: combines `model_id`'s resolution with an `agent` profile's model - `model_id`
 * always wins when it resolved to something (or failed - a bad explicit model_id must surface,
 * not be papered over by falling back to the profile's model). Only when `model_id` was never
 * given ([SubAgentModelResolver.Result.Inherit]) does the profile's model get a chance, and only
 * if [profile] is non-null and its `modelId` is set - otherwise this is a no-op, exactly
 * preserving the behavior before named sub-agent profiles when no agent was requested. Split
 * out as a pure function (same rationale as [finishSubAgentWait]) so the precedence rule is
 * unit-testable without a live
 * [SubAgentEngine].
 */
internal fun resolveSubAgentModel(
    modelResolution: SubAgentModelResolver.Result,
    profile: SubAgentProfile?,
): SubAgentModelResolver.Result = when (modelResolution) {
    is SubAgentModelResolver.Result.Inherit ->
        profile?.modelId?.let { SubAgentModelResolver.Result.Resolved(it) } ?: modelResolution
    else -> modelResolution
}

/**
 * Phase 11 — sub-agent dispatch engine.
 *
 * The engine reuses the existing cron-headless dispatch pattern (mark conv headless,
 * sendMessage, await generation flow's terminal state). It deliberately does NOT
 * re-implement [me.rerere.rikkahub.data.ai.GenerationHandler] — that path is already
 * battle-tested and any duplicate would diverge.
 *
 * Recursion guard: SubAgentEngine refuses to dispatch if the calling conversation is
 * itself headless (i.e. we're already inside a sub-agent / cron / external-automation
 * run). The four `subagent_*` tools are also not registered for headless conversations
 * via the standard tool gating in [me.rerere.rikkahub.data.ai.tools.LocalTools] — but the
 * engine-level check is the load-bearing guard since a misconfigured assistant could
 * still try to call us. v1: no recursion.
 *
 * Concurrency caps:
 *  - Per-assistant cap from [me.rerere.rikkahub.data.model.Assistant.maxConcurrentSubAgents]
 *  - Global cap from [SubAgentDefaults.GLOBAL_CONCURRENCY_CAP]
 *  - Both enforced at dispatch entry — over-cap requests fail fast (background) or block
 *    up to 30s waiting for a slot before failing (foreground, per spec).
 */
class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    /**
     * Phase 24 — unified AgentRun ledger writer. The [SubAgentRegistry] is in-memory only,
     * so a backgrounded sub-agent does NOT survive process death — its registry entry is
     * gone on restart. Writing each sub-agent run to the persistent ledger closes that gap:
     * a run left `running` when the process dies is flipped to `process_lost` by
     * [me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery] on next start, so the user
     * (and `subagent_get`, via the ledger) can see what actually happened. No DI-cycle
     * risk: AgentRunRepository depends only on its DAO.
     */
    private val agentRunRepo: AgentRunRepository,
) {

    /**
     * [ChatService] is resolved lazily via Koin to break the construction cycle:
     *   - [ChatService] constructor takes [LocalTools]
     *   - [LocalTools] constructor takes [SubAgentEngine] (so subagent_dispatch can fire)
     *   - [SubAgentEngine] needs [ChatService] only at dispatch time (sendMessage), so
     *     eager constructor injection here would close the cycle.
     * Same lazy-Koin pattern as [me.rerere.rikkahub.workflow.execution.WorkflowEngine.localTools].
     * Verified post-DI-fix 2026-05-08 — installed APK reaches MainActivity without crash.
     */
    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    /**
     * Phase 24 — maps a sub-agent run id to its `agent_runs` ledger row id. Populated when
     * the run is dispatched, consulted by [executeRun] / [markTerminal] when transitioning
     * the ledger row, removed when the run reaches a terminal status. A run with no entry
     * here simply skips the ledger write (best-effort — the ledger never breaks a run).
     */
    private val ledgerIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    sealed class DispatchResult {
        data class Ok(val run: SubAgentRun) : DispatchResult()
        data class Reject(val error: String, val detail: String) : DispatchResult()
    }

    /**
     * Dispatch a sub-agent. For foreground runs, blocks until terminal status; for
     * background, returns immediately with a PENDING-then-RUNNING run that the caller
     * can poll via subagent_get.
     */
    suspend fun dispatch(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): DispatchResult = withContext(Dispatchers.Default) {
        // Recursion guard: if the caller is in a headless context already (cron job /
        // workflow / another sub-agent), reject. v1 does not allow nested sub-agents.
        if (parentChatId != null) {
            val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull()
            if (parentUuid != null && HeadlessConversations.isHeadless(parentUuid)) {
                return@withContext DispatchResult.Reject(
                    "no_recursion",
                    "sub-agent dispatch is not allowed from inside another headless run"
                )
            }
        }
        val validation = SubAgentRequestValidator.validate(request)
        if (validation is SubAgentRequestValidator.Result.Reject) {
            return@withContext DispatchResult.Reject(validation.error, validation.detail)
        }
        val cleaned = (validation as SubAgentRequestValidator.Result.Ok).request

        // Concurrency cap. Global first (cheaper), then per-assistant.
        if (registry.globalActiveCount() >= SubAgentDefaults.GLOBAL_CONCURRENCY_CAP) {
            return@withContext DispatchResult.Reject(
                "global_cap_reached",
                "max ${SubAgentDefaults.GLOBAL_CONCURRENCY_CAP} concurrent sub-agents across all assistants"
            )
        }
        val perAssistantCap = currentAssistantCap(parentAssistantId)
        if (registry.activeCountForAssistant(parentAssistantId) >= perAssistantCap) {
            return@withContext DispatchResult.Reject(
                "assistant_cap_reached",
                "this assistant's max_concurrent_sub_agents cap of $perAssistantCap is reached"
            )
        }

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val initialRun = SubAgentRun(
            id = runId,
            parentChatId = parentChatId,
            parentAssistantId = parentAssistantId,
            label = cleaned.label?.takeIf { it.isNotBlank() } ?: cleaned.task.take(60),
            task = cleaned.task,
            modelId = cleaned.modelId,
            tools = cleaned.tools,
            runInBackground = cleaned.runInBackground,
            noResult = cleaned.noResult,
            timeoutSeconds = cleaned.timeoutSeconds,
            maxTrips = cleaned.maxTrips,
            status = SubAgentStatus.PENDING,
            startedAtMs = now,
        )
        registry.addPending(initialRun)

        // Phase 24 — open the cross-pillar ledger row. domain_id is the sub-agent run id.
        // The row starts in `queued` (the execution coroutine hasn't been launched yet);
        // executeRun() flips it to `running`. If the process dies before then, boot
        // recovery flips the stranded `queued` row to `process_lost`.
        val ledgerId = agentRunRepo.open(
            kind = AgentRunKind.SubAgent,
            domainId = runId,
            parentRunId = parentChatId,
            status = AgentRunStatus.queued,
            metadata = buildJsonObject {
                put("label", initialRun.label)
                put("parent_assistant_id", parentAssistantId)
                put("run_in_background", cleaned.runInBackground)
            },
        )
        ledgerIds[runId] = ledgerId

        val executionJob = appScope.launch(Dispatchers.IO) {
            executeRun(runId, parentAssistantId, parentChatId, cleaned)
        }
        registry.setJob(runId, executionJob)

        if (cleaned.runInBackground) {
            // Return immediately; final status delivered via registry observation.
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        } else {
            // Foreground — block until terminal.
            try {
                executionJob.join()
            } catch (t: Throwable) {
                AppLog.w(TAG, "foreground sub-agent join failed for $runId", t)
            }
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        }
    }

    private suspend fun currentAssistantCap(parentAssistantId: String): Int {
        val asstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull() ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        val settings = settingsStore.settingsFlow.first()
        val asst = settings.assistants.firstOrNull { it.id == asstUuid }
            ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        return asst.maxConcurrentSubAgents.coerceIn(
            SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
            SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
        )
    }

    /** executeRun 的目标解析结果：模型 / 工作区 / 工具白名单 / 生效任务文本。 */
    private sealed class RunTargets {
        data class Ready(
            val chatModelId: Uuid?,
            val workspaceId: String?,
            val toolScope: List<String>?,
            val elevated: Boolean,
            val effectiveTask: String,
        ) : RunTargets()

        data class Rejected(val message: String) : RunTargets()
    }

    /**
     * 解析子代理的运行目标（agent profile → 模型 → 会话级作用域 → 任务文本）。
     * 抽成独立函数只为让 [executeRun] 的主流程保持可读：解析失败分三类原因，堆在一起
     * 会让分支数超出静态检查门限（detekt CyclomaticComplexMethod）。
     *
     * 优先级：显式 request > agent profile > 父助手（null/空 = 继承父助手）。
     */
    private suspend fun resolveRunTargets(request: SubAgentRequest): RunTargets {
        val settings = settingsStore.settingsFlow.first()
        val profile = when (
            val r = SubAgentProfileResolver.resolve(request.agentName, settings.subAgents)
        ) {
            is SubAgentProfileResolver.Result.NotRequested -> null
            is SubAgentProfileResolver.Result.Resolved -> r.profile
            is SubAgentProfileResolver.Result.Failed -> return RunTargets.Rejected(r.message)
        }
        // model_id 先解析，缺省时回落到 profile 的模型；都未给则继承父助手。
        val chatModelId = when (
            val r = resolveSubAgentModel(
                SubAgentModelResolver.resolve(request.modelId, settings.providers),
                profile,
            )
        ) {
            is SubAgentModelResolver.Result.Inherit -> null
            is SubAgentModelResolver.Result.Resolved -> r.modelId
            is SubAgentModelResolver.Result.Failed -> return RunTargets.Rejected(r.message)
        }
        // B1 会话级作用域：显式 request > profile > 父助手。
        // 子代理因此可以只拿"本地读写 + 搜索"类工具，并跑在独立工作区里。
        val workspaceId = request.workspaceId?.takeIf { it.isNotBlank() }
            ?: profile?.workspaceId?.toString()
        if (workspaceId != null && runCatching { Uuid.parse(workspaceId) }.isFailure) {
            AppLog.w(TAG, "ignoring unparseable sub-agent workspace id: $workspaceId")
        }
        // B1 会话级作用域：默认**收敛为只读集**（headless 工具调用是自动批准的，不能默认给全量）。
        // 优先级：request.tools > profile.toolScope；显式声明 `*` 表示继承父助手全量（逃生口）。
        val requestedScope = request.tools?.takeIf { it.isNotEmpty() } ?: profile?.toolScope
        val inheritAll = requestedScope?.contains(SubAgentDefaults.TOOL_SCOPE_INHERIT_ALL) == true
        val toolScope = when {
            inheritAll -> null
            !requestedScope.isNullOrEmpty() -> requestedScope
            else -> SubAgentDefaults.DEFAULT_SAFE_TOOL_SCOPE
        }
        // 提权判定：继承全量，或作用域内含任一写/执行类工具。
        val elevated = toolScope == null || toolScope.any { it in SubAgentDefaults.ELEVATED_TOOL_NAMES }
        // profile 的系统提示词直接前置到任务文本（子代理没有 per-run system prompt 覆盖）。
        val effectiveTask = profile?.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "$it\n\n${request.task}" }
            ?: request.task
        return RunTargets.Ready(
            chatModelId = chatModelId,
            workspaceId = workspaceId,
            toolScope = toolScope,
            elevated = elevated,
            effectiveTask = effectiveTask,
        )
    }

    private suspend fun executeRun(
        runId: String,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ) {
        registry.update(runId) { it.copy(status = SubAgentStatus.RUNNING) }
        ledgerIds[runId]?.let { agentRunRepo.setStatus(it, AgentRunStatus.running) }

        val parentAsstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: run {
                markTerminal(runId, SubAgentStatus.FAILED, "bad parent assistant id")
                return
            }
        val targets = when (val resolution = resolveRunTargets(request)) {
            is RunTargets.Rejected -> {
                markTerminal(runId, SubAgentStatus.FAILED, resolution.message)
                return
            }
            is RunTargets.Ready -> resolution
        }
        if (targets.workspaceId != null || targets.toolScope != null || targets.elevated) {
            registry.update(runId) {
                it.copy(
                    workspaceId = targets.workspaceId,
                    toolScope = targets.toolScope,
                    elevated = targets.elevated,
                )
            }
        }
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = parentAsstUuid,
            newConversation = true,
        ).copy(
            title = "[Sub-agent] ${request.label?.take(40) ?: request.task.take(40)}",
            chatModelId = targets.chatModelId,
            workspaceIdOverride = targets.workspaceId?.let { raw ->
                runCatching { Uuid.parse(raw) }.getOrNull()
            },
            toolScopeOverride = targets.toolScope,
            // B2：max_trips 真正生效——映射为本次子代理会话的生成步数上限。
            maxToolStepsOverride = request.maxTrips,
        )
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        try {
            // Prepend a wrap-up instruction. Some models naturally write a summary paragraph
            // after their tool-call sequence; others stop after the last tool result and emit
            // no closing text. Without explicit text the parent has nothing to harvest and
            // the sub-agent's findings are lost.
            val taskWithWrapup = buildString {
                append(targets.effectiveTask)
                appendLine()
                appendLine()
                append("When you have finished, end with one short paragraph in plain text that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(taskWithWrapup)))
            // The naive form `withTimeoutOrNull { …first { it == null } }` followed by a
            // `finished == null` check is BROKEN: `.first { it == null }` returns the matched
            // value — which IS null on successful completion (the Job? went to null when the
            // LLM finished). So `finished == null` was true on BOTH timeout AND success, and
            // every sub-agent looked TIMED_OUT despite actually finishing. Use a Unit sentinel
            // so the two outcomes are distinguishable.
            val completed: Unit? = withTimeoutOrNull(request.timeoutSeconds * 1000L) {
                chatService.getGenerationJobStateFlow(conv.id).first { it == null }
                Unit
            }
            val timedOut = finishSubAgentWait(completed = completed != null) {
                runCatching { chatService.stopGeneration(conv.id) }
                    .onFailure { AppLog.w(TAG, "sub-agent timeout: stopGeneration failed for $runId", it) }
            }
            if (timedOut) {
                markTerminal(runId, SubAgentStatus.TIMED_OUT, "exceeded ${request.timeoutSeconds}-second cap")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            // Harvest the assistant's final text from the conversation. Best-effort —
            // we read the latest persisted state of the conversation and concatenate any
            // text parts from the last assistant message. This mirrors how the
            // CronJobWorker treats LLM-mode jobs.
            val finalText = harvestFinalText(conv.id)
            // B2 用量回传：子代理本身就是一次会话，直接聚合它自己的 usage（父会话不受影响）。
            val usage = measureRunUsage(conv.id)
            registry.update(runId) {
                it.copy(
                    status = SubAgentStatus.SUCCEEDED,
                    result = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                    tokensIn = usage.inputTokens,
                    tokensOut = usage.outputTokens,
                    tokensCached = usage.cachedTokens,
                    tripCount = usage.trips,
                )
            }
            ledgerIds.remove(runId)?.let {
                agentRunRepo.markTerminal(it, AgentRunStatus.succeeded)
            }
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } catch (t: Throwable) {
            AppLog.w(TAG, "sub-agent run failed", t)
            // CancellationException → CANCELLED, anything else → FAILED.
            val terminal = if (t is kotlinx.coroutines.CancellationException) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            markTerminal(runId, terminal, "${t::class.simpleName}: ${t.message.orEmpty()}")
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } finally {
            HeadlessConversations.unmark(conv.id)
            registry.clearJob(runId)
        }
    }

    private suspend fun markTerminal(runId: String, status: SubAgentStatus, error: String?) {
        registry.update(runId) {
            it.copy(
                status = status,
                error = error,
                finishedAtMs = System.currentTimeMillis(),
            )
        }
        // Phase 24 — mirror the terminal status into the cross-pillar ledger. TIMED_OUT and
        // FAILED both map to `failed`; CANCELLED maps to `cancelled`. (SUCCEEDED never
        // routes through here — it transitions the ledger row inline in executeRun.)
        ledgerIds.remove(runId)?.let { ledgerId ->
            val ledgerStatus = when (status) {
                SubAgentStatus.CANCELLED -> AgentRunStatus.cancelled
                SubAgentStatus.SUCCEEDED -> AgentRunStatus.succeeded
                else -> AgentRunStatus.failed
            }
            agentRunRepo.markTerminal(ledgerId, ledgerStatus, error)
        }
    }

    /**
     * Wake the parent conversation when a backgrounded sub-agent finishes — the parent's
     * LLM gets a synthetic user message describing the completion and naturally synthesises
     * a reply. Without this, the parent has no way to know the sub-agent finished except by
     * the user manually asking "what happened?".
     *
     * Skip rules:
     *  - Foreground runs: dispatch is synchronous (executionJob.join() in dispatch()), so the
     *    tool result already carries the final state. No wake needed.
     *  - Parents in headless mode: would loop / fork weirdly with cron + sub-agent + workflow
     *    runs. The parent must be a regular interactive (in-app or Telegram-bot) conversation.
     *  - parentChatId / runs missing: defensive.
     *
     * Cancellation hygiene: ChatService.sendMessage cancels any in-flight generation in the
     * target conversation. To avoid stomping on a turn the user is engaged with, we wait up
     * to 5 minutes for the parent to be idle before posting. After 5 minutes we post anyway
     * — better to interrupt than to silently lose the completion.
     */
    private suspend fun notifyParentIfBackground(parentChatId: String?, run: SubAgentRun?) {
        if (parentChatId == null || run == null || !run.runInBackground) return
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return
        if (HeadlessConversations.isHeadless(parentUuid)) return

        val message = buildString {
            appendLine("[Sub-agent ${run.label} — ${run.status.name}]")
            run.error?.takeIf { it.isNotBlank() }?.let {
                appendLine("Error: $it")
            }
            if (!run.noResult) {
                run.result?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    append(it)
                }
            }
        }.trimEnd()

        runCatching {
            withTimeoutOrNull(5 * 60_000L) {
                chatService.getGenerationJobStateFlow(parentUuid).first { it == null }
                Unit
            }
            chatService.sendMessage(parentUuid, listOf(UIMessagePart.Text(message)))
        }.onFailure {
            AppLog.w(TAG, "failed to notify parent $parentChatId of subagent completion", it)
        }
    }

    /** B2：子代理 run 的用量快照（输入/输出/命中缓存 + trip 数）。 */
    private data class RunUsage(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cachedTokens: Long = 0,
        val trips: Int = 0,
    )

    /**
     * 聚合子代理会话自己的 token 用量。父会话的统计（[TokenBudgetTracker] 按 Conversation 聚合）
     * 因此天然不受影响——子代理开销单独可见，不会污染父会话的上下文预算。
     * tripCount = 该会话里生成过的 assistant 轮数（含最终总结轮），对应用户侧"跑了几个回合"。
     */
    private suspend fun measureRunUsage(conversationId: Uuid): RunUsage {
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching RunUsage()
            val totals = TokenBudgetTracker.aggregate(conv)
            val trips = conv.messageNodes.count { node ->
                node.messages.getOrNull(node.selectIndex)?.role?.name?.equals("assistant", ignoreCase = true) == true
            }
            RunUsage(
                inputTokens = totals.inputTokens,
                outputTokens = totals.outputTokens,
                cachedTokens = totals.cachedTokens,
                trips = trips,
            )
        }.getOrDefault(RunUsage())
    }

    private suspend fun harvestFinalText(conversationId: Uuid): String {
        // The Conversation persisted by the generation pipeline contains the full message
        // history (messageNodes). Each MessageNode holds parallel branches in
        // `messages: List<UIMessage>` keyed by `selectIndex`. Walk the currently-selected
        // branch and pull text from the last assistant message — that's the sub-agent's
        // final summary.
        //
        // Robustness: if the last assistant message has NO Text part (some models stop
        // after a tool call and emit no closing text), walk back through previous assistant
        // messages and concatenate their Text parts so we don't return empty. Better to
        // surface partial intermediate text than to return "" and lose the sub-agent's
        // work entirely.
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching ""
            val selectedMessages = conv.messageNodes.mapNotNull { node ->
                node.messages.getOrNull(node.selectIndex)
            }
            val assistantMessages = selectedMessages.filter { msg ->
                msg.role.name.equals("assistant", ignoreCase = true)
            }
            if (assistantMessages.isEmpty()) return@runCatching ""

            // Try the last assistant message's text first.
            val lastTexts = assistantMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (lastTexts.isNotBlank()) return@runCatching lastTexts

            // Fallback: collect text from all assistant messages (preserve order).
            assistantMessages
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
                .trim()
        }.getOrDefault("")
    }
}
