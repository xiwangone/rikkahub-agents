package me.rerere.rikkahub.ui.components.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R

/**
 * 统一的「网段访问控制」输入：输入框 + 常用网段预设（点选填入 / 删除 / 保存当前）。
 *
 * 三处共用同一套预设（`Settings.networkCidrPresets`）——Web 服务器监听范围、调试接口自定义
 * 网段、本地 MCP 服务器白名单，免去每处重复手输 CIDR。组件自身不持有存储，只回调变更。
 *
 * 交互：
 *  - 点预设 chip：把该网段并入输入框（逗号分隔、去重，已存在则无变化）
 *  - chip 尾随删除按钮：从预设中移除
 *  - 「保存当前」：把输入框里解析出的网段并入预设
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkAccessField(
    value: String,
    onValueChange: (String) -> Unit,
    presets: List<String>,
    onPresetsChange: (List<String>) -> Unit,
    label: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            supportingText = { Text(supportingText) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        val entries = parseCidrEntries(value)
        val saveable = entries.filter { it !in presets }
        if (presets.isNotEmpty() || saveable.isNotEmpty()) {
            Text(
                text = stringResource(R.string.network_cidr_presets_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                presets.forEach { cidr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { onValueChange((entries + cidr).distinct().joinToString(", ")) },
                            enabled = enabled,
                            label = { Text(cidr) },
                        )
                        IconButton(
                            onClick = { onPresetsChange(presets - cidr) },
                            enabled = enabled,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.network_cidr_presets_remove),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (saveable.isNotEmpty()) {
                    TextButton(
                        onClick = { onPresetsChange((presets + entries).distinct()) },
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.network_cidr_presets_save))
                    }
                }
            }
        }
    }
}

/** 解析输入框里的网段串：半角/全角逗号与换行分隔，去空白、去重。 */
internal fun parseCidrEntries(raw: String): List<String> =
    raw.split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
