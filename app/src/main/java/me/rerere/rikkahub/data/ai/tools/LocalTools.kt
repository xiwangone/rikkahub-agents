package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupEncryptionManager
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.java.KoinJavaComponent.getKoin
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.CameraResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.buildJavascriptTool
import me.rerere.rikkahub.data.ai.tools.local.deviceInfoTool
import me.rerere.rikkahub.data.ai.tools.local.diagnosticsTool
import me.rerere.rikkahub.data.ai.tools.local.callLogTool
import me.rerere.rikkahub.data.ai.tools.local.cameraPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.clickNodeTool
import me.rerere.rikkahub.data.ai.tools.local.downloadTool
import me.rerere.rikkahub.data.ai.tools.local.fingerprintTool
import me.rerere.rikkahub.data.ai.tools.local.findNodeTool
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
import me.rerere.rikkahub.data.ai.tools.local.webExtractTool
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("app_backup")
    data object AppBackup : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable @SerialName("device_info")    data object DeviceInfo     : LocalToolOption()
    @Serializable @SerialName("toast")          data object Toast          : LocalToolOption()
    @Serializable @SerialName("notification")   data object Notification   : LocalToolOption()
    @Serializable @SerialName("share")          data object Share          : LocalToolOption()
    @Serializable @SerialName("torch")          data object Torch          : LocalToolOption()
    @Serializable @SerialName("vibrate")        data object Vibrate        : LocalToolOption()
    @Serializable @SerialName("brightness")     data object Brightness     : LocalToolOption()
    @Serializable @SerialName("volume")         data object Volume         : LocalToolOption()
    @Serializable @SerialName("media_player")   data object MediaPlayer    : LocalToolOption()
    @Serializable @SerialName("media_scanner")  data object MediaScanner   : LocalToolOption()
    @Serializable @SerialName("download")       data object Download       : LocalToolOption()

    @Serializable @SerialName("location")        data object Location       : LocalToolOption()
    @Serializable @SerialName("contacts")        data object Contacts       : LocalToolOption()
    @Serializable @SerialName("call_log")        data object CallLog        : LocalToolOption()
    @Serializable @SerialName("sms_inbox")       data object SmsInbox       : LocalToolOption()
    @Serializable @SerialName("camera_photo")    data object CameraPhoto    : LocalToolOption()
    @Serializable @SerialName("mic_recorder")    data object MicRecorder    : LocalToolOption()
    @Serializable @SerialName("speech_to_text")  data object SpeechToText   : LocalToolOption()
    @Serializable @SerialName("fingerprint")     data object Fingerprint    : LocalToolOption()
    @Serializable @SerialName("cron_jobs")       data object CronJobs       : LocalToolOption()
    @Serializable @SerialName("ssh")             data object Ssh            : LocalToolOption()
    @Serializable @SerialName("shizuku")         data object Shizuku        : LocalToolOption()
    @Serializable @SerialName("telegram_bot")    data object TelegramBot    : LocalToolOption()
    @Serializable @SerialName("screen_automation") data object ScreenAutomation : LocalToolOption()
    @Serializable @SerialName("app_launcher")      data object AppLauncher       : LocalToolOption()
    @Serializable @SerialName("termux")            data object Termux            : LocalToolOption()
    @Serializable @SerialName("notification_listener") data object NotificationListener : LocalToolOption()
    @Serializable @SerialName("files")               data object Files              : LocalToolOption()
    @Serializable @SerialName("mcp_control")         data object McpControl         : LocalToolOption()
    @Serializable @SerialName("external_automation") data object ExternalAutomation : LocalToolOption()
    @Serializable @SerialName("reliability")         data object Reliability        : LocalToolOption()
    @Serializable @SerialName("sub_agents")          data object SubAgents          : LocalToolOption()
    @Serializable @SerialName("cost_guards")         data object CostGuards         : LocalToolOption()
    @Serializable @SerialName("workflows")           data object Workflows          : LocalToolOption()
    @Serializable @SerialName("skill_import")        data object SkillImport        : LocalToolOption()
    @Serializable @SerialName("js_skills")           data object JsSkills           : LocalToolOption()
    @Serializable @SerialName("vault_tools")         data object VaultTools         : LocalToolOption()
    @Serializable @SerialName("vault_export_env")    data object VaultExportEnv    : LocalToolOption()
    @Serializable @SerialName("system_intents")      data object SystemIntents      : LocalToolOption()
    @Serializable @SerialName("browser")             data object Browser            : LocalToolOption()
    @Serializable @SerialName("web_fetch")           data object WebFetch           : LocalToolOption()

    // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
    @Serializable @SerialName("sms_send")             data object SmsSend             : LocalToolOption()
    @Serializable @SerialName("wallpaper")            data object Wallpaper           : LocalToolOption()
    @Serializable @SerialName("keystore")             data object Keystore            : LocalToolOption()
    @Serializable @SerialName("nfc")                  data object Nfc                 : LocalToolOption()
    @Serializable @SerialName("external_storage")     data object ExternalStorage     : LocalToolOption()
    @Serializable @SerialName("archive")              data object Archive             : LocalToolOption()
    @Serializable @SerialName("keyboard_control")     data object KeyboardControl     : LocalToolOption()
    @Serializable @SerialName("diagnostics")          data object Diagnostics        : LocalToolOption()
    @Serializable @SerialName("model_testing")        data object ModelTesting        : LocalToolOption()
}

/**
 * Deserializes a [LocalToolOption] list leniently: any entry whose "type" this build no longer
 * defines (for example a tool from another build such as `screen_time` that this app removed) is
 * dropped instead of aborting the whole decode. Without this, restoring a backup exported from
 * a build with a different tool set fails the entire settings restore with
 * "Serializer for subclass '<type>' is not found in the polymorphic scope of 'LocalToolOption'"
 * (see the legacy-backup restore path). Encoding is unchanged and known tools decode exactly
 * as before, so this only ever discards options this build could not represent anyway.
 */
/** Reading-family entries that resolve to [LocalToolOption.DeviceInfo] when restoring old settings. */
private val LEGACY_DEVICE_INFO_TYPES = setOf(
    "battery", "audio_info", "telephony_info", "wifi_info", "sensors", "storage_info",
)

/** Older diagnostics / log entries that now resolve to [LocalToolOption.Diagnostics]. */
private val LEGACY_DIAGNOSTICS_TYPES = setOf("app_diagnostics", "app_logs")

object LenientLocalToolListSerializer : KSerializer<List<LocalToolOption>> {
    private val delegate = ListSerializer(LocalToolOption.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<LocalToolOption>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<LocalToolOption> {
        // Per-element tolerance only applies to JSON; any other format uses the strict delegate
        // (settings are only ever (de)serialized as JSON in this app).
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonArray) {
            // Not the shape we expect; re-decode the same element strictly rather than guess.
            return jsonDecoder.json.decodeFromJsonElement(delegate, element)
        }
        return element.mapNotNull { item ->
            val typeName = (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            val mapped = when {
                typeName in LEGACY_DEVICE_INFO_TYPES -> JsonObject(mapOf("type" to JsonPrimitive("device_info")))
                typeName in LEGACY_DIAGNOSTICS_TYPES -> JsonObject(mapOf("type" to JsonPrimitive("diagnostics")))
                else -> item
            }
            try {
                jsonDecoder.json.decodeFromJsonElement(LocalToolOption.serializer(), mapped)
            } catch (e: SerializationException) {
                null // a tool type this build does not define; drop it, don't fail the import
            }
        }.distinct()
    }
}

private val TOP_TOOL_EXAMPLES: Map<String, String> = mapOf(
    "device_info" to "device_info(kind=\"battery\")",
    "show_toast" to "show_toast(text=\"Done\")",
    "post_notification" to "post_notification(title=\"Reminder\", body=\"Take a break\")",
    "share" to "share(text=\"Hello\")",
    "set_torch" to "set_torch(enabled=true)",
    "vibrate" to "vibrate(duration_ms=250)",
    "get_brightness" to "get_brightness()",
    "set_brightness" to "set_brightness(value=160)",
    "get_volume" to "get_volume(stream=\"media\")",
    "set_volume" to "set_volume(stream=\"media\", percent=50)",
    "play_media" to "play_media(source=\"file:///sdcard/Music/song.mp3\", title=\"Song\")",
    "stop_media" to "stop_media()",
    "pause_media" to "pause_media()",
    "resume_media" to "resume_media()",
)

internal fun appendTopToolExample(tool: Tool): Tool {
    val example = TOP_TOOL_EXAMPLES[tool.name] ?: return tool
    if (tool.description.contains("Example:", ignoreCase = true)) return tool
    return tool.copy(description = "${tool.description.trim()} Example: $example.")
}

internal fun appendHumanErrorToToolResult(part: UIMessagePart): UIMessagePart {
    if (part !is UIMessagePart.Text) return part
    val jsonObject = runCatching {
        Json.parseToJsonElement(part.text).jsonObject
    }.getOrNull() ?: return part
    if ("error" !in jsonObject) return part

    val detail = jsonObject["detail"] ?: jsonObject["reason"]
    val recovery = jsonObject["recovery"]
    val humanError = jsonObject["human_error"]?.jsonPrimitive?.contentOrNull
        ?: humanizeToolError(jsonObject)

    return part.copy(
        text = buildJsonObject {
            jsonObject["error"]?.let { put("error", it) }
            detail?.let { put("detail", it) }
            recovery?.let { put("recovery", it) }
            put("human_error", humanError)
            jsonObject.forEach { (key, value) ->
                if (key !in STANDARD_ERROR_KEYS) {
                    put(key, value)
                }
            }
        }.toString()
    )
}

internal fun addHumanErrorEnvelopes(tool: Tool): Tool = tool.copy(
    execute = { input ->
        tool.execute(input).map(::appendHumanErrorToToolResult)
    }
)

private fun humanizeToolError(jsonObject: JsonObject): String {
    val error = jsonObject["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val detail = jsonObject["detail"]?.jsonPrimitive?.contentOrNull
        ?: jsonObject["reason"]?.jsonPrimitive?.contentOrNull
        ?: jsonObject["recovery"]?.jsonPrimitive?.contentOrNull
    val readableError = error.replace('_', ' ').ifBlank { "Tool error" }
    return if (detail.isNullOrBlank()) {
        readableError.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    } else {
        "$readableError: $detail"
    }
}

private val STANDARD_ERROR_KEYS = setOf("error", "detail", "reason", "recovery", "human_error")


/**
 * Runtime capability snapshot — decides whether tools that depend on a system service are injected
 * at all.
 *
 * When the capability is unavailable the tool never reaches the schema, so the model cannot even
 * attempt a call that would fail anyway (same idea as gating a tool list on capability flags).
 * Cached briefly: probing means a binder ping / package lookup, and assembly runs on every
 * generation.
 */
private object ToolCapabilities {
    private const val TTL_MS = 30_000L

    data class Snapshot(
        val shizukuReady: Boolean,
        val accessibilityReady: Boolean,
        val termuxInstalled: Boolean,
    ) {
        /** 该能力当前是否可用（[ToolCapability.NONE] 恒为真）。 */
        fun satisfies(capability: ToolCapability): Boolean =
            when (capability) {
                ToolCapability.NONE -> true
                ToolCapability.SHIZUKU -> shizukuReady
                ToolCapability.ACCESSIBILITY -> accessibilityReady
                ToolCapability.TERMUX -> termuxInstalled
            }
    }

    private var cachedAt = 0L
    private var cached = Snapshot(false, false, false)


    fun of(context: Context): Snapshot {
        val now = System.currentTimeMillis()
        if (now - cachedAt < TTL_MS) return cached
        val snapshot = Snapshot(
            shizukuReady = runCatching {
                me.rerere.rikkahub.shizuku.ShizukuManager.isBinderAlive()
            }.getOrDefault(false),
            accessibilityReady = runCatching {
                AccessibilityServiceHandle.isEnabledInSettings(context)
            }.getOrDefault(false),
            termuxInstalled = runCatching {
                context.packageManager.getPackageInfo("com.termux", 0)
                true
            }.getOrDefault(false),
        )
        cached = snapshot
        cachedAt = now
        return snapshot
    }
}

/** `app_backup` 的默认备份项（与设置页默认保持一致的保守集合）。 */
private val DEFAULT_APP_BACKUP_ITEMS = listOf(
    S3Config.BackupItem.DATABASE,
    S3Config.BackupItem.SETTINGS,
    S3Config.BackupItem.AVATARS,
    S3Config.BackupItem.SKILLS,
    S3Config.BackupItem.WORKSPACE_DOCS,
)

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

    /**
     * `app_backup`：生成 App 数据备份（数据库含**密钥库密文**、设置、头像、技能、工作区文档）。
     *
     * - `target=local`（默认）：只落本地（App 私有 cache）；备份加密开启时产出 `.enc`，
     *   口令取「设置 → 备份」里**已记住的备份口令**——不需要、也不应该把口令发到对话里。
     * - `target=s3` / `webdav`：复用设置里**已配置**的目标，生成后直接上传（加密同理由备份设置决定）。
     *
     * 需人工审批（包内含敏感数据）。
     */
    val appBackupTool by lazy {
        Tool(
            name = "app_backup",
            description =
                "Create an app-data backup (database incl. vault ciphertext, settings, avatars, skills, workspace docs) and " +
                    "either save it locally (target=local, default: returns a file path) or upload it to the backup destination " +
                    "already configured in Settings -> Backup (target=s3 or webdav). Encryption follows the app's backup " +
                    "settings; the backup password never needs to be shared in chat. Requires approval.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional backup items, e.g. [\"DATABASE\",\"SETTINGS\",\"AVATARS\",\"SKILLS\",\"WORKSPACE_DOCS\",\"CHAT_FILES\",\"FONTS_IMAGES\",\"TOOL_OUTPUTS\"]. Defaults to a core set.",
                                )
                                put("items", buildJsonObject { put("type", "string") })
                            },
                        )
                        put(
                            "target",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "'local' (default: create the file and return its path), 's3' or 'webdav' (upload to the destination configured in Settings -> Backup).",
                                )
                            },
                        )
                    },
                    required = emptyList(),
                )
            },
            needsApproval = { true },
            execute = {
                val params = it.jsonObject
                val requested =
                    params["items"]?.jsonArray
                        ?.mapNotNull { e -> e.jsonPrimitive.contentOrNull }
                        ?.mapNotNull { n -> runCatching { S3Config.BackupItem.valueOf(n.uppercase()) }.getOrNull() }
                        .orEmpty()
                val items = requested.ifEmpty { DEFAULT_APP_BACKUP_ITEMS }
                val target = params["target"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { s -> s.isNotBlank() } ?: "local"
                val settings = settingsStore.settingsFlow.value
                val encryptedBySettings = settings.backupEncryptionEnabled

                when (target) {
                    "s3" -> {
                        val base = settings.s3Configs.firstOrNull() ?: settings.s3Config
                        if (base.endpoint.isBlank()) {
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("error", "s3_not_configured")
                                        put("hint", "Configure an S3 target first: Settings -> Backup -> S3.")
                                    }.toString(),
                                ),
                            )
                        } else {
                            getKoin().get<S3Sync>().backupToS3(base.copy(items = items))
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("ok", true)
                                        put("target", "s3")
                                        put("uploaded", true)
                                        put("encrypted", encryptedBySettings)
                                        put("items", buildJsonArray { items.forEach { add(it.name) } })
                                    }.toString(),
                                ),
                            )
                        }
                    }

                    "webdav" -> {
                        val base = settings.webDavConfigs.firstOrNull()
                        if (base == null || base.url.isBlank()) {
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("error", "webdav_not_configured")
                                        put("hint", "Configure a WebDAV target first: Settings -> Backup -> WebDAV.")
                                    }.toString(),
                                ),
                            )
                        } else {
                            val webItems =
                                items.mapNotNull { s ->
                                    runCatching { WebDavConfig.BackupItem.valueOf(s.name) }.getOrNull()
                                }
                            getKoin().get<WebDavSync>().backup(base.copy(items = webItems))
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("ok", true)
                                        put("target", "webdav")
                                        put("uploaded", true)
                                        put("encrypted", encryptedBySettings)
                                        put("items", buildJsonArray { items.forEach { add(it.name) } })
                                    }.toString(),
                                ),
                            )
                        }
                    }

                    else -> {
                        val plain = getKoin().get<S3Sync>().prepareBackupFile(S3Config(items = items))
                        val encryptionManager = getKoin().get<BackupEncryptionManager>()
                        val file =
                            runCatching { encryptionManager.maybeEncrypt(plain) }.getOrElse { e ->
                                return@Tool listOf(
                                    UIMessagePart.Text(
                                        buildJsonObject {
                                            put("error", "backup_password_missing")
                                            put(
                                                "hint",
                                                "Backup encryption is on but this device has no remembered password. Enter it once in Settings -> Backup (no need to share it in chat).",
                                            )
                                            put("detail", e.message.orEmpty())
                                        }.toString(),
                                    ),
                                )
                            }
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("path", file.absolutePath)
                                    put("size_bytes", file.length())
                                    put("encrypted", file != plain)
                                    put("items", buildJsonArray { items.forEach { add(it.name) } })
                                    put(
                                        "hint",
                                        "Copy this file out (e.g. copy_file) and keep it safe; it contains sensitive data.",
                                    )
                                }.toString(),
                            ),
                        )
                    }
                }
            },
        )
    }

    val timeTool by lazy {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val clipboardTool by lazy {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val text = context.readClipboardText()
                        val hits = me.rerere.rikkahub.data.ai.tools.local
                            .SensitiveContentDetector.scan(text)
                        val payload = buildJsonObject {
                            put("text", text)
                            if (hits.isNotEmpty()) {
                                put("sensitive_content_detected", true)
                                put(
                                    "warning",
                                    "Clipboard appears to contain sensitive content " +
                                        "(${hits.joinToString { it.name.lowercase() }}). " +
                                        "Do NOT echo the value back to the user, log it, " +
                                        "or include it in summaries or URLs unless they " +
                                        "explicitly ask for it."
                                )
                            }
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

    val ttsTool by lazy {
        Tool(
            name = "text_to_speech",
            description = """
                Speak text aloud to the user using the device's text-to-speech engine.
                Use this when the user asks you to read something aloud, or when audio output is appropriate.
                The tool returns immediately; audio plays in the background on the device.
                Provide natural, readable text without markdown formatting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "The text to speak aloud")
                        })
                    },
                    required = listOf("text")
                )
            },
            execute = {
                val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required")
                eventBus.emit(AppEvent.Speak(text))
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val askUserTool by lazy {
        Tool(
            name = "ask_user",
            description = """
                Ask the user one or more questions (each may offer suggested options) when you
                need clarification or confirmation. Answers map question IDs to responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select one option or enter custom text), multi (select options and/or enter custom text)"
                                        )
                                    })
                                })
                                put("required", kotlinx.serialization.json.buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = { true },
            execute = {
                // Reached only when no human-in-the-loop surface intercepted this call. The
                // in-app question card and the Telegram clarify flow both handle ask_user before
                // execute() runs; any other context (cron jobs, sub-agents) has nobody to answer,
                // so return a graceful envelope telling the model to ask in plain text rather than
                // throwing an opaque tool_failed.
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "ask_user_unavailable")
                            put(
                                "detail",
                                "Interactive questions aren't available in this context. Ask your question in your normal reply text instead; the user will read it and answer."
                            )
                        }.toString()
                    )
                )
            }
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        val capabilities = ToolCapabilities.of(context)
        // 单一注入入口：授权门（用户勾选）与能力门（运行时能力）都通过才暴露给模型。
        fun enabled(option: LocalToolOption): Boolean =
            options.contains(option) && capabilities.satisfies(LocalToolCatalog.capabilityOf(option))

        if (enabled(LocalToolOption.JavascriptEngine)) {
            tools.add(buildJavascriptTool())
        }
        if (enabled(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (enabled(LocalToolOption.AppBackup)) {
            tools.add(appBackupTool)
        }
        if (enabled(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (enabled(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (enabled(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (enabled(LocalToolOption.DeviceInfo)) {
            tools.add(deviceInfoTool(context))
        }
        if (enabled(LocalToolOption.Toast)) {
            tools.add(toastTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.Notification)) {
            tools.add(notificationTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.Share)) {
            tools.add(shareTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.Torch)) {
            tools.add(torchTool(context))
        }
        if (enabled(LocalToolOption.Vibrate)) {
            tools.add(vibrateTool(context))
        }
        if (enabled(LocalToolOption.Brightness)) {
            tools.add(getBrightnessTool(context))
            tools.add(setBrightnessTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.Volume)) {
            tools.add(getVolumeTool(context))
            tools.add(setVolumeTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.MediaPlayer)) {
            tools.add(playMediaTool(context, invocationContext, interactiveToolStreamer))
            tools.add(stopMediaTool(context))
            tools.add(pauseMediaTool(context))
            tools.add(resumeMediaTool(context))
            tools.add(seekMediaTool(context))
            tools.add(getMediaStatusTool())
        }
        if (enabled(LocalToolOption.MediaScanner)) {
            tools.add(mediaScannerTool(context))
        }
        if (enabled(LocalToolOption.Download)) {
            tools.add(downloadTool(context))
            tools.add(writeTextFileTool(context))
        }
        if (enabled(LocalToolOption.Location)) {
            tools.add(locationTool(context))
        }
        if (enabled(LocalToolOption.Contacts)) {
            tools.add(searchContactsTool(context))
            tools.add(listContactsTool(context))
        }
        if (enabled(LocalToolOption.CallLog)) {
            tools.add(callLogTool(context))
        }
        if (enabled(LocalToolOption.SmsInbox)) {
            tools.add(listSmsInboxTool(context))
            tools.add(searchSmsTool(context))
        }
        if (enabled(LocalToolOption.CameraPhoto)) {
            tools.add(cameraPhotoTool(context, cameraResultBuffer))
        }
        if (enabled(LocalToolOption.MicRecorder)) {
            tools.add(micRecorderTool(context))
        }
        if (enabled(LocalToolOption.SpeechToText)) {
            tools.add(speechToTextTool(context))
        }
        if (enabled(LocalToolOption.Fingerprint)) {
            tools.add(fingerprintTool(context, biometricResultBuffer))
        }
        if (enabled(LocalToolOption.Ssh)) {
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
        }
        if (enabled(LocalToolOption.TelegramBot)) {
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
        }
        if (enabled(LocalToolOption.CronJobs)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.scheduleJobTool(scheduledJobRepository, cronJobScheduler, settingsStore,
                knownToolNamesProvider = { tools.map { it.name } }))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listJobsTool(scheduledJobRepository))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.deleteJobTool(scheduledJobRepository, scheduledJobRunRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.pauseJobTool(scheduledJobRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.resumeJobTool(scheduledJobRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.triggerJobNowTool(scheduledJobRepository, cronJobScheduler))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.getJobHistoryTool(scheduledJobRepository, scheduledJobRunRepository))
        }
        if (enabled(LocalToolOption.ScreenAutomation)) {
            tools.add(tapTool(invocationContext, interactiveToolStreamer))
            tools.add(longPressTool(invocationContext, interactiveToolStreamer))
            tools.add(swipeTool(invocationContext, interactiveToolStreamer))
            tools.add(readWindowTreeTool(invocationContext, interactiveToolStreamer))
            tools.add(findNodeTool(invocationContext, interactiveToolStreamer))
            tools.add(clickNodeTool(invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.setTextTool(invocationContext, interactiveToolStreamer))
            tools.add(scrollTool(invocationContext, interactiveToolStreamer))
            tools.add(globalActionTool(invocationContext, interactiveToolStreamer))
            tools.add(takeScreenshotTool(context))  // take_screenshot IS the screenshot; skip auto-stream
            tools.add(me.rerere.rikkahub.data.ai.tools.local.wakeScreenTool(context))
        }
        if (enabled(LocalToolOption.AppLauncher)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.launchAppTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listInstalledAppsTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listAppActivitiesTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.launchActivityTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.openUrlTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.Termux)) {
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
            tools.add(whisperStatusTool(context, settingsStore))
        }
        if (enabled(LocalToolOption.NotificationListener)) {
            tools.add(listRecentNotificationsTool())
            tools.add(listActiveNotificationsTool())
            tools.add(dismissNotificationTool())
            tools.add(notificationActionClickTool())
            tools.add(notificationReplyTool())
            tools.add(notificationStatusTool(notificationListenerPreferences, telegramBotPreferences))
        }
        if (enabled(LocalToolOption.Files)) {
            tools.add(listFilesTool())
            tools.add(readFileTool())
            tools.add(writeBinaryFileTool())
            tools.add(deleteFileTool())
            tools.add(moveFileTool())
            tools.add(copyFileTool())
            tools.add(createDirectoryTool())
            tools.add(fileInfoTool())
            tools.add(findFilesTool())
            tools.add(showImageTool(context, invocationContext.modelCanSeeImages))  // inline image display; no separate auto-stream needed
            tools.add(openFileTool(context, invocationContext, interactiveToolStreamer))
            // Batch ops (item 5.5) — list-or-glob copy / move / delete. Same toggle group
            // as the single-path file tools; every path still goes through PathSafetyGuard.
            tools.add(batchCopyTool())
            tools.add(batchMoveTool())
            tools.add(batchDeleteTool())
        }
        if (enabled(LocalToolOption.McpControl)) {
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpListTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpGetTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpAddTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpUpdateTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpDeleteTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpSetEnabledTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpTestTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpListToolsTool(settingsStore, mcpManager))
            tools.add(me.rerere.rikkahub.data.ai.mcp.control.mcpSetToolApprovalTool(settingsStore))
        }
        if (enabled(LocalToolOption.ExternalAutomation)) {
            tools.add(me.rerere.rikkahub.automation.externalAutomationStatusTool(externalAutomationConfig))
            tools.add(me.rerere.rikkahub.automation.externalAutomationSetEnabledTool(externalAutomationConfig))
            tools.add(me.rerere.rikkahub.automation.externalAutomationAddTrustedPackageTool(externalAutomationConfig))
            tools.add(me.rerere.rikkahub.automation.externalAutomationRemoveTrustedPackageTool(externalAutomationConfig))
        }
        if (enabled(LocalToolOption.Reliability)) {
            tools.add(me.rerere.rikkahub.reliability.checkAppUpdatesTool(gitHubReleaseChecker))
            tools.add(me.rerere.rikkahub.reliability.generateBugReportTool(context, bugReportBuilder))
        }
        if (enabled(LocalToolOption.SubAgents)) {
            // Pass the caller context so the recursion guard inside SubAgentEngine.dispatch
            // can fire — the dispatch tool itself can't read its own coroutine context, but
            // ChatService / cron / workflow / external-automation know who's calling at the
            // moment they construct the tool list.
            tools.add(
                me.rerere.rikkahub.subagent.subagentDispatchTool(
                    subAgentEngine,
                    invocationContext,
                    settingsStore.settingsFlow.value.subAgents,
                )
            )
            tools.add(me.rerere.rikkahub.subagent.subagentListTool(subAgentRegistry))
            tools.add(me.rerere.rikkahub.subagent.subagentGetTool(subAgentRegistry))
            tools.add(me.rerere.rikkahub.subagent.subagentCancelTool(subAgentRegistry))
        }
        if (enabled(LocalToolOption.CostGuards)) {
            tools.add(me.rerere.rikkahub.costguards.checkTokenUsageTool(settingsStore, conversationRepo))
            // L4 observability: measure the assembled tool surface (size, ordering, hash).
            // Registered after the other cost-guard tools; the lambda is read at execute()
            // time, by which point the whole list — this tool included — has been built.
            tools.add(me.rerere.rikkahub.costguards.toolSurfaceReportTool { tools.toList() })
        }
        if (enabled(LocalToolOption.SkillImport)) {
            tools.add(me.rerere.rikkahub.skills.skillInstallFromUrlTool(skillUrlImporter, settingsStore, skillManager))
            tools.add(me.rerere.rikkahub.skills.skillInstallFromTextTool(skillUrlImporter, settingsStore, skillManager))
        }
        if (enabled(LocalToolOption.JsSkills)) {
            tools.add(me.rerere.rikkahub.skills.js.runJsTool(
                context, skillManager, jsSkillRunner, skillSecretsStore,
            ))
        }
        if (enabled(LocalToolOption.VaultTools)) {
            tools.add(me.rerere.rikkahub.data.vault.vaultCredentialNamesTool(vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultCredentialPrepareTool(vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultCredentialMetaTool(vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultCredentialAuditTool(vaultRepository))
            // 引用反查：改名/删除前先看有哪些配置按名字引用它（只读，返回值不含值）
            tools.add(
                me.rerere.rikkahub.data.vault.vaultCredentialRefsTool(settingsStore, sshHostRepository),
            )
            // 合并重复条目：引用重指向 + 删除多余条目（先比对值指纹）
            tools.add(
                me.rerere.rikkahub.data.vault.vaultCredentialMergeTool(
                    vaultRepository, settingsStore, sshHostRepository,
                ),
            )
            // 命名规范化：把历史脏名改成合规名（默认 dry_run，执行时同步引用）
            tools.add(
                me.rerere.rikkahub.data.vault.vaultCredentialNormalizeTool(
                    vaultRepository, settingsStore, sshHostRepository,
                ),
            )
            // 失效引用检查：配置引用了不存在的凭证名（否则要等 401 才发现）
            tools.add(
                me.rerere.rikkahub.data.vault.vaultDanglingRefsTool(
                    vaultRepository, settingsStore, sshHostRepository,
                ),
            )
            tools.add(
                me.rerere.rikkahub.data.vault.vaultCredentialUpdateTool(
                    context, vaultRepository, settingsStore, sshHostRepository,
                ),
            )
            tools.add(me.rerere.rikkahub.data.vault.vaultCredentialDeleteTool(context, vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultGenKeyTool(context, vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultSshExecTool(context, vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultHttpExecTool(context, vaultRepository))
        }
        if (enabled(LocalToolOption.VaultExportEnv)) {
            tools.add(me.rerere.rikkahub.data.vault.vaultExportEnvTool(context, vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultExportLoadCredsTool(context, vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultImportLoadCredsTool(context, vaultRepository))
            tools.add(me.rerere.rikkahub.data.vault.vaultCompareLoadCredsTool(context, vaultRepository))
        }
        if (enabled(LocalToolOption.Shizuku)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.shizukuExecTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.appForceStopTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.appDisableTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.appEnableTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.appUninstallTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.appOpsGetTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.appOpsSetTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.settingsGetTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.settingsPutTool(context))
        }
        if (enabled(LocalToolOption.SystemIntents)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.createCalendarEventTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.createContactTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.sendEmailIntentTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.sendSmsIntentTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.openWifiSettingsTool(context, invocationContext, interactiveToolStreamer))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.showLocationOnMapTool(context, invocationContext, interactiveToolStreamer))
        }
        if (enabled(LocalToolOption.Workflows)) {
            // workflow_create persists the authoringAssistantId from [context] so the
            // engine can resolve the right tool surface at fire time (not "any assistant
            // with the Workflows toggle on", which is non-deterministic across UI reorder).
            tools.add(me.rerere.rikkahub.workflow.tools.workflowCreateTool(
                workflowRepository,
                knownToolNamesProvider = { tools.map { it.name } },
                callerContext = invocationContext,
            ))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowListTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowGetTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowUpdateTool(
                workflowRepository,
                knownToolNamesProvider = { tools.map { it.name } },
                callerContext = invocationContext,
            ))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowDeleteTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowSetEnabledTool(workflowRepository))
            tools.add(me.rerere.rikkahub.workflow.tools.workflowRunTool(workflowEngine, workflowRepository))
        }
        if (enabled(LocalToolOption.Browser)) {
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
        }
        if (enabled(LocalToolOption.WebFetch)) {
            // Lightweight HTTP GET/POST (item 1.2) — backed by the shared OkHttp singleton.
            tools.add(webFetchTool(okHttpClient))
        }
        // Phase 25 — Phase 3 second cut + ExternalStorage + Archive.
        if (enabled(LocalToolOption.SmsSend)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.smsSendTool(context))
        }
        if (enabled(LocalToolOption.Wallpaper)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.setWallpaperTool(context))
        }
        if (enabled(LocalToolOption.Keystore)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreGenerateKeyTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreSignTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreVerifyTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreEncryptTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreDecryptTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreDeleteKeyTool())
            tools.add(me.rerere.rikkahub.data.ai.tools.local.keystoreListKeysTool())
        }
        if (enabled(LocalToolOption.Nfc)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.nfcReadTagTool(context, nfcResultBuffer, invocationContext))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.nfcWriteTagTool(context, nfcResultBuffer, invocationContext))
        }
        if (enabled(LocalToolOption.ExternalStorage)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listStorageVolumesTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listGrantedDirectoriesTool(context, storageVolumeGrantStore))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.grantDirectoryAccessTool(
                context, storageVolumeGrantStore, safPickerResultBuffer, invocationContext,
            ))
        }
        if (enabled(LocalToolOption.Archive)) {
            tools.add(me.rerere.rikkahub.data.ai.tools.local.zipFilesTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.unzipFileTool(context))
            tools.add(me.rerere.rikkahub.data.ai.tools.local.listZipContentsTool(context))
        }
        if (enabled(LocalToolOption.KeyboardControl)) {
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
        }
        // AI 自诊断/自管理（第一批，纯读工具）。
        if (enabled(LocalToolOption.Diagnostics)) {
            tools.add(diagnosticsTool(context, settingsStore, doctorChecks, conversationRepo))
        }
        if (enabled(LocalToolOption.ModelTesting)) {
            tools.add(testModelTool(providerManager, settingsStore, context))
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
