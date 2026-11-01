package me.rerere.rikkahub.data.ai.tools

import android.content.Context
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
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
    @SerialName(ASK_USER_TOOL_NAME)
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
    @Serializable @SerialName("screen_time")     data object ScreenTime     : LocalToolOption()
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
    @Serializable @SerialName("chart_display")       data object ChartDisplay       : LocalToolOption()

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
            } catch (_: SerializationException) {
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

    // New envelope shape: {"code","message","data":{"error","recovery","hint",...}}.
    // The message field is already human-readable; mirror it as human_error for
    // consumers that key off that field.
    val data = jsonObject["data"]?.jsonObject
    val newShapeError = data?.get("error")?.jsonPrimitive?.contentOrNull
    if (newShapeError != null) {
        if (data.containsKey("human_error")) return part
        val message = jsonObject["message"]?.jsonPrimitive?.contentOrNull
            ?: humanizeToolError(data)
        return part.copy(
            text = buildJsonObject {
                jsonObject.forEach { (key, value) ->
                    if (key != "data") put(key, value)
                }
                put("data", buildJsonObject {
                    data.forEach { (k, v) -> put(k, v) }
                    put("human_error", message)
                })
            }.toString()
        )
    }

    // Legacy flat shape {"error": ...} — kept for backward compatibility.
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
        ?: jsonObject["hint"]?.jsonPrimitive?.contentOrNull
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
/**
 * 运行时能力探测（Shizuku / 无障碍 / Termux）。
 *
 * 可见性为 internal：装配层用它做“能力门”，只读诊断（`diagnostics kind=tool_scope`）
 * 也用它解释“哪些选项因能力未就绪而未启用”—— 同一份判据，避免两套逻辑漂移。
 */
internal object ToolCapabilities {
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

