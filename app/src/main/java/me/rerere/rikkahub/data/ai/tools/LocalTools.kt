package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.add
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.CameraResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.rikkahub.data.ai.tools.local.buildChartDisplayTool
import me.rerere.rikkahub.data.ai.tools.local.buildJavascriptTool
import me.rerere.rikkahub.data.ai.tools.local.buildScreenTimeTool
import me.rerere.rikkahub.data.ai.tools.local.deviceInfoTool
import me.rerere.rikkahub.data.ai.tools.local.diagnosticsTool
import me.rerere.rikkahub.data.ai.tools.local.callLogTool
import me.rerere.rikkahub.data.ai.tools.local.cameraPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.clickNodeTool
import me.rerere.rikkahub.data.ai.tools.local.clickAndReadTool
import me.rerere.rikkahub.data.ai.tools.local.downloadTool
import me.rerere.rikkahub.data.ai.tools.local.fingerprintTool
import me.rerere.rikkahub.data.ai.tools.local.findNodeTool
import me.rerere.rikkahub.data.ai.tools.local.waitForTool
import me.rerere.rikkahub.data.ai.tools.local.getBrightnessTool
import me.rerere.rikkahub.data.ai.tools.local.getVolumeTool
import me.rerere.rikkahub.data.ai.tools.local.globalActionTool
import me.rerere.rikkahub.data.ai.tools.local.testModelTool
import me.rerere.rikkahub.data.ai.tools.local.listContactsTool
import me.rerere.rikkahub.data.ai.tools.local.listSmsInboxTool
import me.rerere.rikkahub.data.ai.tools.local.locationTool
import me.rerere.rikkahub.data.ai.tools.local.longPressTool
import me.rerere.rikkahub.data.ai.tools.local.mediaScannerTool
import me.rerere.rikkahub.data.ai.tools.local.micRecorderTool
import me.rerere.rikkahub.data.ai.tools.local.notificationTool
import me.rerere.rikkahub.data.ai.tools.local.getMediaStatusTool
import me.rerere.rikkahub.data.ai.tools.local.pauseMediaTool
import me.rerere.rikkahub.data.ai.tools.local.playMediaTool
import me.rerere.rikkahub.data.ai.tools.local.resumeMediaTool
import me.rerere.rikkahub.data.ai.tools.local.seekMediaTool
import me.rerere.rikkahub.data.ai.tools.local.readWindowTreeTool
import me.rerere.rikkahub.data.ai.tools.local.scrollTool
import me.rerere.rikkahub.data.ai.tools.local.searchContactsTool
import me.rerere.rikkahub.data.ai.tools.local.searchSmsTool
import me.rerere.rikkahub.data.ai.tools.local.setBrightnessTool
import me.rerere.rikkahub.data.ai.tools.local.setVolumeTool
import me.rerere.rikkahub.data.ai.tools.local.shareTool
import me.rerere.rikkahub.data.ai.tools.local.speechToTextTool
import me.rerere.rikkahub.data.ai.tools.local.stopMediaTool
import me.rerere.rikkahub.data.ai.tools.local.swipeTool
import me.rerere.rikkahub.data.ai.tools.local.takeScreenshotTool
import me.rerere.rikkahub.data.ai.tools.local.tapTool
import me.rerere.rikkahub.data.ai.tools.local.toastTool
import me.rerere.rikkahub.data.ai.tools.local.torchTool
import me.rerere.rikkahub.data.ai.tools.local.vibrateTool
import me.rerere.rikkahub.data.ai.tools.local.deleteSshHostTool
import me.rerere.rikkahub.data.ai.tools.local.forgetSshHostKeyTool
import me.rerere.rikkahub.data.ai.tools.local.listSshHostsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramAddWhitelistTool
import me.rerere.rikkahub.data.ai.tools.local.telegramDeleteCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramDisableTool
import me.rerere.rikkahub.data.ai.tools.local.telegramEnableTool
import me.rerere.rikkahub.data.ai.tools.local.telegramGetCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramRemoveWhitelistTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendDocumentTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendMessageTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetAssistantTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetDefaultChatTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetTokenTool
import me.rerere.rikkahub.data.ai.tools.local.telegramStatusTool
import me.rerere.rikkahub.data.ai.tools.local.saveSshHostTool
import me.rerere.rikkahub.data.ai.tools.local.sshPresetsTool
import me.rerere.rikkahub.data.ai.tools.local.sshJobPollTool
import me.rerere.rikkahub.data.ai.tools.local.sshDownloadTool
import me.rerere.rikkahub.data.ai.tools.local.sshExecSavedTool
import me.rerere.rikkahub.data.ai.tools.local.vaultDeployKeyTool
import me.rerere.rikkahub.data.ai.tools.local.sshExecTool
import me.rerere.rikkahub.data.ai.tools.local.sshUploadTool
import me.rerere.rikkahub.data.ai.tools.local.writeTextFileTool
import me.rerere.rikkahub.data.ai.tools.local.showImageTool
import me.rerere.rikkahub.data.ai.tools.local.openFileTool
import me.rerere.rikkahub.data.ai.tools.local.transcribeAudioFileTool
import me.rerere.rikkahub.data.ai.tools.local.whisperStatusTool
import me.rerere.rikkahub.data.ai.tools.local.listFilesTool
import me.rerere.rikkahub.data.ai.tools.local.readFileTool
import me.rerere.rikkahub.data.ai.tools.local.writeBinaryFileTool
import me.rerere.rikkahub.data.ai.tools.local.deleteFileTool
import me.rerere.rikkahub.data.ai.tools.local.moveFileTool
import me.rerere.rikkahub.data.ai.tools.local.copyFileTool
import me.rerere.rikkahub.data.ai.tools.local.createDirectoryTool
import me.rerere.rikkahub.data.ai.tools.local.fileInfoTool
import me.rerere.rikkahub.data.ai.tools.local.findFilesTool
import me.rerere.rikkahub.data.ai.tools.local.dismissNotificationTool
import me.rerere.rikkahub.data.ai.tools.local.listActiveNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.listRecentNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.notificationActionClickTool
import me.rerere.rikkahub.data.ai.tools.local.notificationReplyTool
import me.rerere.rikkahub.data.ai.tools.local.notificationStatusTool
import me.rerere.rikkahub.data.ai.tools.local.batchCopyTool
import me.rerere.rikkahub.data.ai.tools.local.batchMoveTool
import me.rerere.rikkahub.data.ai.tools.local.batchDeleteTool
import me.rerere.rikkahub.data.ai.tools.local.webFetchTool
import me.rerere.rikkahub.data.event.AppEventBus


class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val cameraResultBuffer: CameraResultBuffer,
    private val biometricResultBuffer: BiometricResultBuffer,
    private val scheduledJobRepository: me.rerere.rikkahub.data.repository.ScheduledJobRepository,
    private val scheduledJobRunRepository: me.rerere.rikkahub.data.repository.ScheduledJobRunRepository,
    private val cronJobScheduler: me.rerere.rikkahub.service.CronJobScheduler,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    private val sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    private val telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    private val telegramBotClient: me.rerere.rikkahub.data.telegram.TelegramBotClient,
    private val notificationListenerPreferences: me.rerere.rikkahub.data.notifications.NotificationListenerPreferences,
    private val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager,
    private val externalAutomationConfig: me.rerere.rikkahub.automation.ExternalAutomationConfig,
    private val gitHubReleaseChecker: me.rerere.rikkahub.reliability.GitHubReleaseChecker,
    private val bugReportBuilder: me.rerere.rikkahub.reliability.BugReportBuilder,
    private val subAgentEngine: me.rerere.rikkahub.subagent.SubAgentEngine,
    private val subAgentRegistry: me.rerere.rikkahub.subagent.SubAgentRegistry,
    private val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
    private val workflowRepository: me.rerere.rikkahub.workflow.repository.WorkflowRepository,
    private val workflowEngine: me.rerere.rikkahub.workflow.execution.WorkflowEngine,
    private val skillUrlImporter: me.rerere.rikkahub.skills.SkillUrlImporter,
    private val skillManager: me.rerere.rikkahub.data.files.SkillManager,
    private val jsSkillRunner: me.rerere.rikkahub.skills.js.JsSkillRunner,
    private val skillSecretsStore: me.rerere.rikkahub.skills.js.SkillSecretsStore,
    private val vaultRepository: me.rerere.rikkahub.data.vault.CredentialVaultRepository,
    // Browser per-tool toggle store. Pass 2 reads a [snapshotBlocking] of the map so each
    // tool factory gates its own registration on whether the user has flipped it on. Master
    // toggle ([LocalToolOption.Browser]) acts as the group on/off; per-tool toggles act as
    // a sub-allow-list. Both must be true for a tool to register.
    private val browserPreferences: me.rerere.rikkahub.browser.BrowserPreferences,
    // TermuxPreferences is injected here solely to force Koin to construct it at first tool
    // use (same trick as browserPreferences above). Koin singles are lazy; without this
    // constructor reference the singleton — and its init{} sync seed + async collectors —
    // would never build in headless sessions that skip Settings -> Termux.
    @Suppress("UNUSED_PARAMETER")
    termuxPreferences: me.rerere.rikkahub.data.preferences.TermuxPreferences,
    // Post-action screenshot streamer for headless mode (Telegram bot / cron / sub-agent).
    // Injected rather than Koin-resolved inside each factory so JVM tests can pass a mock.
    private val interactiveToolStreamer: InteractiveToolStreamer,
    // Phase 25 — NFC / SAF Activity-bridge buffers + the SAF tree-grant store.
    private val nfcResultBuffer: me.rerere.rikkahub.data.ai.tools.local.NfcResultBuffer,
    private val safPickerResultBuffer: me.rerere.rikkahub.data.ai.tools.local.SafPickerResultBuffer,
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore,
    // Shared OkHttp singleton (NetworkChangeMonitor-registered) — backs the web_fetch tool.
    private val okHttpClient: okhttp3.OkHttpClient,
    // agent-keyboard IPC client — backs the keyboard_* tools (drives the active text field).
    private val keyboardApiClient: me.rerere.rikkahub.data.keyboard.KeyboardApiClient,
    // AI 自诊断/自管理工具（第一批）复用依赖。
    private val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks,
    private val providerManager: me.rerere.ai.provider.ProviderManager,
) {
    // eval_javascript 的实现已移到同目录 JavascriptTool.kt：那边用原生内存/栈上限、
    // 求值超时与中断、日志长度上限做保护，装配处直接调用 buildJavascriptTool()。

    val appBackupTool by lazy { buildAppBackupTool(settingsStore) }
    val timeTool by lazy { buildTimeTool() }
    val clipboardTool by lazy { buildClipboardTool(context) }
    val ttsTool by lazy { buildTtsTool(eventBus) }
    val askUserTool by lazy { buildAskUserTool() }

    // 拆分为域分组函数，拼接顺序与原来完全一致，仅为满足行数限制。
    private fun toolRegistry(): List<ToolEntry> =
        coreDeviceToolEntries() +
            connectivityToolEntries() +
            fileAndManagementToolEntries() +
            vaultAndWorkflowToolEntries() +
            miscToolEntries()

    private fun coreDeviceToolEntries(): List<ToolEntry> = listOf(
    ToolEntry(LocalToolOption.JavascriptEngine) { tools, _ ->
        tools.add(buildJavascriptTool())
    },
    ToolEntry(LocalToolOption.TimeInfo) { tools, _ ->
        tools.add(timeTool)
    },
    ToolEntry(LocalToolOption.AppBackup) { tools, _ ->
        tools.add(appBackupTool)
    },
    ToolEntry(LocalToolOption.Clipboard) { tools, _ ->
        tools.add(clipboardTool)
    },
    ToolEntry(LocalToolOption.Tts) { tools, _ ->
        tools.add(ttsTool)
    },
    ToolEntry(LocalToolOption.AskUser) { tools, _ ->
        tools.add(askUserTool)
    },
    ToolEntry(LocalToolOption.DeviceInfo) { tools, _ ->
        tools.add(deviceInfoTool(context))
    },
    ToolEntry(LocalToolOption.Toast) { tools, invocationContext ->
        tools.add(toastTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.Notification) { tools, invocationContext ->
        tools.add(notificationTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.Share) { tools, invocationContext ->
        tools.add(shareTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.Torch) { tools, _ ->
        tools.add(torchTool(context))
    },
    ToolEntry(LocalToolOption.Vibrate) { tools, _ ->
        tools.add(vibrateTool(context))
    },
    ToolEntry(LocalToolOption.Brightness) { tools, invocationContext ->
        tools.add(getBrightnessTool(context))
        tools.add(setBrightnessTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.Volume) { tools, invocationContext ->
        tools.add(getVolumeTool(context))
        tools.add(setVolumeTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.MediaPlayer) { tools, invocationContext ->
        tools.add(playMediaTool(context, invocationContext, interactiveToolStreamer))
        tools.add(stopMediaTool(context))
        tools.add(pauseMediaTool(context))
        tools.add(resumeMediaTool(context))
        tools.add(seekMediaTool(context))
        tools.add(getMediaStatusTool())
    },
    ToolEntry(LocalToolOption.MediaScanner) { tools, _ ->
        tools.add(mediaScannerTool(context))
    },
    ToolEntry(LocalToolOption.Download) { tools, _ ->
        tools.add(downloadTool(context))
        tools.add(writeTextFileTool(context))
    },
    ToolEntry(LocalToolOption.Location) { tools, _ ->
        tools.add(locationTool(context))
    },
    ToolEntry(LocalToolOption.Contacts) { tools, _ ->
        tools.add(searchContactsTool(context))
        tools.add(listContactsTool(context))
    },
    ToolEntry(LocalToolOption.CallLog) { tools, _ ->
        tools.add(callLogTool(context))
    },
    ToolEntry(LocalToolOption.SmsInbox) { tools, _ ->
        tools.add(listSmsInboxTool(context))
        tools.add(searchSmsTool(context))
    },
    ToolEntry(LocalToolOption.ScreenTime) { tools, _ ->
        tools.add(buildScreenTimeTool(context, eventBus))
    },
    ToolEntry(LocalToolOption.CameraPhoto) { tools, _ ->
        tools.add(cameraPhotoTool(context, cameraResultBuffer))
    },
    ToolEntry(LocalToolOption.MicRecorder) { tools, _ ->
        tools.add(micRecorderTool(context))
    },
    ToolEntry(LocalToolOption.SpeechToText) { tools, _ ->
        tools.add(speechToTextTool(context))
    },
    ToolEntry(LocalToolOption.Fingerprint) { tools, _ ->
        tools.add(fingerprintTool(context, biometricResultBuffer))
    },
    )

    private fun connectivityToolEntries(): List<ToolEntry> = listOf(
    ToolEntry(LocalToolOption.Ssh) { tools, _ ->
        tools.add(sshExecTool(context))
        tools.add(saveSshHostTool(sshHostRepository))
        tools.add(listSshHostsTool(sshHostRepository))
        tools.add(deleteSshHostTool(sshHostRepository))
        tools.add(sshExecSavedTool(context, sshHostRepository, vaultRepository))
        tools.add(sshPresetsTool())
        tools.add(sshJobPollTool(context, sshHostRepository, vaultRepository))
        tools.add(sshUploadTool(context, sshHostRepository, vaultRepository))
        tools.add(sshDownloadTool(context, sshHostRepository, vaultRepository))
        tools.add(forgetSshHostKeyTool(context))
        tools.add(vaultDeployKeyTool(context, sshHostRepository, vaultRepository))
    },
    ToolEntry(LocalToolOption.TelegramBot) { tools, _ ->
        tools.add(telegramSetTokenTool(telegramBotPreferences, telegramBotClient))
        tools.add(telegramStatusTool(context, telegramBotPreferences, telegramBotClient))
        tools.add(telegramEnableTool(context, telegramBotPreferences))
        tools.add(telegramDisableTool(context, telegramBotPreferences))
        tools.add(telegramAddWhitelistTool(telegramBotPreferences))
        tools.add(telegramRemoveWhitelistTool(telegramBotPreferences))
        tools.add(telegramSetDefaultChatTool(telegramBotPreferences))
        tools.add(telegramSetAssistantTool(telegramBotPreferences))
        tools.add(telegramSendMessageTool(telegramBotPreferences, telegramBotClient))
        tools.add(telegramSendPhotoTool(telegramBotPreferences, telegramBotClient))
        tools.add(telegramSendDocumentTool(telegramBotPreferences, telegramBotClient))
        tools.add(telegramSetCommandsTool(telegramBotPreferences, telegramBotClient))
        tools.add(telegramGetCommandsTool(telegramBotClient))
        tools.add(telegramDeleteCommandsTool(telegramBotPreferences, telegramBotClient))
    },
    ToolEntry(LocalToolOption.CronJobs) { tools, _ ->
        tools.add(
            me.rerere.rikkahub.data.ai.tools.local.scheduleJobDirectTool(
                scheduledJobRepository, cronJobScheduler, settingsStore,
                knownToolNamesProvider = { tools.map { it.name } },
            ),
        )
        tools.add(
            me.rerere.rikkahub.data.ai.tools.local.scheduleJobLlmTool(
                scheduledJobRepository, cronJobScheduler, settingsStore,
            ),
        )
        tools.add(
            me.rerere.rikkahub.data.ai.tools.local.scheduleJobTool(
                scheduledJobRepository, cronJobScheduler, settingsStore,
                knownToolNamesProvider = { tools.map { it.name } },
            ),
        )
        tools.add(me.rerere.rikkahub.data.ai.tools.local.listJobsTool(scheduledJobRepository))
        tools.add(
            me.rerere.rikkahub.data.ai.tools.local.deleteJobTool(
                scheduledJobRepository, scheduledJobRunRepository, cronJobScheduler,
            ),
        )
        tools.add(me.rerere.rikkahub.data.ai.tools.local.pauseJobTool(scheduledJobRepository, cronJobScheduler))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.resumeJobTool(scheduledJobRepository, cronJobScheduler))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.triggerJobNowTool(scheduledJobRepository, cronJobScheduler))
        tools.add(
            me.rerere.rikkahub.data.ai.tools.local.getJobHistoryTool(
                scheduledJobRepository, scheduledJobRunRepository,
            ),
        )
    },
    ToolEntry(LocalToolOption.ScreenAutomation) { tools, invocationContext ->
        tools.add(tapTool(invocationContext, interactiveToolStreamer))
        tools.add(longPressTool(invocationContext, interactiveToolStreamer))
        tools.add(swipeTool(invocationContext, interactiveToolStreamer))
        tools.add(readWindowTreeTool(invocationContext, interactiveToolStreamer))
        tools.add(findNodeTool(invocationContext, interactiveToolStreamer))
        tools.add(clickNodeTool(invocationContext, interactiveToolStreamer))
        tools.add(clickAndReadTool(invocationContext, interactiveToolStreamer))
        tools.add(waitForTool(invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.setTextTool(invocationContext, interactiveToolStreamer))
        tools.add(scrollTool(invocationContext, interactiveToolStreamer))
        tools.add(globalActionTool(invocationContext, interactiveToolStreamer))
        tools.add(takeScreenshotTool(context)) // take_screenshot IS the screenshot; skip auto-stream
        tools.add(me.rerere.rikkahub.data.ai.tools.local.wakeScreenTool(context))
    },
    ToolEntry(LocalToolOption.AppLauncher) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.launchAppTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.listInstalledAppsTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.listAppActivitiesTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.launchActivityTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.openUrlTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.Termux) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool(context))
        // Persistent interactive (tmux-backed) sessions: ssh-with-prompts, sudo, REPLs,
        // stateful shells. start is approval-gated; send is hardline-guarded per call.
        tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionStartTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionSendTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionReadTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionKillTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.termuxSessionListTool(context))
        // transcribe_audio_file shells out to whisper-cli via Termux's RUN_COMMAND
        // service — it has a hard transitive dependency on Termux being present. No
        // separate toggle; it lives under the Termux toggle.
        tools.add(transcribeAudioFileTool(context))
        // whisper_status is a free read-only pre-flight check — no approval needed.
        // The LLM calls this BEFORE attempting transcription to know what's set up.
        tools.add(whisperStatusTool(context, settingsStore, invocationContext))
    },
    ToolEntry(LocalToolOption.NotificationListener) { tools, _ ->
        tools.add(listRecentNotificationsTool())
        tools.add(listActiveNotificationsTool())
        tools.add(dismissNotificationTool())
        tools.add(notificationActionClickTool())
        tools.add(notificationReplyTool())
        tools.add(notificationStatusTool(notificationListenerPreferences, telegramBotPreferences))
    },
    )

    private fun fileAndManagementToolEntries(): List<ToolEntry> = listOf(
    ToolEntry(LocalToolOption.Files) { tools, invocationContext ->
        tools.add(listFilesTool())
        tools.add(readFileTool())
        tools.add(writeBinaryFileTool())
        tools.add(deleteFileTool())
        tools.add(moveFileTool())
        tools.add(copyFileTool())
        tools.add(createDirectoryTool())
        tools.add(fileInfoTool())
        tools.add(findFilesTool())
        tools.add(showImageTool(context, invocationContext.modelCanSeeImages)) // inline image display; no separate auto-stream needed
        tools.add(openFileTool(context, invocationContext, interactiveToolStreamer))
        // Batch ops (item 5.5) — list-or-glob copy / move / delete. Same toggle group
        // as the single-path file tools; every path still goes through PathSafetyGuard.
        tools.add(batchCopyTool())
        tools.add(batchMoveTool())
        tools.add(batchDeleteTool())
    },
    ToolEntry(LocalToolOption.McpControl) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpListTool(settingsStore, mcpManager, invocationContext))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpGetTool(settingsStore, mcpManager, invocationContext))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpAddTool(settingsStore, mcpManager, invocationContext))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpUpdateTool(settingsStore, mcpManager))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpDeleteTool(settingsStore, mcpManager))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpSetEnabledTool(settingsStore, mcpManager))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpTestTool(settingsStore, mcpManager))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpListToolsTool(settingsStore, mcpManager, invocationContext))
        tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpSetToolApprovalTool(settingsStore))
    },
    ToolEntry(LocalToolOption.ExternalAutomation) { tools, _ ->
        tools.add(me.rerere.rikkahub.automation.externalAutomationStatusTool(externalAutomationConfig))
        tools.add(me.rerere.rikkahub.automation.externalAutomationSetEnabledTool(externalAutomationConfig))
        tools.add(me.rerere.rikkahub.automation.externalAutomationAddTrustedPackageTool(externalAutomationConfig))
        tools.add(me.rerere.rikkahub.automation.externalAutomationRemoveTrustedPackageTool(externalAutomationConfig))
    },
    ToolEntry(LocalToolOption.Reliability) { tools, _ ->
        tools.add(me.rerere.rikkahub.reliability.checkAppUpdatesTool(gitHubReleaseChecker))
        tools.add(me.rerere.rikkahub.reliability.generateBugReportTool(context, bugReportBuilder))
    },
    ToolEntry(LocalToolOption.SubAgents) { tools, invocationContext ->
        // Pass the caller context so the recursion guard inside SubAgentEngine.dispatch
        // can fire — the dispatch tool itself can't read its own coroutine context, but
        // ChatService / cron / workflow / external-automation know who's calling at the
        // moment they construct the tool list.
        tools.add(
            me.rerere.rikkahub.subagent.subagentDispatchTool(
                subAgentEngine,
                invocationContext,
                settingsStore.settingsFlow.value.subAgents,
            ),
        )
        tools.add(me.rerere.rikkahub.subagent.subagentListTool(subAgentRegistry))
        tools.add(me.rerere.rikkahub.subagent.subagentGetTool(subAgentRegistry))
        tools.add(me.rerere.rikkahub.subagent.subagentCancelTool(subAgentRegistry))
    },
    ToolEntry(LocalToolOption.CostGuards) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.costguards.checkTokenUsageTool(settingsStore, conversationRepo, invocationContext))
        // L4 observability: measure the assembled tool surface (size, ordering, hash).
        // Registered after the other cost-guard tools; the lambda is read at execute()
        // time, by which point the whole list — this tool included — has been built.
        tools.add(me.rerere.rikkahub.costguards.toolSurfaceReportTool { tools.toList() })
    },
    )

    private fun vaultAndWorkflowToolEntries(): List<ToolEntry> = listOf(
    ToolEntry(LocalToolOption.SkillImport) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.skills.skillInstallFromUrlTool(skillUrlImporter, settingsStore, skillManager, invocationContext))
        tools.add(me.rerere.rikkahub.skills.skillInstallFromTextTool(skillUrlImporter, settingsStore, skillManager, invocationContext))
    },
    ToolEntry(LocalToolOption.JsSkills) { tools, _ ->
        tools.add(
            me.rerere.rikkahub.skills.js.runJsTool(
                context, skillManager, jsSkillRunner, skillSecretsStore,
            ),
        )
    },
    ToolEntry(LocalToolOption.VaultTools) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.vault.vaultCredentialNamesTool(vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultPublicKeyEntriesTool(vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultCredentialPrepareTool(vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultCredentialMetaTool(vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultCredentialAuditTool(vaultRepository))
        // 引用反查：改名/删除前先看有哪些配置按名字引用它（只读，返回值不含值）
        tools.add(
            me.rerere.rikkahub.data.vault.vaultCredentialRefsTool(settingsStore, sshHostRepository, telegramBotPreferences),
        )
        // 合并重复条目：引用重指向 + 删除多余条目（先比对值指纹）
        tools.add(
            me.rerere.rikkahub.data.vault.vaultCredentialMergeTool(
                vaultRepository, settingsStore, sshHostRepository, telegramBotPreferences, context,
            ),
        )
        // 命名规范化：把历史脏名改成合规名（默认 dry_run，执行时同步引用）
        tools.add(
            me.rerere.rikkahub.data.vault.vaultCredentialNormalizeTool(
                vaultRepository, settingsStore, sshHostRepository, telegramBotPreferences, context,
            ),
        )
        // 失效引用检查：配置引用了不存在的凭证名（否则要等 401 才发现）
        tools.add(
            me.rerere.rikkahub.data.vault.vaultDanglingRefsTool(
                vaultRepository, settingsStore, sshHostRepository, telegramBotPreferences,
            ),
        )
        tools.add(
            me.rerere.rikkahub.data.vault.vaultCredentialUpdateTool(
                context, vaultRepository, settingsStore, sshHostRepository, telegramBotPreferences,
            ),
        )
        tools.add(
            me.rerere.rikkahub.data.vault.vaultCredentialBulkUpdateTool(
                context, vaultRepository, settingsStore, sshHostRepository, telegramBotPreferences,
            ),
        )
        tools.add(me.rerere.rikkahub.data.vault.vaultCredentialDeleteTool(context, vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultGenKeyTool(context, vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultPgpSignTool(vaultRepository, context))
        tools.add(me.rerere.rikkahub.data.vault.vaultPgpVerifyTool(vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultPgpEncryptTool(vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultPgpDecryptTool(vaultRepository, context))
        tools.add(me.rerere.rikkahub.data.vault.vaultSshExecTool(context, vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultHttpExecTool(context, vaultRepository))
    },
    ToolEntry(LocalToolOption.VaultExportEnv) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.vault.vaultExportEnvTool(context, vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultExportLoadCredsTool(context, vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultImportLoadCredsTool(context, vaultRepository))
        tools.add(me.rerere.rikkahub.data.vault.vaultCompareLoadCredsTool(context, vaultRepository))
    },
    ToolEntry(LocalToolOption.Shizuku) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.shizukuExecTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.appForceStopTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.appDisableTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.appEnableTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.appUninstallTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.appOpsGetTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.appOpsSetTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.settingsGetTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.settingsPutTool(context))
    },
    ToolEntry(LocalToolOption.SystemIntents) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.createCalendarEventTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.createContactTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.sendEmailIntentTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.sendSmsIntentTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.openWifiSettingsTool(context, invocationContext, interactiveToolStreamer))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.showLocationOnMapTool(context, invocationContext, interactiveToolStreamer))
    },
    ToolEntry(LocalToolOption.Workflows) { tools, invocationContext ->
        // workflow_create persists the authoringAssistantId from [context] so the
        // engine can resolve the right tool surface at fire time (not "any assistant
        // with the Workflows toggle on", which is non-deterministic across UI reorder).
        tools.add(
            me.rerere.rikkahub.workflow.tools.workflowCreateTool(
                workflowRepository,
                knownToolNamesProvider = { tools.map { it.name } },
                callerContext = invocationContext,
            ),
        )
        tools.add(me.rerere.rikkahub.workflow.tools.workflowListTool(workflowRepository))
        tools.add(me.rerere.rikkahub.workflow.tools.workflowGetTool(workflowRepository))
        tools.add(
            me.rerere.rikkahub.workflow.tools.workflowUpdateTool(
                workflowRepository,
                knownToolNamesProvider = { tools.map { it.name } },
                callerContext = invocationContext,
            ),
        )
        tools.add(me.rerere.rikkahub.workflow.tools.workflowDeleteTool(workflowRepository))
        tools.add(me.rerere.rikkahub.workflow.tools.workflowSetEnabledTool(workflowRepository))
        tools.add(me.rerere.rikkahub.workflow.tools.workflowRunTool(workflowEngine, workflowRepository))
    },
    )

    private fun miscToolEntries(): List<ToolEntry> = listOf(
    ToolEntry(LocalToolOption.Browser) { tools, invocationContext ->
        // Per-tool registration. The user can grant only the tools they trust — read
        // tools default ON, write tools default OFF (see BrowserToolDefaults.DEFAULT_ENABLED).
        // snapshotBlocking() reads DataStore once; steady-state cost is microseconds because
        // DataStore caches the latest Preferences instance after the first decode.
        val browserPrefs = browserPreferences.snapshotBlocking()
        me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS.forEach { name ->
            if (browserPrefs[name] == true) {
                me.rerere.rikkahub.data.ai.tools.local.createBrowserTool(
                    toolName = name,
                    context = context,
                    // Pass 3: thread the caller context so browser_open can pick the
                    // foreground vs headless mode by reading HeadlessConversations.
                    invocationContext = invocationContext,
                )?.let { tools.add(it) }
            }
        }
    },
    ToolEntry(LocalToolOption.WebFetch) { tools, _ ->
        // Lightweight HTTP GET/POST (item 1.2) — backed by the shared OkHttp singleton.
        tools.add(webFetchTool(okHttpClient))
    },
    ToolEntry(LocalToolOption.ChartDisplay) { tools, _ ->
        // Renders line/bar/scatter charts inside the chat; validation happens in the tool,
        // the UI parses tool input via ChartSpec.fromJson.
        tools.add(buildChartDisplayTool())
    },
    // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
    ToolEntry(LocalToolOption.SmsSend) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.smsSendTool(context))
    },
    ToolEntry(LocalToolOption.Wallpaper) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.setWallpaperTool(context))
    },
    ToolEntry(LocalToolOption.Keystore) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreGenerateKeyTool())
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreSignTool())
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreVerifyTool())
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreEncryptTool())
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreDecryptTool())
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreDeleteKeyTool())
        tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreListKeysTool())
    },
    ToolEntry(LocalToolOption.Nfc) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.nfcReadTagTool(context, nfcResultBuffer, invocationContext))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.nfcWriteTagTool(context, nfcResultBuffer, invocationContext))
    },
    ToolEntry(LocalToolOption.ExternalStorage) { tools, invocationContext ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.listStorageVolumesTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.listGrantedDirectoriesTool(context, storageVolumeGrantStore))
        tools.add(
            me.rerere.rikkahub.data.ai.tools.local.grantDirectoryAccessTool(
                context, storageVolumeGrantStore, safPickerResultBuffer, invocationContext,
            ),
        )
    },
    ToolEntry(LocalToolOption.Archive) { tools, _ ->
        tools.add(me.rerere.rikkahub.data.ai.tools.local.zipFilesTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.unzipFileTool(context))
        tools.add(me.rerere.rikkahub.data.ai.tools.local.listZipContentsTool(context))
    },
    ToolEntry(LocalToolOption.KeyboardControl) { tools, _ ->
        // Drives the active text field through the co-signed agent-keyboard IME.
        // Write tools are approval-gated via ToolApprovalDefaults; the two read tools
        // (keyboard_read_field, keyboard_editor_info) are not.
        tools.add(keyboardTypeTool(keyboardApiClient))
        tools.add(keyboardReadFieldTool(keyboardApiClient))
        tools.add(keyboardPressKeyTool(keyboardApiClient))
        tools.add(keyboardDeleteTool(keyboardApiClient))
        tools.add(keyboardClearTool(keyboardApiClient))
        tools.add(keyboardEditorInfoTool(keyboardApiClient))
        tools.add(keyboardSetCursorTool(keyboardApiClient))
        tools.add(keyboardSelectRangeTool(keyboardApiClient))
    },
    // AI 自诊断/自管理（第一批，纯读工具）。
    ToolEntry(LocalToolOption.Diagnostics) { tools, invocationContext ->
        tools.add(diagnosticsTool(context, settingsStore, doctorChecks, conversationRepo, invocationContext))
    },
    ToolEntry(LocalToolOption.ModelTesting) { tools, invocationContext ->
        tools.add(testModelTool(providerManager, settingsStore, context, invocationContext))
    },
    )

    fun getTools(
        options: List<LocalToolOption>,
        invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        val capabilities = ToolCapabilities.of(context)
        // 单一注入入口：授权门（用户勾选）与能力门（运行时能力）都通过才暴露给模型。
        fun enabled(option: LocalToolOption): Boolean =
            options.contains(option) && capabilities.satisfies(LocalToolCatalog.capabilityOf(option))

        // 注册表驱动：遍历 toolRegistry()，授权门通过即构建。
        for (entry in toolRegistry()) {
            if (enabled(entry.option)) {
                entry.addTo(this, tools, invocationContext)
            }
        }
        // Centralised opt-in to needsApproval. Tool factories themselves don't have to know
        // whether their op is destructive — ToolApprovalDefaults is the single source of
        // truth, and the GenerationLoop / Telegram/in-app prompt path keys off needsApproval.
        return tools.map { t ->
            val withApproval = if (ToolApprovalDefaults.requiresApproval(t.name)) {
                t.copy(needsApproval = { true })
            } else {
                t
            }
            // 埋点已上移到 ChatToolFactory 的统一出口（覆盖 workspace/MCP/skills 等全部工具）。
            addHumanErrorEnvelopes(appendTopToolExample(withApproval))
        }
    }
}
