package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/** 审批决策（与来源无关）：允许一次 / 本会话允许 / 总是允许 / 拒绝。 */
internal enum class ApprovalDecision { Once, ChatScope, Always, Deny }

/**
 * 审批决策按钮组（**与来源解耦**）：本地工具卡与接入审批卡共用同一套按钮、尺寸与文案，
 * 避免两处各写一遍导致能力与外观漂移。
 *
 * - 按钮带文字（不再是 28dp 纯图标钮）：48dp 高，一行排不下自动换行；
 * - [showAlwaysAllow]：是否提供「总是允许」。本地工具按白名单收紧（如 MCP 配置类工具），
 *   接入侧按服务端协议能力决定；
 * - 「拒绝」是否需要先输入理由，由调用方在回调里处理。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalDecisionRow(
    inFlight: Boolean,
    onDecision: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
    showAlwaysAllow: Boolean = true,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { if (!inFlight) onDecision(ApprovalDecision.Once) },
            enabled = !inFlight,
            modifier = Modifier.height(48.dp),
        ) {
            Text(stringResource(R.string.chat_message_tool_approve_once))
        }
        if (showAlwaysAllow) {
            FilledTonalButton(
                onClick = { if (!inFlight) onDecision(ApprovalDecision.Always) },
                enabled = !inFlight,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.chat_message_tool_approve_always))
            }
        }
        FilledTonalButton(
            onClick = { if (!inFlight) onDecision(ApprovalDecision.ChatScope) },
            enabled = !inFlight,
            modifier = Modifier.height(48.dp),
        ) {
            Text(stringResource(R.string.chat_message_tool_approve_chat))
        }
        FilledTonalButton(
            onClick = { if (!inFlight) onDecision(ApprovalDecision.Deny) },
            enabled = !inFlight,
            modifier = Modifier.height(48.dp),
        ) {
            Text(stringResource(R.string.chat_message_tool_deny))
        }
    }
}
