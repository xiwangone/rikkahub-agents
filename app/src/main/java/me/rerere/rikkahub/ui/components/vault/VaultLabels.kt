package me.rerere.rikkahub.ui.components.vault

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vault.CredentialType

/**
 * 凭证分组 / 类型的显示名。
 *
 * 存储值是与交换格式对齐的英文标识（`ssh-key`、`Other`），**显示层必须本地化** —— 这条规则原本只写在
 * 凭证库页面里，其它引用入口（SSH 工作区、主机、SecretRefField）直接把原始 id 显示出来。收到这里统一。
 *
 * 每个都有两个重载：`Context` 版（普通函数，供排序 / 非 Compose 处调用）与 `@Composable` 版
 * （用 `stringResource`，避免 `LocalContextGetResourceValueCall` —— 配置变化不重建、可能拿旧值）。
 */

/** 分组显示名；未知分组回退原值。 */
internal fun vaultGroupLabel(context: Context, grp: String): String = when (grp) {
    "Git" -> context.getString(R.string.vault_group_git)
    "AI" -> context.getString(R.string.vault_group_ai)
    "ECS" -> context.getString(R.string.vault_group_ecs)
    "SSH" -> context.getString(R.string.vault_group_ssh)
    "Network" -> context.getString(R.string.vault_group_network)
    "MCP" -> context.getString(R.string.vault_group_mcp)
    "Backend" -> context.getString(R.string.vault_group_backend)
    "System" -> context.getString(R.string.vault_group_system)
    "Notification" -> context.getString(R.string.vault_group_notification)
    "Other" -> context.getString(R.string.vault_group_other)
    else -> grp
}

/** 分组显示名（Composable 版）。 */
@Composable
internal fun vaultGroupLabelText(grp: String): String = when (grp) {
    "Git" -> stringResource(R.string.vault_group_git)
    "AI" -> stringResource(R.string.vault_group_ai)
    "ECS" -> stringResource(R.string.vault_group_ecs)
    "SSH" -> stringResource(R.string.vault_group_ssh)
    "Network" -> stringResource(R.string.vault_group_network)
    "MCP" -> stringResource(R.string.vault_group_mcp)
    "Backend" -> stringResource(R.string.vault_group_backend)
    "System" -> stringResource(R.string.vault_group_system)
    "Notification" -> stringResource(R.string.vault_group_notification)
    "Other" -> stringResource(R.string.vault_group_other)
    else -> grp
}

/** 类型显示名；未知值回退原值。 */
internal fun vaultTypeLabel(context: Context, type: String): String = when (type) {
    CredentialType.SSH_KEY -> context.getString(R.string.vault_type_ssh_key)
    CredentialType.API_KEY -> context.getString(R.string.vault_type_api_key)
    CredentialType.BASIC_AUTH -> context.getString(R.string.vault_type_basic_auth)
    CredentialType.TOTP -> context.getString(R.string.vault_type_totp)
    CredentialType.CUSTOM -> context.getString(R.string.vault_type_custom_fields)
    CredentialType.CLOUD_AK -> context.getString(R.string.vault_type_cloud_ak)
    else -> type
}

/** 类型显示名（Composable 版）。 */
@Composable
internal fun vaultTypeLabelText(type: String): String = when (type) {
    CredentialType.SSH_KEY -> stringResource(R.string.vault_type_ssh_key)
    CredentialType.API_KEY -> stringResource(R.string.vault_type_api_key)
    CredentialType.BASIC_AUTH -> stringResource(R.string.vault_type_basic_auth)
    CredentialType.TOTP -> stringResource(R.string.vault_type_totp)
    CredentialType.CUSTOM -> stringResource(R.string.vault_type_custom_fields)
    CredentialType.CLOUD_AK -> stringResource(R.string.vault_type_cloud_ak)
    else -> type
}
