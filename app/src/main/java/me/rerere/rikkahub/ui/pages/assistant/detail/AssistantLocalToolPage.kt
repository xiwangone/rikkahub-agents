package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import rikka.shizuku.Shizuku

private const val TAG = "AssistantLocalToolPage"

/**
 * 助手本地工具权限装配页：170+ 本地与工作区工具开关、批量权限授权与过滤。
 *
 * 本文件很长（2100+ 行）。阅读与维护时请按下面的段落名检索符号，不要依赖行号：
 *  - 页面入口与分类列表：[AssistantLocalToolPage]
 *  - 工具分组：系统工具、工作区工具、终端工具、文件工具、浏览器与自动化
 *  - 权限与审批：逐个工具的免审批 / 强制审批配置
 *
 * 相关文件：工具定义 [me.rerere.rikkahub.data.ai.tools.LocalTools]；工具索引 [me.rerere.rikkahub.data.ai.tools.registry.ToolIndex]。
 */
@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM =
        koinViewModel(
            parameters = {
                parametersOf(id)
            },
        )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val sourceAssistants =
        remember(settings.assistants, id) {
            settings.assistants.filter { it.id.toString() != id }
        }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) },
            // Transform-based path used by the per-tool toggles. Each tap runs inside
            // SettingsStore.update's mutex against the genuinely-current Assistant, so
            // rapid taps no longer race + clobber each other (was: tap A then B then C
            // could land with only C persisted because each tap snapshotted the same
            // pre-A Assistant from `assistant.value`).
            onUpdateAssistant = { transform -> vm.updateAssistant(transform) },
            sourceAssistants = sourceAssistants,
            onImportToolConfig = { sourceId, includeMcp, includeSkills, includeWebSearch ->
                vm.importToolConfigFrom(sourceId, includeMcp, includeSkills, includeWebSearch)
            },
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    sourceAssistants: List<Assistant>,
    onImportToolConfig: (sourceId: String, includeMcp: Boolean, includeSkills: Boolean, includeWebSearch: Boolean) -> Unit,
) {
    fun toggleLocalTool(
        option: LocalToolOption,
        enabled: Boolean,
    ) {
        // Use the transform path so rapid taps (especially through a permission-grant
        // round-trip to system Settings) all serialise against the actual current state
        // instead of whatever stale snapshot the recomposition was holding.
        onUpdateAssistant { current ->
            current.copy(
                localTools =
                    if (enabled) {
                        current.localTools + option
                    } else {
                        current.localTools - option
                    },
            )
        }
    }

    // Setup-hint popups for toggles whose successful enable depends on user setup the
    // toggle itself can't perform: Termux's allow-external-apps property, the Telegram
    // bot token, and the cross-tool dependency hint for cron. Each is gated to fire at
    // most once per visit to this screen, and only when the missing thing is actually
    // missing (e.g. Termux dialog is suppressed if the integration was already verified
    // recently; Telegram dialog is suppressed if a token is on file).
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val telegramBotPreferences = koinInject<TelegramBotPreferences>()
    // Hardware-availability gate for the NFC toggle: a device with no NFC chip can never
    // run the nfc tools, so the toggle is shown disabled with a "no NFC hardware" subtitle
    // rather than letting the user enable a tool that would only ever error.
    val hasNfc =
        remember {
            ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
        }

    var showTermuxPostGrantDialog by remember { mutableStateOf(false) }
    var showTelegramNoTokenDialog by remember { mutableStateOf(false) }
    var showWorkflowsHintDialog by remember { mutableStateOf(false) }
    var showKeyboardSetupDialog by remember { mutableStateOf(false) }
    var termuxDialogShownThisVisit by remember { mutableStateOf(false) }
    var telegramDialogShownThisVisit by remember { mutableStateOf(false) }
    var cronToastShownThisVisit by remember { mutableStateOf(false) }
    var workflowsDialogShownThisVisit by remember { mutableStateOf(false) }
    var keyboardDialogShownThisVisit by remember { mutableStateOf(false) }

    // Import tool config from another assistant (one-shot copy, no live sync).
    var showExtraColdDialog by remember { mutableStateOf(false) }
    var extraColdDraft by remember(assistant.extraColdTools) {
        mutableStateOf(assistant.extraColdTools.joinToString("\n"))
    }

    var showCompactOutputDialog by remember { mutableStateOf(false) }
    var compactOutputDraft by remember(assistant.toolOutputCompactTools) {
        mutableStateOf(assistant.toolOutputCompactTools.joinToString("\n"))
    }

    var showDigestDialog by remember { mutableStateOf(false) }
    var digestToolsDraft by remember(assistant.toolOutputDigestTools) {
        mutableStateOf(assistant.toolOutputDigestTools.joinToString("\n"))
    }
    var digestKeywordsDraft by remember(assistant.toolOutputDigestKeywords) {
        mutableStateOf(assistant.toolOutputDigestKeywords.joinToString("\n"))
    }

    var showOnlyToolsDialog by remember { mutableStateOf(false) }
    var onlyToolsDraft by remember(assistant.onlyTools) {
        mutableStateOf(assistant.onlyTools.joinToString("\n"))
    }
    var pendingOnlyTools by remember { mutableStateOf<List<String>?>(null) }
    var showImportSourcePicker by remember { mutableStateOf(false) }
    var importCandidate by remember { mutableStateOf<Assistant?>(null) }
    var importIncludeMcp by remember { mutableStateOf(true) }
    var importIncludeSkills by remember { mutableStateOf(true) }
    var importIncludeWebSearch by remember { mutableStateOf(true) }

    val cronHintText = stringResource(R.string.assistant_page_local_tools_cron_jobs_toast_hint)
    val termuxCommand = stringResource(R.string.assistant_page_local_tools_termux_postgrant_command)
    val termuxCopiedText = stringResource(R.string.assistant_page_local_tools_termux_postgrant_copied)

    AssistantLocalToolDialogs(
        ctx = ctx,
        toaster = toaster,
        sourceAssistants = sourceAssistants,
        onUpdateAssistant = onUpdateAssistant,
        onImportToolConfig = onImportToolConfig,
        termuxCommand = termuxCommand,
        termuxCopiedText = termuxCopiedText,
        showTermuxPostGrantDialog = showTermuxPostGrantDialog,
        onDismissTermuxPostGrantDialog = { showTermuxPostGrantDialog = false },
        showExtraColdDialog = showExtraColdDialog,
        onDismissExtraColdDialog = { showExtraColdDialog = false },
        extraColdDraft = extraColdDraft,
        onExtraColdDraftChange = { extraColdDraft = it },
        showCompactOutputDialog = showCompactOutputDialog,
        onDismissCompactOutputDialog = { showCompactOutputDialog = false },
        compactOutputDraft = compactOutputDraft,
        onCompactOutputDraftChange = { compactOutputDraft = it },
        showDigestDialog = showDigestDialog,
        onDismissDigestDialog = { showDigestDialog = false },
        digestToolsDraft = digestToolsDraft,
        onDigestToolsDraftChange = { digestToolsDraft = it },
        digestKeywordsDraft = digestKeywordsDraft,
        onDigestKeywordsDraftChange = { digestKeywordsDraft = it },
        showOnlyToolsDialog = showOnlyToolsDialog,
        onDismissOnlyToolsDialog = { showOnlyToolsDialog = false },
        onlyToolsDraft = onlyToolsDraft,
        onOnlyToolsDraftChange = { onlyToolsDraft = it },
        pendingOnlyTools = pendingOnlyTools,
        onPendingOnlyToolsChange = { pendingOnlyTools = it },
        showTelegramNoTokenDialog = showTelegramNoTokenDialog,
        onDismissTelegramNoTokenDialog = { showTelegramNoTokenDialog = false },
        showWorkflowsHintDialog = showWorkflowsHintDialog,
        onDismissWorkflowsHintDialog = { showWorkflowsHintDialog = false },
        showKeyboardSetupDialog = showKeyboardSetupDialog,
        onDismissKeyboardSetupDialog = { showKeyboardSetupDialog = false },
        showImportSourcePicker = showImportSourcePicker,
        onDismissImportSourcePicker = { showImportSourcePicker = false },
        importCandidate = importCandidate,
        onImportCandidateChange = { importCandidate = it },
        importIncludeMcp = importIncludeMcp,
        onImportIncludeMcpChange = { importIncludeMcp = it },
        importIncludeSkills = importIncludeSkills,
        onImportIncludeSkillsChange = { importIncludeSkills = it },
        importIncludeWebSearch = importIncludeWebSearch,
        onImportIncludeWebSearchChange = { importIncludeWebSearch = it },
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Import tool config from another assistant (one-shot copy)
        CardGroup {
            item(
                onClick = { showImportSourcePicker = true },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_import_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_import_desc))
                },
            )
        }

        // 助手级「额外降冷档」：只做少注入（不提供升档 —— 扩大注入集会改变请求前缀、击穿缓存）
        Text(
            text = stringResource(R.string.assistant_page_extra_cold_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_extra_cold_title)) },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_extra_cold_desc, assistant.extraColdTools.size))
                },
                trailingContent = {
                    TextButton(onClick = { showExtraColdDialog = true }) {
                        Text(stringResource(R.string.assistant_page_extra_cold_action))
                    }
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_digest_output_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.assistant_page_digest_output_desc,
                            assistant.toolOutputDigestTools.size,
                        ),
                    )
                },
                trailingContent = {
                    TextButton(onClick = { showDigestDialog = true }) {
                        Text(stringResource(R.string.assistant_page_compact_output_action))
                    }
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_compact_output_title)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.assistant_page_compact_output_desc,
                            assistant.toolOutputCompactTools.size,
                        ),
                    )
                },
                trailingContent = {
                    TextButton(onClick = { showCompactOutputDialog = true }) {
                        Text(stringResource(R.string.assistant_page_compact_output_action))
                    }
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_only_tools_title)) },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_only_tools_desc, assistant.onlyTools.size))
                },
                trailingContent = {
                    TextButton(onClick = { showOnlyToolsDialog = true }) {
                        Text(stringResource(R.string.assistant_page_extra_cold_action))
                    }
                },
            )
        }


        // Built-in tools section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_existing),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            toolSwitchItem(R.string.assistant_page_local_tools_javascript_engine_title, R.string.assistant_page_local_tools_javascript_engine_desc, assistant.localTools.contains(LocalToolOption.JavascriptEngine), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_time_info_title, R.string.assistant_page_local_tools_time_info_desc, assistant.localTools.contains(LocalToolOption.TimeInfo), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.TimeInfo, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_clipboard_title, R.string.assistant_page_local_tools_clipboard_desc, assistant.localTools.contains(LocalToolOption.Clipboard), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.Clipboard, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_tts_title, R.string.assistant_page_local_tools_tts_desc, assistant.localTools.contains(LocalToolOption.Tts), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.Tts, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_ask_user_title, R.string.assistant_page_local_tools_ask_user_desc, assistant.localTools.contains(LocalToolOption.AskUser), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.AskUser, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_diagnostics_title, R.string.assistant_page_local_tools_diagnostics_desc, assistant.localTools.contains(LocalToolOption.Diagnostics), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.Diagnostics, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_model_testing_title, R.string.assistant_page_local_tools_model_testing_desc, assistant.localTools.contains(LocalToolOption.ModelTesting), usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.ModelTesting, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_device_info_title, R.string.assistant_page_local_tools_device_info_desc, assistant.localTools.contains(LocalToolOption.DeviceInfo)) { toggleLocalTool(LocalToolOption.DeviceInfo, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_system_intents_title, R.string.assistant_page_local_tools_system_intents_desc, assistant.localTools.contains(LocalToolOption.SystemIntents)) { toggleLocalTool(LocalToolOption.SystemIntents, it) }
        }

        // Output section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_output),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            toolSwitchItem(R.string.assistant_page_local_tools_toast_title, R.string.assistant_page_local_tools_toast_desc, assistant.localTools.contains(LocalToolOption.Toast)) { toggleLocalTool(LocalToolOption.Toast, it) }
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notification_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notification_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Notification),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Notification, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_share_title, R.string.assistant_page_local_tools_share_desc, assistant.localTools.contains(LocalToolOption.Share)) { toggleLocalTool(LocalToolOption.Share, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_chart_display_title, R.string.assistant_page_local_tools_chart_display_desc, assistant.localTools.contains(LocalToolOption.ChartDisplay)) { toggleLocalTool(LocalToolOption.ChartDisplay, it) }
        }

        // Personal data section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_personal_data),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_location_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_location_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Location),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Location, it) },
                        requiredRuntimePerms =
                            listOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_contacts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_contacts_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Contacts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Contacts, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_CONTACTS),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_call_log_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_call_log_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CallLog),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CallLog, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_CALL_LOG),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_inbox_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_inbox_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SmsInbox),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SmsInbox, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_SMS),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_camera_photo_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_camera_photo_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CameraPhoto),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CameraPhoto, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.CAMERA),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mic_recorder_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mic_recorder_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.MicRecorder),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.MicRecorder, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_speech_to_text_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_speech_to_text_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SpeechToText),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SpeechToText, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_fingerprint_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_fingerprint_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Fingerprint),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Fingerprint, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.USE_BIOMETRIC),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notifications_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notifications_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.NotificationListener),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.NotificationListener, it) },
                        requiresNotificationListener = true,
                    )
                },
            )
        }

        // Media section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_media),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_files_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_files_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Files),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Files, it) },
                        // MANAGE_EXTERNAL_STORAGE is a special "appop" permission. Without it,
                        // File.listFiles() on shared storage paths only returns subdirectories
                        // and the app's own creations — every pre-existing file is hidden.
                        requiresAllFilesAccess = true,
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_download_title, R.string.assistant_page_local_tools_download_desc, assistant.localTools.contains(LocalToolOption.Download)) { toggleLocalTool(LocalToolOption.Download, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_media_player_title, R.string.assistant_page_local_tools_media_player_desc, assistant.localTools.contains(LocalToolOption.MediaPlayer)) { toggleLocalTool(LocalToolOption.MediaPlayer, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_media_scanner_title, R.string.assistant_page_local_tools_media_scanner_desc, assistant.localTools.contains(LocalToolOption.MediaScanner)) { toggleLocalTool(LocalToolOption.MediaScanner, it) }
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_network),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ssh_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ssh_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Ssh),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Ssh, it) },
                        // Android 17 (API 37) blocks LAN socket access without
                        // ACCESS_LOCAL_NETWORK. SSH is the canonical case — every
                        // saved host lives on someone's WiFi/VPN. On older API
                        // levels the permission doesn't exist; the request silently
                        // no-ops since the manifest-declared permission isn't
                        // dangerous-protection there.
                        requiredRuntimePerms =
                            if (Build.VERSION.SDK_INT >= 37) {
                                listOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                            } else {
                                emptyList()
                            },
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telegram_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telegram_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.TelegramBot),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.TelegramBot, newValue)
                            if (newValue && !telegramDialogShownThisVisit) {
                                scope.launch {
                                    if (telegramBotPreferences.current().token.isBlank()) {
                                        telegramDialogShownThisVisit = true
                                        showTelegramNoTokenDialog = true
                                    }
                                }
                            }
                        },
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_browser_title, R.string.assistant_page_local_tools_browser_desc, assistant.localTools.contains(LocalToolOption.Browser)) { toggleLocalTool(LocalToolOption.Browser, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_web_fetch_title, R.string.assistant_page_local_tools_web_fetch_desc, assistant.localTools.contains(LocalToolOption.WebFetch)) { toggleLocalTool(LocalToolOption.WebFetch, it) }
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_automation),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.CronJobs),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.CronJobs, newValue)
                            if (newValue && !cronToastShownThisVisit) {
                                cronToastShownThisVisit = true
                                toaster.show(cronHintText)
                            }
                        },
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_workflows_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_workflows_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Workflows),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.Workflows, newValue)
                            // Workflows depend on a mix of runtime grants the toggle itself
                            // can't request (geofence needs background-location, notification
                            // triggers need notification-listener, app-launch triggers need
                            // accessibility, BT triggers need BLUETOOTH_CONNECT). Surface a
                            // one-time hint at enable so the user knows what to grant when
                            // they author a workflow whose trigger needs it.
                            if (newValue && !workflowsDialogShownThisVisit) {
                                workflowsDialogShownThisVisit = true
                                showWorkflowsHintDialog = true
                            }
                        },
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_sub_agents_title, R.string.assistant_page_local_tools_sub_agents_desc, assistant.localTools.contains(LocalToolOption.SubAgents)) { toggleLocalTool(LocalToolOption.SubAgents, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_skill_import_title, R.string.assistant_page_local_tools_skill_import_desc, assistant.localTools.contains(LocalToolOption.SkillImport)) { toggleLocalTool(LocalToolOption.SkillImport, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_js_skills_title, R.string.assistant_page_local_tools_js_skills_desc, assistant.localTools.contains(LocalToolOption.JsSkills)) { toggleLocalTool(LocalToolOption.JsSkills, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_mcp_control_title, R.string.assistant_page_local_tools_mcp_control_desc, assistant.localTools.contains(LocalToolOption.McpControl)) { toggleLocalTool(LocalToolOption.McpControl, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_external_automation_title, R.string.assistant_page_local_tools_external_automation_desc, assistant.localTools.contains(LocalToolOption.ExternalAutomation)) { toggleLocalTool(LocalToolOption.ExternalAutomation, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_reliability_title, R.string.assistant_page_local_tools_reliability_desc, assistant.localTools.contains(LocalToolOption.Reliability)) { toggleLocalTool(LocalToolOption.Reliability, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_cost_guards_title, R.string.assistant_page_local_tools_cost_guards_desc, assistant.localTools.contains(LocalToolOption.CostGuards)) { toggleLocalTool(LocalToolOption.CostGuards, it) }
        }

        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_screen_automation),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_automation_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_automation_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenAutomation),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenAutomation, it) },
                        requiresAccessibilityService = true,
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_app_launcher_title, R.string.assistant_page_local_tools_app_launcher_desc, assistant.localTools.contains(LocalToolOption.AppLauncher)) { toggleLocalTool(LocalToolOption.AppLauncher, it) }
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_title))
                },
                supportingContent = {
                    TermuxStatusRowSubtitle(
                        enabled = assistant.localTools.contains(LocalToolOption.Termux),
                    )
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Termux),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.Termux, newValue)
                            if (newValue && !termuxDialogShownThisVisit) {
                                // Skip the dialog if a recent successful verify proves the
                                // property file is already in place — nothing new to teach.
                                val recentlyVerified =
                                    TermuxIntegration.lastVerifiedOkAtMs > 0 &&
                                        (System.currentTimeMillis() - TermuxIntegration.lastVerifiedOkAtMs) <
                                        24L * 60 * 60 * 1000
                                if (!recentlyVerified) {
                                    termuxDialogShownThisVisit = true
                                    showTermuxPostGrantDialog = true
                                }
                            }
                        },
                        // Termux's RUN_COMMAND service is gated behind a dangerous-level
                        // custom permission. Requesting it through the standard runtime flow
                        // pops the system dialog so termux_run_command works without an adb
                        // grant. If Termux is not installed the request silently no-ops.
                        requiredRuntimePerms = listOf("com.termux.permission.RUN_COMMAND"),
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_keyboard_title))
                },
                supportingContent = {
                    KeyboardStatusRowSubtitle()
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.KeyboardControl),
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.KeyboardControl, newValue)
                            // One-time enable-time hint: the toggle can't install the
                            // companion app or switch the active IME for the user.
                            if (newValue && !keyboardDialogShownThisVisit) {
                                keyboardDialogShownThisVisit = true
                                showKeyboardSetupDialog = true
                            }
                        },
                    )
                },
            )
        }

        // Hardware control section
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_hardware),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            toolSwitchItem(R.string.assistant_page_local_tools_torch_title, R.string.assistant_page_local_tools_torch_desc, assistant.localTools.contains(LocalToolOption.Torch)) { toggleLocalTool(LocalToolOption.Torch, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_vibrate_title, R.string.assistant_page_local_tools_vibrate_desc, assistant.localTools.contains(LocalToolOption.Vibrate)) { toggleLocalTool(LocalToolOption.Vibrate, it) }
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_brightness_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_brightness_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Brightness),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Brightness, it) },
                        requiresWriteSettings = true,
                    )
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_volume_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_volume_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.Volume),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Volume, it) },
                        requiresDndAccess = true,
                    )
                },
            )
        }

        // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
        Text(
            text = stringResource(R.string.assistant_page_local_tools_section_privileged),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_send_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_send_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.SmsSend),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SmsSend, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.SEND_SMS),
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_wallpaper_title, R.string.assistant_page_local_tools_wallpaper_desc, assistant.localTools.contains(LocalToolOption.Wallpaper)) { toggleLocalTool(LocalToolOption.Wallpaper, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_keystore_title, R.string.assistant_page_local_tools_keystore_desc, assistant.localTools.contains(LocalToolOption.Keystore)) { toggleLocalTool(LocalToolOption.Keystore, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_shizuku_title, R.string.assistant_page_local_tools_shizuku_desc, assistant.localTools.contains(LocalToolOption.Shizuku)) { toggleLocalTool(LocalToolOption.Shizuku, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_vault_tools_title, R.string.assistant_page_local_tools_vault_tools_desc, assistant.localTools.contains(LocalToolOption.VaultTools)) { toggleLocalTool(LocalToolOption.VaultTools, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_vault_export_env_title, R.string.assistant_page_local_tools_vault_export_env_desc, assistant.localTools.contains(LocalToolOption.VaultExportEnv)) { toggleLocalTool(LocalToolOption.VaultExportEnv, it) }
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_nfc_title))
                },
                supportingContent = {
                    Text(
                        if (hasNfc) {
                            stringResource(R.string.assistant_page_local_tools_nfc_desc)
                        } else {
                            stringResource(R.string.assistant_page_local_tools_nfc_unavailable)
                        },
                    )
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = hasNfc && assistant.localTools.contains(LocalToolOption.Nfc),
                        // Guard the callback too: even if the switch is somehow toggled,
                        // a device with no NFC chip never gets the tool enabled.
                        onCheckedChange = { if (hasNfc) toggleLocalTool(LocalToolOption.Nfc, it) },
                        enabled = hasNfc,
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_external_storage_title, R.string.assistant_page_local_tools_external_storage_desc, assistant.localTools.contains(LocalToolOption.ExternalStorage)) { toggleLocalTool(LocalToolOption.ExternalStorage, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_archive_title, R.string.assistant_page_local_tools_archive_desc, assistant.localTools.contains(LocalToolOption.Archive)) { toggleLocalTool(LocalToolOption.Archive, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_app_backup_title, R.string.assistant_page_local_tools_app_backup_desc, assistant.localTools.contains(LocalToolOption.AppBackup)) { toggleLocalTool(LocalToolOption.AppBackup, it) }
        }

    }
}
