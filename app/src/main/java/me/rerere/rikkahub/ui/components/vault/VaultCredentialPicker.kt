package me.rerere.rikkahub.ui.components.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import me.rerere.rikkahub.ui.components.ui.CappedLazyColumn

/** 「全部」筛选项：与类型标识一样用语言中立写法，避免为每种语言新增翻译。 */
private const val FILTER_ALL = "all"

/**
 * 凭证选择器：**搜索 + 按分组 / 按类型筛选**。
 *
 * 为什么需要：凭证条目增多后（数十条），各处"引用凭证"时靠记忆手输名字既慢又易错；
 * 统一用本选择器即可：输入关键词缩小范围，或按分组、按类型快速定位。
 *
 * - 分组与类型各有一个「全部」选项，两者可叠加。
 * - 未分类（类型为空）的条目会出现在「全部」下，不会被筛选漏掉。
 * - 全部内容装在**限高的惰性列表**里：条目再多也不会把底部按钮挤出屏幕，
 *   也不会出现嵌套可滚动组件导致的测量异常。
 */
@Composable
fun VaultCredentialPickerDialog(
    entries: List<VaultCredentialEntity>,
    onPick: (VaultCredentialEntity) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 打开时预选的类型（如 `ssh-key` / `basic-auth`）；null = 全部。
     * 按用途预筛能显著降低「把 API key 选去当 SSH 凭据」这类误选。
     */
    initialTypeFilter: String? = null,
) {
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf(FILTER_ALL) }
    var typeFilter by remember(initialTypeFilter) { mutableStateOf(initialTypeFilter ?: FILTER_ALL) }

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
            CappedLazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.vault_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item(key = "group_filter") {
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
                }

                // 类型筛选：只在库里确实有类型时出现，避免空行占位
                if (types.isNotEmpty()) {
                    item(key = "type_filter") {
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
                }

                item(key = "count") {
                    Text(
                        text = "${filtered.size} / ${entries.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(filtered.size, key = { filtered[it].id }) { index ->
                    val entry = filtered[index]
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { onPick(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(entry.name)
                        }
                        if (entry.description.isNotBlank()) {
                            Text(
                                text = entry.description,
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
