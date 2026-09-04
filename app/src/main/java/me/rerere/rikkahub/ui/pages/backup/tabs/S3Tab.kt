package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupRunState
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.S3BackupItemsSelector
import me.rerere.rikkahub.ui.pages.backup.backupItemLabel
import me.rerere.rikkahub.ui.pages.backup.components.PasswordPromptDialog
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onLoading
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S3Tab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s3Config = settings.activeS3Config()
    val s3Configs = settings.s3Configs
    val backupItemsState by vm.s3BackupItems.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showBackupFiles by remember { mutableStateOf(false) }
    var restoringItemId by remember { mutableStateOf<String?>(null) }
    // 恢复加密备份：需输口令的待处理项
    var pendingPasswordItem by remember { mutableStateOf<S3BackupItem?>(null) }
    var pendingPasswordEncFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingPasswordError by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }
    var configMenuExpanded by remember { mutableStateOf(false) }

    /** 编辑当前配置：同步到列表与单字段兼容槽。 */
    fun updateS3Config(newConfig: S3Config) {
        val cfgList = s3Configs
        val effectiveId =
            if (cfgList.isEmpty()) {
                settings.s3Config.id.ifBlank { kotlin.uuid.Uuid.random().toString() }
            } else {
                newConfig.id.ifBlank { kotlin.uuid.Uuid.random().toString() }
            }
        val cfg = newConfig.copy(id = effectiveId)
        val newList =
            if (cfgList.any { it.id == effectiveId }) {
                cfgList.map { if (it.id == effectiveId) cfg else it }
            } else {
                cfgList + cfg
            }
        vm.updateSettings(
            settings.copy(
                s3Config = cfg,
                s3Configs = newList,
                activeS3ConfigId = effectiveId,
            ),
        )
    }

    /** 切换当前配置。 */
    fun selectS3Config(cfgId: String) {
        vm.updateSettings(
            settings.copy(
                activeS3ConfigId = cfgId,
                s3Config = s3Configs.firstOrNull { it.id == cfgId } ?: settings.s3Config,
            ),
        )
    }

    /** 新增配置并切换过去。 */
    fun addS3Config() {
        val newCfg = S3Config(id = kotlin.uuid.Uuid.random().toString())
        vm.updateSettings(
            settings.copy(
                s3Config = newCfg,
                s3Configs = s3Configs + newCfg,
                activeS3ConfigId = newCfg.id,
            ),
        )
    }

    val lastBackupText =
        if (settings.backupReminderConfig.lastBackupTime == 0L) {
            stringResource(R.string.backup_page_reminder_no_record)
        } else {
            stringResource(
                R.string.backup_page_reminder_last_time,
                Instant.ofEpochMilli(settings.backupReminderConfig.lastBackupTime).toLocalDateTime(),
            )
        }
    val backupFileSummary =
        when (val state = backupItemsState) {
            is UiState.Success -> "${stringResource(R.string.backup_page_files)}: ${state.data.size}"
            UiState.Loading -> "${stringResource(R.string.backup_page_files)}: ..."
            UiState.Idle -> "${stringResource(R.string.backup_page_files)}: -"
            is UiState.Error -> "${stringResource(R.string.backup_page_files)}: -"
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupStatusCard(
                title = stringResource(R.string.backup_page_s3_backup),
                lastBackupText = lastBackupText,
                fileSummaryText = backupFileSummary,
            )

            // 多配置：下拉切换 + 新增
            val allConfigs = if (s3Configs.isEmpty()) listOf(s3Config) else s3Configs
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_select_config)) },
                    supportingContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ExposedDropdownMenuBox(
                                expanded = configMenuExpanded,
                                onExpandedChange = { configMenuExpanded = it },
                                modifier = Modifier.weight(1f),
                            ) {
                                OutlinedTextField(
                                    value = s3Config.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = configMenuExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                    singleLine = true,
                                )
                                ExposedDropdownMenu(
                                    expanded = configMenuExpanded,
                                    onDismissRequest = { configMenuExpanded = false },
                                ) {
                                    allConfigs.forEach { cfg ->
                                        DropdownMenuItem(
                                            text = { Text(cfg.displayName) },
                                            onClick = {
                                                configMenuExpanded = false
                                                selectS3Config(cfg.id.ifBlank { cfg.displayName })
                                            },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { addS3Config() }) {
                                Icon(HugeIcons.PlusSign, contentDescription = stringResource(R.string.backup_page_add_config))
                            }
                        }
                    },
                )
            }

            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_config_name)) },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s3Config.name,
                            onValueChange = { updateS3Config(s3Config.copy(name = it.trim())) },
                            placeholder = { Text(s3Config.displayName) },
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_s3_endpoint)) },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s3Config.endpoint,
                            onValueChange = { updateS3Config(s3Config.copy(endpoint = it.trim())) },
                            placeholder = { Text("https://s3.amazonaws.com") },
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_s3_access_key_id)) },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s3Config.accessKeyId,
                            onValueChange = { updateS3Config(s3Config.copy(accessKeyId = it.trim())) },
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_s3_secret_access_key)) },
                    supportingContent = {
                        var passwordVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s3Config.secretAccessKey,
                            onValueChange = { updateS3Config(s3Config.copy(secretAccessKey = it.trim())) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image =
                                    if (passwordVisible) {
                                        HugeIcons.ViewOff
                                    } else {
                                        HugeIcons.View
                                    }
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(imageVector = image, contentDescription = null)
                                }
                            },
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_s3_bucket)) },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s3Config.bucket,
                            onValueChange = { updateS3Config(s3Config.copy(bucket = it.trim())) },
                            placeholder = { Text("my-bucket") },
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_s3_path_style)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_s3_path_style_desc)) },
                    trailingContent = {
                        Switch(
                            checked = s3Config.pathStyle,
                            onCheckedChange = { updateS3Config(s3Config.copy(pathStyle = it)) },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_s3_region)) },
                    supportingContent = {
                        Column {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = s3Config.region,
                                onValueChange = { updateS3Config(s3Config.copy(region = it.trim())) },
                                placeholder = { Text("us-east-1") },
                                singleLine = true,
                            )
                            Text(
                                text = stringResource(R.string.backup_page_s3_region_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                        S3BackupItemsSelector(
                            allItems = S3Config.BackupItem.entries.toList(),
                            selectedItems = s3Config.items,
                            onChange = { newItems ->
                                updateS3Config(s3Config.copy(items = newItems))
                            },
                        )
                    },
                )
            }
        }

        HorizontalDivider()
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = {
                    vm.runTestS3 { result ->
                        result.onSuccess {
                            toaster.show(
                                context.getString(R.string.backup_page_connection_success),
                                type = ToastType.Success,
                            )
                        }.onFailure { e ->
                            toaster.show(
                                context.getString(
                                    R.string.backup_page_connection_failed,
                                    e.message ?: "",
                                ),
                                type = ToastType.Error,
                            )
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.backup_page_test_connection))
            }
            OutlinedButton(
                onClick = {
                    vm.loadS3BackupFileItems()
                    showBackupFiles = true
                },
            ) {
                Text(stringResource(R.string.backup_page_restore))
            }

            Button(
                onClick = {
                    isBackingUp = true
                    vm.runBackupToS3 { state ->
                        when (state) {
                            is BackupRunState.Running -> isBackingUp = true
                            is BackupRunState.Success -> {
                                isBackingUp = false
                                toaster.show(
                                    context.getString(R.string.backup_page_backup_success),
                                    type = ToastType.Success,
                                )
                            }

                            is BackupRunState.NeedsPassword -> isBackingUp = false
                            is BackupRunState.Failed -> {
                                isBackingUp = false
                                toaster.show(
                                    state.error?.message
                                        ?: context.getString(R.string.backup_page_unknown_error),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    }
                },
                enabled = !isBackingUp,
            ) {
                if (isBackingUp) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(HugeIcons.Upload02, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isBackingUp) {
                        stringResource(R.string.backup_page_backing_up)
                    } else {
                        stringResource(R.string.backup_page_backup_now)
                    },
                )
            }
        }
    }

    if (showBackupFiles) {
        ModalBottomSheet(
            onDismissRequest = {
                showBackupFiles = false
            },
            sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.8f)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.backup_page_s3_backup_files),
                    modifier = Modifier.fillMaxWidth(),
                )
                backupItemsState
                    .onSuccess {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(it) { item ->
                                S3BackupItemCard(
                                    item = item,
                                    isRestoring = restoringItemId == item.displayName,
                                    onDelete = {
                                        vm.runDeleteS3BackupFile(item) { state ->
                                            when (state) {
                                                is BackupRunState.Success -> {
                                                    toaster.show(
                                                        context.getString(R.string.backup_page_delete_success),
                                                        type = ToastType.Success,
                                                    )
                                                }

                                                is BackupRunState.Failed -> {
                                                    toaster.show(
                                                        context.getString(
                                                            R.string.backup_page_delete_failed,
                                                            state.error?.message ?: "",
                                                        ),
                                                        type = ToastType.Error,
                                                    )
                                                }

                                                else -> {}
                                            }
                                        }
                                    },
                                    onRestore = { restoreItem ->
                                        restoringItemId = restoreItem.displayName
                                        vm.runRestoreFromS3(restoreItem) { state ->
                                            when (state) {
                                                is BackupRunState.Running -> {
                                                    restoringItemId = restoreItem.displayName
                                                }

                                                is BackupRunState.Success -> {
                                                    restoringItemId = null
                                                    showBackupFiles = false
                                                    onShowRestartDialog()
                                                }

                                                is BackupRunState.NeedsPassword -> {
                                                    restoringItemId = null
                                                    pendingPasswordItem = restoreItem
                                                    pendingPasswordEncFile = state.encFile
                                                    pendingPasswordError = null
                                                }

                                                is BackupRunState.Failed -> {
                                                    restoringItemId = null
                                                    toaster.show(
                                                        context.getString(
                                                            R.string.backup_page_restore_failed,
                                                            state.error?.message ?: "",
                                                        ),
                                                        type = ToastType.Error,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }.onError {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_page_loading_failed, it.message ?: ""),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }.onLoading {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator()
                        }
                    }
            }
        }
    }

    // 恢复加密备份需口令：弹输入框
    pendingPasswordItem?.let { item ->
        PasswordPromptDialog(
            onDismiss = {
                pendingPasswordItem = null
                pendingPasswordEncFile = null
                pendingPasswordError = null
            },
            onConfirm = { password ->
                pendingPasswordError = null
                restoringItemId = item.displayName
                vm.runRestoreFromS3WithPassword(
                    item = item,
                    password = password,
                    encFile = pendingPasswordEncFile,
                ) { state ->
                    when (state) {
                        is BackupRunState.Running -> restoringItemId = item.displayName
                        is BackupRunState.Success -> {
                            restoringItemId = null
                            pendingPasswordItem = null
                            pendingPasswordEncFile = null
                            showBackupFiles = false
                            onShowRestartDialog()
                        }
                        is BackupRunState.NeedsPassword -> {
                            restoringItemId = null
                            pendingPasswordError =
                                context.getString(R.string.backup_page_encryption_wrong_password)
                        }
                        is BackupRunState.Failed -> {
                            restoringItemId = null
                            pendingPasswordItem = null
                            pendingPasswordEncFile = null
                            toaster.show(
                                context.getString(
                                    R.string.backup_page_restore_failed,
                                    state.error?.message ?: "",
                                ),
                                type = ToastType.Error,
                            )
                        }
                    }
                }
            },
            errorMessage = pendingPasswordError,
        )
    }
}

@Composable
private fun BackupStatusCard(
    title: String,
    lastBackupText: String,
    fileSummaryText: String,
) {
    CardGroup {
        item(
            headlineContent = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = lastBackupText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = fileSummaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun S3BackupItemCard(
    item: S3BackupItem,
    isRestoring: Boolean = false,
    onDelete: (S3BackupItem) -> Unit = {},
    onRestore: (S3BackupItem) -> Unit = {},
) {
    CardGroup {
        item(
            headlineContent = {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.lastModified.toLocalDateTime(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = item.size.fileSizeToString(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                onDelete(item)
                            },
                            enabled = !isRestoring,
                        ) {
                            Text(stringResource(R.string.backup_page_delete))
                        }
                        Button(
                            onClick = {
                                onRestore(item)
                            },
                            enabled = !isRestoring,
                        ) {
                            if (isRestoring) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (isRestoring) {
                                    stringResource(R.string.backup_page_restoring)
                                } else {
                                    stringResource(R.string.backup_page_restore_now)
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
