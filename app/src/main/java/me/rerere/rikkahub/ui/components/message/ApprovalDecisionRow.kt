package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * 布局口径（重排过一次，原 FlowRow 自动换行会让「拒绝」独占一行并在气泡里居中，很空）：
 * - **一行排开、等宽平分**（[Row] + `weight`），不再使用 `FlowRow` 自动换行；
 * - **按主次分色**：拒绝 = 轻量 TextButton，本会话/总是允许 = FilledTonalButton，允许一次 = 主色 Button
 *   —— 四个按钮同色时看不出该点哪个；
 * - 高度 40dp（仍在可点范围内，比 48dp 省一圈），字号 `labelMedium` 保证「本会话允许」这类 5 字文案
 *   在 1/4 宽度内不换行。
 *
 * - [showAlwaysAllow]：是否提供「总是允许」。本地工具按白名单收紧（如 MCP 配置类工具），
 *   接入侧按服务端协议能力决定；
 * - 「拒绝」是否需要先输入理由，由调用方在回调里处理。
 */
@Composable
internal fun ApprovalDecisionRow(
    inFlight: Boolean,
    onDecision: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
    showAlwaysAllow: Boolean = true,
) {
    val enabled = !inFlight
    val compactPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(
            onClick = { if (enabled) onDecision(ApprovalDecision.Deny) },
            enabled = enabled,
            contentPadding = compactPadding,
            modifier = Modifier.weight(0.8f).height(40.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_deny),
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        FilledTonalButton(
            onClick = { if (enabled) onDecision(ApprovalDecision.ChatScope) },
            enabled = enabled,
            contentPadding = compactPadding,
            modifier = Modifier.weight(1.1f).height(40.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_approve_chat),
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (showAlwaysAllow) {
            FilledTonalButton(
                onClick = { if (enabled) onDecision(ApprovalDecision.Always) },
                enabled = enabled,
                contentPadding = compactPadding,
                modifier = Modifier.weight(1.1f).height(40.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_message_tool_approve_always),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Button(
            onClick = { if (enabled) onDecision(ApprovalDecision.Once) },
            enabled = enabled,
            contentPadding = compactPadding,
            modifier = Modifier.weight(1.1f).height(40.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_approve_once),
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
