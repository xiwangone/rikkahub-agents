package me.rerere.rikkahub.ui.components.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

/** 「全部」筛选项：用语言中立的标识，与类型标识风格一致，避免为每种语言新增翻译。 */
private const val FILTER_ALL = "all"

/**
 * 凭证选择器：**搜索 + 按分组 / 按类型筛选**。
 *
 * 为什么需要：凭证条目增多后（数十条），各处"引用凭证"时靠记忆手输名字既慢又易错；
 * 统一用本选择器即可：输入关键词缩小范围，或按分组、按类型快速定位。
 *
 * - 分组与类型各有一个「全部」选项；两者可叠加。
 * - 类型用语言中立的标识（与通用凭据交换格式对齐），不额外翻译。
 * - 未分类（类型为空）的条目会归入「全部」，不会被筛选漏掉。
 */
@Composable
fun VaultCredentialPickerDialog(
    entries: List<VaultCredentialEntity>,
    onPick: (VaultCredentialEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf(FILTER_ALL) }
    var typeFilter by remember { mutableStateOf(FILTER_ALL) }

    val groups = remember(entries) { entries.map { it.grp }.filter { it.isNotBlank() }.distinct().sorted() }
    val types = remember(entries) { entries.map { it.type }.filter { it.isNotBlank() }.distinct().sorted() }

    val q = query.trim().lowercase()
    val filtered = entries.filter { e ->
        (q.isEmpty() ||
            e.name.lowercase().contains(q) ||
            e.description.lowercase().contains(q)) &&
            (groupFilter == FILTER_ALL || e.grp == groupFilter) &&
            (typeFilter == FILTER_ALL || e.type == typeFilter)
    }.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_ssh_pick_vault_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.vault_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 分组筛选
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = groupFilter == FILTER_ALL,
                        onClick = { groupFilter = FILTER_ALL },
                        label = { Text(FILTER_ALL) },
                    )
                    groups.forEach { g ->
                        FilterChip(
                            selected = groupFilter == g,
                            onClick = { groupFilter = g },
                            label = { Text(g) },
                        )
                    }
                }

                // 类型筛选（只在该库确实存在类型时出现，避免空行占用空间）
                if (types.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = typeFilter == FILTER_ALL,
                            onClick = { typeFilter = FILTER_ALL },
                            label = { Text(FILTER_ALL) },
                        )
                        types.forEach { t ->
                            FilterChip(
                                selected = typeFilter == t,
                                onClick = { typeFilter = t },
                                label = { Text(t) },
                            )
                        }
                    }
                }

                Text(
                    text = "${filtered.size} / ${entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    items(filtered, key = { it.id }) { e ->
                        TextButton(
                            onClick = { onPick(e) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(e.name)
                        }
                        if (e.description.isNotBlank()) {
                            Text(
                                text = e.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}
