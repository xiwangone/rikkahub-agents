package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.SshKeyPairDialog
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.components.vault.VaultCredentialPickerDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * SSH 主机管理页（Material 3 Expressive 分组卡片样式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshHostsPage() {
    val sshHostRepository: SshHostRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsStore.settingsFlow.value,
    )
    val vaultRepo: me.rerere.rikkahub.data.vault.CredentialVaultRepository = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<SshHostEntity?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SshHostEntity?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            sshHostRepository.getAll()
        }.onSuccess { list ->
            AppLog.d("SshHostsPage", "加载主机列表: ${list.size} 个 ${list.map { it.name }}")
            hosts = list
            loadError = null
        }.onFailure { e ->
            AppLog.w("SshHostsPage", "加载主机列表失败: ${e.message}")
            loadError = e.message ?: e.javaClass.simpleName
            toaster.show(context.getString(R.string.setting_ssh_hosts_load_failed, loadError!!))
        }
    }

    fun reload() {
        scope.launch {
            runCatching {
                sshHostRepository.getAll()
            }.onSuccess { list ->
                AppLog.d("SshHostsPage", "刷新主机列表: ${list.size} 个")
                hosts = list
                loadError = null
            }.onFailure { e ->
                AppLog.w("SshHostsPage", "刷新主机列表失败: ${e.message}")
                loadError = e.message ?: e.javaClass.simpleName
                toaster.show(context.getString(R.string.setting_ssh_hosts_load_failed, loadError!!))
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_ssh_hosts_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            editing = null
                            showEdit = true
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_ssh_host_add),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loadError != null) {
                item("sshLoadError") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_ssh_hosts_load_failed, loadError!!),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    loadError = null
                                    reload()
                                },
                            ) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
            }

            item("sshDefaultPlatform") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.setting_ssh_default_platform),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SshPlatform.entries.forEachIndexed { index, platform ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = SshPlatform.entries.size),
                                selected = settings.defaultSshPlatform == platform,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update { it.copy(defaultSshPlatform = platform) }
                                    }
                                },
                            ) {
                                Text(stringResource(sshPlatformLabelRes(platform)))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.setting_ssh_default_platform_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item("sshHostsGroup") {
                // ⚠ CardGroup 内部用 `remember { CardGroupScopeImpl() }` 缓存条目列表，而该列表不是 State：
                // 列表数据（hosts）从空加载成 N 个时，LazyColumn 的 item 不保证会让 CardGroup 的 scope 重建，
                // 表现为“数据已加载（日志可见）但页面空白、新加主机也不显示”。用 key(hosts) 强制重建。
                key(hosts) {
                    CardGroup(
                        title = { Text(stringResource(R.string.setting_ssh_hosts_title)) },
                    ) {
                        hosts.forEach { host ->
                        item(
                            onClick = {
                                editing = host
                                showEdit = true
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                            headlineContent = { Text(host.name) },
                            supportingContent = {
                                Text(
                                    text = "${host.user}@${host.host}:${host.port}",
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            navController.navigate(Screen.SshTerminal(host.name))
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.ComputerTerminal01,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.setting_ssh_host_connect),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    IconButton(
                                        onClick = { deleteTarget = host },
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.setting_ssh_host_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
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
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Add01,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
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
            }

            item("sshHint") {
                Text(
                    text = stringResource(R.string.setting_ssh_host_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
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
                        AppLog.w(
                            "SshHostsPage",
                            "failed to save SSH host: name=${entity.name} err=${e.message}",
                        )
                        toaster.show(context.getString(R.string.ssh_hosts_save_failed, e.message ?: e.javaClass.simpleName))
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.setting_ssh_host_delete_title),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            val target = deleteTarget
            deleteTarget = null
            if (target != null) {
                scope.launch {
                    sshHostRepository.deleteByName(target.name)
                    reload()
                    toaster.show(context.getString(R.string.setting_ssh_host_deleted, target.name))
                }
            }
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name ?: ""))
    }
}

@Composable
private fun SshHostEditDialog(
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
    val context = LocalContext.current
    val nameInvalid = validationAttempted && name.trim().isBlank()
    val hostInvalid = validationAttempted && host.trim().isBlank()
    val portValue = port.toIntOrNull()
    val portInvalid = validationAttempted && (portValue == null || portValue !in 1..65535)
    val userInvalid = validationAttempted && user.trim().isBlank()
    LaunchedEffect(Unit) { vaultEntries = vaultRepo.getAll() }
    val serverTemplates = vaultEntries.filter { it.grp == "server" }
    val keyCandidates = vaultEntries.filter { it.grp == "SSH" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.setting_ssh_host_add else R.string.setting_ssh_host_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
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
                    OutlinedTextField(
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
                    OutlinedTextField(
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
                OutlinedTextField(
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

                if (serverTemplates.isNotEmpty()) {
                    OutlinedButton(onClick = { showTemplatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.setting_ssh_from_template))
                    }
                    if (showTemplatePicker) {
                        serverTemplates.forEach { tpl ->
                            TextButton(
                                onClick = {
                                    val json = vaultRepo.decryptValue(tpl)
                                    if (json == null) {
                                        toaster.show(
                                            context.getString(
                                                R.string.setting_ssh_template_apply_failed,
                                                context.getString(R.string.setting_ssh_template_decrypt_failed),
                                            ),
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
                                                context.getString(
                                                    R.string.setting_ssh_template_apply_failed,
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
                }
                if (templateRef != null) {
                    Text(
                        stringResource(R.string.setting_ssh_template_used, templateRef!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (keyCandidates.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showVaultPicker = true },
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val nameV = name.trim()
                    val hostV = host.trim()
                    val portV = port.toIntOrNull()
                    val userV = user.trim()
                    if (nameV.isBlank() || hostV.isBlank() || portV == null || portV !in 1..65535 || userV.isBlank()) {
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
private fun sshPlatformLabelRes(platform: SshPlatform): Int =
    when (platform) {
        SshPlatform.AUTO -> R.string.setting_ssh_platform_auto
        SshPlatform.LINUX -> R.string.setting_ssh_platform_linux
        SshPlatform.WINDOWS -> R.string.setting_ssh_platform_windows
    }
