package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.backend.BackendInteractionNotifier.InteractionDelivery
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.AskQuestion
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * 后端连接路径的消息内交互组件（审批卡 / 提问卡）。
 *
 * 与通用消息渲染组件分文件维护，避免两侧改动互相影响；
 * 文案使用独立的键名前缀。
 */

/**
 * 服务端审批卡：展示待审批工具 + 批准/拒绝。
 *
 * 应答优先走 BackendInteractionNotifier（与通知栏按钮同一通道，即 BackendApi.approve）；
 * 若该请求不在此通道中（非后端直连场景），回退到传入的通用回调。
 */
@Composable
internal fun BackendApprovalCard(
    requestId: String,
    tool: String,
    subject: String?,
    onToolApproval: (
        (
            toolCallId: String,
            approved: Boolean,
            reason: String,
            scope: me.rerere.rikkahub.service.ChatService.ApprovalScope,
            toolName: String,
        ) -> Unit
    )?,
) {
    val notifier: me.rerere.rikkahub.data.ai.backend.BackendInteractionNotifier = org.koin.compose.koinInject()
    // 与通知栏状态同步：曾列入待办、现已不在集合 → 说明已在别处（通知栏）处理
    val pendingIds by notifier.pendingIds.collectAsStateWithLifecycle()
    var everPending by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(pendingIds) { if (requestId in pendingIds) everPending = true }
    val approveDone = stringResource(R.string.backend_approval_approved)
    val denyDone = stringResource(R.string.backend_approval_denied)
    val staleHint = stringResource(R.string.backend_approval_stale)
    val deliveryFailedHint = stringResource(R.string.backend_approval_delivery_failed)
    val scope = rememberCoroutineScope()
    var inFlight by remember(requestId) { mutableStateOf(false) }
    var resolved by remember(requestId) { mutableStateOf(false) }
    val handledElsewhere = everPending && requestId !in pendingIds && !resolved
    var feedback by remember(requestId) { mutableStateOf<String?>(null) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(0.9f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.backend_pending_approval, tool, subject?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
            )
            // 按钮样式与对话内工具审批保持一致（同组件、同尺寸、同图标）
            ApprovalDecisionRow(
                inFlight = inFlight || resolved,
                onDecision = { decision ->
                    if (!(inFlight || resolved)) {
                        inFlight = true
                        val approved = decision != ApprovalDecision.Deny
                        // 决策 → 应答参数：本会话允许用 session，总是允许再加 persist
                        val session =
                            decision == ApprovalDecision.ChatScope || decision == ApprovalDecision.Always
                        val persist = decision == ApprovalDecision.Always
                        // 先等投递结果再定状态：投递失败时请求仍在待处理，按钮保持可用可重试，
                        // 只有确认不在待处理时才回退到通用审批回调。
                        scope.launch {
                            when (
                                notifier.approveById(
                                    requestId,
                                    approved,
                                    session = session,
                                    persist = persist,
                                )
                            ) {
                                InteractionDelivery.DELIVERED -> {
                                    resolved = true
                                    feedback = if (approved) approveDone else denyDone
                                }

                                InteractionDelivery.NOT_PENDING -> {
                                    onToolApproval?.invoke(
                                        requestId,
                                        approved,
                                        "",
                                        when (decision) {
                                            ApprovalDecision.Always ->
                                                me.rerere.rikkahub.service.ChatService.ApprovalScope.Always
                                            ApprovalDecision.ChatScope ->
                                                me.rerere.rikkahub.service.ChatService.ApprovalScope.ChatScope
                                            else -> me.rerere.rikkahub.service.ChatService.ApprovalScope.Once
                                        },
                                        tool,
                                    )
                                    feedback = staleHint
                                }

                                InteractionDelivery.FAILED -> {
                                    feedback = deliveryFailedHint
                                }
                            }
                            inFlight = false
                        }
                    }
                },
            )
            (feedback ?: if (handledElsewhere) staleHint else null)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 服务端提问卡：渲染问题与选项（样式与对话内提问一致），提交后给出回执。
 */
@Composable
internal fun BackendAskCard(
    requestId: String,
    questions: List<me.rerere.ai.ui.AskQuestion>,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val notifier: me.rerere.rikkahub.data.ai.backend.BackendInteractionNotifier = koinInject()
    val pendingIds by notifier.pendingIds.collectAsStateWithLifecycle()
    var everPending by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(pendingIds) { if (requestId in pendingIds) everPending = true }
    var submitted by remember(requestId) { mutableStateOf(false) }
    var answeredLabel by remember(requestId) { mutableStateOf<String?>(null) }
    var feedback by remember(requestId) { mutableStateOf<String?>(null) }
    val answeredText = stringResource(R.string.backend_ask_submitted)
    val staleText = stringResource(R.string.backend_approval_stale)
    val deliveryFailedHint = stringResource(R.string.backend_approval_delivery_failed)
    val scope = rememberCoroutineScope()
    val handledElsewhere = everPending && requestId !in pendingIds && !submitted

    // 接入 → 中性模型：服务端问题的字段归一到共用提问模型，渲染交给 AskPromptCard
    val promptQuestions = remember(questions) { parseBackendAskQuestions(questions) }
    val answeredLabels =
        answeredLabel
            ?.let { label -> promptQuestions.associate { it.id to label } }
            .orEmpty()
    val status =
        when {
            submitted -> AskPromptStatus.Answered
            handledElsewhere -> AskPromptStatus.Stale
            else -> AskPromptStatus.Pending
        }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(0.9f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.backend_pending_reply),
                style = MaterialTheme.typography.labelMedium,
            )
            AskPromptCard(
                questions = promptQuestions,
                status = status,
                answeredLabels = answeredLabels,
                statusText = feedback ?: if (handledElsewhere) staleText else null,
                // 点选即答：与接入既有交互一致（服务端按单条作答）
                submitMode = AskPromptSubmitMode.Immediate,
                onAnswers = { answers ->
                    val label = answers.values.firstOrNull().orEmpty()
                    // 先等投递结果再定状态：投递失败时请求仍在待处理，不置已提交、用户可重试。
                    scope.launch {
                        when (notifier.answerById(requestId, label)) {
                            InteractionDelivery.DELIVERED -> {
                                submitted = true
                                answeredLabel = label
                                feedback = answeredText
                            }

                            InteractionDelivery.NOT_PENDING -> {
                                submitted = true
                                answeredLabel = label
                                onToolAnswer?.invoke(requestId, label)
                                feedback = staleText
                            }

                            InteractionDelivery.FAILED -> {
                                feedback = deliveryFailedHint
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * 服务端工具的折叠 step —— Backend 直连产生的 [UIMessagePart.ServerTool] 以链式
 * 思考块呈现：默认折叠，点击展开查看输入/输出。与思考卡片统一收纳在
 * Chain-of-Thought 折叠区内，避免与正文平铺混杂。
 */
@Composable
internal fun ChainOfThoughtScope.ChatMessageServerToolStep(
    tool: UIMessagePart.ServerTool,
    loading: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val statusText =
        when (tool.status) {
            ServerToolStatus.IN_PROGRESS ->
                stringResource(R.string.chat_server_tool_in_progress)
            ServerToolStatus.COMPLETED ->
                stringResource(R.string.chat_server_tool_completed)
            ServerToolStatus.FAILED ->
                stringResource(R.string.chat_server_tool_failed)
        }
    val inputText =
        tool.input?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
        }
    val outputText =
        tool.output?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
        }
    val title = tool.toolName.ifBlank { stringResource(R.string.chat_server_tool_title) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            Icon(
                imageVector = HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
            )
        },
        contentVisible = expanded,
        content = {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!inputText.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.chat_server_tool_input, inputText.take(200)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!outputText.isNullOrBlank() && tool.isFinished) {
                    Text(
                        text = stringResource(R.string.chat_server_tool_output, outputText.take(300)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
    )
}
