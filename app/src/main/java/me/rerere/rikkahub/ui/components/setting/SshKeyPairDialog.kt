package me.rerere.rikkahub.ui.components.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var savedName by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text(if (savedName == null) "生成 SSH 密钥对" else "密钥已保存") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (savedName == null) {
                    // 第一步：输入凭证名 → 生成
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("凭证名称（保存到凭证库）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = group,
                        onValueChange = { group = it },
                        label = { Text("凭证分组（如 SSH）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "将生成 RSA-2048 密钥对：私钥加密存入凭证库，公钥用于配置服务器 authorized_keys。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 第二步：显示公钥（可复制）
                    Text(
                        "私钥已保存到凭证库「$savedName」。将以下公钥添加到目标服务器的 authorized_keys：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        publicKey ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(publicKey ?: ""))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("复制公钥") }
                    Text(
                        "复制后粘贴到服务器 ~/.ssh/authorized_keys（若服务器已能用密码登录，可让我帮你一条命令写入）。",
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
                                runCatching { SshKeyGenerator.generate() }
                            }
                            result.onSuccess { pair ->
                                try {
                                    repository.save(name.trim(), pair.privateKeyPem, "SSH 密钥对（生成于 RikkaHub Agents）", group.trim().ifBlank { "SSH" })
                                    publicKey = pair.publicKeyLine
                                    savedName = name.trim()
                                } catch (e: Throwable) {
                                    error = "保存凭证失败: ${e.message}"
                                }
                            }.onFailure { e ->
                                error = "生成密钥失败: ${e.message}"
                            }
                            generating = false
                        }
                    },
                ) { Text(if (generating) "生成中…" else "生成并保存") }
            } else {
                TextButton(onClick = { onSaved(savedName!!) }) { Text("完成") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!generating) onDismiss() }) { Text("取消") }
        },
    )
}
