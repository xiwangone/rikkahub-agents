package me.rerere.ai.agent

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.sum
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.util.json

// 输出为空的工具会被当成没执行过，之后每一步都会再执行一次，所以换成占位文本
private const val EMPTY_TOOL_OUTPUT = "(no output)"

/** 合并或下游处理失败：不是网络故障，不能重放模型请求 */
internal class StreamChunkHandlingException(override val cause: Throwable) : RuntimeException(cause)

internal class AgentLoop(
    private val request: AgentRequest,
    private val hooks: AgentHooks,
    events: FlowCollector<AgentEvent>,
) {
    private val events = FailureTrackingCollector(events)
    private val retryPolicy = request.retry.copy(
        nonRetryable = { failure -> this.events.failure != null || request.retry.nonRetryable(failure) },
    )
    private val runStartedAt = TimeSource.Monotonic.markNow()
    private val history: MutableList<UIMessage>
    private var workingMessage: UIMessage?

    // 已完成的模型请求次数（maxSteps 的口径）；重发本步同样消耗一次请求额度
    private var steps = 0

    init {
        val last = request.messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history = request.messages.dropLast(1).toMutableList()
            workingMessage = last
        } else {
            history = request.messages.toMutableList()
            workingMessage = null
        }
    }

    suspend fun run() {
        while (true) {
            var reissueStep = false
            when (val decision = hooks.beforeStep(stepContext())) {
                StepDecision.Continue -> Unit

                is StepDecision.Append -> history.add(decision.message)
                is StepDecision.Replace -> applyHistory(decision.messages)
                is StepDecision.RetryCurrentStep -> {
                    decision.inject?.let { history.add(it) }
                    reissueStep = true
                }

                is StepDecision.Stop -> return finish(decision.reason)
            }
            val current = workingMessage
            val pending = current?.getTools()?.filter { !it.isExecuted }.orEmpty()
            if (!reissueStep && current != null && pending.isNotEmpty()) {
                val awaiting = requestApprovals(current)
                if (awaiting.isNotEmpty()) return finish(AgentFinishReason.AwaitingApproval(awaiting))
                // 审批判定可能已改写这条消息，执行前重新取一次
                executeTools(checkNotNull(workingMessage).getTools().filter { !it.isExecuted })
            } else if (!reissueStep && current != null && steps > 0) {
                return finish(AgentFinishReason.Completed)
            }
            if (steps >= request.maxSteps) return finish(AgentFinishReason.StepLimitReached)
            steps += generateStep()
        }
    }

    /** 换掉权威历史；末尾是助手消息时它仍是本轮正在积累的助手消息 */
    private fun applyHistory(messages: List<UIMessage>) {
        val last = messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history.clear()
            history.addAll(messages.dropLast(1))
        } else {
            history.clear()
            history.addAll(messages)
        }
    }

    private fun stepContext(): StepContext {
        val current = workingMessage
        return StepContext(
            stepIndex = steps,
            messages = if (current == null) history.toList() else history + current,
            pendingTools = current?.getTools()?.filter { !it.isExecuted }.orEmpty(),
            elapsed = runStartedAt.elapsedNow(),
            usage = current?.usage,
        )
    }

    // 新的工具调用按定义判断是否需要审批，需要的标为 Pending；返回仍在等待审批的调用
    private suspend fun requestApprovals(current: UIMessage): Set<String> {
        val updated = current.copy(
            parts = current.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted) {
                    val declared = findTool(part.toolName)?.needsApproval?.invoke(part.inputAsJson()) == true
                    val decided = hooks.decideApproval(part, declared)
                    if (decided == part.approvalState) part else part.copy(approvalState = decided)
                } else {
                    part
                }
            },
        )
        if (updated != current) update(updated)
        return updated.getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId }
    }

    private suspend fun generateStep(): Int {
        val current = workingMessage
        val step = hooks.prepareStep(if (current == null) history.toList() else history + current)
        val params = step.params.copy(tools = request.tools)
        val base = current ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = params.model.id,
        ).also {
            workingMessage = it
            events.emit(AgentEvent.MessageUpdated(it))
        }
        val generated = if (request.stream) {
            stream(step.messages, params, base)
        } else {
            generate(step.messages, params, base)
        }
        val finished = hooks.finishStep(generated, newPartsFrom = base.parts.size).copy(finishedAt = now())
        update(finished)
        return (finished.stepCount - base.stepCount).coerceAtLeast(0)
    }

    // 合并失败保留原有包装；下游投递失败由 events 单独锁存并禁止重试。
    // 外层再解包 throw e.cause!! 抛原始异常（cause 不会丢失）。
    // InstanceOfCheckForException：需要先拿异常对象再判类型（区分“合并失败包装”与原始失败），无法用多 catch 表达。
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "InstanceOfCheckForException")
    private suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
        // 本步从清空的统计开始，再与之前的步骤合并/累加（见 withStepStats）。
        val stepStart = base.copy(
            usage = null,
            lastUsage = null,
            generationTimeMs = null,
            finishedAt = null,
            finishReason = null,
        )
        var subscriptions = 0
        return try {
            withRetry(retryPolicy, onRetry = { attempt ->
                currentCoroutineContext().ensureActive()
                events.emit(AgentEvent.Retrying(attempt))
            }) {
                // 每次重试都从本步开始时的消息重新合并，不会把重试的响应接在已经展示的半截回复后面；
                // 各次尝试共用同一个消息 id，调用方直接覆盖即可。
                hooks.onStreamAttemptReset(subscriptions++)
                val handler = StreamChunkHandler(params.model)
                var attempt = stepStart
                var lastRequestUsage: TokenUsage? = null
                var sawUsageChunk = false
                var requestCount = 1
                var firstChunk: TimeMark? = null
                hooks.interceptStream(request.provider.streamText(request.providerSetting, messages, params))
                    .collect { chunk ->
                        val mark = firstChunk ?: TimeSource.Monotonic.markNow().also { firstChunk = it }
                        if (chunk is StreamChunk.Usage) {
                            sawUsageChunk = true
                            lastRequestUsage = if (chunk.latestUsageReported) {
                                chunk.lastUsage ?: chunk.usage
                            } else {
                                null
                            }
                            requestCount = maxOf(requestCount, chunk.requestCount)
                        }
                        attempt = try {
                            handler.handle(listOf(attempt), chunk).single()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 合并失败不是网络故障，不能重放模型请求
                            throw StreamChunkHandlingException(e)
                        }
                        // Delivery failures are latched by events and excluded from retryPolicy.
                        // Never replay a model request to repair a broken downstream collector.
                        // 预览阶段不提交本请求的 usage/stepCount：只有流正常跑完（下面的 withStepStats）才算完成，
                        // 取消/失败/重试中的尝试只算 preview。
                        preview(
                            attempt.copy(
                                usage = base.usage,
                                lastUsage = base.lastUsage,
                                stepCount = base.stepCount,
                                generationTimeMs = (base.generationTimeMs ?: 0L) + mark.elapsedNow().inWholeMilliseconds,
                                finishedAt = base.finishedAt,
                                finishReason = base.finishReason,
                            ),
                            newPartsFrom = base.parts.size,
                        )
                    }
                events.throwIfFailed()
                attempt.withStepStats(
                    base,
                    firstChunk?.elapsedNow() ?: ZERO,
                    lastRequestUsage = if (sawUsageChunk) lastRequestUsage else attempt.usage,
                    requestCount = requestCount,
                )
            }
        } catch (error: Throwable) {
            // A provider adapter may have caught a downstream cancellation and replaced it
            // with an IOException. Preserve the first delivery failure, not that replacement.
            events.throwIfFailed()
            if (error is StreamChunkHandlingException) throw error.cause
            throw error
        }
    }

    private suspend fun generate(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
        var requestMark = TimeSource.Monotonic.markNow()
        val result = withRetry(retryPolicy, onRetry = { attempt ->
            currentCoroutineContext().ensureActive()
            events.emit(AgentEvent.Retrying(attempt))
        }) {
            requestMark = TimeSource.Monotonic.markNow()
            request.provider.generateText(request.providerSetting, messages, params)
        }
        val requestTime = requestMark.elapsedNow()
        // 和流式一样只追加 part，不并进之前步骤的文本或推理里，否则 newPartsFrom 之后就不全是这一步的内容了。
        // One request contributes one usage snapshot; append its parts without summing the
        // previous cumulative value into this step before withStepStats does the sum.
        val generated = base.copy(
            parts = base.parts + result.message.parts,
            annotations = result.message.annotations.ifEmpty { base.annotations },
            modelId = params.model.id,
            modelSnapshot = ModelSnapshot(params.model.modelId, params.model.displayName),
            finishedAt = now(),
            finishReason = result.finishReason,
            usage = result.usage,
            lastUsage = result.lastUsage,
        ).finishReasoning().withStepStats(
            base,
            requestTime,
            lastRequestUsage = result.lastUsage,
            requestCount = result.requestCount,
        )
        preview(generated, newPartsFrom = base.parts.size)
        return generated
    }

    // 按调用顺序逐个执行，每执行完一个就更新一次，中途取消时已经执行的结果不会丢
    private suspend fun executeTools(pending: List<UIMessagePart.Tool>) {
        for (tool in pending) {
            val output = execute(tool)
            val current = checkNotNull(workingMessage)
            update(
                current.copy(
                    parts = current.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == tool.toolCallId) {
                            part.copy(output = output)
                        } else {
                            part
                        }
                    },
                ),
            )
        }
    }

    private suspend fun execute(tool: UIMessagePart.Tool): List<UIMessagePart> =
        when (val state = tool.approvalState) {
            is ToolApprovalState.Denied -> errorOutput(
                "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}",
            )

            is ToolApprovalState.Answered -> listOf(UIMessagePart.Text(state.answer))
            ToolApprovalState.Auto, ToolApprovalState.Approved -> guardedRun(tool)
            ToolApprovalState.Pending -> error("Tool ${tool.toolCallId} is still waiting for approval")
        }

    /** 执行前决策：钩子可以拦截、拒绝或放行；放行时按预算限时执行 */
    private suspend fun guardedRun(tool: UIMessagePart.Tool): List<UIMessagePart> {
        val definition = findTool(tool.toolName)
        val args = parseArgs(tool)
        val outcome = hooks.beforeToolCall(tool, args, ToolCallScopeImpl(tool, args))
        if (definition == null) return errorOutput("Tool ${tool.toolName} not found")
        return when (outcome) {
            is ToolCallOutcome.Intercept -> outcome.output.ifEmpty { placeholder() }
            is ToolCallOutcome.Reject -> outcome.output.ifEmpty { placeholder() }
            ToolCallOutcome.InvalidArgs -> invalidArgsOutput(tool)
            is ToolCallOutcome.Execute -> when {
                args == null -> invalidArgsOutput(tool)
                else -> runTool(tool, definition, args, outcome.timeout, outcome.context)
            }
        }
    }

    // 有意 catch Throwable：单个工具失败要转成 error 包络回灌给模型，不能让整轮生成崩掉；取消已单独上抛。
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTool(
        tool: UIMessagePart.Tool,
        definition: Tool,
        args: JsonElement,
        timeout: Duration?,
        context: CoroutineContext,
    ): List<UIMessagePart> {
        val call: suspend () -> List<UIMessagePart> = { definition.execute(args) }
        return try {
            val output = when {
                timeout != null && context != EmptyCoroutineContext ->
                    withContext(context) { withTimeout(timeout) { call() } }

                timeout != null -> withTimeout(timeout) { call() }
                context != EmptyCoroutineContext -> withContext(context) { call() }
                else -> call()
            }
            hooks.processToolOutput(tool, output).ifEmpty { placeholder() }
        } catch (e: TimeoutCancellationException) {
            // 超时由调用方给的预算掐掉，不能当成工具异常，也不能触发网络重试
            hooks.toolFailureOutput(tool, e)
                ?: errorOutput("Tool ${tool.toolName} timed out after $timeout. It was not completed.")
        } catch (e: CancellationException) {
            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
            throw e
        } catch (e: Throwable) {
            hooks.toolFailureOutput(tool, e)
                ?: errorOutput("[${e.javaClass.name}] ${e.message}\n${e.stackTraceToString()}")
        }
    }

    /** 解析一次，钩子与执行共用；失败返回 null，由 InvalidArgs 分支产出结构化提示 */
    private fun parseArgs(tool: UIMessagePart.Tool): JsonElement? = runCatching {
        json.parseToJsonElement(tool.input.ifBlank { "{}" })
    }.getOrNull()

    private fun invalidArgsOutput(tool: UIMessagePart.Tool): List<UIMessagePart> = errorOutput(
        "Invalid tool arguments JSON for ${tool.toolName}",
    )

    private fun placeholder(): List<UIMessagePart> = listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT))

    private fun findTool(name: String) = request.tools.find { it.name == name }

    private suspend fun update(updated: UIMessage) {
        workingMessage = updated
        events.emit(AgentEvent.MessageUpdated(updated))
    }

    // 生成中途的内容只发给调用方，这一步结束前循环内部一直基于原始内容合并
    private suspend fun preview(generating: UIMessage, newPartsFrom: Int) {
        events.emitFrom { AgentEvent.MessageUpdated(hooks.previewStep(generating, newPartsFrom)) }
    }

    private suspend fun finish(reason: AgentFinishReason) {
        val current = workingMessage ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        events.emit(AgentEvent.Finished(current, reason))
    }

    /** 钩子可见的执行侧能力面：把「执行中」状态立刻对外发布 */
    private inner class ToolCallScopeImpl(
        private val tool: UIMessagePart.Tool,
        override val arguments: JsonElement?,
    ) : ToolCallScope {
        override val elapsed: Duration get() = runStartedAt.elapsedNow()
        override val usage: TokenUsage? get() = workingMessage?.usage

        override suspend fun publish(tool: UIMessagePart.Tool) {
            val current = checkNotNull(workingMessage)
            update(
                current.copy(
                    parts = current.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == tool.toolCallId) tool else part
                    },
                ),
            )
        }
    }
}

private fun errorOutput(error: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))

// 当前 receiver 只含本模型请求的 usage；不同请求累计一次，工具执行不增加 stepCount。
private fun UIMessage.withStepStats(
    stepBase: UIMessage,
    stepTime: Duration,
    lastRequestUsage: TokenUsage? = usage,
    requestCount: Int = 1,
): UIMessage = copy(
    usage = stepBase.usage.sum(usage),
    lastUsage = lastRequestUsage,
    stepCount = stepBase.stepCount + requestCount.coerceAtLeast(1),
    generationTimeMs = (stepBase.generationTimeMs ?: 0L) + stepTime.inWholeMilliseconds,
)

private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
