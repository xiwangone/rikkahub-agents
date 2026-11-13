package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SshHostProbeMode
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.ui.components.setting.SshKeyPairDialog
import me.rerere.rikkahub.ui.components.setting.sshKeyChoiceAvailability
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.components.vault.VaultCredentialPickerDialog

@Composable
internal fun SshHostEditDialog(
    initial: SshHostEntity?,
    vaultRepo: me.rerere.rikkahub.data.vault.CredentialVaultRepository,
    onDismiss: () -> Unit,
    onSave: (SshHostEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember { mutableStateOf(initial?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(initial?.passphrase ?: "") }
    var vaultCredentialRef by remember { mutableStateOf(initial?.vaultCredentialRef) }
    var templateRef by remember { mutableStateOf(initial?.templateRef) }
    var platform by remember { mutableStateOf(SshPlatform.fromPreference(initial?.platform)) }
    var allowHostKeyRotation by remember { mutableStateOf(initial?.allowHostKeyRotation ?: false) }
    var vaultEntries by remember { mutableStateOf<List<me.rerere.rikkahub.data.db.entity.VaultCredentialEntity>>(emptyList()) }
    var showVaultPicker by remember { mutableStateOf(false) }
    var showKeyGen by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var validationAttempted by remember { mutableStateOf(false) }
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val templateApplyFailedFormat = stringResource(R.string.setting_ssh_template_apply_failed)
    val templateDecryptFailedText = stringResource(R.string.setting_ssh_template_decrypt_failed)
    val nameInvalid = validationAttempted && name.trim().isBlank()
    val hostInvalid = validationAttempted && host.trim().isBlank()
    val portValue = port.toIntOrNull()
    val portInvalid = validationAttempted && (portValue == null || portValue !in 1..65535)
    val userInvalid = validationAttempted && user.trim().isBlank()
    LaunchedEffect(Unit) { vaultEntries = vaultRepo.getAll() }
    val serverTemplates = vaultEntries.filter { it.grp == "server" }
    val keyCandidates = vaultEntries.filter { it.grp == "SSH" }
    val keyChoice = sshKeyChoiceAvailability(serverTemplates.isNotEmpty(), keyCandidates.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.setting_ssh_host_add else R.string.setting_ssh_host_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_ssh_host_name)) },
                    singleLine = true,
                    isError = nameInvalid,
                    supportingText = {
                        if (nameInvalid) Text(stringResource(R.string.setting_ssh_host_name_required))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.setting_ssh_host_addr)) },
                        singleLine = true,
                        isError = hostInvalid,
                        supportingText = {
                            if (hostInvalid) Text(stringResource(R.string.setting_ssh_host_addr_required))
                        },
                        modifier = Modifier.weight(0.60f),
                    )
                    SettingTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.setting_ssh_host_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = portInvalid,
                        supportingText = {
                            if (portInvalid) Text(stringResource(R.string.setting_ssh_host_port_invalid))
                        },
                        modifier = Modifier.weight(0.40f),
                    )
                }
                SettingTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.setting_ssh_host_user)) },
                    singleLine = true,
                    isError = userInvalid,
                    supportingText = {
                        if (userInvalid) Text(stringResource(R.string.setting_ssh_host_user_required))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretRefField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.setting_ssh_host_password),
                    nameHint = "SSH_PASSWORD",
                    typeHint = CredentialType.BASIC_AUTH,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretRefField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = stringResource(R.string.setting_ssh_host_private_key),
                    nameHint = "SSH_KEY",
                    typeHint = CredentialType.SSH_KEY,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretRefField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.setting_ssh_host_passphrase),
                    nameHint = "SSH_PASSPHRASE",
                    typeHint = CredentialType.BASIC_AUTH,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.setting_ssh_platform_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SshPlatform.entries.forEachIndexed { index, platformOption ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = SshPlatform.entries.size),
                            selected = platform == platformOption,
                            onClick = { platform = platformOption },
                        ) {
                            Text(stringResource(sshPlatformLabelRes(platformOption)))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_ssh_allow_host_key_rotation),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.setting_ssh_allow_host_key_rotation_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = allowHostKeyRotation,
                        onCheckedChange = { allowHostKeyRotation = it },
                    )
                }

                // “从模板新建”始终显示：空库时置灰并注明原因，否则用户根本不知道有模板功能。
                OutlinedButton(
                    onClick = { showTemplatePicker = true },
                    enabled = keyChoice.templateEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setting_ssh_from_template))
                }
                if (keyChoice.templateHintVisible) {
                    Text(
                        text = stringResource(R.string.setting_ssh_no_saved_template_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showTemplatePicker) {
                        serverTemplates.forEach { tpl ->
                            TextButton(
                                onClick = {
                                    val json = vaultRepo.decryptValue(tpl)
                                    if (json == null) {
                                        toaster.show(
                                            templateApplyFailedFormat.format(templateDecryptFailedText),
                                        )
                                    } else {
                                        runCatching {
                                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                                            host = obj["host"]?.jsonPrimitive?.contentOrNull ?: host
                                            port = (obj["port"]?.jsonPrimitive?.contentOrNull ?: port)
                                            user = obj["user"]?.jsonPrimitive?.contentOrNull ?: user
                                            vaultCredentialRef = obj["keyRef"]?.jsonPrimitive?.contentOrNull ?: vaultCredentialRef
                                            templateRef = tpl.name
                                        }.onFailure { e ->
                                            toaster.show(
                                                templateApplyFailedFormat.format(
                                                    e.message ?: e.javaClass.simpleName,
                                                ),
                                            )
                                        }
                                    }
                                    showTemplatePicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(tpl.name)
                            }
                        }
                    }
                if (templateRef != null) {
                    Text(
                        stringResource(R.string.setting_ssh_template_used, templateRef!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // “新建密钥”始终显示（空库时最需要）；仅“从凭证库选择”在无候选项时置灰。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showVaultPicker = true },
                        enabled = keyChoice.vaultPickEnabled,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Key01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.setting_ssh_pick_vault_key),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { showKeyGen = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.vault_new_key),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (keyChoice.vaultHintVisible) {
                    Text(
                        text = stringResource(R.string.setting_ssh_no_saved_key_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showVaultPicker) {
                    VaultCredentialPickerDialog(
                        entries = keyCandidates,
                        onPick = {
                            vaultCredentialRef = it.name
                            showVaultPicker = false
                        },
                        onDismiss = { showVaultPicker = false },
                    )
                }
                if (showKeyGen) {
                    SshKeyPairDialog(
                        credentialName = "",
                        defaultGroup = "SSH",
                        onDismiss = { showKeyGen = false },
                        onSaved = { savedName ->
                            vaultCredentialRef = savedName
                            showKeyGen = false
                        },
                    )
                }
                if (vaultCredentialRef != null) {
                    Text(
                        stringResource(R.string.setting_ssh_vault_key_used, vaultCredentialRef!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val nameV = name.trim()
                    val hostV = host.trim()
                    val portV = port.toIntOrNull()
                    val userV = user.trim()
                    val fieldsInvalid = nameV.isBlank() || hostV.isBlank() || userV.isBlank()
                    val portRangeInvalid = portV == null || portV !in 1..65535
                    if (fieldsInvalid || portRangeInvalid) {
                        validationAttempted = true
                        return@TextButton
                    }
                    onSave(
                        SshHostEntity(
                            name = nameV,
                            host = hostV,
                            port = portV,
                            user = userV,
                            password = password.ifBlank { null },
                            privateKey = privateKey.ifBlank { null },
                            passphrase = passphrase.ifBlank { null },
                            vaultCredentialRef = vaultCredentialRef,
                            templateRef = templateRef,
                            // 编辑保存是整行 upsert：未在界面上编辑的字段必须从原实体带回，
                            // 否则一次保存会把它们悄悄清成 null。
                            fallbackHostsJson = initial?.fallbackHostsJson,
                            jumpHost = initial?.jumpHost,
                            sshOptions = initial?.sshOptions,
                            platform = platform.preferenceName,
                            allowHostKeyRotation = allowHostKeyRotation,
                            createdAtMs = initial?.createdAtMs ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.settings_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** 平台枚举到界面文案的映射。 */
internal fun sshPlatformLabelRes(platform: SshPlatform): Int =
    when (platform) {
        SshPlatform.AUTO -> R.string.setting_ssh_platform_auto
        SshPlatform.LINUX -> R.string.setting_ssh_platform_linux
        SshPlatform.WINDOWS -> R.string.setting_ssh_platform_windows
    }

/** 探针触发模式枚举到界面文案的映射。 */
internal fun sshHostProbeModeLabelRes(mode: SshHostProbeMode): Int =
    when (mode) {
        SshHostProbeMode.AUTO -> R.string.setting_ssh_host_probe_auto
        SshHostProbeMode.MANUAL_ONLY -> R.string.setting_ssh_host_probe_manual_only
        SshHostProbeMode.OFF -> R.string.setting_ssh_host_probe_off
    }
