package me.rerere.ai.agent

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 一次 agent 运行的输入。
 *
 * [messages] 的最后一条是助手消息时，从它继续：先处理它上面还没执行的工具调用（例如审批之后），
 * 再请求模型；否则新建一条助手消息。一次运行只产出这一条助手消息，其余消息只读，不会被改写
 * （要改写历史请用 [AgentHooks.beforeStep] 返回的写回决策）。
 */
data class AgentRequest(
    val provider: Provider<ProviderSetting>,
    val providerSetting: ProviderSetting,
    val messages: List<UIMessage>,
    val tools: List<Tool> = emptyList(),
    val stream: Boolean = true,
    /** 最多请求模型的次数 */
    val maxSteps: Int = 256,
    val retry: RetryPolicy = RetryPolicy.NONE,
)

/**
 * 调用方在循环各个节点上的介入点。
 *
 * 除了 [prepareStep]，其余成员都有默认实现且不产生任何副作用，因此不实现某个钩子时循环行为
 * 与该钩子不存在时完全一致。所有钩子都不切换调度器，在收集方的协程上下文里被调用。
 */
// 钩子数随 agent 生命周期阶段增长（每个阶段一个回调），本质是“阶段清单”而非功能堆积；
// 拆成多个接口会让实现方必须实现多处，反而丢掉“不实现即无副作用”的保证。
@Suppress("TooManyFunctions")
interface AgentHooks {
    /**
     * 每次请求模型之前调用，返回这一步实际发出的消息和参数；系统提示词、上下文裁剪、输入转换都在这里做。
     *
     * [messages] 是历史加上本轮的助手消息；第一步还没有内容时不包含它。
     * 返回的 [StepRequest.params] 里的 tools 会被换成 [AgentRequest.tools]，
     * 声明给模型的工具和能执行的工具始终是同一份。
     */
    suspend fun prepareStep(messages: List<UIMessage>): StepRequest

    /**
     * 生成过程中每次发出更新之前调用，返回值只用于这一次 [AgentEvent.MessageUpdated]，不会写回循环内部。
     * 适合 think 标签这类要边生成边展示、等这一步结束才真正处理的转换；[newPartsFrom] 的含义同 [finishStep]。
     */
    suspend fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /**
     * 每一步生成结束、执行工具之前调用，返回值作为这一步的结果。
     *
     * 下标 [newPartsFrom] 及之后的 part 是这一步新生成的；之前的来自更早的步骤，已经处理过一次。
     */
    suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /**
     * 工具执行抛错、或被 [ToolCallOutcome.Execute.timeout] 掐掉时调用，返回注入给模型的输出。
     *
     * 返回 null 时使用内核的默认错误文本。用于保留结构化错误码（工具失败、墙钟取消等），
     * 同时避免把整段异常栈回灌给模型。取消（[kotlinx.coroutines.CancellationException]）不会走到这里。
     */
    suspend fun toolFailureOutput(
        tool: UIMessagePart.Tool,
        error: Throwable,
    ): List<UIMessagePart>? = null

    /** 工具执行成功后、结果写回之前调用，例如截断过长的输出。拒绝、回答和执行出错的结果不经过这里。 */
    suspend fun processToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
    ): List<UIMessagePart> = output

    /**
     * 包住模型流式响应，在事件折叠进消息之前逐段交付，每个（重试的）请求各订阅一次。
     *
     * 用途是把只对「原始事件流」成立的判定放在这里：空闲超时、空响应判定、输出重复检测、
     * 硬时长上限、事件合并窗口，以及这些判定的重试取舍。实现方可以：
     * - 延后、合并或丢弃事件（只观察不改内容时，原样透出即可）；
     * - 正常结束返回的 Flow 来提前收尾（等价于模型说完）；
     * - 抛异常来终止本次尝试，异常是否触发重试由 [AgentRequest.retry] 的 `retryOn` 决定；
     *   不想被重放的失败请包装成 [RetryPolicy.nonRetryable] 指定的类型。
     *
     * 每次订阅都会得到一条全新的源 Flow，因此订阅内的局部状态只属于本次尝试；跨尝试要保留的状态
     * （例如重复检测的三档游标）请保存在实现方对象里，并在 [onStreamAttemptReset] 复位需要复位的部分。
     * 默认原样透出。非 suspend：只负责装配 Flow，实际消费仍由循环在收集方上下文里进行。
     */
    fun interceptStream(chunks: Flow<StreamChunk>): Flow<StreamChunk> = chunks

    /**
     * 每次（重新）订阅 [interceptStream] 之前调用，[attempt] 从 0 开始，重试时递增。
     * 用于复位「本次尝试是否已收到事件」「本次尝试的请求起点」这类每尝试状态。
     */
    suspend fun onStreamAttemptReset(attempt: Int) {}

    /**
     * 未执行工具的状态判定（审批协议的决策点），对同一批工具逐个调用后再统一决定是否收尾。
     *
     * [declaredApproval] 是工具定义里 `needsApproval` 的同步判定结果；默认实现只在
     * 「声明要审批且当前是 Auto」时翻成 Pending。需要**异步新鲜复核**（用户在上一刻刚授予
     * 「本轮总是允许」、或某些调用无论授权都要硬拦）时覆盖这里，返回
     * [ToolApprovalState.Auto]/[ToolApprovalState.Pending]/[ToolApprovalState.Denied] 之一即可，
     * 其余状态原样返回。返回非 Auto 的其它已有状态不会影响未涉及的工具。
     */
    suspend fun decideApproval(
        tool: UIMessagePart.Tool,
        declaredApproval: Boolean,
    ): ToolApprovalState = if (declaredApproval && tool.approvalState is ToolApprovalState.Auto) {
        ToolApprovalState.Pending
    } else {
        tool.approvalState
    }

    /**
     * 工具执行之前，按调用顺序逐个调用；续跑（消息上还挂着未执行的工具）路径同样会走到这里。
     *
     * [parsedArgs] 是循环对本次调用参数解析一次的结果（解析失败为 null），执行时直接复用，
     * 由 [ToolCallScope.arguments] 透出给实现方。返回 [ToolCallOutcome.Intercept] 或
     * [ToolCallOutcome.Reject] 时不会执行该工具，注入的输出会被标记为已执行，因此下一步模型能看到结果。
     * 默认全部放行。
     */
    suspend fun beforeToolCall(
        tool: UIMessagePart.Tool,
        parsedArgs: JsonElement?,
        scope: ToolCallScope,
    ): ToolCallOutcome = ToolCallOutcome.Execute()

    /**
     * 每一轮开始时调用：在执行未落地的工具、请求模型之前。
     *
     * 与 [prepareStep] 的区别是这里的返回值会**写回权威历史**——注入的合成消息因此跨步骤可见，
     * 也能被历史扫描按 `isSynthetic` 区分。用于步间的消息演化（注入续写提示、补充说明）
     * 和步级预算判定（返回 [StepDecision.Stop] 结束整次运行）。默认放行且不改历史。
     */
    suspend fun beforeStep(step: StepContext): StepDecision = StepDecision.Continue

    /**
     * 工具结果已经写回当前助手消息后、下一次模型请求之前调用。
     * 与 [beforeStep] 不同，此钩子位于工具执行与紧随其后的模型请求之间，适合把
     * 刚到达的用户补充写入请求历史；此时模型仍能在本轮内看到工具结果和补充内容。
     */
    suspend fun afterToolExecution(step: StepContext) {}
}

/** 一步请求的实际内容：消息与参数。 */
data class StepRequest(
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
)

/** [AgentHooks.beforeStep] 的输入。 */
data class StepContext(
    /** 已经完成的请求次数，首轮为 0 */
    val stepIndex: Int,
    /** 权威历史，含本轮的助手消息（首轮为 null 时不含） */
    val messages: List<UIMessage>,
    /** 本轮助手消息上已执行完、尚未回灌给模型的工具调用 */
    val pendingTools: List<UIMessagePart.Tool>,
    /** 自本次运行开始经过的时间 */
    val elapsed: Duration,
    /** 累计用量，首轮为 null */
    val usage: TokenUsage?,
)

/** [AgentHooks.beforeStep] 的返回值。 */
sealed interface StepDecision {
    /** 不改历史，按 [AgentHooks.prepareStep] 的输出生成 */
    data object Continue : StepDecision

    /** 在历史末尾追加一条消息后继续 */
    data class Append(val message: UIMessage) : StepDecision

    /** 用 [messages] 替换本次运行的历史（保留本轮助手消息由实现方负责） */
    data class Replace(val messages: List<UIMessage>) : StepDecision

    /**
     * 重新发起本步（不计入已完成的生成），[inject] 会先写回历史末尾。
     * 用于「换个说法再试一次」的降级：图片读取失败退回可读工具、断流后要求续写。
     */
    data class RetryCurrentStep(val inject: UIMessage? = null) : StepDecision

    /** 立刻结束本次运行，[reason] 随 [AgentEvent.Finished] 透出 */
    data class Stop(val reason: AgentFinishReason) : StepDecision
}

/** [AgentHooks.beforeToolCall] 的返回值。 */
sealed interface ToolCallOutcome {
    /**
     * 正常执行。
     *
     * [timeout] 非空时该工具在超时后取消（超时不会计入重试，也不会当作工具错误）；
     * [context] 会与执行上下文合并，用于把调用归属传给工具实现。
     */
    data class Execute(
        val timeout: Duration? = null,
        val context: CoroutineContext = EmptyCoroutineContext,
    ) : ToolCallOutcome

    /** 不执行，以 [output] 作为该工具的输出（守卫类拦截走这条，保留结构化原因） */
    data class Intercept(val output: List<UIMessagePart>) : ToolCallOutcome

    /** 不执行，以 [output] 作为该工具的输出，语义上是「未获批准」 */
    data class Reject(val output: List<UIMessagePart>) : ToolCallOutcome

    /** 参数解析失败：产出结构化的参数错误输出，而不是异常原文 */
    data object InvalidArgs : ToolCallOutcome
}

/** [AgentHooks.beforeToolCall] 的能力面。 */
interface ToolCallScope {
    /** 与 [AgentHooks.beforeToolCall] 的 parsedArgs 同源，执行时复用同一份解析结果 */
    val arguments: JsonElement?

    /** 本次运行已消耗的时间，预算判定用 */
    val elapsed: Duration

    /** 累计用量，首轮为 null */
    val usage: TokenUsage?

    /** 立刻对外发布该工具的当前状态（触发一次 [AgentEvent.MessageUpdated]），用于「执行中」面包屑 */
    suspend fun publish(tool: UIMessagePart.Tool)
}

/** 循环对外发出的事件。 */
sealed interface AgentEvent {
    /** 本轮助手消息的最新内容，整次运行 id 不变 */
    data class MessageUpdated(val message: UIMessage) : AgentEvent

    /** 请求失败，即将重试；之后再收到 [MessageUpdated] 说明重试的请求已经返回内容 */
    data class Retrying(val attempt: RetryAttempt) : AgentEvent

    /** 运行结束，总是最后一个事件；出错时直接抛出异常，不会发出这个事件 */
    data class Finished(val message: UIMessage, val reason: AgentFinishReason) : AgentEvent
}

/** 本次运行结束的原因。 */
sealed interface AgentFinishReason {
    /** 模型最后一步没有再调用工具 */
    data object Completed : AgentFinishReason

    /** 有工具在等待用户审批；写入审批结果后，用这条消息再运行一次即可继续 */
    data class AwaitingApproval(val toolCallIds: Set<String>) : AgentFinishReason

    /** 达到 [AgentRequest.maxSteps]，最后一步调用的工具已经执行，但模型还没有回复 */
    data object StepLimitReached : AgentFinishReason

    /** 生成前预算判定结束本次运行（由 [AgentHooks.beforeStep] 返回 [StepDecision.Stop] 产生） */
    data class BudgetExhausted(val kind: Kind) : AgentFinishReason

    /** 预算种类，仅用于归因展示 */
    enum class Kind { WALL_CLOCK, TOKEN_BUDGET, LOOP_GUARD, MANUAL }
}

/**
 * 运行 agent 循环：请求模型 → 执行工具 → 再请求模型，直到模型不再调用工具、有工具需要审批或达到步数上限。
 *
 * 工具按调用顺序逐个执行，有任意一个需要审批时，同一批的工具都等审批后再一起执行。
 * 返回的 Flow 不切换调度器，Provider 请求和工具执行都在收集方的上下文里运行，需要时由调用方 flowOn。
 * 取消时直接抛出，最后一个 [AgentEvent.MessageUpdated] 就是取消前的状态，已经执行完的工具结果都在里面。
 */
fun runAgent(request: AgentRequest, hooks: AgentHooks): Flow<AgentEvent> = flow {
    require(request.maxSteps > 0) { "maxSteps must be positive" }
    AgentLoop(request, hooks, this).run()
}
