package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
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
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.FilterHorizontal
import me.rerere.hugeicons.stroke.Snow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * 助手本地工具权限装配页：本地与工作区工具开关、批量权限授权与过滤。
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
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val screenTimePermissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    fun toggleLocalTool(
        option: LocalToolOption,
        enabled: Boolean,
    ) {
        if (enabled && option == LocalToolOption.ScreenTime && !ctx.hasUsageStatsPermission()) {
            toaster.show(message = screenTimePermissionRequiredText, type = ToastType.Warning)
            ctx.openUsageAccessSettings()
        }
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

    val scope = rememberCoroutineScope()
    val telegramBotPreferences = koinInject<TelegramBotPreferences>()
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
        CardGroup {
            item(
                onClick = { showImportSourcePicker = true },
                leadingContent = { CardGroupIcon(HugeIcons.Copy01) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_import_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_import_desc))
                },
            )
        }

        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_extra_cold_section)) },
        ) {
            item(
                leadingContent = { CardGroupIcon(HugeIcons.Snow) },
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
                leadingContent = { CardGroupIcon(HugeIcons.ChartColumn) },
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
                leadingContent = { CardGroupIcon(HugeIcons.FilterHorizontal) },
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
                leadingContent = { CardGroupIcon(HugeIcons.CheckList) },
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
        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_existing)) },
        ) {
            toolSwitchItem(R.string.assistant_page_local_tools_javascript_engine_title, R.string.assistant_page_local_tools_javascript_engine_desc, assistant.localTools.contains(LocalToolOption.JavascriptEngine), option = LocalToolOption.JavascriptEngine, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_time_info_title, R.string.assistant_page_local_tools_time_info_desc, assistant.localTools.contains(LocalToolOption.TimeInfo), option = LocalToolOption.TimeInfo, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.TimeInfo, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_clipboard_title, R.string.assistant_page_local_tools_clipboard_desc, assistant.localTools.contains(LocalToolOption.Clipboard), option = LocalToolOption.Clipboard, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.Clipboard, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_tts_title, R.string.assistant_page_local_tools_tts_desc, assistant.localTools.contains(LocalToolOption.Tts), option = LocalToolOption.Tts, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.Tts, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_ask_user_title, R.string.assistant_page_local_tools_ask_user_desc, assistant.localTools.contains(LocalToolOption.AskUser), option = LocalToolOption.AskUser, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.AskUser, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_screen_time_title, R.string.assistant_page_local_tools_screen_time_desc, assistant.localTools.contains(LocalToolOption.ScreenTime), option = LocalToolOption.ScreenTime, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.ScreenTime, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_diagnostics_title, R.string.assistant_page_local_tools_diagnostics_desc, assistant.localTools.contains(LocalToolOption.Diagnostics), option = LocalToolOption.Diagnostics, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.Diagnostics, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_model_testing_title, R.string.assistant_page_local_tools_model_testing_desc, assistant.localTools.contains(LocalToolOption.ModelTesting), option = LocalToolOption.ModelTesting, usePermissionedSwitch = false) { toggleLocalTool(LocalToolOption.ModelTesting, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_device_info_title, R.string.assistant_page_local_tools_device_info_desc, assistant.localTools.contains(LocalToolOption.DeviceInfo), option = LocalToolOption.DeviceInfo) { toggleLocalTool(LocalToolOption.DeviceInfo, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_system_intents_title, R.string.assistant_page_local_tools_system_intents_desc, assistant.localTools.contains(LocalToolOption.SystemIntents), option = LocalToolOption.SystemIntents) { toggleLocalTool(LocalToolOption.SystemIntents, it) }
        }

        // Output section
        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_output)) },
        ) {
            toolSwitchItem(R.string.assistant_page_local_tools_toast_title, R.string.assistant_page_local_tools_toast_desc, assistant.localTools.contains(LocalToolOption.Toast), option = LocalToolOption.Toast) { toggleLocalTool(LocalToolOption.Toast, it) }
            val notifChecked = assistant.localTools.contains(LocalToolOption.Notification)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Notification, notifChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notification_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notification_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = notifChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Notification, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_share_title, R.string.assistant_page_local_tools_share_desc, assistant.localTools.contains(LocalToolOption.Share), option = LocalToolOption.Share) { toggleLocalTool(LocalToolOption.Share, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_chart_display_title, R.string.assistant_page_local_tools_chart_display_desc, assistant.localTools.contains(LocalToolOption.ChartDisplay), option = LocalToolOption.ChartDisplay) { toggleLocalTool(LocalToolOption.ChartDisplay, it) }
        }

        // Personal data section
        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_personal_data)) },
        ) {
            val locationChecked = assistant.localTools.contains(LocalToolOption.Location)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Location, locationChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_location_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_location_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = locationChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Location, it) },
                        requiredRuntimePerms =
                            listOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                    )
                },
            )
            val contactsChecked = assistant.localTools.contains(LocalToolOption.Contacts)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Contacts, contactsChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_contacts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_contacts_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = contactsChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Contacts, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_CONTACTS),
                    )
                },
            )
            val callLogChecked = assistant.localTools.contains(LocalToolOption.CallLog)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.CallLog, callLogChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_call_log_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_call_log_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = callLogChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CallLog, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_CALL_LOG),
                    )
                },
            )
            val smsInboxChecked = assistant.localTools.contains(LocalToolOption.SmsInbox)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.SmsInbox, smsInboxChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_inbox_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_inbox_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = smsInboxChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SmsInbox, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.READ_SMS),
                    )
                },
            )
            val cameraChecked = assistant.localTools.contains(LocalToolOption.CameraPhoto)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.CameraPhoto, cameraChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_camera_photo_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_camera_photo_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = cameraChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.CameraPhoto, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.CAMERA),
                    )
                },
            )
            val micChecked = assistant.localTools.contains(LocalToolOption.MicRecorder)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.MicRecorder, micChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mic_recorder_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_mic_recorder_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = micChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.MicRecorder, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
            val sttChecked = assistant.localTools.contains(LocalToolOption.SpeechToText)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.SpeechToText, sttChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_speech_to_text_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_speech_to_text_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = sttChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SpeechToText, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
            val fpChecked = assistant.localTools.contains(LocalToolOption.Fingerprint)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Fingerprint, fpChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_fingerprint_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_fingerprint_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = fpChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Fingerprint, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.USE_BIOMETRIC),
                    )
                },
            )
            val notifListenerChecked = assistant.localTools.contains(LocalToolOption.NotificationListener)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.NotificationListener, notifListenerChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notifications_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_notifications_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = notifListenerChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.NotificationListener, it) },
                        requiresNotificationListener = true,
                    )
                },
            )
        }

        // Media section
        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_media)) },
        ) {
            val filesChecked = assistant.localTools.contains(LocalToolOption.Files)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Files, filesChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_files_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_files_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = filesChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Files, it) },
                        requiresAllFilesAccess = true,
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_download_title, R.string.assistant_page_local_tools_download_desc, assistant.localTools.contains(LocalToolOption.Download), option = LocalToolOption.Download) { toggleLocalTool(LocalToolOption.Download, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_media_player_title, R.string.assistant_page_local_tools_media_player_desc, assistant.localTools.contains(LocalToolOption.MediaPlayer), option = LocalToolOption.MediaPlayer) { toggleLocalTool(LocalToolOption.MediaPlayer, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_media_scanner_title, R.string.assistant_page_local_tools_media_scanner_desc, assistant.localTools.contains(LocalToolOption.MediaScanner), option = LocalToolOption.MediaScanner) { toggleLocalTool(LocalToolOption.MediaScanner, it) }
        }

        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_network)) },
        ) {
            val sshChecked = assistant.localTools.contains(LocalToolOption.Ssh)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Ssh, sshChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ssh_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ssh_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = sshChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Ssh, it) },
                        requiredRuntimePerms =
                            if (Build.VERSION.SDK_INT >= 37) {
                                listOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                            } else {
                                emptyList()
                            },
                    )
                },
            )
            val tgChecked = assistant.localTools.contains(LocalToolOption.TelegramBot)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.TelegramBot, tgChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telegram_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_telegram_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = tgChecked,
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
            toolSwitchItem(R.string.assistant_page_local_tools_browser_title, R.string.assistant_page_local_tools_browser_desc, assistant.localTools.contains(LocalToolOption.Browser), option = LocalToolOption.Browser) { toggleLocalTool(LocalToolOption.Browser, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_web_fetch_title, R.string.assistant_page_local_tools_web_fetch_desc, assistant.localTools.contains(LocalToolOption.WebFetch), option = LocalToolOption.WebFetch) { toggleLocalTool(LocalToolOption.WebFetch, it) }
        }

        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_automation)) },
        ) {
            val cronChecked = assistant.localTools.contains(LocalToolOption.CronJobs)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.CronJobs, cronChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_cron_jobs_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = cronChecked,
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
            val workflowsChecked = assistant.localTools.contains(LocalToolOption.Workflows)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Workflows, workflowsChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_workflows_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_workflows_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = workflowsChecked,
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.Workflows, newValue)
                            if (newValue && !workflowsDialogShownThisVisit) {
                                workflowsDialogShownThisVisit = true
                                showWorkflowsHintDialog = true
                            }
                        },
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_sub_agents_title, R.string.assistant_page_local_tools_sub_agents_desc, assistant.localTools.contains(LocalToolOption.SubAgents), option = LocalToolOption.SubAgents) { toggleLocalTool(LocalToolOption.SubAgents, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_skill_import_title, R.string.assistant_page_local_tools_skill_import_desc, assistant.localTools.contains(LocalToolOption.SkillImport), option = LocalToolOption.SkillImport) { toggleLocalTool(LocalToolOption.SkillImport, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_js_skills_title, R.string.assistant_page_local_tools_js_skills_desc, assistant.localTools.contains(LocalToolOption.JsSkills), option = LocalToolOption.JsSkills) { toggleLocalTool(LocalToolOption.JsSkills, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_mcp_control_title, R.string.assistant_page_local_tools_mcp_control_desc, assistant.localTools.contains(LocalToolOption.McpControl), option = LocalToolOption.McpControl) { toggleLocalTool(LocalToolOption.McpControl, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_external_automation_title, R.string.assistant_page_local_tools_external_automation_desc, assistant.localTools.contains(LocalToolOption.ExternalAutomation), option = LocalToolOption.ExternalAutomation) { toggleLocalTool(LocalToolOption.ExternalAutomation, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_reliability_title, R.string.assistant_page_local_tools_reliability_desc, assistant.localTools.contains(LocalToolOption.Reliability), option = LocalToolOption.Reliability) { toggleLocalTool(LocalToolOption.Reliability, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_cost_guards_title, R.string.assistant_page_local_tools_cost_guards_desc, assistant.localTools.contains(LocalToolOption.CostGuards), option = LocalToolOption.CostGuards) { toggleLocalTool(LocalToolOption.CostGuards, it) }
        }

        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_screen_automation)) },
        ) {
            val screenAutoChecked = assistant.localTools.contains(LocalToolOption.ScreenAutomation)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.ScreenAutomation, screenAutoChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_automation_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_automation_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = screenAutoChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenAutomation, it) },
                        requiresAccessibilityService = true,
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_app_launcher_title, R.string.assistant_page_local_tools_app_launcher_desc, assistant.localTools.contains(LocalToolOption.AppLauncher), option = LocalToolOption.AppLauncher) { toggleLocalTool(LocalToolOption.AppLauncher, it) }
            val termuxChecked = assistant.localTools.contains(LocalToolOption.Termux)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Termux, termuxChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_title))
                },
                supportingContent = {
                    TermuxStatusRowSubtitle(
                        enabled = termuxChecked,
                    )
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = termuxChecked,
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.Termux, newValue)
                            if (newValue && !termuxDialogShownThisVisit) {
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
                        requiredRuntimePerms = listOf("com.termux.permission.RUN_COMMAND"),
                    )
                },
            )
            val kbChecked = assistant.localTools.contains(LocalToolOption.KeyboardControl)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.KeyboardControl, kbChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_keyboard_title))
                },
                supportingContent = {
                    KeyboardStatusRowSubtitle()
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = kbChecked,
                        onCheckedChange = { newValue ->
                            toggleLocalTool(LocalToolOption.KeyboardControl, newValue)
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
        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_hardware)) },
        ) {
            toolSwitchItem(R.string.assistant_page_local_tools_torch_title, R.string.assistant_page_local_tools_torch_desc, assistant.localTools.contains(LocalToolOption.Torch), option = LocalToolOption.Torch) { toggleLocalTool(LocalToolOption.Torch, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_vibrate_title, R.string.assistant_page_local_tools_vibrate_desc, assistant.localTools.contains(LocalToolOption.Vibrate), option = LocalToolOption.Vibrate) { toggleLocalTool(LocalToolOption.Vibrate, it) }
            val brightnessChecked = assistant.localTools.contains(LocalToolOption.Brightness)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Brightness, brightnessChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_brightness_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_brightness_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = brightnessChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Brightness, it) },
                        requiresWriteSettings = true,
                    )
                },
            )
            val volumeChecked = assistant.localTools.contains(LocalToolOption.Volume)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Volume, volumeChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_volume_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_volume_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = volumeChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Volume, it) },
                        requiresDndAccess = true,
                    )
                },
            )
        }

        CardGroup(
            label = { Text(stringResource(R.string.assistant_page_local_tools_section_privileged)) },
        ) {
            val smsSendChecked = assistant.localTools.contains(LocalToolOption.SmsSend)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.SmsSend, smsSendChecked) },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_send_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_sms_send_desc))
                },
                trailingContent = {
                    PermissionedSwitch(
                        checked = smsSendChecked,
                        onCheckedChange = { toggleLocalTool(LocalToolOption.SmsSend, it) },
                        requiredRuntimePerms = listOf(Manifest.permission.SEND_SMS),
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_wallpaper_title, R.string.assistant_page_local_tools_wallpaper_desc, assistant.localTools.contains(LocalToolOption.Wallpaper), option = LocalToolOption.Wallpaper) { toggleLocalTool(LocalToolOption.Wallpaper, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_keystore_title, R.string.assistant_page_local_tools_keystore_desc, assistant.localTools.contains(LocalToolOption.Keystore), option = LocalToolOption.Keystore) { toggleLocalTool(LocalToolOption.Keystore, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_shizuku_title, R.string.assistant_page_local_tools_shizuku_desc, assistant.localTools.contains(LocalToolOption.Shizuku), option = LocalToolOption.Shizuku) { toggleLocalTool(LocalToolOption.Shizuku, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_vault_tools_title, R.string.assistant_page_local_tools_vault_tools_desc, assistant.localTools.contains(LocalToolOption.VaultTools), option = LocalToolOption.VaultTools) { toggleLocalTool(LocalToolOption.VaultTools, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_vault_export_env_title, R.string.assistant_page_local_tools_vault_export_env_desc, assistant.localTools.contains(LocalToolOption.VaultExportEnv), option = LocalToolOption.VaultExportEnv) { toggleLocalTool(LocalToolOption.VaultExportEnv, it) }
            val nfcChecked = hasNfc && assistant.localTools.contains(LocalToolOption.Nfc)
            item(
                leadingContent = { ToolOptionGroupIcon(LocalToolOption.Nfc, nfcChecked) },
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
                        checked = nfcChecked,
                        onCheckedChange = { if (hasNfc) toggleLocalTool(LocalToolOption.Nfc, it) },
                        enabled = hasNfc,
                    )
                },
            )
            toolSwitchItem(R.string.assistant_page_local_tools_external_storage_title, R.string.assistant_page_local_tools_external_storage_desc, assistant.localTools.contains(LocalToolOption.ExternalStorage), option = LocalToolOption.ExternalStorage) { toggleLocalTool(LocalToolOption.ExternalStorage, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_archive_title, R.string.assistant_page_local_tools_archive_desc, assistant.localTools.contains(LocalToolOption.Archive), option = LocalToolOption.Archive) { toggleLocalTool(LocalToolOption.Archive, it) }
            toolSwitchItem(R.string.assistant_page_local_tools_app_backup_title, R.string.assistant_page_local_tools_app_backup_desc, assistant.localTools.contains(LocalToolOption.AppBackup), option = LocalToolOption.AppBackup) { toggleLocalTool(LocalToolOption.AppBackup, it) }
        }
    }
}
