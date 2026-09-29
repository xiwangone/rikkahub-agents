package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R

/** 审批决策（与来源无关）：允许一次 / 本会话允许 / 总是允许 / 拒绝。 */
internal enum class ApprovalDecision { Once, ChatScope, Always, Deny }

/**
 * 审批决策按钮组（**与来源解耦**）：本地工具卡与接入审批卡共用同一套按钮、尺寸与图标，
 * 避免两处各写一遍导致能力与外观漂移。
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
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(
            onClick = { if (!inFlight) onDecision(ApprovalDecision.Once) },
            enabled = !inFlight,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Tick01,
                contentDescription = stringResource(R.string.chat_message_tool_approve),
                modifier = Modifier.size(14.dp),
            )
        }
        if (showAlwaysAllow) {
            FilledTonalIconButton(
                onClick = { if (!inFlight) onDecision(ApprovalDecision.Always) },
                enabled = !inFlight,
                modifier = Modifier.size(28.dp),
            ) {
                Text("∞", style = MaterialTheme.typography.labelMedium)
            }
        }
        FilledTonalIconButton(
            onClick = { if (!inFlight) onDecision(ApprovalDecision.ChatScope) },
            enabled = !inFlight,
            modifier = Modifier.size(28.dp),
        ) {
            Text("💬", style = MaterialTheme.typography.labelSmall)
        }
        FilledTonalIconButton(
            onClick = { if (!inFlight) onDecision(ApprovalDecision.Deny) },
            enabled = !inFlight,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = stringResource(R.string.chat_message_tool_deny),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
