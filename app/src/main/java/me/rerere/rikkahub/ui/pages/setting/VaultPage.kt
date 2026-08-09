package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vault.CredentialImporter
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 安全凭证库（二级设置页）。
 *
 * 功能设置区：指纹开关（MVP 占位，后续接 BiometricPrompt）、导入凭证、
 * 应急导出（后续）、撤销全部会话（后续）。
 * 入口 → 三级「密钥列表」页（VaultCredentialsPage）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPage() {
    val navController = LocalNavController.current
    val repository: CredentialVaultRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var credentialCount by remember { mutableStateOf(0) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 刷新计数
    fun refreshCount() {
        scope.launch { credentialCount = repository.count() }
    }
    refreshCount()

    // SAF 文件选择：导入 load-creds.sh
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        val content =
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                BufferedReader(InputStreamReader(input)).readText()
                            } ?: ""
                        val parsed = CredentialImporter.parse(content)
                        val imported = repository.importEntries(parsed)
                        importResult = "已导入 $imported 条凭证（共解析 ${parsed.size} 条）"
                    }.onFailure { e ->
                        importResult = "导入失败: ${e.message}"
                    }
                    refreshCount()
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_vault_title)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("密钥库凭证（${credentialCount} 条）", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { navController.navigate(me.rerere.rikkahub.Screen.VaultCredentials) }) {
                        Text("管理密钥")
                    }
                }
                Text(
                    text = "凭证以 AES-GCM 密文存于本机 Room（密钥由 Android Keystore 托管），明文不落盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("指纹认证（开发中）", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("打开凭证库时要求指纹/锁屏验证", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = false, onCheckedChange = {})
                        }
                        Text(
                            text = "计划：BiometricPrompt + CryptoObject 绑定 Keystore 密钥，指纹通过才释放解密能力。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("从文件导入", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "支持 load-creds.sh 格式（export KEY=\"value\" + # 注释分组）。选择手机上的凭证文件即可批量导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.Upload02, null, modifier = Modifier.padding(end = 8.dp))
                            Text("选择文件导入")
                        }
                        importResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("应急导出 / 解锁会话（开发中）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "后续：口令加密导出 .vault 包、短时解锁会话供沙箱/ECS 远程请求解密。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("危险区", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.LockKey, null, modifier = Modifier.padding(end = 8.dp))
                            Text("清空全部凭证")
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部凭证？") },
            text = { Text("将删除密钥库中所有凭证条目（Android Keystore 密钥本身保留）。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            repository.clearAll()
                            refreshCount()
                        }
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } },
        )
    }
}
