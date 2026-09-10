package me.rerere.rikkahub.ui.components.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.SshKeyGenerator
import org.koin.compose.koinInject

/**
 * 生成 SSH 密钥对并存入凭证库的通用对话框。
 *
 * 用途：需要「生成密钥对 → 私钥存凭证库 → 拿到公钥装到服务器」的所有页面
 * （后端服务页 / SSH 主机页 / 凭证库页等）。
 *
 * 流程：输入凭证名 → 点击生成 → RSA-2048 密钥对生成，
 * 私钥自动存入凭证库（AES-GCM 加密），公钥显示在对话框内可复制。
 * 生成成功后通过 [onSaved] 回传凭证名，调用方回填 authRef/vaultCredentialRef。
 *
 * @param credentialName 预填的凭证名（如 "ssh-ax3000"）
 * @param defaultGroup 凭证分组（默认 Other）
 * @param onDismiss 关闭对话框
 * @param onSaved 保存成功回调（参数 = 凭证名），调用方回填引用
 */
@Composable
fun SshKeyPairDialog(
    credentialName: String,
    defaultGroup: String,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val repository: CredentialVaultRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var name by remember { mutableStateOf(credentialName.ifBlank { "ssh-key-${System.currentTimeMillis() % 100000}" }) }
    var group by remember { mutableStateOf(defaultGroup.ifBlank { "SSH" }) }
    var description by remember { mutableStateOf(context.getString(R.string.ssh_key_default_desc)) }
    var keyType by remember { mutableStateOf(SshKeyGenerator.KeyType.RSA) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var savedName by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text(if (savedName == null) stringResource(R.string.ssh_key_dialog_generate_title) else stringResource(R.string.ssh_key_dialog_saved_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (savedName == null) {
                    // 第一步：输入凭证名 → 选择算法 → 生成
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.ssh_key_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = group,
                        onValueChange = { group = it },
                        label = { Text(stringResource(R.string.ssh_key_group_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.ssh_key_desc_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 算法选择（三选一，FlowRow 换行防止按钮溢出屏幕）
                    Text(stringResource(R.string.ssh_key_type_label), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SshKeyGenerator.KeyType.entries.forEach { t ->
                            OutlinedButton(
                                onClick = { keyType = t },
                                enabled = !generating,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (keyType == t) 2.dp else 0.dp,
                                    if (keyType == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                            ) { Text(t.label) }
                        }
                    }
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.ssh_key_desc_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 第二步：显示公钥（可复制/可选中）
                    Text(
                        stringResource(R.string.ssh_key_saved_instruction, savedName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            publicKey ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(publicKey ?: ""))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ssh_key_copy_public)) }
                    Text(
                        stringResource(R.string.ssh_key_after_copy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (savedName == null) {
                TextButton(
                    enabled = !generating && name.isNotBlank(),
                    onClick = {
                        generating = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                runCatching { SshKeyGenerator.generate(keyType) }
                            }
                            result.onSuccess { pair ->
                                try {
                                    repository.save(
                                        name = name.trim(),
                                        value = pair.privateKeyPem,
                                        description = description.trim().ifBlank { context.getString(R.string.ssh_key_default_desc_with_type, keyType) },
                                        group = group.trim().ifBlank { "SSH" },
                                        publicKey = pair.publicKeyLine,
                                    )
                                    publicKey = pair.publicKeyLine
                                    savedName = name.trim()
                                } catch (e: Throwable) {
                                    error = context.getString(R.string.ssh_key_save_failed, e.message)
                                }
                            }.onFailure { e ->
                                error = if (e is java.security.NoSuchAlgorithmException) {
                                    context.getString(R.string.ssh_key_unsupported_type, keyType.label)
                                } else {
                                    context.getString(R.string.ssh_key_generate_failed, e.message)
                                }
                            }
                            generating = false
                        }
                    },
                ) { Text(if (generating) stringResource(R.string.ssh_key_generating) else stringResource(R.string.ssh_key_generate_save)) }
            } else {
                TextButton(onClick = { onSaved(savedName!!) }) { Text(stringResource(R.string.ssh_key_done)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!generating) onDismiss() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
