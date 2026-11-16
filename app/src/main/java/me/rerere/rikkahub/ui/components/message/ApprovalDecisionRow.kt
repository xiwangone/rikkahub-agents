package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Infinity01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R

/** 审批决策（与来源无关）：允许一次 / 本会话允许 / 总是允许 / 拒绝。 */
internal enum class ApprovalDecision { Once, ChatScope, Always, Deny }

/** 按钮内图标尺寸：够看清，又不挤压 5 字文案。 */
private val APPROVAL_ICON_SIZE = 14.dp

/**
 * 审批决策按钮组（**与来源解耦**）：本地工具卡与接入审批卡共用同一套按钮、尺寸与文案，
 * 避免两处各写一遍导致能力与外观漂移。
 *
 * 布局口径（两次迭代后的定稿）：
 * - **一行排开、等宽平分**（[Row] + `weight`），不用 `FlowRow` 自动换行 ——
 *   自动换行会让「拒绝」独占一行并在气泡里居中，很空；
 * - **按钮带图标 + 文字**：允许一次 ✓ / 本会话允许 💬 / 总是允许 ∞ / 拒绝 ✗；
 *   只有文字时四个同色按钮分不出主次，图标是最省宽度的区分手段；
 * - **按主次分色**：拒绝 = 轻量 TextButton + `error` 红字，本会话/总是允许 = FilledTonalButton，
 *   允许一次 = 主色 Button；
 * - 高度 40dp，字号 `labelSmall`（比 `labelMedium` 小一号），保证「本会话允许」这类 5 字文案
 *   在 1/4 宽度内连同图标一起放得下。
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
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            modifier = Modifier.weight(0.8f).height(40.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = null,
                modifier = Modifier.size(APPROVAL_ICON_SIZE),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.chat_message_tool_deny),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        FilledTonalButton(
            onClick = { if (enabled) onDecision(ApprovalDecision.ChatScope) },
            enabled = enabled,
            contentPadding = compactPadding,
            modifier = Modifier.weight(1.1f).height(40.dp),
        ) {
            Icon(
                imageVector = HugeIcons.BubbleChat,
                contentDescription = null,
                modifier = Modifier.size(APPROVAL_ICON_SIZE),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.chat_message_tool_approve_chat),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (showAlwaysAllow) {
            FilledTonalButton(
                onClick = { if (enabled) onDecision(ApprovalDecision.Always) },
                enabled = enabled,
                contentPadding = compactPadding,
                modifier = Modifier.weight(1.1f).height(40.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Infinity01,
                    contentDescription = null,
                    modifier = Modifier.size(APPROVAL_ICON_SIZE),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.chat_message_tool_approve_always),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Button(
            onClick = { if (enabled) onDecision(ApprovalDecision.Once) },
            enabled = enabled,
            contentPadding = compactPadding,
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.weight(1.1f).height(40.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Tick01,
                contentDescription = null,
                modifier = Modifier.size(APPROVAL_ICON_SIZE),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.chat_message_tool_approve_once),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
