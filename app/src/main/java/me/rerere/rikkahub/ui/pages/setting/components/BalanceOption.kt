package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.BalanceField
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.isJsonExprValid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.ui.theme.JetbrainsMono

// 以 / 开头的相对路径，或以 http(s):// 开头的绝对地址（中转平台的余额接口常不在 baseUrl 下）
private val ApiPathRegex = Regex("""^(https?://|/)[^ \t\n\r]*$""")

/** 更新多字段列表中的某一项。 */
private fun BalanceOption.updateField(index: Int, field: BalanceField): BalanceOption =
    copy(fields = fields.toMutableList().also { it[index] = field })

@Composable
fun SettingProviderBalanceOption(
    provider: ProviderSetting,
    balanceOption: BalanceOption,
    modifier: Modifier = Modifier,
    onEdit: (BalanceOption) -> Unit,
) {
    var expand by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_balance_info),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    expand = !expand
                },
            ) {
                if (expand) {
                    Icon(
                        imageVector = HugeIcons.ArrowUp01,
                        contentDescription = null,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = null,
                    )
                }
            }
            Checkbox(
                checked = balanceOption.enabled,
                onCheckedChange = { onEdit(balanceOption.copy(enabled = it)) },
            )
        }
        AnimatedVisibility(visible = expand) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = balanceOption.apiPath,
                    onValueChange = { onEdit(balanceOption.copy(apiPath = it)) },
                    label = { Text(stringResource(R.string.setting_provider_page_balance_api_path)) },
                    isError = balanceOption.apiPath.isNotBlank() && !balanceOption.apiPath.matches(ApiPathRegex),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_balance_fields),
                    style = MaterialTheme.typography.labelMedium,
                )
                balanceOption.fields.forEachIndexed { index, field ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = field.label,
                                onValueChange = { v -> onEdit(balanceOption.updateField(index, field.copy(label = v))) },
                                label = { Text(stringResource(R.string.setting_provider_page_balance_field_label)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            IconButton(
                                onClick = {
                                    val next = balanceOption.fields.toMutableList().apply { removeAt(index) }
                                    onEdit(balanceOption.copy(fields = next))
                                },
                            ) { Text("✕") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = field.path,
                                onValueChange = { v -> onEdit(balanceOption.updateField(index, field.copy(path = v))) },
                                label = { Text(stringResource(R.string.setting_provider_page_balance_field_path)) },
                                isError = field.path.isNotBlank() && !isJsonExprValid(field.path),
                                modifier = Modifier.weight(2f),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                            )
                            OutlinedTextField(
                                value = field.unit,
                                onValueChange = { v -> onEdit(balanceOption.updateField(index, field.copy(unit = v))) },
                                label = { Text(stringResource(R.string.setting_provider_page_balance_field_unit)) },
                                modifier = Modifier.weight(0.7f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = field.suffix,
                                onValueChange = { v -> onEdit(balanceOption.updateField(index, field.copy(suffix = v))) },
                                label = { Text(stringResource(R.string.setting_provider_page_balance_field_suffix)) },
                                modifier = Modifier.weight(0.8f),
                                singleLine = true,
                            )
                        }
                    }
                }
                Button(
                    onClick = { onEdit(balanceOption.copy(fields = balanceOption.fields + BalanceField())) },
                ) { Text(stringResource(R.string.setting_provider_page_balance_add_field)) }
                // 未使用多字段时保留旧的单字段配置（兼容既有平台预置）
                if (balanceOption.fields.isEmpty()) {
                    OutlinedTextField(
                        value = balanceOption.resultPath,
                        onValueChange = { onEdit(balanceOption.copy(resultPath = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                        isError = balanceOption.resultPath.isNotBlank() && !isJsonExprValid(balanceOption.resultPath),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    )
                }
                IconButton(
                    onClick = {
                        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                        if (defaultProvider != null) {
                            onEdit(defaultProvider.balanceOption.copy())
                        } else {
                            onEdit(BalanceOption())
                        }
                    },
                ) {
                    Icon(HugeIcons.Refresh03, null)
                }
            }
        }
    }
}
