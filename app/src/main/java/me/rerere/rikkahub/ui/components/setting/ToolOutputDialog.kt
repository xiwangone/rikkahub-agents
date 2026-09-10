package me.rerere.rikkahub.ui.components.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

@Composable
fun ToolOutputDialog(
    enabled: Boolean,
    maxCharsKB: Int,
    onDismiss: () -> Unit,
    onConfirm: (enabled: Boolean, maxCharsKB: Int) -> Unit,
) {
    var currentEnabled by remember { mutableIntStateOf(if (enabled) 1 else 0) }
    // 以 String 保存输入值（而非 Float/Int），支持自由删除/编辑，避免 5→50→500 追加问题
    var currentKB by remember { mutableStateOf(maxCharsKB.toString()) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(stringResource(R.string.setting_model_page_tool_output))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_output_limit_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_model_page_tool_output),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = currentEnabled == 1,
                        onCheckedChange = { currentEnabled = if (it) 1 else 0 },
                    )
                }

                if (currentEnabled == 1) {
                    OutlinedTextField(
                        value = currentKB,
                        onValueChange = { currentKB = it },
                        label = { Text(stringResource(R.string.setting_model_page_tool_output_max_chars)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Text(
                    text = stringResource(R.string.tool_output_limit_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 空输入/非法输入回退默认 5KB；合法输入钳制在 1-20
                    onConfirm(currentEnabled == 1, currentKB.toIntOrNull()?.coerceIn(1, 20) ?: 5)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
