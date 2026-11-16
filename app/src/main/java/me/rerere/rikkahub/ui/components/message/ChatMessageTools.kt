package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.isSecretKey
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ASK_USER_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.components.message.tools.DefaultToolPreview
import me.rerere.rikkahub.ui.components.message.tools.ToolStatusBadge
import me.rerere.rikkahub.ui.components.message.tools.ToolSummarySurface
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.message.tools.approvalScopeStringRes
import me.rerere.rikkahub.ui.components.message.tools.getErrorCode
import me.rerere.rikkahub.ui.components.message.tools.resolveToolStepStatus
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.LogRedactor
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

// Per-tool icon/title/summary/preview rendering now lives in ToolUIRegistry
// (see message/tools/BuiltinToolUIs.kt). This file only keeps the cross-cutting
// approval-card UI and the interactive ask_user flow, plus the small JSON helper
// they rely on.
private fun JsonElement?.getStringContent(key: String): String? =
    this
        ?.jsonObjectOrNull
        ?.get(key)
        ?.jsonPrimitiveOrNull
        ?.contentOrNull

private fun JsonElement?.getHardlineReasonCode(): String? =
    this
        ?.jsonObjectOrNull
        ?.get("data")
        ?.jsonObjectOrNull
        ?.get(HardlineCommandGuard.HARDLINE_REASON_CODE_FIELD)
        ?.jsonPrimitiveOrNull
        ?.contentOrNull

private fun hardlineReasonStringResource(code: String?): Int? = when (code) {
    "workspace_root_delete" -> R.string.chat_message_tool_hardline_reason_workspace_root_delete
    "root_filesystem_delete" -> R.string.chat_message_tool_hardline_reason_root_filesystem_delete
    "home_root_delete" -> R.string.chat_message_tool_hardline_reason_home_root_delete
    "system_directory_delete" -> R.string.chat_message_tool_hardline_reason_system_directory_delete
    "home_directory_delete" -> R.string.chat_message_tool_hardline_reason_home_directory_delete
    "filesystem_format" -> R.string.chat_message_tool_hardline_reason_filesystem_format
    "raw_block_device_dd" -> R.string.chat_message_tool_hardline_reason_raw_block_device_dd
    "raw_block_device_redirect" -> R.string.chat_message_tool_hardline_reason_raw_block_device_redirect
    "fork_bomb" -> R.string.chat_message_tool_hardline_reason_fork_bomb
    "kill_all_processes" -> R.string.chat_message_tool_hardline_reason_kill_all_processes
    "system_shutdown_reboot" -> R.string.chat_message_tool_hardline_reason_system_shutdown_reboot
    "init_zero_six" -> R.string.chat_message_tool_hardline_reason_init_zero_six
    "systemctl_shutdown_reboot" -> R.string.chat_message_tool_hardline_reason_systemctl_shutdown_reboot
    "telinit_zero_six" -> R.string.chat_message_tool_hardline_reason_telinit_zero_six
    "encoded_payload_to_shell" -> R.string.chat_message_tool_hardline_reason_encoded_payload_to_shell
    "hex_payload_to_shell" -> R.string.chat_message_tool_hardline_reason_hex_payload_to_shell
    "eval_command_substitution" -> R.string.chat_message_tool_hardline_reason_eval_command_substitution
    else -> null
}

/** 非 JSON 工具结果（例如截断提示）返回 null，交给默认渲染器显示原始文本。 */
internal fun parseToolOutputContent(tool: UIMessagePart.Tool): JsonElement? {
    if (!tool.isExecuted) return null
    return runCatching {
        JsonInstant.parseToJsonElement(
            // ⚠ 输出里可能混有审批来源标记行（`[approval: …]`）→ 必须先剥掉再整体解析：
            //   否则解析失败返回 null，会让**所有**已注册渲染器的 Preview / Summary 一起失效
            //   （带标记的调用会一律落到 DefaultToolPreview 的 JSON 详情）
            stripApprovalProvenance(
                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
            ),
        )
    }.getOrNull()
}

/**
 * 待审批摘要的最大字符数（JSON 部分，不含「工具名: 」前缀）。审批卡是决策界面，
 * 入参再长也不能把卡片撑开，超长截断加省略号。
 */
internal const val PENDING_TOOL_SUMMARY_MAX_CHARS = 200

/**
 * 按键名递归脱敏：键名命中敏感词（[isSecretKey]，裸 `token` 与短值也在内）时，
 * 该键的值一律替换为 `***`，嵌套对象与数组同样处理。审批卡是给人看的界面，
 * 凭证原文绝不能出现在这里。
 *
 * 只靠 [LogRedactor.maskText] 不够：它的键名表没有裸 `token`，且值短于 8 字符时不掩；
 * 按键名脱敏没有长度下限。编码后再过一次 maskText 做纵深防御
 * （嵌在字符串值里的 `?token=…` 这类形态）。
 */
private fun maskSensitiveArgs(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(
                element.mapValues { (key, value) ->
                    if (isSecretKey(key)) JsonPrimitive("***") else maskSensitiveArgs(value)
                },
            )
        is JsonArray -> JsonArray(element.map(::maskSensitiveArgs))
        else -> element
    }

/**
 * 待审批调用的「工具名 + 入参」一行摘要，显示在审批区顶部（标题行下方、按钮上方），
 * 让用户不用点开展开就能看到这次调用要干什么。
 *
 * 渲染器的 Summary 描述的是工具输出，待审批调用尚无输出，故直接展示入参。
 * 入参先按 JSON 键名脱敏、再过自由文本脱敏；超长截断。非法 JSON 时 inputAsJson
 * 返回空对象，摘要退化为「工具名: {}」。
 */
internal fun pendingToolSummaryLine(tool: UIMessagePart.Tool): String {
    val masked = maskSensitiveArgs(tool.inputAsJson())
    val text =
        LogRedactor.maskText(
            runCatching { JsonInstant.encodeToString(masked) }.getOrNull().orEmpty(),
        )
    val truncated =
        if (text.length > PENDING_TOOL_SUMMARY_MAX_CHARS) {
            text.take(PENDING_TOOL_SUMMARY_MAX_CHARS) + "…"
        } else {
            text
        }
    return "${tool.toolName}: $truncated"
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: (
        (
            toolCallId: String,
            approved: Boolean,
            reason: String,
            scope: me.rerere.rikkahub.service.ChatService.ApprovalScope,
            toolName: String,
        ) -> Unit
    )? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val parsedContent = remember(tool) { parseToolOutputContent(tool) }
    val outputHardlineReason = parsedContent.getHardlineReasonCode()
    val outputHardlineReasonRes = hardlineReasonStringResource(outputHardlineReason)
    val localizedOutputHardlineMessage = outputHardlineReasonRes?.let { reasonRes ->
        stringResource(R.string.chat_message_tool_hardline_blocked, stringResource(reasonRes))
    }
    val displayContent = remember(parsedContent, localizedOutputHardlineMessage) {
        if (localizedOutputHardlineMessage == null || parsedContent !is JsonObject) {
            parsedContent
        } else {
            JsonObject(parsedContent + ("message" to JsonPrimitive(localizedOutputHardlineMessage)))
        }
    }
    // 输出不是 JSON（例如超长被截断后只剩文本预览）时，定制渲染器读不到任何字段，
    // 详情改用默认渲染展示原文
    val outputUnparsable = remember(tool, parsedContent) { tool.isExecuted && parsedContent == null }
    val context =
        remember(tool, loading, displayContent) {
            ToolUIContext(
                tool = tool,
                arguments = tool.inputAsJson(),
                content = displayContent,
                loading = loading,
            )
        }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    // Text parts nested in tool.output (e.g. run_js's webview payload) that carry
    // rikkahub.webview metadata never reach the top-level Text rendering branch in
    // ChatMessage.kt, since tool output is always folded into this step. Surface them
    // here so skill webview cards render (#38).
    val webviewParts =
        tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .filter { it.hasSkillWebviewMeta() }

    // 诊断（「审批停住但无按钮」排查）：渲染侧看到的审批状态与回调可用性。
    // 只有它和 GenLoop 的 approval-check 一起看，才能区分「状态没到 UI」与「到了但回调为空」。
    // 降噪：key 只保留影响「按钮是否出现」的量，同一状态下的重复重组不再重复打印；
    // 且只有进入「待审批」这种需要人工介入的状态才进重要日志。
    LaunchedEffect(tool.toolCallId, isPending, onToolApproval != null) {
        val line =
            "render ${tool.toolName} id=${tool.toolCallId} isPending=$isPending " +
                "state=${tool.approvalState} executed=${tool.isExecuted} hasCallback=${onToolApproval != null}"
        if (isPending) AppLog.i("ToolApprovalUI", line) else AppLog.d("ToolApprovalUI", line)
    }

    // Summary detection is delegated to the registered renderer; image output, webview
    // cards and denial reasons are common to all tools.
    val hasExtraContent =
        renderer.hasSummary(context) || isDenied || images.isNotEmpty() || webviewParts.isNotEmpty()
    val approvedScope = (tool.approvalState as? ToolApprovalState.Approved)?.scope
    val stepStatus =
        resolveToolStepStatus(
            isPending = isPending,
            isDenied = isDenied,
            isExecuted = tool.isExecuted,
            success = context.content.getStringContent("success")?.toBooleanStrictOrNull(),
            exitCode = context.content.getStringContent("exit_code") ?: context.content.getStringContent("exitCode"),
            hasError = context.content.getErrorCode()?.let { it.isNotBlank() && !it.equals("false", ignoreCase = true) } == true,
            isInterruptedAttempt = tool.isInterruptedAttempt && !loading,
            approvedScope = approvedScope,
        )

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp,
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = renderer.iconColor(context),
                )
            }
        },
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = renderer.title(context),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f).shimmer(isLoading = loading),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolStatusBadge(status = stepStatus)
            }
        },
        extra =
            if (hasExtraContent) {
                {
                    // 折叠切换钮（小 chevron）：独立于标题行点击（开结果弹窗）。
                    // 内层 clickable 吃掉点击事件，点它只切换 expanded，不触发外层的 onClick。
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable { expanded = !expanded },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = stringResource(if (expanded) R.string.code_block_collapse else R.string.code_block_expand),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                null
            },
        belowTitle =
            if (isPending && onToolApproval != null) {
                {
                    // Per-row in-flight flag to debounce double-taps. Without this, two rapid
                    // clicks on Approve fire handleToolApproval twice — the second cancel()s
                    // the first's resume coroutine mid-flight, wastes the in-flight gen step,
                    // and can race the persisted state mutation. Keyed on toolCallId so a
                    // recomposition for a different tool doesn't carry the flag.
                    var inFlight by remember(tool.toolCallId) { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val isScheduleJob =
                            tool.toolName in setOf("schedule_job", "schedule_job_direct", "schedule_job_llm")
                        val mcpRendered =
                            if (tool.toolName.startsWith("mcp_")) {
                                runCatching {
                                    (tool.inputAsJson() as? JsonObject)?.let {
                                        me.rerere.rikkahub.data.ai.mcp.control.McpApprovalRenderer
                                            .render(tool.toolName, it)
                                    }
                                }.getOrNull()
                            } else {
                                null
                            }
                        val workflowRendered =
                            if (me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer
                                    .isWorkflowTool(tool.toolName) &&
                                tool.toolName !in setOf("workflow_list", "workflow_get")
                            ) {
                                runCatching {
                                    me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer
                                        .renderPlain(tool.toolName, tool.input.ifBlank { "{}" })
                                }.getOrNull()
                            } else {
                                null
                            }
                        // 通用工具没有专用审批体时，补一行「工具名 + 入参」摘要（折叠态也可见），
                        // 让用户不用点开展开就能看到这次调用要干什么。
                        // 已有专用体的工具（schedule_job / mcp_ / workflow_*）用专用体，不再补通用摘要。
                        val showGenericSummary =
                            !isScheduleJob && mcpRendered.isNullOrBlank() && workflowRendered.isNullOrBlank()
                        if (showGenericSummary) {
                            Text(
                                text = pendingToolSummaryLine(tool),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // schedule_job family is the one approval that AUTHORISES future autonomous
                        // execution, not just one tool. Surface the consequence here so the
                        // user knows what they're approving — every tool the cron prompt
                        // invokes will run without prompts. (HARDLINE blocks still apply.)
                        if (isScheduleJob) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_schedule_job_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            // Surface mode-specific detail so the user sees WHAT will run.
                            // Split tools fix the mode in the name; legacy schedule_job carries it in input.
                            val jobInput = tool.inputAsJson()
                            val mode = jobInput.getStringContent("mode")
                                ?: when (tool.toolName) {
                                    "schedule_job_direct" -> "direct"
                                    "schedule_job_llm" -> "llm"
                                    else -> null
                                }
                            if (mode == "direct") {
                                val actions =
                                    runCatching {
                                        (jobInput as? JsonObject)
                                            ?.get("actions")
                                            ?.jsonArray
                                    }.getOrNull()
                                if (actions != null) {
                                    Column {
                                        actions.forEachIndexed { i, el ->
                                            val obj = el as? JsonObject
                                            val toolName = obj?.get("tool")?.jsonPrimitive?.contentOrNull ?: "?"
                                            val args = obj?.get("args")?.toString().orEmpty()
                                            val truncatedArgs = if (args.length > 120) args.take(120) + "…" else args
                                            Text(
                                                text = "  ${i + 1}. $toolName $truncatedArgs",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else if (mode == "llm") {
                                val prompt = jobInput.getStringContent("prompt").orEmpty()
                                if (prompt.isNotEmpty()) {
                                    val truncatedPrompt = if (prompt.length > 200) prompt.take(200) + "…" else prompt
                                    Text(
                                        text = truncatedPrompt,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // MCP control tools: render args via the redacting helper so headers
                        // like Authorization / X-Api-Key never appear plainly in the approval
                        // card. Generic args display would leak them (audit finding).
                        if (!mcpRendered.isNullOrBlank()) {
                            Text(
                                text = mcpRendered,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Workflow_* mutators: human-readable approval body ("Create workflow X /
                        // When: WiFi connects to HomeWiFi / Do: 1. ssh_exec_saved(host=home) / …").
                        // Action arg values whose key is in the secret-redaction list are masked.
                        if (!workflowRendered.isNullOrBlank()) {
                            Text(
                                text = workflowRendered,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Four-button row: Allow / Always Allow / Allow for this chat / Deny.
                        // Order matches the Telegram inline-keyboard layout so the user sees the
                        // same mental model on both surfaces.
                        // Tools listed in ToolApprovalDefaults.NO_ALWAYS_ALLOW (e.g. mcp_add /
                        // mcp_update — adding an MCP server is a privilege-escalation surface)
                        // drop the Always-Allow button so each call requires fresh confirmation.
                        val allowAlwaysButton =
                            me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
                                .allowsAlwaysAllow(tool.toolName)
                        ApprovalDecisionRow(
                            inFlight = inFlight,
                            showAlwaysAllow = allowAlwaysButton,
                            onDecision = { decision ->
                                when (decision) {
                                    ApprovalDecision.Deny -> showDenyDialog = true
                                    ApprovalDecision.Once -> {
                                        inFlight = true
                                        onToolApproval(
                                            tool.toolCallId,
                                            true,
                                            "",
                                            me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                                            tool.toolName,
                                        )
                                    }
                                    ApprovalDecision.Always -> {
                                        inFlight = true
                                        onToolApproval(
                                            tool.toolCallId,
                                            true,
                                            "",
                                            me.rerere.rikkahub.service.ChatService.ApprovalScope.Always,
                                            tool.toolName,
                                        )
                                    }
                                    ApprovalDecision.ChatScope -> {
                                        inFlight = true
                                        onToolApproval(
                                            tool.toolCallId,
                                            true,
                                            "",
                                            me.rerere.rikkahub.service.ChatService.ApprovalScope.ChatScope,
                                            tool.toolName,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            } else {
                null
            },
        onClick = { showResult = true },
        content =
            if (hasExtraContent) {
                {
                    ToolSummarySurface {
                        // 批准后已执行：徽标已按执行结果显示，详情里留一行当时批的范围。
                        if (approvedScope != null && tool.isExecuted) {
                            Text(
                                text = stringResource(
                                    R.string.chat_message_tool_approved_scope,
                                    stringResource(approvalScopeStringRes(approvedScope)),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        renderer.Summary(context)
                        webviewParts.forEach { webviewPart ->
                            SkillWebviewCardOrNull(part = webviewPart)
                        }
                        if (images.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.wrapContentWidth(),
                            ) {
                                items(images, key = { it.url }) { image ->
                                    ZoomableAsyncImage(
                                        model = image.url,
                                        contentDescription = null,
                                        modifier =
                                            Modifier
                                                .height(64.dp)
                                                .wrapContentWidth(),
                                    )
                                }
                            }
                        }
                        if (isDenied) {
                            val storedReason = (tool.approvalState as ToolApprovalState.Denied).reason
                            val hardlineCode = HardlineCommandGuard.uiReasonCodeFromEncoded(storedReason)
                            val hardlineReasonRes = hardlineReasonStringResource(hardlineCode)
                            val reason = if (hardlineReasonRes != null) {
                                stringResource(
                                    R.string.chat_message_tool_hardline_blocked,
                                    stringResource(hardlineReasonRes),
                                )
                            } else {
                                storedReason
                            }
                            Text(
                                text =
                                    stringResource(R.string.chat_message_tool_denied) +
                                        if (reason.isNotBlank()) ": $reason" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                null
            },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(
                    tool.toolCallId,
                    false,
                    reason,
                    me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                    tool.toolName,
                )
            },
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            onDismissRequest = { showResult = false },
            content = {
                if (outputUnparsable) {
                    DefaultToolPreview(context = context, title = renderer.title(context))
                } else {
                    renderer.Preview(
                        context = context,
                        onDismissRequest = { showResult = false },
                    )
                }
            },
        )
    }
}

@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // 诊断（同审批排查）：ask_user 走独立渲染分支，需单独记录其审批状态与回调可用性。
    // 降噪同审批卡：key 去掉 approvalState，且只有「待回答」才进重要日志。
    LaunchedEffect(tool.toolCallId, isPending, onToolAnswer != null) {
        val line =
            "ask_user id=${tool.toolCallId} isPending=$isPending state=${tool.approvalState} " +
                "hasAnswerCallback=${onToolAnswer != null}"
        if (isPending) AppLog.i("ToolApprovalUI", line) else AppLog.d("ToolApprovalUI", line)
    }

    // 解析层：把工具参数归一成中性提问模型，渲染层不再认工具参数结构。
    val questions = remember(arguments) { parseAskToolQuestions(arguments) }

    // 已回答态回显用户答案：工具把答案放在 approvalState.answer 的 {"answers":{id:值}} 里。
    val answeredLabels =
        remember(tool.approvalState) {
            val state = tool.approvalState
            if (state !is ToolApprovalState.Answered) {
                emptyMap()
            } else {
                runCatching {
                    JsonInstant
                        .parseToJsonElement(state.answer)
                        .jsonObject["answers"]
                        ?.jsonObject
                        ?.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull ?: "" }
                }.getOrNull().orEmpty()
            }
        }

    val askStatus =
        when {
            isAnswered -> AskPromptStatus.Answered
            isPending && onToolAnswer != null -> AskPromptStatus.Pending
            else -> AskPromptStatus.Stale
        }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text =
                    if (questions.size <= 1) {
                        firstQuestion
                    } else {
                        stringResource(
                            R.string.chat_message_tool_ask_questions,
                            questions.size,
                        )
                    },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            AskPromptCard(
                questions = questions,
                status = askStatus,
                answeredLabels = answeredLabels,
                onAnswers = { answers ->
                    onToolAnswer?.invoke(tool.toolCallId, buildAskAnswersJson(answers))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
