package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import org.koin.compose.koinInject
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.AskQuestion
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

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
    val pendingIds by notifier.pendingIds.collectAsState()
    var everPending by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(pendingIds) { if (requestId in pendingIds) everPending = true }
    val approveDone = stringResource(R.string.backend_approval_approved)
    val denyDone = stringResource(R.string.backend_approval_denied)
    val staleHint = stringResource(R.string.backend_approval_stale)
    var inFlight by remember(requestId) { mutableStateOf(false) }
    var resolved by remember(requestId) { mutableStateOf(false) }
    val handledElsewhere = everPending && requestId !in pendingIds && !resolved
    var feedback by remember(requestId) { mutableStateOf<String?>(null) }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(
                    onClick = {
                        if (inFlight || resolved) return@FilledTonalIconButton
                        inFlight = true
                        val ok = notifier.approveById(requestId, true)
                        resolved = ok
                        if (ok) {
                            feedback = approveDone
                        } else {
                            onToolApproval?.invoke(
                                requestId,
                                true,
                                "",
                                me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                                tool,
                            )
                            feedback = staleHint
                        }
                        inFlight = false
                    },
                    enabled = !inFlight && !resolved,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = stringResource(R.string.chat_message_tool_approve),
                        modifier = Modifier.size(14.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        if (inFlight || resolved) return@FilledTonalIconButton
                        inFlight = true
                        val ok = notifier.approveById(requestId, true)
                        resolved = ok
                        feedback = if (ok) approveDone else staleHint
                        if (!ok) {
                            onToolApproval?.invoke(
                                requestId,
                                true,
                                "",
                                me.rerere.rikkahub.service.ChatService.ApprovalScope.Always,
                                tool,
                            )
                        }
                        inFlight = false
                    },
                    enabled = !inFlight && !resolved,
                    modifier = Modifier.size(28.dp),
                ) {
                    Text("\u221e", style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalIconButton(
                    onClick = {
                        if (inFlight || resolved) return@FilledTonalIconButton
                        inFlight = true
                        val ok = notifier.approveById(requestId, false)
                        resolved = ok
                        feedback = if (ok) denyDone else staleHint
                        if (!ok) {
                            onToolApproval?.invoke(
                                requestId,
                                false,
                                "",
                                me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                                tool,
                            )
                        }
                        inFlight = false
                    },
                    enabled = !inFlight && !resolved,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.chat_message_tool_deny),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
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
    val pendingIds by notifier.pendingIds.collectAsState()
    var everPending by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(pendingIds) { if (requestId in pendingIds) everPending = true }
    var submitted by remember(requestId) { mutableStateOf(false) }
    var feedback by remember(requestId) { mutableStateOf<String?>(null) }
    val answeredText = stringResource(R.string.backend_ask_submitted)
    val staleText = stringResource(R.string.backend_approval_stale)
    val handledElsewhere = everPending && requestId !in pendingIds && !submitted
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
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
            questions.forEach { q ->
                Text(
                    text = q.prompt,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (q.options.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        q.options.forEach { opt ->
                            FilterChip(
                                selected = feedback == answeredText,
                                enabled = !submitted,
                                onClick = {
                                    submitted = true
                                    val ok = notifier.answerById(requestId, opt.label)
                                    if (!ok) onToolAnswer?.invoke(requestId, opt.label)
                                    feedback = if (ok) answeredText else staleText
                                },
                                label = {
                                    Text(opt.label, style = MaterialTheme.typography.labelSmall)
                                },
                            )
                        }
                    }
                }
            }
            (feedback ?: if (handledElsewhere) staleText else null)?.let {
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
