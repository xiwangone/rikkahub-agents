package me.rerere.rikkahub.ui.pages.setting.doctor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vault.VaultProviderKeyRefs
import me.rerere.rikkahub.service.debug.DebugApiManager
import me.rerere.rikkahub.service.debug.DebugApiStartFailure
import me.rerere.rikkahub.service.debug.ListenConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.NetworkAccessField
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DoctorScreen(vm: DoctorViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    val severityCounts =
        remember(state.results) {
            mapOf(
                Severity.FAIL to state.results.count { it.severity == Severity.FAIL },
                Severity.WARN to state.results.count { it.severity == Severity.WARN },
                Severity.OK to state.results.count { it.severity == Severity.OK },
                Severity.INFO to state.results.count { it.severity == Severity.INFO },
            )
        }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_doctor)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                SummaryCard(
                    counts = severityCounts,
                    running = state.running,
                    onRunAgain = { vm.runAll() },
                    onCopyReport = {
                        copyToClipboard(ctx, vm.buildReport())
                        scope.launch {
                            snackbar.showSnackbar(resources.getString(R.string.setting_page_doctor_report_copied))
                        }
                    },
                )
            }

            item(key = "debug_api") {
                DebugApiCard(manager = koinInject())
            }

            DoctorCategory.entries.forEach { category ->
                val rows = state.results.filter { it.category == category }
                if (rows.isEmpty()) return@forEach
                item("group-${category.name}") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        title = { Text(stringResource(category.displayNameRes)) },
                    ) {
                        for (check in rows) {
                            item(
                                leadingContent = { SeverityDot(check.severity) },
                                headlineContent = { Text(stringResource(check.labelRes)) },
                                supportingContent = {
                                    Text(
                                        check.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = {
                                    val fix = check.fix
                                    if (fix != null) {
                                        FixButton(
                                            fix = fix,
                                            onAutoFix = { f ->
                                                scope.launch {
                                                    val res = vm.applyAutoFix(f)
                                                    snackbar.showSnackbar(res.message)
                                                }
                                            },
                                            onOpenIntent = { intent ->
                                                runCatching {
                                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    ctx.startActivity(intent)
                                                }.onFailure {
                                                    scope.launch {
                                                        snackbar.showSnackbar(
                                                            resources.getString(
                                                                R.string.doctor_msg_open_failed,
                                                                it.message ?: it::class.simpleName ?: "?",
                                                            ),
                                                        )
                                                    }
                                                }
                                            },
                                            onOpenAppRoute = { key -> nav.navigate(routeFor(key)) },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    counts: Map<Severity, Int>,
    running: Boolean,
    onRunAgain: () -> Unit,
    onCopyReport: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CountPill(stringResource(R.string.doctor_summary_fail), counts[Severity.FAIL] ?: 0, Severity.FAIL)
                CountPill(stringResource(R.string.doctor_summary_warn), counts[Severity.WARN] ?: 0, Severity.WARN)
                CountPill(stringResource(R.string.doctor_summary_ok), counts[Severity.OK] ?: 0, Severity.OK)
                CountPill(stringResource(R.string.doctor_summary_info), counts[Severity.INFO] ?: 0, Severity.INFO)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onRunAgain,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.setting_page_doctor_running))
                    } else {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.setting_page_doctor_run_again))
                    }
                }
                OutlinedButton(
                    onClick = onCopyReport,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.setting_page_doctor_copy_report))
                }
            }
        }
    }
}

@Composable
private fun CountPill(
    label: String,
    count: Int,
    severity: Severity,
) {
    val color = severityColor(severity)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            color = color,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SeverityDot(severity: Severity) {
    Box(
        modifier =
            Modifier
                .size(10.dp)
                .background(severityColor(severity), CircleShape),
    )
}

@Composable
private fun FixButton(
    fix: FixAction,
    onAutoFix: (FixAction.AutoFix) -> Unit,
    onOpenIntent: (android.content.Intent) -> Unit,
    onOpenAppRoute: (AppRouteKey) -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val (labelRes, click) =
        when (fix) {
            is FixAction.AutoFix -> fix.labelRes to {
                // 破坏性修复先弹确认框；其余修复保持点即执行
                if (fix.confirmRes != null) showConfirm = true else onAutoFix(fix)
            }
            is FixAction.OpenIntent -> fix.labelRes to { onOpenIntent(fix.intent) }
            is FixAction.OpenAppRoute -> fix.labelRes to { onOpenAppRoute(fix.routeKey) }
        }
    if (showConfirm && fix is FixAction.AutoFix && fix.confirmRes != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            text = { Text(stringResource(fix.confirmRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onAutoFix(fix)
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    OutlinedButton(
        onClick = click,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.OK -> MaterialTheme.colorScheme.primary
        Severity.INFO -> MaterialTheme.colorScheme.tertiary
        Severity.WARN -> Color(0xFFFFB300)
        Severity.FAIL -> MaterialTheme.colorScheme.error
    }

private fun routeFor(key: AppRouteKey): Screen =
    when (key) {
        AppRouteKey.SettingTelegram -> Screen.SettingTelegram
        AppRouteKey.SettingScheduledJobs -> Screen.SettingScheduledJobs
        AppRouteKey.SettingWorkflows -> Screen.SettingWorkflows
        AppRouteKey.SettingPermissions -> Screen.SettingPermissions
        AppRouteKey.SettingProvider -> Screen.SettingProvider
        AppRouteKey.Assistant -> Screen.Assistant
    }

private fun copyToClipboard(
    ctx: Context,
    text: String,
) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.doctor_report_clip_label), text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugApiCard(manager: DebugApiManager) {
    val scope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val port by manager.port.collectAsStateWithLifecycle()
    val storedToken by manager.tokenFlow.collectAsStateWithLifecycle(initialValue = "")
    var tokenInput by remember { mutableStateOf("") }
    val startError by manager.startError.collectAsStateWithLifecycle()
    val listenCfg by manager.listenConfig.collectAsStateWithLifecycle(
        initialValue = ListenConfig(ListenConfig.MODE_LOOPBACK, DebugApiManager.HOST_LOOPBACK, ""),
    )
    var revealedToken by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val savedMessageText = stringResource(R.string.setting_page_doctor_debug_api_saved)
    val copiedMessageText = stringResource(R.string.setting_page_doctor_debug_api_copied)

    CardGroup(
        modifier = Modifier.padding(horizontal = 4.dp),
        title = { Text(stringResource(R.string.setting_page_doctor_debug_api_title)) },
    ) {
        switchItem(
            checked = running,
            onCheckedChange = { enabled ->
                scope.launch { manager.setEnabled(enabled) }
            },
            leadingContent = { CardGroupIcon(HugeIcons.Bug01) },
            headlineContent = {
                Text(stringResource(R.string.setting_page_doctor_debug_api_title))
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.setting_page_doctor_debug_api_desc))
                    Text(
                        text =
                            if (running && port > 0) {
                                stringResource(
                                    R.string.setting_page_doctor_debug_api_status_running,
                                    listenCfg.host,
                                    port,
                                )
                            } else {
                                stringResource(R.string.setting_page_doctor_debug_api_status_stopped)
                            },
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    startError?.let { err ->
                        Text(
                            text =
                                stringResource(
                                    if (err == DebugApiStartFailure.TOKEN_REF_UNRESOLVED) {
                                        R.string.setting_page_doctor_debug_api_err_ref
                                    } else {
                                        R.string.setting_page_doctor_debug_api_err_port
                                    },
                                ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (running && port > 0 && port != DebugApiManager.PORT) {
                        Text(stringResource(R.string.setting_page_doctor_debug_api_port_moved, port))
                    }
                }
            },
        )

        formItem(
            leadingContent = { CardGroupIcon(HugeIcons.Earth) },
            headlineContent = { Text(stringResource(R.string.setting_page_doctor_debug_api_listen)) },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = listenCfg.mode == ListenConfig.MODE_LOOPBACK,
                    onClick = { scope.launch { manager.setListenMode(ListenConfig.MODE_LOOPBACK) } },
                    label = { Text(stringResource(R.string.setting_page_doctor_debug_api_listen_loopback)) },
                )
                FilterChip(
                    selected = listenCfg.mode == ListenConfig.MODE_LAN,
                    onClick = { scope.launch { manager.setListenMode(ListenConfig.MODE_LAN) } },
                    label = { Text(stringResource(R.string.setting_page_doctor_debug_api_listen_lan)) },
                )
                FilterChip(
                    selected = listenCfg.mode == ListenConfig.MODE_CUSTOM,
                    onClick = { scope.launch { manager.setListenMode(ListenConfig.MODE_CUSTOM) } },
                    label = { Text(stringResource(R.string.setting_page_doctor_debug_api_listen_custom)) },
                )
                FilterChip(
                    selected = listenCfg.mode == ListenConfig.MODE_ALL,
                    onClick = { scope.launch { manager.setListenMode(ListenConfig.MODE_ALL) } },
                    label = { Text(stringResource(R.string.setting_page_doctor_debug_api_listen_all)) },
                )
            }
            if (listenCfg.mode == ListenConfig.MODE_CUSTOM) {
                var cidrInput by remember(listenCfg.mode) { mutableStateOf(listenCfg.cidrs) }
                NetworkAccessField(
                    value = cidrInput,
                    onValueChange = { cidrInput = it },
                    presets = settings.networkCidrPresets,
                    onPresetsChange = { presets ->
                        scope.launch { settingsStore.update { it.copy(networkCidrPresets = presets) } }
                    },
                    label = stringResource(R.string.setting_page_doctor_debug_api_cidrs_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            manager.setCustomCidrs(cidrInput)
                            toaster.show(
                                message = context.getString(R.string.setting_page_doctor_debug_api_saved),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setting_page_doctor_debug_api_cidrs_apply))
                }
            }
            if (listenCfg.mode == ListenConfig.MODE_ALL) {
                Text(
                    stringResource(R.string.setting_page_doctor_debug_api_listen_all_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.setting_page_doctor_debug_api_exec_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val live = if (revealedToken) manager.currentToken() else null
        formItem(
            leadingContent = { CardGroupIcon(HugeIcons.Key01) },
            headlineContent = { Text(stringResource(R.string.setting_page_doctor_debug_api_token_hint)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.setting_page_doctor_debug_api_current,
                        live ?: tokenSourceLabel(storedToken),
                    ),
                )
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecretRefField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = stringResource(R.string.setting_page_doctor_debug_api_token_hint),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            manager.setToken(tokenInput)
                            tokenInput = ""
                            revealedToken = false
                            toaster.show(message = savedMessageText)
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { revealedToken = !revealedToken }) {
                    Text(
                        stringResource(
                            if (revealedToken) {
                                R.string.setting_page_doctor_debug_api_hide
                            } else {
                                R.string.setting_page_doctor_debug_api_show
                            },
                        ),
                    )
                }
                TextButton(
                    onClick = {
                        manager.currentToken()?.let {
                            clipboardManager.setText(AnnotatedString(it))
                            toaster.show(message = copiedMessageText)
                        }
                    },
                ) {
                    Text(stringResource(R.string.setting_page_doctor_debug_api_copy))
                }
            }
        }
    }
}

@Composable
private fun tokenSourceLabel(stored: String): String =
    when {
        stored.isBlank() -> stringResource(R.string.setting_page_doctor_debug_api_src_auto)
        stored.startsWith(VaultProviderKeyRefs.PREFIX) ->
            stringResource(
                if (VaultProviderKeyRefs.resolveOrNull(stored) != null) {
                    R.string.setting_page_doctor_debug_api_src_ref_ok
                } else {
                    R.string.setting_page_doctor_debug_api_src_ref_bad
                },
                stored,
            )
        else -> stringResource(R.string.setting_page_doctor_debug_api_src_custom, displayToken(stored))
    }

private fun displayToken(token: String): String =
    when {
        token.isBlank() -> ""
        token.startsWith("\$") -> token
        else -> token.take(2) + "•".repeat((token.length - 4).coerceAtLeast(3)) + token.takeLast(2)
    }
