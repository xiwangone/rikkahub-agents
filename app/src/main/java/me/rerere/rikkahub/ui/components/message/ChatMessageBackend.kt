package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * 接入类（Backend 直连路径）专用的消息内交互组件。
 *
 * ⚠️ 隔离约定（见 真源/规划/远程能力令牌-20260912.md §十三）：
 * 本文件内组件**仅供接入类服务使用**，与原生云端 AI 的渲染组件物理分离；
 * 文案一律用 `backend_*` 前缀，避免与原生 `chat_msg_*` 文案互相影响。
 */

/**
 * Backend 直连路径的服务端审批卡：展示待审批工具 + 批准/拒绝按钮。
 * 复用 [ChatMessage] 传入的 onToolApproval 回调（toolCallId = 服务端 requestId）。
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
    var inFlight by remember(requestId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.backend_pending_approval, tool, subject?.let { "\n$it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !inFlight,
                onClick = {
                    inFlight = true
                    onToolApproval?.invoke(
                        requestId,
                        true,
                        "",
                        me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                        tool,
                    )
                },
            ) {
                Text(stringResource(R.string.chat_message_tool_approve))
            }
            TextButton(
                enabled = !inFlight,
                onClick = {
                    inFlight = true
                    onToolApproval?.invoke(
                        requestId,
                        false,
                        "",
                        me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                        tool,
                    )
                },
            ) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        }
    }
}

/**
 * Backend 直连路径的服务端提问卡：渲染每个问题的选项/文本输入 + 提交。
 * 复用 [ChatMessage] 传入的 onToolAnswer 回调（toolCallId = 服务端 requestId）。
 */
@Composable
internal fun BackendAskCard(
    requestId: String,
    questions: List<me.rerere.ai.ui.AskQuestion>,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    var submitted by remember(requestId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    q.options.forEach { opt ->
                        TextButton(
                            enabled = !submitted,
                            onClick = {
                                submitted = true
                                onToolAnswer?.invoke(requestId, opt.label)
                            },
                        ) {
                            Text(opt.label)
                        }
                    }
                }
            }
        }
    }
}
