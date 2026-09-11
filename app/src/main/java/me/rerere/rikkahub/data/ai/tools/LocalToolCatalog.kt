package me.rerere.rikkahub.data.ai.tools

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

    /** 非空分组，供界面按类别渲染 */
    fun groups(): List<Pair<LocalToolCategory, List<LocalToolOption>>> =
        LocalToolCategory.entries.map { it to byCategory[it].orEmpty() }.filter { it.second.isNotEmpty() }
}
