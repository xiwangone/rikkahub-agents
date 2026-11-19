package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 终端扩展键栏，工作区终端与 SSH 主机终端共用。
 *
 * 两页的键位与行为完全一致，只有底色不同：工作区页的栏在 surfaceContainerLow
 * 的通栏上，SSH 页直接落在页面底色上——底色由调用方经 [modifier] 传入。
 */
@Composable
internal fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .then(modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        // 触屏上最高频的三个控制键：中断 / EOF / 清屏。有了直发键就不必「先按 CTRL 再点字母」，
        // 长按拖动选词等操作也不会被 CTRL 粘滞态干扰。
        TerminalExtraKey("⌃C") { onSendText("\u0003") }
        TerminalExtraKey("⌃D") { onSendText("\u0004") }
        TerminalExtraKey("⌃L") { onSendText("\u000C") }
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
    )
}
