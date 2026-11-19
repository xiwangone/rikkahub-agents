package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * 终端会话结束后的底部提示行：「已退出」小标签 + 「重新打开」按钮。
 *
 * SSH 主机终端页与工作区终端页共用：两页的提示行结构逐字相同，只有文案
 * 的字符串键与重开回调不同。共用后仍各自传自己的字符串资源键，避免两页
 * 文案漂移。
 */
@Composable
fun BoxScope.TerminalExitRow(
    exitedText: String,
    reopenText: String,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 2.dp,
        ) {
            Text(
                text = exitedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        TextButton(onClick = onReopen) {
            Text(reopenText)
        }
    }
}

/** SSH 主机终端页的退出提示行：取 ssh_terminal_* 字符串。 */
@Composable
fun BoxScope.SshTerminalExitRow(
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TerminalExitRow(
        exitedText = stringResource(R.string.ssh_terminal_exited),
        reopenText = stringResource(R.string.ssh_terminal_reopen),
        onReopen = onReopen,
        modifier = modifier,
    )
}

/** 工作区终端页的退出提示行：取 workspace_terminal_* 字符串。 */
@Composable
fun BoxScope.WorkspaceTerminalExitRow(
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TerminalExitRow(
        exitedText = stringResource(R.string.workspace_terminal_exited),
        reopenText = stringResource(R.string.workspace_terminal_reopen),
        onReopen = onReopen,
        modifier = modifier,
    )
}
