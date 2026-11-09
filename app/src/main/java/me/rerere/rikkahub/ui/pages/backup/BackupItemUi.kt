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
import me.rerere.rikkahub.data.sync.LogicalDomains
import me.rerere.rikkahub.data.sync.s3.S3Config

/** 备份条目显示名（Compose 内取字符串资源）。 */
@Composable
fun backupItemLabel(item: WebDavConfig.BackupItem): String =
    stringResource(backupItemLabelRes(item))

/**
 * 常用项常显在主区：设置、头像、提供商、凭证库、SSH 主机。
 * 数据库（含全部聊天、体积大）与文件类、定时任务收进「高级选项」，默认不勾。
 */
fun WebDavConfig.BackupItem.isCoreItem(): Boolean =
    this == WebDavConfig.BackupItem.SETTINGS ||
        this == WebDavConfig.BackupItem.AVATARS ||
        this == WebDavConfig.BackupItem.PROVIDERS ||
        this == WebDavConfig.BackupItem.VAULT_CREDENTIALS ||
        this == WebDavConfig.BackupItem.SSH_HOSTS

fun S3Config.BackupItem.isCoreItem(): Boolean =
    this == S3Config.BackupItem.SETTINGS ||
        this == S3Config.BackupItem.AVATARS ||
        this == S3Config.BackupItem.PROVIDERS ||
        this == S3Config.BackupItem.VAULT_CREDENTIALS ||
        this == S3Config.BackupItem.SSH_HOSTS

/** 是否属于「换机可用」的逻辑域（与迁移包 migration/ 段同名）。 */
fun WebDavConfig.BackupItem.isLogicalItem(): Boolean = name in LogicalDomains.ALL

fun S3Config.BackupItem.isLogicalItem(): Boolean = name in LogicalDomains.ALL

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
        coreItems = allItems.filter { it != WebDavConfig.BackupItem.FILES && it.isCoreItem() },
        advancedItems = allItems.filter { it != WebDavConfig.BackupItem.FILES && !it.isCoreItem() },
        selectedItems = selectedItems,
        onChange = onChange,
        chip = { item, selected, onToggle ->
            BackupItemChip(
                item = item,
                selected = selected,
                onToggle = onToggle,
            )
        },
        desc = { item -> backupItemDescRes(item) },
        showDatabaseOnlyWarning =
            selectedItems.contains(WebDavConfig.BackupItem.DATABASE) &&
                selectedItems.none { it.isLogicalItem() },
    )
}

@Composable
fun S3BackupItemsSelector(
    allItems: List<S3Config.BackupItem>,
    selectedItems: List<S3Config.BackupItem>,
    onChange: (List<S3Config.BackupItem>) -> Unit,
) {
    BackupItemsSelectorContent(
        coreItems = allItems.filter { it != S3Config.BackupItem.FILES && it.isCoreItem() },
        advancedItems = allItems.filter { it != S3Config.BackupItem.FILES && !it.isCoreItem() },
        selectedItems = selectedItems,
        onChange = onChange,
        chip = { item, selected, onToggle ->
            BackupItemChip(
                item = item,
                selected = selected,
                onToggle = onToggle,
            )
        },
        desc = { item -> backupItemDescRes(item) },
        showDatabaseOnlyWarning =
            selectedItems.contains(S3Config.BackupItem.DATABASE) &&
                selectedItems.none { it.isLogicalItem() },
    )
}

@Composable
private fun <T> BackupItemsSelectorContent(
    coreItems: List<T>,
    advancedItems: List<T>,
    selectedItems: List<T>,
    onChange: (List<T>) -> Unit,
    chip: @Composable (T, Boolean, (Boolean) -> Unit) -> Unit,
    desc: (T) -> Int?,
    showDatabaseOnlyWarning: Boolean,
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
        // 勾选项的内容说明：让用户知道“勾了这项到底备了什么”（文案已存在，此处接到 UI）
        SelectedItemDescs(coreItems.filter { it in selectedItems }, desc)
        // 只勾数据库、没勾任何按域项：换机后包里的 vault/SSH 仍抵旧机 Keystore 密文，需明确提示
        if (showDatabaseOnlyWarning) {
            Text(
                stringResource(R.string.backup_page_database_only_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
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
                // 数据库已归入高级区：提醒它含全部聊天（体积大、网络通道上传受限）
                Text(
                    stringResource(R.string.backup_page_items_db_size_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                SelectedItemDescs(advancedItems.filter { it in selectedItems }, desc)
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
        WebDavConfig.BackupItem.MEDIA_CREATION -> R.string.backup_page_item_media_creation
        WebDavConfig.BackupItem.FILES -> R.string.backup_page_item_legacy_files
        WebDavConfig.BackupItem.PROVIDERS -> R.string.backup_page_item_providers
        WebDavConfig.BackupItem.VAULT_CREDENTIALS -> R.string.backup_page_item_vault_credentials
        WebDavConfig.BackupItem.SSH_HOSTS -> R.string.backup_page_item_ssh_hosts
        WebDavConfig.BackupItem.SCHEDULED_JOBS -> R.string.backup_page_item_scheduled_jobs
    }

@Composable
fun backupItemLabel(item: S3Config.BackupItem): String =
    stringResource(backupItemLabelRes(item))

/** 勾选项下方的“包含什么”说明（与 [backupItemLabelRes] 同构）。 */
@Composable
private fun <T> SelectedItemDescs(
    selected: List<T>,
    desc: (T) -> Int?,
) {
    selected.forEach { item ->
        desc(item)?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 备份项“包含什么”的说明资源（文案已存在，此前未接 UI）。 */
fun backupItemDescRes(item: WebDavConfig.BackupItem): Int? =
    when (item) {
        WebDavConfig.BackupItem.DATABASE -> R.string.backup_page_item_database_desc
        WebDavConfig.BackupItem.SETTINGS -> R.string.backup_page_item_settings_desc
        WebDavConfig.BackupItem.AVATARS -> R.string.backup_page_item_avatars_desc
        WebDavConfig.BackupItem.WORKSPACE_DOCS -> R.string.backup_page_item_workspace_docs_desc
        WebDavConfig.BackupItem.SKILLS -> R.string.backup_page_item_skills_desc
        WebDavConfig.BackupItem.CHAT_FILES -> R.string.backup_page_item_chat_files_desc
        WebDavConfig.BackupItem.FONTS_IMAGES -> R.string.backup_page_item_fonts_images_desc
        WebDavConfig.BackupItem.TOOL_OUTPUTS -> R.string.backup_page_item_tool_outputs_desc
        WebDavConfig.BackupItem.MEDIA_CREATION -> R.string.backup_page_item_media_creation_desc
        WebDavConfig.BackupItem.FILES -> R.string.backup_page_item_legacy_files_desc
        WebDavConfig.BackupItem.PROVIDERS -> R.string.backup_page_item_providers_desc
        WebDavConfig.BackupItem.VAULT_CREDENTIALS -> R.string.backup_page_item_vault_credentials_desc
        WebDavConfig.BackupItem.SSH_HOSTS -> R.string.backup_page_item_ssh_hosts_desc
        WebDavConfig.BackupItem.SCHEDULED_JOBS -> R.string.backup_page_item_scheduled_jobs_desc
    }

fun backupItemDescRes(item: S3Config.BackupItem): Int? =
    when (item) {
        S3Config.BackupItem.DATABASE -> R.string.backup_page_item_database_desc
        S3Config.BackupItem.SETTINGS -> R.string.backup_page_item_settings_desc
        S3Config.BackupItem.AVATARS -> R.string.backup_page_item_avatars_desc
        S3Config.BackupItem.WORKSPACE_DOCS -> R.string.backup_page_item_workspace_docs_desc
        S3Config.BackupItem.SKILLS -> R.string.backup_page_item_skills_desc
        S3Config.BackupItem.CHAT_FILES -> R.string.backup_page_item_chat_files_desc
        S3Config.BackupItem.FONTS_IMAGES -> R.string.backup_page_item_fonts_images_desc
        S3Config.BackupItem.TOOL_OUTPUTS -> R.string.backup_page_item_tool_outputs_desc
        S3Config.BackupItem.MEDIA_CREATION -> R.string.backup_page_item_media_creation_desc
        S3Config.BackupItem.FILES -> R.string.backup_page_item_legacy_files_desc
        S3Config.BackupItem.PROVIDERS -> R.string.backup_page_item_providers_desc
        S3Config.BackupItem.VAULT_CREDENTIALS -> R.string.backup_page_item_vault_credentials_desc
        S3Config.BackupItem.SSH_HOSTS -> R.string.backup_page_item_ssh_hosts_desc
        S3Config.BackupItem.SCHEDULED_JOBS -> R.string.backup_page_item_scheduled_jobs_desc
    }

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
        S3Config.BackupItem.MEDIA_CREATION -> R.string.backup_page_item_media_creation
        S3Config.BackupItem.FILES -> R.string.backup_page_item_legacy_files
        S3Config.BackupItem.PROVIDERS -> R.string.backup_page_item_providers
        S3Config.BackupItem.VAULT_CREDENTIALS -> R.string.backup_page_item_vault_credentials
        S3Config.BackupItem.SSH_HOSTS -> R.string.backup_page_item_ssh_hosts
        S3Config.BackupItem.SCHEDULED_JOBS -> R.string.backup_page_item_scheduled_jobs
    }
