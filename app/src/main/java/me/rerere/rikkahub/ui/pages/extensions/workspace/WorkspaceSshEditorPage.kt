package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 新建 / 编辑 SSH 工作区。凭证位只填**凭证库条目引用**（`$$NAME` 或从选择器挑一条），
 * 明文不落这张表、也不落库；首次连接确认主机密钥指纹，指纹变了会拒绝并要求重新确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSshEditorPage(id: String? = null) {
    val navController = LocalNavController.current
    val vm: WorkspaceSshEditorVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    var showSaveAnyway by remember { mutableStateOf(false) }
    val nameDuplicate = draft.name.isNotBlank() && draft.name.trim() in state.knownNames
    val canSubmit = vm.canSubmit()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            vm.acknowledgeSaved()
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (vm.isNew) R.string.workspace_ssh_editor_title_create
                            else R.string.workspace_ssh_editor_title_edit,
                        ),
                    )
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_ssh_editor_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.name,
                onValueChange = vm::setName,
                label = { Text(stringResource(R.string.workspace_page_name)) },
                singleLine = true,
                enabled = !state.busy,
                isError = nameDuplicate,
                supportingText = {
                    if (nameDuplicate) Text(stringResource(R.string.workspace_page_name_duplicate))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.host,
                onValueChange = vm::setHost,
                label = { Text(stringResource(R.string.workspace_ssh_host)) },
                placeholder = { Text(stringResource(R.string.workspace_ssh_host_placeholder)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.portInput,
                    onValueChange = vm::setPort,
                    label = { Text(stringResource(R.string.workspace_ssh_port)) },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.3f),
                )
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = vm::setUsername,
                    label = { Text(stringResource(R.string.workspace_ssh_username)) },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.weight(0.7f),
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SshAuthType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SshAuthType.entries.size),
                        selected = draft.authType == type,
                        enabled = !state.busy,
                        onClick = { vm.setAuthType(type) },
                    ) {
                        Text(
                            stringResource(
                                when (type) {
                                    SshAuthType.PASSWORD -> R.string.workspace_ssh_auth_password
                                    SshAuthType.PRIVATE_KEY -> R.string.workspace_ssh_auth_private_key
                                },
                            ),
                        )
                    }
                }
            }
            val credentialDesc = stringResource(R.string.workspace_ssh_credential_desc)
            when (draft.authType) {
                SshAuthType.PASSWORD -> SecretRefField(
                    value = draft.passwordRef,
                    onValueChange = vm::setPasswordRef,
                    label = stringResource(R.string.workspace_ssh_password_ref),
                    description = credentialDesc,
                    nameHint = "SSH_PASSWORD",
                    typeHint = CredentialType.BASIC_AUTH,
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                SshAuthType.PRIVATE_KEY -> {
                    SecretRefField(
                        value = draft.privateKeyRef,
                        onValueChange = vm::setPrivateKeyRef,
                        label = stringResource(R.string.workspace_ssh_private_key_ref),
                        description = credentialDesc,
                        nameHint = "SSH_KEY",
                        typeHint = CredentialType.SSH_KEY,
                        maxLines = 3,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretRefField(
                        value = draft.passphraseRef,
                        onValueChange = vm::setPassphraseRef,
                        label = stringResource(R.string.workspace_ssh_passphrase_ref),
                        description = credentialDesc,
                        nameHint = "SSH_PASSPHRASE",
                        typeHint = CredentialType.BASIC_AUTH,
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            OutlinedTextField(
                value = draft.remoteDir,
                onValueChange = vm::setRemoteDir,
                label = { Text(stringResource(R.string.workspace_ssh_remote_dir)) },
                singleLine = true,
                enabled = !state.busy,
                supportingText = { Text(stringResource(R.string.workspace_ssh_remote_dir_desc)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = draft.hostKey?.let { key ->
                    stringResource(R.string.workspace_ssh_host_key_trusted, key.type, key.fingerprint)
                } ?: stringResource(R.string.workspace_ssh_host_key_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (draft.hostKey == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            state.probeResult?.let { result ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_ssh_probe_success, result.shell, result.homeDir),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!result.remoteDirExists) {
                            Text(
                                text = stringResource(R.string.workspace_ssh_probe_remote_dir_missing),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            state.probeError?.let { error ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.workspace_ssh_probe_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { showSaveAnyway = true }) {
                        Text(stringResource(R.string.workspace_ssh_save_anyway))
                    }
                }
            }

            state.saveError?.let { error ->
                Text(
                    text = stringResource(R.string.workspace_ssh_save_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::testConnection,
                    enabled = canSubmit && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (state.busy) R.string.workspace_ssh_connecting else R.string.workspace_ssh_test,
                        ),
                    )
                }
                Button(
                    onClick = vm::save,
                    enabled = canSubmit && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }

    state.pendingHostKey?.let { pending ->
        HostKeyConfirmDialog(
            address = "${draft.username}@${draft.host}:${state.portInput}",
            actual = pending.actual,
            expected = pending.expected,
            onConfirm = vm::confirmHostKey,
            onDismiss = vm::dismissHostKey,
        )
    }

    if (showSaveAnyway) {
        AlertDialog(
            onDismissRequest = { showSaveAnyway = false },
            title = { Text(stringResource(R.string.workspace_ssh_save_anyway_title)) },
            text = { Text(stringResource(R.string.workspace_ssh_save_anyway_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showSaveAnyway = false
                    vm.saveAnyway()
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAnyway = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun HostKeyConfirmDialog(
    address: String,
    actual: SshHostKey,
    expected: SshHostKey?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (expected == null) R.string.workspace_ssh_host_key_unknown_title
                    else R.string.workspace_ssh_host_key_changed_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        if (expected == null) R.string.workspace_ssh_host_key_unknown_desc
                        else R.string.workspace_ssh_host_key_changed_desc,
                        address,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expected != null) {
                    Text(
                        text = stringResource(R.string.workspace_ssh_host_key_previous, expected.type, expected.fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.workspace_ssh_host_key_received, actual.type, actual.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.workspace_ssh_host_key_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
