package me.rerere.rikkahub.ui.pages.setting
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * SSH 主机管理页：用户可视化配置主机（名称/地址/用户/认证），
 * AI 可通过 save_ssh_host / ssh_exec_saved / vault_ssh_exec 直接调用。
 * 凭证存 Room（现状）；Vault 加密存储为后续增强。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshHostsPage() {
    val sshHostRepository: SshHostRepository = koinInject()
    val vaultRepo: me.rerere.rikkahub.data.vault.CredentialVaultRepository = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val settings = LocalSettings.current

    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<SshHostEntity?>(null) }
    var showEdit by remember { mutableStateOf(false) }

    // 加载主机列表
    androidx.compose.runtime.LaunchedEffect(Unit) {
        hosts = sshHostRepository.getAll()
    }

    fun reload() {
        scope.launch { hosts = sshHostRepository.getAll() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_ssh_hosts_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("sshHosts") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_ssh_hosts_title)) },
                ) {
                    hosts.forEach { host ->
                        item(
                            onClick = {
                                editing = host
                                showEdit = true
                            },
                            headlineContent = { Text(host.name) },
                            supportingContent = { Text("${host.user}@${host.host}:${host.port}") },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            navController.navigate(me.rerere.rikkahub.Screen.SshTerminal(host.name))
                                        },
                                    ) {
                                        Text(stringResource(R.string.setting_ssh_host_connect), color = MaterialTheme.colorScheme.primary)
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                sshHostRepository.deleteByName(host.name)
                                                reload()
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.setting_ssh_host_delete), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                        )
                    }
                    item(
                        onClick = {
                            editing = null
                            showEdit = true
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.setting_ssh_host_add),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            item("sshHint") {
                Text(
                    text = stringResource(R.string.setting_ssh_host_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }

    if (showEdit) {
        SshHostEditDialog(
            initial = editing,
            vaultRepo = vaultRepo,
            onDismiss = { showEdit = false },
            onSave = { entity ->
                scope.launch {
                    runCatching {
                        sshHostRepository.upsert(entity)
                    }.onSuccess {
                        reload()
                        showEdit = false
                    }.onFailure { e ->
                        me.rerere.rikkahub.data.log.AppLog.w(
                            "SshHostsPage",
                            "保存 SSH 主机失败: name=${entity.name} err=${e.message}",
                        )
                        toaster.show("保存失败: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            },
        )
    }
}

@Composable
private fun SshHostEditDialog(
    initial: SshHostEntity?,
    vaultRepo: me.rerere.rikkahub.data.vault.CredentialVaultRepository,
    onDismiss: () -> Unit,
    onSave: (SshHostEntity) -> Unit,
) {
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember { mutableStateOf(initial?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(initial?.passphrase ?: "") }
    var vaultCredentialRef by remember { mutableStateOf(initial?.vaultCredentialRef) }
    var templateRef by remember { mutableStateOf(initial?.templateRef) }
    val scope = rememberCoroutineScope()
    var vaultEntries by remember { mutableStateOf<List<me.rerere.rikkahub.data.db.entity.VaultCredentialEntity>>(emptyList()) }
    var showVaultPicker by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vaultEntries = vaultRepo.getAll() }
    val serverTemplates = vaultEntries.filter { it.grp == "server" }
    // 私钥候选：只显示 SSH 组凭证（ECS_SSH_KEY_* / GITHUB_SSH_KEY / PC_SSH_KEY 等），
    // API key（DEEPSEEK 等）不是 SSH 私钥，不该出现在选择器里
    val keyCandidates = vaultEntries.filter { it.grp == "SSH" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.setting_ssh_host_add else R.string.setting_ssh_host_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.setting_ssh_host_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.setting_ssh_host_addr)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.setting_ssh_host_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.setting_ssh_host_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.setting_ssh_host_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, label = { Text(stringResource(R.string.setting_ssh_host_private_key)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text(stringResource(R.string.setting_ssh_host_passphrase)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                // 从服务器样板选择
                if (serverTemplates.isNotEmpty()) {
                    OutlinedButton(onClick = { showTemplatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.setting_ssh_from_template))
                    }
                    if (showTemplatePicker) {
                        serverTemplates.forEach { tpl ->
                            TextButton(
                                onClick = {
                                    val json = vaultRepo.decryptValue(tpl)
                                    if (json != null) {
                                        runCatching {
                                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                                            host = obj["host"]?.jsonPrimitive?.contentOrNull ?: host
                                            port = (obj["port"]?.jsonPrimitive?.contentOrNull ?: port)
                                            user = obj["user"]?.jsonPrimitive?.contentOrNull ?: user
                                            vaultCredentialRef = obj["keyRef"]?.jsonPrimitive?.contentOrNull ?: vaultCredentialRef
                                            templateRef = tpl.name
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
                }
                if (templateRef != null) {
                    Text(stringResource(R.string.setting_ssh_template_used, templateRef!!), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }

                // 私钥：从 Vault 选择（引用，不明文粘贴）
                if (keyCandidates.isNotEmpty()) {
                    OutlinedButton(onClick = { showVaultPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.setting_ssh_pick_vault_key))
                    }
                    if (showVaultPicker) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            keyCandidates.forEach { cred ->
                                TextButton(onClick = { vaultCredentialRef = cred.name; showVaultPicker = false }, modifier = Modifier.fillMaxWidth()) {
                                    Text(cred.name)
                                }
                            }
                        }
                    }
                    if (vaultCredentialRef != null) {
                        Text(stringResource(R.string.setting_ssh_vault_key_used, vaultCredentialRef!!), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val nameV = name.trim()
                    val hostV = host.trim()
                    if (nameV.isBlank() || hostV.isBlank()) return@TextButton
                    onSave(
                        SshHostEntity(
                            name = nameV,
                            host = hostV,
                            port = port.toIntOrNull() ?: 22,
                            user = user.trim(),
                            password = password.ifBlank { null },
                            privateKey = privateKey.ifBlank { null },
                            passphrase = passphrase.ifBlank { null },
                            vaultCredentialRef = vaultCredentialRef,
                            templateRef = templateRef,
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
