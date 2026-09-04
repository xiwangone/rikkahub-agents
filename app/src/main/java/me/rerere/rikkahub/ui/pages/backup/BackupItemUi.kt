package me.rerere.rikkahub.ui.pages.backup

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config

/** 备份条目显示名（Compose 内取字符串资源）。 */
@Composable
fun backupItemLabel(item: WebDavConfig.BackupItem): String =
    stringResource(backupItemLabelRes(item))

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
