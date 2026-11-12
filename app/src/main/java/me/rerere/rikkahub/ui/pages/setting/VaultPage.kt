package me.rerere.rikkahub.ui.pages.setting

import android.provider.OpenableColumns
import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.db.entity.VaultAuditDefaults
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.vault.AuditAnomalyDetector
import me.rerere.rikkahub.data.vault.AuditExporter
import me.rerere.rikkahub.data.vault.CredentialPurpose
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultAppLock
import me.rerere.rikkahub.data.vault.VaultBiometric
import me.rerere.rikkahub.data.vault.VaultExporter
import me.rerere.rikkahub.data.vault.VaultFormats
import me.rerere.rikkahub.data.vault.VaultImportOutcome
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.data.vault.VaultSessionManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 安全凭证库（二级设置页，Material 3 Expressive 分组卡片样式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPage() {
    val navController = LocalNavController.current
    val repository: CredentialVaultRepository = koinInject()
    val vaultPreferences: VaultPreferences = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val passwordRequiredStr = stringResource(R.string.vault_export_password_required)

    var credentialCount by remember { mutableStateOf(0) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var exportFormat by remember { mutableStateOf(VaultFormats.FORMAT_VAULT) }
    var backupPassword by remember { mutableStateOf("") }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var auditLogs by remember { mutableStateOf<List<VaultAuditLogEntity>>(emptyList()) }
    var auditFilter by remember { mutableStateOf<String?>(null) }
    var auditActionFilter by remember { mutableStateOf<String?>(null) }
    var auditTimeDays by remember { mutableStateOf(0) }
    val shownAudit =
        remember(auditLogs, auditFilter, auditActionFilter, auditTimeDays) {
            val now = System.currentTimeMillis()
            auditLogs.filter { log ->
                (auditFilter == null || log.credentialName == auditFilter) &&
                    (auditActionFilter == null || log.action == auditActionFilter) &&
                    (auditTimeDays == 0 || log.tsMs >= now - auditTimeDays * 86_400_000L)
            }
        }
    val auditGroups: List<List<VaultAuditLogEntity>> =
        remember(shownAudit) {
            val groups = mutableListOf<MutableList<VaultAuditLogEntity>>()
            shownAudit.forEach { log ->
                val last = groups.lastOrNull()?.lastOrNull()
                if (last != null && last.credentialName == log.credentialName && last.tsMs - log.tsMs < 1500) {
                    groups.last().add(log)
                } else {
                    groups.add(mutableListOf(log))
                }
            }
            groups
        }

    val auditAnomalies = remember(shownAudit) { AuditAnomalyDetector.detect(shownAudit) }

    fun actionNeedsAuth(action: String): Boolean =
        CredentialPurpose.values().any { it.action == action && it.requiresAuthorization }

    var auditExportFormat by remember { mutableStateOf("json") }
    val auditExportLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val text = if (auditExportFormat == "csv") AuditExporter.toCsv(shownAudit) else AuditExporter.toJson(shownAudit)
                            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        }
                    }
                }
            }
        }
    var auditRetentionDays by remember { mutableStateOf(VaultAuditDefaults.RETENTION_DAYS.toInt()) }
    var auditCap by remember { mutableStateOf(VaultAuditDefaults.CAP) }
    var auditRollupMinutes by remember { mutableStateOf(VaultAuditDefaults.ROLLUP_MINUTES) }
    LaunchedEffect(Unit) { vaultPreferences.auditRetentionDays.collect { auditRetentionDays = it } }
    LaunchedEffect(Unit) { vaultPreferences.auditCap.collect { auditCap = it } }
    LaunchedEffect(Unit) { vaultPreferences.auditRollupMinutes.collect { auditRollupMinutes = it } }
    var sessionToken by remember { mutableStateOf<String?>(null) }
    var sessionResult by remember { mutableStateOf<String?>(null) }
    val vaultSessionManager: VaultSessionManager = koinInject()
    val biometricEnabled by vaultPreferences.biometricEnabled.collectAsState(initial = true)

    val biometricBuffer: BiometricResultBuffer = koinInject()
    var unlocked by remember { mutableStateOf(!biometricEnabled) }
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled && !unlocked) {
            val ok =
                VaultBiometric.authenticate(
                    context = context,
                    buffer = biometricBuffer,
                    title = context.getString(R.string.vault_biometric_open_title),
                )
            unlocked = ok
        }
    }

    var sessionListState by remember { mutableStateOf<List<me.rerere.rikkahub.data.vault.VaultSessionInfo>>(emptyList()) }

    fun refreshSessionList() {
        scope.launch { sessionListState = vaultSessionManager.listSessions() }
    }

    fun refreshCount() {
        scope.launch { credentialCount = repository.count() }
    }

    fun refreshAudit() {
        scope.launch { auditLogs = repository.recentAudit(100) }
    }

    fun formatTime(tsMs: Long): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(tsMs))
    }
    refreshCount()
    refreshAudit()

    val backupExportLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && backupPassword.isNotBlank()) {
                val titleStr = context.getString(R.string.vault_biometric_export_title)
                val cancelledStr = context.getString(R.string.vault_export_cancelled)
                val successStr = context.getString(R.string.vault_backup_success)
                val failedStr = context.getString(R.string.vault_export_failed)
                scope.launch {
                    runCatching {
                        if (biometricEnabled) {
                            val ok = VaultBiometric.authenticate(context, biometricBuffer, titleStr)
                            if (!ok) {
                                backupResult = cancelledStr
                                return@launch
                            }
                        }
                        val entries = repository.getAll()
                        val plaintexts =
                            entries.mapNotNull { e ->
                                repository.decryptValue(e)?.let { VaultExporter.Quad(e.name, it, e.description, e.grp, e.publicKey, e.type) }
                            }
                        withContext(Dispatchers.IO) {
                            val vaultJson = VaultExporter.exportWithGroups(backupPassword, plaintexts)
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(vaultJson.encodeToByteArray())
                            }
                        }
                        plaintexts.forEach { q ->
                            repository.logAccess(q.name, "backup", "backup")
                        }
                        backupResult = successStr.format(plaintexts.size)
                    }.onFailure { e ->
                        backupResult = failedStr.format(e.message ?: "")
                    }
                }
            }
        }

    val backupRestoreLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && backupPassword.isNotBlank()) {
                val restoreSuccessStr = context.getString(R.string.vault_backup_restore_success)
                val restoreFailedStr = context.getString(R.string.vault_backup_restore_failed)
                scope.launch {
                    runCatching {
                        val restored =
                            withContext(Dispatchers.IO) {
                                val content =
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        BufferedReader(InputStreamReader(input)).use { it.readText() }
                                    } ?: ""
                                VaultExporter.import(content, backupPassword)
                            }
                        restored.forEach { q ->
                            runCatching {
                                repository.save(
                                    CredentialVaultRepository.normalizeName(q.name),
                                    q.plaintext,
                                    q.description,
                                    q.group.ifEmpty { "Other" },
                                    q.publicKey,
                                    type = q.type,
                                    metaJson = q.metaJson,
                                )
                            }
                        }
                        backupResult = restoreSuccessStr.format(restored.size)
                    }.onFailure { e ->
                        backupResult = restoreFailedStr.format(e.message ?: "")
                    }
                    refreshCount()
                }
            }
        }

    val exportLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) {
                val titleStr = context.getString(R.string.vault_biometric_export_title)
                val cancelledStr = context.getString(R.string.vault_export_cancelled)
                val successStr = context.getString(R.string.vault_export_success)
                val failedStr = context.getString(R.string.vault_export_failed)
                scope.launch {
                    runCatching {
                        if (exportFormat == VaultFormats.FORMAT_VAULT && exportPassword.isBlank()) {
                            exportResult = passwordRequiredStr
                            return@launch
                        }
                        if (biometricEnabled && exportFormat == VaultFormats.FORMAT_VAULT) {
                            val ok = VaultBiometric.authenticate(context, biometricBuffer, titleStr)
                            if (!ok) {
                                exportResult = cancelledStr
                                return@launch
                            }
                        }
                        val entries = repository.getAll()
                        val plaintexts =
                            entries.mapNotNull { e ->
                                repository.decryptValue(e)?.let { VaultExporter.Quad(e.name, it, e.description, e.grp, e.publicKey, e.type) }
                            }
                        withContext(Dispatchers.IO) {
                            val output =
                                when (exportFormat) {
                                    VaultFormats.FORMAT_CSV -> VaultFormats.toCsv(plaintexts)
                                    VaultFormats.FORMAT_BITWARDEN -> VaultFormats.toBitwarden(plaintexts)
                                    VaultFormats.FORMAT_LOADCREDS -> VaultExporter.toLoadCreds(plaintexts)
                                    else -> VaultExporter.exportWithGroups(exportPassword, plaintexts)
                                }
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(output.encodeToByteArray())
                            }
                        }
                        plaintexts.forEach { q ->
                            repository.logAccess(q.name, "export", "export-$exportFormat")
                        }
                        exportResult = successStr.format(plaintexts.size, exportFormat)
                    }.onFailure { e ->
                        exportResult = failedStr.format(e.message ?: "")
                    }
                }
            }
        }

    val importLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        val content =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    BufferedReader(InputStreamReader(input)).use { it.readText() }
                                } ?: ""
                            }
                        val displayName =
                            runCatching {
                                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                        if (nameIndex >= 0) cursor.getString(nameIndex) else null
                                    } else {
                                        null
                                    }
                                }
                            }.getOrNull() ?: uri.lastPathSegment
                        when (val outcome = repository.importFromContent(content, displayName, importPassword)) {
                            is VaultImportOutcome.Success -> {
                                val result = outcome.result
                                importResult =
                                    context.getString(R.string.vault_import_success, result.imported, result.parsed) +
                                        if (result.overwrittenDifferentValue.isNotEmpty()) {
                                            "\n" + context.getString(
                                                R.string.vault_import_overwritten,
                                                result.overwrittenDifferentValue.size,
                                            )
                                        } else {
                                            ""
                                        }
                            }

                            VaultImportOutcome.Unrecognized ->
                                importResult = context.getString(R.string.vault_import_unrecognized)

                            VaultImportOutcome.PasswordRequired ->
                                importResult = context.getString(R.string.vault_export_password_required)
                        }
                    }.onFailure { e ->
                        importResult = context.getString(R.string.vault_import_failed, e.message ?: "")
                    }
                    refreshCount()
                }
            }
        }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_vault_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (unlocked) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = padding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    CardGroup {
                        item(
                            onClick = { navController.navigate(Screen.VaultCredentials) },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Key01,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            },
                            headlineContent = {
                                Text(stringResource(R.string.vault_credential_count, credentialCount))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.vault_page_intro))
                            },
                            trailingContent = {
                                Button(onClick = { navController.navigate(Screen.VaultCredentials) }) {
                                    Text(stringResource(R.string.vault_manage_credentials))
                                }
                            },
                        )
                    }
                }

                item {
                    var hasAppPin by remember { mutableStateOf<Boolean?>(null) }
                    var pinDialogShown by remember { mutableStateOf(false) }
                    var clearDialogShown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { hasAppPin = VaultAppLock.hasPin(context) }

                    CardGroup(
                        title = { Text(stringResource(R.string.vault_fingerprint_title)) },
                    ) {
                        switchItem(
                            checked = biometricEnabled,
                            onCheckedChange = { scope.launch { vaultPreferences.setBiometricEnabled(it) } },
                            leadingContent = { CardGroupIcon(HugeIcons.Shield01) },
                            headlineContent = { Text(stringResource(R.string.vault_fingerprint_desc)) },
                            supportingContent = { Text(stringResource(R.string.vault_fingerprint_plan)) },
                        )
                        item(
                            leadingContent = { CardGroupIcon(HugeIcons.LockKey) },
                            headlineContent = { Text(stringResource(R.string.vault_applock_title)) },
                            supportingContent = { Text(stringResource(R.string.vault_applock_desc)) },
                            trailingContent = {
                                Row {
                                    when (hasAppPin) {
                                        false ->
                                            TextButton(onClick = { pinDialogShown = true }) {
                                                Text(stringResource(R.string.vault_applock_set))
                                            }
                                        true -> {
                                            TextButton(onClick = { pinDialogShown = true }) {
                                                Text(stringResource(R.string.vault_applock_change))
                                            }
                                            TextButton(onClick = { clearDialogShown = true }) {
                                                Text(stringResource(R.string.vault_applock_clear))
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            },
                        )
                    }

                    if (pinDialogShown) {
                        var pinInput by remember { mutableStateOf("") }
                        var pinInput2 by remember { mutableStateOf("") }
                        val ok = pinInput.length >= 4 && pinInput == pinInput2
                        AlertDialog(
                            onDismissRequest = { pinDialogShown = false },
                            title = { Text(stringResource(R.string.vault_pin_setup_title)) },
                            text = {
                                Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = pinInput,
                                        onValueChange = { pinInput = it },
                                        label = { Text(stringResource(R.string.vault_pin_hint)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                                    )
                                    if (pinInput.isNotEmpty() && pinInput.length < 4) {
                                        Text(
                                            stringResource(R.string.vault_pin_too_short),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    OutlinedTextField(
                                        value = pinInput2,
                                        onValueChange = { pinInput2 = it },
                                        label = { Text(stringResource(R.string.vault_pin_confirm_hint)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                                    )
                                    if (pinInput2.isNotEmpty() && pinInput2 != pinInput) {
                                        Text(
                                            stringResource(R.string.vault_pin_mismatch),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = ok,
                                    onClick = {
                                        scope.launch {
                                            VaultAppLock.setPin(context, pinInput)
                                            hasAppPin = true
                                            pinDialogShown = false
                                        }
                                    },
                                ) { Text(stringResource(R.string.vault_pin_confirm)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { pinDialogShown = false }) {
                                    Text(stringResource(R.string.vault_pin_cancel))
                                }
                            },
                        )
                    }
                    if (clearDialogShown) {
                        AlertDialog(
                            onDismissRequest = { clearDialogShown = false },
                            title = { Text(stringResource(R.string.vault_applock_clear)) },
                            text = { Text(stringResource(R.string.vault_applock_clear_confirm)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            VaultAppLock.clearPin(context)
                                            hasAppPin = false
                                            clearDialogShown = false
                                        }
                                    },
                                ) { Text(stringResource(R.string.vault_applock_clear)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { clearDialogShown = false }) {
                                    Text(stringResource(R.string.vault_pin_cancel))
                                }
                            },
                        )
                    }
                }

                item {
                    VaultSessionSection(
                        vaultSessionManager = vaultSessionManager,
                        biometricEnabled = biometricEnabled,
                        biometricBuffer = biometricBuffer,
                        sessionToken = sessionToken,
                        onSessionTokenChange = { sessionToken = it },
                        sessionResult = sessionResult,
                        onSessionResultChange = { sessionResult = it },
                        sessionListState = sessionListState,
                        onRefreshSessionList = ::refreshSessionList,
                    )
                }

                item {
                    VaultAuditAndBackupSection(
                        shownAudit = shownAudit,
                        auditGroups = auditGroups,
                        auditAnomalies = auditAnomalies,
                        auditFilter = auditFilter,
                        onAuditFilterChange = { auditFilter = it },
                        auditActionFilter = auditActionFilter,
                        onAuditActionFilterChange = { auditActionFilter = it },
                        auditTimeDays = auditTimeDays,
                        onAuditTimeDaysChange = { auditTimeDays = it },
                        auditRetentionDays = auditRetentionDays,
                        auditCap = auditCap,
                        auditRollupMinutes = auditRollupMinutes,
                        onExportAudit = { fmt ->
                            auditExportFormat = fmt
                            auditExportLauncher.launch("vault-audit-${System.currentTimeMillis()}.$fmt")
                        },
                        onClearAuditConfirmed = {
                            repository.clearAudit()
                            refreshAudit()
                        },
                        importPassword = importPassword,
                        onImportPasswordChange = { importPassword = it },
                        onLaunchImport = {
                            importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                        },
                        importResult = importResult,
                        exportFormat = exportFormat,
                        onExportFormatSelect = { fmt ->
                            exportFormat = fmt
                            exportResult = null
                        },
                        exportPassword = exportPassword,
                        onExportPasswordChange = { exportPassword = it },
                        onLaunchExport = {
                            if (exportFormat == VaultFormats.FORMAT_VAULT && exportPassword.isBlank()) {
                                exportResult = passwordRequiredStr
                            } else {
                                val suffix =
                                    when (exportFormat) {
                                        VaultFormats.FORMAT_CSV -> ".csv"
                                        VaultFormats.FORMAT_BITWARDEN -> ".json"
                                        VaultFormats.FORMAT_LOADCREDS -> ".sh"
                                        else -> ".vault"
                                    }
                                exportLauncher.launch("RikkaHub-Agents-Vault-${System.currentTimeMillis()}$suffix")
                            }
                        },
                        exportResult = exportResult,
                        backupPassword = backupPassword,
                        onBackupPasswordChange = { backupPassword = it },
                        onLaunchBackupExport = { msg ->
                            if (backupPassword.isBlank()) {
                                backupResult = msg
                            } else {
                                backupExportLauncher.launch("RikkaHub-Agents-Vault-Backup-${System.currentTimeMillis()}.vault")
                            }
                        },
                        onLaunchBackupRestore = {
                            backupRestoreLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        backupResult = backupResult,
                        onShowClearDialog = { showClearDialog = true },
                        actionNeedsAuth = ::actionNeedsAuth,
                        formatTime = ::formatTime,
                    )
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CardGroupIcon(
                    icon = HugeIcons.LockKey,
                    containerSize = 56.dp,
                    iconSize = 28.dp,
                )
                Text(
                    text = stringResource(R.string.vault_biometric_locked_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                val biometricOpenTitle = stringResource(R.string.vault_biometric_open_title)
                Button(
                    onClick = {
                        scope.launch {
                            val ok =
                                VaultBiometric.authenticate(
                                    context = context,
                                    buffer = biometricBuffer,
                                    title = biometricOpenTitle,
                                )
                            unlocked = ok
                        }
                    },
                ) { Text(stringResource(R.string.vault_biometric_unlock)) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.vault_clear_confirm_title)) },
            text = { Text(stringResource(R.string.vault_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            repository.clearAll()
                            refreshCount()
                        }
                    },
                ) { Text(stringResource(R.string.vault_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.vault_cancel))
                }
            },
        )
    }
}

internal fun auditCountSuffix(count: Int): String = if (count > 1) " ×$count" else ""
