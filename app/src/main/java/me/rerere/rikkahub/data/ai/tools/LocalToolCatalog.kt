package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.R

/**
 * 本地工具的能力分类，用于分组展示与按类别批量勾选。
 */
enum class LocalToolCategory(val id: String) {
    DEVICE_INFO("device_info"),
    DEVICE_CONTROL("device_control"),
    MEDIA("media"),
    FILES("files"),
    PERSONAL("personal"),
    REMOTE("remote"),
    VAULT("vault"),
    AUTOMATION("automation"),
    WEB_AI("web_ai"),
}

/**
 * 本地工具目录：分类归属与全量列表。
 *
 * 新增工具时在此登记（分类决定其在配置界面中的分组与批量勾选行为）。
 * 未登记的工具不会出现在列表中，便于及时发现遗漏。
 */
object LocalToolCatalog {

    private val byCategory: Map<LocalToolCategory, List<LocalToolOption>> =
        mapOf(
            LocalToolCategory.DEVICE_INFO to
                listOf(
                    LocalToolOption.Battery,
                    LocalToolOption.AudioInfo,
                    LocalToolOption.TelephonyInfo,
                    LocalToolOption.WifiInfo,
                    LocalToolOption.Sensors,
                    LocalToolOption.StorageInfo,
                    LocalToolOption.AppDiagnostics,
                    LocalToolOption.AppLogs,
                ),
            LocalToolCategory.DEVICE_CONTROL to
                listOf(
                    LocalToolOption.Torch,
                    LocalToolOption.Vibrate,
                    LocalToolOption.Brightness,
                    LocalToolOption.Volume,
                    LocalToolOption.Toast,
                    LocalToolOption.Clipboard,
                    LocalToolOption.Nfc,
                    LocalToolOption.Wallpaper,
                    LocalToolOption.KeyboardControl,
                    LocalToolOption.AppLauncher,
                    LocalToolOption.SystemIntents,
                    LocalToolOption.ScreenAutomation,
                    LocalToolOption.Notification,
                    LocalToolOption.NotificationListener,
                ),
            LocalToolCategory.MEDIA to
                listOf(
                    LocalToolOption.MediaPlayer,
                    LocalToolOption.MediaScanner,
                    LocalToolOption.Tts,
                    LocalToolOption.MicRecorder,
                    LocalToolOption.SpeechToText,
                    LocalToolOption.CameraPhoto,
                    LocalToolOption.Download,
                ),
            LocalToolCategory.FILES to
                listOf(
                    LocalToolOption.Files,
                    LocalToolOption.ExternalStorage,
                    LocalToolOption.Archive,
                ),
            LocalToolCategory.PERSONAL to
                listOf(
                    LocalToolOption.Location,
                    LocalToolOption.Contacts,
                    LocalToolOption.CallLog,
                    LocalToolOption.SmsInbox,
                    LocalToolOption.SmsSend,
                    LocalToolOption.Share,
                ),
            LocalToolCategory.REMOTE to
                listOf(
                    LocalToolOption.Ssh,
                    LocalToolOption.Shizuku,
                    LocalToolOption.Termux,
                    LocalToolOption.McpControl,
                ),
            LocalToolCategory.VAULT to
                listOf(
                    LocalToolOption.VaultTools,
                    LocalToolOption.VaultExportEnv,
                    LocalToolOption.Keystore,
                    LocalToolOption.Fingerprint,
                ),
            LocalToolCategory.AUTOMATION to
                listOf(
                    LocalToolOption.CronJobs,
                    LocalToolOption.ExternalAutomation,
                    LocalToolOption.Workflows,
                    LocalToolOption.SubAgents,
                    LocalToolOption.Reliability,
                    LocalToolOption.CostGuards,
                    LocalToolOption.SkillImport,
                    LocalToolOption.JsSkills,
                    LocalToolOption.TelegramBot,
                ),
            LocalToolCategory.WEB_AI to
                listOf(
                    LocalToolOption.Browser,
                    LocalToolOption.WebFetch,
                    LocalToolOption.JavascriptEngine,
                    LocalToolOption.TimeInfo,
                    LocalToolOption.AskUser,
                    LocalToolOption.ModelTesting,
                ),
        )

    /** 全量列表（按分类顺序展开） */
    val all: List<LocalToolOption> = LocalToolCategory.entries.flatMap { byCategory[it].orEmpty() }

    /** 工具的展示名资源 id（界面显示功能名，而不是类名）。 */
    fun labelRes(tool: LocalToolOption): Int =
        when (tool) {
            LocalToolOption.JavascriptEngine -> R.string.mcp_tool_javascript_engine
            LocalToolOption.TimeInfo -> R.string.mcp_tool_time_info
            LocalToolOption.Clipboard -> R.string.mcp_tool_clipboard
            LocalToolOption.Tts -> R.string.mcp_tool_tts
            LocalToolOption.AskUser -> R.string.mcp_tool_ask_user
            LocalToolOption.Battery -> R.string.mcp_tool_battery
            LocalToolOption.AudioInfo -> R.string.mcp_tool_audio_info
            LocalToolOption.TelephonyInfo -> R.string.mcp_tool_telephony_info
            LocalToolOption.WifiInfo -> R.string.mcp_tool_wifi_info
            LocalToolOption.Sensors -> R.string.mcp_tool_sensors
            LocalToolOption.StorageInfo -> R.string.mcp_tool_storage_info
            LocalToolOption.Toast -> R.string.mcp_tool_toast
            LocalToolOption.Notification -> R.string.mcp_tool_notification
            LocalToolOption.Share -> R.string.mcp_tool_share
            LocalToolOption.Torch -> R.string.mcp_tool_torch
            LocalToolOption.Vibrate -> R.string.mcp_tool_vibrate
            LocalToolOption.Brightness -> R.string.mcp_tool_brightness
            LocalToolOption.Volume -> R.string.mcp_tool_volume
            LocalToolOption.MediaPlayer -> R.string.mcp_tool_media_player
            LocalToolOption.MediaScanner -> R.string.mcp_tool_media_scanner
            LocalToolOption.Download -> R.string.mcp_tool_download
            LocalToolOption.Location -> R.string.mcp_tool_location
            LocalToolOption.Contacts -> R.string.mcp_tool_contacts
            LocalToolOption.CallLog -> R.string.mcp_tool_call_log
            LocalToolOption.SmsInbox -> R.string.mcp_tool_sms_inbox
            LocalToolOption.CameraPhoto -> R.string.mcp_tool_camera_photo
            LocalToolOption.MicRecorder -> R.string.mcp_tool_mic_recorder
            LocalToolOption.SpeechToText -> R.string.mcp_tool_speech_to_text
            LocalToolOption.Fingerprint -> R.string.mcp_tool_fingerprint
            LocalToolOption.CronJobs -> R.string.mcp_tool_cron_jobs
            LocalToolOption.Ssh -> R.string.mcp_tool_ssh
            LocalToolOption.Shizuku -> R.string.mcp_tool_shizuku
            LocalToolOption.TelegramBot -> R.string.mcp_tool_telegram_bot
            LocalToolOption.ScreenAutomation -> R.string.mcp_tool_screen_automation
            LocalToolOption.AppLauncher -> R.string.mcp_tool_app_launcher
            LocalToolOption.Termux -> R.string.mcp_tool_termux
            LocalToolOption.NotificationListener -> R.string.mcp_tool_notification_listener
            LocalToolOption.Files -> R.string.mcp_tool_files
            LocalToolOption.McpControl -> R.string.mcp_tool_mcp_control
            LocalToolOption.ExternalAutomation -> R.string.mcp_tool_external_automation
            LocalToolOption.Reliability -> R.string.mcp_tool_reliability
            LocalToolOption.SubAgents -> R.string.mcp_tool_sub_agents
            LocalToolOption.CostGuards -> R.string.mcp_tool_cost_guards
            LocalToolOption.Workflows -> R.string.mcp_tool_workflows
            LocalToolOption.SkillImport -> R.string.mcp_tool_skill_import
            LocalToolOption.JsSkills -> R.string.mcp_tool_js_skills
            LocalToolOption.VaultTools -> R.string.mcp_tool_vault_tools
            LocalToolOption.VaultExportEnv -> R.string.mcp_tool_vault_export_env
            LocalToolOption.SystemIntents -> R.string.mcp_tool_system_intents
            LocalToolOption.Browser -> R.string.mcp_tool_browser
            LocalToolOption.WebFetch -> R.string.mcp_tool_web_fetch
            LocalToolOption.SmsSend -> R.string.mcp_tool_sms_send
            LocalToolOption.Wallpaper -> R.string.mcp_tool_wallpaper
            LocalToolOption.Keystore -> R.string.mcp_tool_keystore
            LocalToolOption.Nfc -> R.string.mcp_tool_nfc
            LocalToolOption.ExternalStorage -> R.string.mcp_tool_external_storage
            LocalToolOption.Archive -> R.string.mcp_tool_archive
            LocalToolOption.KeyboardControl -> R.string.mcp_tool_keyboard_control
            LocalToolOption.AppDiagnostics -> R.string.mcp_tool_app_diagnostics
            LocalToolOption.AppLogs -> R.string.mcp_tool_app_logs
            LocalToolOption.ModelTesting -> R.string.mcp_tool_model_testing
        }

    /** 非空分组，供界面按类别渲染 */
    fun groups(): List<Pair<LocalToolCategory, List<LocalToolOption>>> =
        LocalToolCategory.entries.map { it to byCategory[it].orEmpty() }.filter { it.second.isNotEmpty() }
}
