package me.rerere.rikkahub.ui.pages.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config

/** 备份条目显示名（Compose 内取字符串资源）。 */
@Composable
fun backupItemLabel(item: WebDavConfig.BackupItem): String =
    stringResource(backupItemLabelRes(item))

/** 核心状态项（数据库/设置/头像）：默认备份，主区常显。其余为高级文件类（默认不勾）。 */
fun WebDavConfig.BackupItem.isCoreItem(): Boolean =
    this == WebDavConfig.BackupItem.DATABASE ||
        this == WebDavConfig.BackupItem.SETTINGS ||
        this == WebDavConfig.BackupItem.AVATARS

fun S3Config.BackupItem.isCoreItem(): Boolean =
    this == S3Config.BackupItem.DATABASE ||
        this == S3Config.BackupItem.SETTINGS ||
        this == S3Config.BackupItem.AVATARS

/** 单个备份项 chip：勾选状态 + 点击切换。 */
@Composable
fun BackupItemChip(
    item: WebDavConfig.BackupItem,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(backupItemLabel(item)) },
    )
}

@Composable
fun BackupItemChip(
    item: S3Config.BackupItem,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(backupItemLabel(item)) },
    )
}

/**
 * 通用"核心项常显 + 高级文件类折叠"选择区。
 * [allItems] 全部可选项；[selectedItems] 当前勾选；[onChange] 更新勾选。
 */
@Composable
fun WebDavBackupItemsSelector(
    allItems: List<WebDavConfig.BackupItem>,
    selectedItems: List<WebDavConfig.BackupItem>,
    onChange: (List<WebDavConfig.BackupItem>) -> Unit,
) {
    BackupItemsSelectorContent(
        coreItems = allItems.filter { it.isCoreItem() },
        advancedItems = allItems.filter { !it.isCoreItem() },
        selectedItems = selectedItems,
        onChange = onChange,
        chip = { item, selected, onToggle ->
            BackupItemChip(
                item = item,
                selected = selected,
                onToggle = onToggle,
            )
        },
    )
}

@Composable
fun S3BackupItemsSelector(
    allItems: List<S3Config.BackupItem>,
    selectedItems: List<S3Config.BackupItem>,
    onChange: (List<S3Config.BackupItem>) -> Unit,
) {
    BackupItemsSelectorContent(
        coreItems = allItems.filter { it.isCoreItem() },
        advancedItems = allItems.filter { !it.isCoreItem() },
        selectedItems = selectedItems,
        onChange = onChange,
        chip = { item, selected, onToggle ->
            BackupItemChip(
                item = item,
                selected = selected,
                onToggle = onToggle,
            )
        },
    )
}

@Composable
private fun <T> BackupItemsSelectorContent(
    coreItems: List<T>,
    advancedItems: List<T>,
    selectedItems: List<T>,
    onChange: (List<T>) -> Unit,
    chip: @Composable (T, Boolean, (Boolean) -> Unit) -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            coreItems.forEach { item ->
                chip(item, item in selectedItems) { checked ->
                    onChange(if (checked) selectedItems + item else selectedItems - item)
                }
            }
        }
        if (advancedItems.isNotEmpty()) {
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    stringResource(R.string.backup_page_advanced_options) + if (showAdvanced) " ▾" else " ▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (showAdvanced) {
                Text(
                    text = stringResource(R.string.backup_page_advanced_options_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    advancedItems.forEach { item ->
                        chip(item, item in selectedItems) { checked ->
                            onChange(if (checked) selectedItems + item else selectedItems - item)
                        }
                    }
                }
            }
        }
    }
}

@StringRes
fun backupItemLabelRes(item: WebDavConfig.BackupItem): Int =
    when (item) {
        WebDavConfig.BackupItem.DATABASE -> R.string.backup_page_item_database
        WebDavConfig.BackupItem.SETTINGS -> R.string.backup_page_item_settings
        WebDavConfig.BackupItem.AVATARS -> R.string.backup_page_item_avatars
        WebDavConfig.BackupItem.WORKSPACE_DOCS -> R.string.backup_page_item_workspace_docs
        WebDavConfig.BackupItem.SKILLS -> R.string.backup_page_item_skills
        WebDavConfig.BackupItem.CHAT_FILES -> R.string.backup_page_item_chat_files
        WebDavConfig.BackupItem.FONTS_IMAGES -> R.string.backup_page_item_fonts_images
        WebDavConfig.BackupItem.TOOL_OUTPUTS -> R.string.backup_page_item_tool_outputs
        WebDavConfig.BackupItem.FILES -> R.string.backup_page_item_legacy_files
    }

@Composable
fun backupItemLabel(item: S3Config.BackupItem): String =
    stringResource(backupItemLabelRes(item))

@StringRes
fun backupItemLabelRes(item: S3Config.BackupItem): Int =
    when (item) {
        S3Config.BackupItem.DATABASE -> R.string.backup_page_item_database
        S3Config.BackupItem.SETTINGS -> R.string.backup_page_item_settings
        S3Config.BackupItem.AVATARS -> R.string.backup_page_item_avatars
        S3Config.BackupItem.WORKSPACE_DOCS -> R.string.backup_page_item_workspace_docs
        S3Config.BackupItem.SKILLS -> R.string.backup_page_item_skills
        S3Config.BackupItem.CHAT_FILES -> R.string.backup_page_item_chat_files
        S3Config.BackupItem.FONTS_IMAGES -> R.string.backup_page_item_fonts_images
        S3Config.BackupItem.TOOL_OUTPUTS -> R.string.backup_page_item_tool_outputs
        S3Config.BackupItem.FILES -> R.string.backup_page_item_legacy_files
    }
