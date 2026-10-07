package me.rerere.rikkahub.data.ai.tools.registry
import me.rerere.rikkahub.data.ai.tools.SurfaceTier
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy

/**
 * 工具统一索引：定义 / 注册 / 渲染 / 调用四个维度的集中交叉引用。
 *
 * 设计沿用既有思路（不重新设计）：
 * - 注册层：ToolEntry 数据驱动（LocalToolRegistry.kt）：从 getTools 抽出，50+ 个分支改为数据表；
 * - 分组：toolRegistry() 按域分组拼接（LocalTools.kt 注释：原单一函数 394 行触发 LongMethod）；
 * - 渲染层：ToolUIRegistry.registeredKeys 供诊断做覆盖自检（AppDiagnosticsTools.uiCoverage）；
 * - 调用层：MCP LocalToolRegistry 的按名称索引模式（mcp/server/LocalToolRegistry.kt）。
 *
 * 本文件只做索引层：实现保留原位，不做物理合并。
 * 新增工具时在此登记一行；[ToolIndexTest] 校验定义/注册/渲染三方对齐，
 * 未登记即构建失败（“实现了却没注册”自动检查）。
 *
 * 字段说明：
 * - definitionSite：定义位置，格式为“相对于 data/ai/tools/ 的路径#构建函数”；
 * - registeredVia：注册路径，LocalToolOption.X（经 ToolEntry）或 ChatToolFactory.*（直接组装）；
 *   UNREGISTERED 表示已定义但未接入注入路径（死代码或待处理）；
 * - category：LocalToolCategory 分组 id；非 option 工具记为“—”；
 * - renderer：专用渲染器类简单名；null 表示走 DefaultToolUIRenderer；
 * - invocation：调用方式，STANDARD 经 Tool.execute（GenerationLoop / ChatToolFactory / LocalMcpServer），
 *   INTERACTIVE 走独立交互分支（如 ask_user，不进渲染框架）。
 *
 * 注意：MCP 动态工具（mcp__&lt;server&gt;__&lt;tool&gt;）运行时生成，不在静态索引内。
 * Browser 工具（browser_*）为动态装配（BrowserToolDefaults.ALL_TOOLS），
 * 定义位置记为常量表，调用时按用户授权逐个创建。
 */
object ToolIndex {

    /** 调用方式 */
    enum class InvocationKind {
        /** 标准：经 Tool.execute 调用 */
        STANDARD,
        /** 独立交互分支：不走 Tool.execute，如 ask_user */
        INTERACTIVE,
    }

    /** 单条索引 */
    data class Entry(
        val toolName: String,
        val definitionSite: String,
        val registeredVia: String,
        val category: String,
        val renderer: String?,
        val invocation: InvocationKind,
        /** 温度档位：默认按 [ToolSurfacePolicy] 判定，可逐条覆盖。 */
        val tier: SurfaceTier = ToolSurfacePolicy.tierOf(toolName),
    )

    val entries: List<Entry> = listOf(
Entry("app_backup", "LocalTools.kt#appBackupTool", "LocalToolOption.AppBackup", "FILES", null, InvocationKind.STANDARD),
        Entry("app_disable", "local/ShizukuAppOpsTools.kt#appDisableTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("app_enable", "local/ShizukuAppOpsTools.kt#appEnableTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("app_force_stop", "local/ShizukuAppOpsTools.kt#appForceStopTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("app_uninstall", "local/ShizukuAppOpsTools.kt#appUninstallTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("appops_get", "local/ShizukuAppOpsTools.kt#appOpsGetTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("appops_set", "local/ShizukuAppOpsTools.kt#appOpsSetTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("ask_user", "LocalTools.kt#askUserTool", "LocalToolOption.AskUser", "WEB_AI", null, InvocationKind.INTERACTIVE),
        Entry("batch_copy", "local/FileBatchTools.kt#batchCopyTool", "LocalToolOption.Files", "FILES", "BatchCopyToolUI", InvocationKind.STANDARD),
        Entry("batch_delete", "local/FileBatchTools.kt#batchDeleteTool", "LocalToolOption.Files", "FILES", "BatchDeleteToolUI", InvocationKind.STANDARD),
        Entry("batch_move", "local/FileBatchTools.kt#batchMoveTool", "LocalToolOption.Files", "FILES", "BatchMoveToolUI", InvocationKind.STANDARD),
Entry("browser_back", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_click", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_click_and_read", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_current_url", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_done", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_eval_js", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_forward", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_get_dom", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_get_links", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_get_text", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_open", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_press_key", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_screenshot", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_scroll", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_select", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_submit", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_type", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("browser_wait_for", "browser/BrowserToolDefaults.kt#ALL_TOOLS", "LocalToolOption.Browser", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("chart_display", "local/ChartDisplayTool.kt#buildChartDisplayTool", "LocalToolOption.ChartDisplay", "WEB_AI", "ChartDisplayToolUI", InvocationKind.STANDARD),
        Entry("check_app_updates", "reliability/ReliabilityTools.kt#checkAppUpdatesTool", "LocalToolOption.Reliability", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("check_token_usage", "costguards/CostGuardTools.kt#checkTokenUsageTool", "LocalToolOption.CostGuards", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("click_node", "local/FindNodeTool.kt#clickNodeTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "ClickNodeToolUI", InvocationKind.STANDARD),
        Entry("clipboard_tool", "LocalTools.kt#clipboardTool", "LocalToolOption.Clipboard", "DEVICE_CONTROL", "ClipboardToolUI", InvocationKind.STANDARD),
        Entry("conversation_search", "ConversationTools.kt#createConversationTools", "ChatToolFactory", "CONVERSATION", "ConversationSearchToolUI", InvocationKind.STANDARD),
        Entry("copy_file", "local/FileManagerTool.kt#copyFileTool", "LocalToolOption.Files", "FILES", "FileOpToolUI", InvocationKind.STANDARD),
        Entry("create_calendar_event", "local/SystemIntentTools.kt#createCalendarEventTool", "LocalToolOption.SystemIntents", "DEVICE_CONTROL", "CreateCalendarEventToolUI", InvocationKind.STANDARD),
        Entry("create_contact", "local/SystemIntentTools.kt#createContactTool", "LocalToolOption.SystemIntents", "DEVICE_CONTROL", "CreateContactToolUI", InvocationKind.STANDARD),
        Entry("create_directory", "local/FileManagerTool.kt#createDirectoryTool", "LocalToolOption.Files", "FILES", "CreateDirectoryToolUI", InvocationKind.STANDARD),
        Entry("delete_file", "local/FileManagerTool.kt#deleteFileTool", "LocalToolOption.Files", "FILES", "DeleteFileToolUI", InvocationKind.STANDARD),
        Entry("delete_job", "local/CronJobTool.kt#deleteJobTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("delete_ssh_host", "local/SshHostsTool.kt#deleteSshHostTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("device_info", "local/DeviceInfoTool.kt#deviceInfoTool", "LocalToolOption.DeviceInfo", "DEVICE_INFO", null, InvocationKind.STANDARD),
        Entry("diagnostics", "local/AppDiagnosticsTools.kt#diagnosticsTool", "LocalToolOption.Diagnostics", "DEVICE_INFO", null, InvocationKind.STANDARD),
        Entry("diff_files", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "DiffFilesToolUI", InvocationKind.STANDARD),
        Entry("dismiss_notification", "local/NotificationListenerTool.kt#dismissNotificationTool", "LocalToolOption.NotificationListener", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("download_file", "local/DownloadTool.kt#downloadTool", "LocalToolOption.Download", "MEDIA", "DownloadFileToolUI", InvocationKind.STANDARD),
        Entry("eval_javascript", "local/JavascriptTool.kt#buildJavascriptTool", "LocalToolOption.JavascriptEngine", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("external_automation_add_trusted_package", "automation/ExternalAutomationTools.kt#externalAutomationAddTrustedPackageTool", "LocalToolOption.ExternalAutomation", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("external_automation_remove_trusted_package", "automation/ExternalAutomationTools.kt#externalAutomationRemoveTrustedPackageTool", "LocalToolOption.ExternalAutomation", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("external_automation_set_enabled", "automation/ExternalAutomationTools.kt#externalAutomationSetEnabledTool", "LocalToolOption.ExternalAutomation", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("external_automation_status", "automation/ExternalAutomationTools.kt#externalAutomationStatusTool", "LocalToolOption.ExternalAutomation", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("file_info", "local/FileManagerTool.kt#fileInfoTool", "LocalToolOption.Files", "FILES", null, InvocationKind.STANDARD),
        Entry("find_files", "local/FileManagerTool.kt#findFilesTool", "LocalToolOption.Files", "FILES", "FindFilesToolUI", InvocationKind.STANDARD),
        Entry("find_node", "local/FindNodeTool.kt#findNodeTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("generate_bug_report", "reliability/ReliabilityTools.kt#generateBugReportTool", "LocalToolOption.Reliability", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("get_brightness", "local/BrightnessTool.kt#getBrightnessTool", "LocalToolOption.Brightness", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("get_job_history", "local/CronJobTool.kt#getJobHistoryTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("get_location", "local/LocationTool.kt#locationTool", "LocalToolOption.Location", "PERSONAL", "LocationToolUI", InvocationKind.STANDARD),
        Entry("get_media_status", "local/MediaPlayerTool.kt#getMediaStatusTool", "LocalToolOption.MediaPlayer", "MEDIA", null, InvocationKind.STANDARD),
        Entry("get_screen_time", "local/ScreenTimeTool.kt#buildScreenTimeTool", "LocalToolOption.ScreenTime", "PERSONAL", "GetScreenTimeToolUI", InvocationKind.STANDARD),
        Entry("get_time_info", "LocalTools.kt#timeTool", "LocalToolOption.TimeInfo", "WEB_AI", "GetTimeInfoToolUI", InvocationKind.STANDARD),
        Entry("get_tool_schema", "ToolDiscoveryTools.kt#buildToolDiscoveryTools", "ChatToolFactory.buildToolDiscoveryTools", "—", null, InvocationKind.STANDARD),
        Entry("get_volume", "local/VolumeTool.kt#getVolumeTool", "LocalToolOption.Volume", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("global_action", "local/GlobalActionTool.kt#globalActionTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("grant_directory_access", "local/ExternalStorageTools.kt#grantDirectoryAccessTool", "LocalToolOption.ExternalStorage", "FILES", null, InvocationKind.STANDARD),
        Entry("keyboard_clear", "KeyboardTools.kt#keyboardClearTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_delete", "KeyboardTools.kt#keyboardDeleteTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_editor_info", "KeyboardTools.kt#keyboardEditorInfoTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_press_key", "KeyboardTools.kt#keyboardPressKeyTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_read_field", "KeyboardTools.kt#keyboardReadFieldTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_select_range", "KeyboardTools.kt#keyboardSelectRangeTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_set_cursor", "KeyboardTools.kt#keyboardSetCursorTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keyboard_type", "KeyboardTools.kt#keyboardTypeTool", "LocalToolOption.KeyboardControl", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("keystore_decrypt", "local/KeystoreTools.kt#keystoreDecryptTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("keystore_delete_key", "local/KeystoreTools.kt#keystoreDeleteKeyTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("keystore_encrypt", "local/KeystoreTools.kt#keystoreEncryptTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("keystore_generate_key", "local/KeystoreTools.kt#keystoreGenerateKeyTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("keystore_list_keys", "local/KeystoreTools.kt#keystoreListKeysTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("keystore_sign", "local/KeystoreTools.kt#keystoreSignTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("keystore_verify", "local/KeystoreTools.kt#keystoreVerifyTool", "LocalToolOption.Keystore", "VAULT", null, InvocationKind.STANDARD),
        Entry("launch_activity", "local/AppLauncherTool.kt#launchActivityTool", "LocalToolOption.AppLauncher", "DEVICE_CONTROL", "LaunchActivityToolUI", InvocationKind.STANDARD),
        Entry("launch_app", "local/AppLauncherTool.kt#launchAppTool", "LocalToolOption.AppLauncher", "DEVICE_CONTROL", "LaunchAppToolUI", InvocationKind.STANDARD),
        Entry("list_active_notifications", "local/NotificationListenerTool.kt#listActiveNotificationsTool", "LocalToolOption.NotificationListener", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("list_app_activities", "local/AppLauncherTool.kt#listAppActivitiesTool", "LocalToolOption.AppLauncher", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("list_call_log", "local/CallLogTool.kt#callLogTool", "LocalToolOption.CallLog", "PERSONAL", null, InvocationKind.STANDARD),
        Entry("list_contacts", "local/ContactsTool.kt#listContactsTool", "LocalToolOption.Contacts", "PERSONAL", null, InvocationKind.STANDARD),
        Entry("list_files", "local/FileManagerTool.kt#listFilesTool", "LocalToolOption.Files", "FILES", "ListFilesToolUI", InvocationKind.STANDARD),
        Entry("list_granted_directories", "local/ExternalStorageTools.kt#listGrantedDirectoriesTool", "LocalToolOption.ExternalStorage", "FILES", null, InvocationKind.STANDARD),
        Entry("list_installed_apps", "local/AppLauncherTool.kt#listInstalledAppsTool", "LocalToolOption.AppLauncher", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("list_jobs", "local/CronJobTool.kt#listJobsTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("list_recent_notifications", "local/NotificationListenerTool.kt#listRecentNotificationsTool", "LocalToolOption.NotificationListener", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("list_sms_inbox", "local/SmsInboxTool.kt#listSmsInboxTool", "LocalToolOption.SmsInbox", "PERSONAL", "SmsInboxToolUI", InvocationKind.STANDARD),
        Entry("list_ssh_hosts", "local/SshHostsTool.kt#listSshHostsTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("list_storage_volumes", "local/ExternalStorageTools.kt#listStorageVolumesTool", "LocalToolOption.ExternalStorage", "FILES", null, InvocationKind.STANDARD),
        Entry("list_tools", "ToolDiscoveryTools.kt#buildToolDiscoveryTools", "ChatToolFactory.buildToolDiscoveryTools", "—", null, InvocationKind.STANDARD),
        Entry("list_zip_contents", "local/ArchiveTools.kt#listZipContentsTool", "LocalToolOption.Archive", "FILES", null, InvocationKind.STANDARD),
        Entry("long_press", "local/TapTool.kt#longPressTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "LongPressToolUI", InvocationKind.STANDARD),
        Entry("mcp_add", "data/ai/mcp/control/McpControlTools.kt#mcpAddTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_delete", "data/ai/mcp/control/McpControlTools.kt#mcpDeleteTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_get", "data/ai/mcp/control/McpControlTools.kt#mcpGetTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_list", "data/ai/mcp/control/McpControlTools.kt#mcpListTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_list_tools", "data/ai/mcp/control/McpControlTools.kt#mcpListToolsTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_set_enabled", "data/ai/mcp/control/McpControlTools.kt#mcpSetEnabledTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_test", "data/ai/mcp/control/McpControlTools.kt#mcpTestTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("mcp_update", "data/ai/mcp/control/McpControlTools.kt#mcpUpdateTool", "LocalToolOption.McpControl", "REMOTE", null, InvocationKind.STANDARD),
        Entry("memory_search", "MemoryTools.kt#buildMemoryTools", "ChatToolFactory.memoryToolsIfEnabled", "—", "MemorySearchToolUI", InvocationKind.STANDARD),
        Entry("memory_tool", "MemoryTools.kt#buildMemoryTools", "ChatToolFactory.memoryToolsIfEnabled", "—", "MemoryToolUI", InvocationKind.STANDARD),
        Entry("move_file", "local/FileManagerTool.kt#moveFileTool", "LocalToolOption.Files", "FILES", "MoveFileToolUI", InvocationKind.STANDARD),
        Entry("nfc_read_tag", "local/NfcTools.kt#nfcReadTagTool", "LocalToolOption.Nfc", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("nfc_write_tag", "local/NfcTools.kt#nfcWriteTagTool", "LocalToolOption.Nfc", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("notification_action_click", "local/NotificationListenerTool.kt#notificationActionClickTool", "LocalToolOption.NotificationListener", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("notification_reply", "local/NotificationListenerTool.kt#notificationReplyTool", "LocalToolOption.NotificationListener", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("notification_status", "local/NotificationListenerTool.kt#notificationStatusTool", "LocalToolOption.NotificationListener", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("open_file", "local/OpenFileTool.kt#openFileTool", "LocalToolOption.Files", "FILES", "OpenFileToolUI", InvocationKind.STANDARD),
        Entry("open_url", "local/AppLauncherTool.kt#openUrlTool", "LocalToolOption.AppLauncher", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("open_wifi_settings", "local/SystemIntentTools.kt#openWifiSettingsTool", "LocalToolOption.SystemIntents", "DEVICE_CONTROL", "OpenWifiSettingsToolUI", InvocationKind.STANDARD),
        Entry("pause_job", "local/CronJobTool.kt#pauseJobTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("pause_media", "local/MediaPlayerTool.kt#pauseMediaTool", "LocalToolOption.MediaPlayer", "MEDIA", null, InvocationKind.STANDARD),
        Entry("play_media", "local/MediaPlayerTool.kt#playMediaTool", "LocalToolOption.MediaPlayer", "MEDIA", null, InvocationKind.STANDARD),
        Entry("post_notification", "local/NotificationTool.kt#notificationTool", "LocalToolOption.Notification", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("read_file", "local/FileManagerTool.kt#readFileTool", "LocalToolOption.Files", "FILES", "LocalReadFileToolUI", InvocationKind.STANDARD),
        Entry("read_window_tree", "local/WindowTreeTool.kt#readWindowTreeTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("recent_chats", "ConversationTools.kt#createConversationTools", "ChatToolFactory", "CONVERSATION", "RecentChatsToolUI", InvocationKind.STANDARD),
        Entry("record_audio", "local/MicRecorderTool.kt#micRecorderTool", "LocalToolOption.MicRecorder", "MEDIA", null, InvocationKind.STANDARD),
        Entry("resume_job", "local/CronJobTool.kt#resumeJobTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("resume_media", "local/MediaPlayerTool.kt#resumeMediaTool", "LocalToolOption.MediaPlayer", "MEDIA", null, InvocationKind.STANDARD),
        Entry("run_js", "skills/js/RunJsTool.kt#runJsTool", "LocalToolOption.JsSkills", "AUTOMATION", "RunJsToolUI", InvocationKind.STANDARD),
        Entry("save_ssh_host", "local/SshHostsTool.kt#saveSshHostTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("scan_media", "local/MediaScannerTool.kt#mediaScannerTool", "LocalToolOption.MediaScanner", "MEDIA", null, InvocationKind.STANDARD),
        Entry("schedule_job", "local/CronJobTool.kt#scheduleJobTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("schedule_job_direct", "local/CronJobTool.kt#scheduleJobDirectTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("schedule_job_llm", "local/CronJobTool.kt#scheduleJobLlmTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("scrape_web", "SearchTools.kt#createSearchTools", "ChatToolFactory.createSearchTools", "—", "ScrapeWebToolUI", InvocationKind.STANDARD),
        Entry("scroll", "local/ScrollTool.kt#scrollTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "ScrollToolUI", InvocationKind.STANDARD),
        Entry("search_contacts", "local/ContactsTool.kt#searchContactsTool", "LocalToolOption.Contacts", "PERSONAL", null, InvocationKind.STANDARD),
        Entry("search_sms", "local/SmsInboxTool.kt#searchSmsTool", "LocalToolOption.SmsInbox", "PERSONAL", "SearchSmsToolUI", InvocationKind.STANDARD),
        Entry("search_web", "SearchTools.kt#createSearchTools", "ChatToolFactory.createSearchTools", "—", "SearchWebToolUI", InvocationKind.STANDARD),
        Entry("seek_media", "local/MediaPlayerTool.kt#seekMediaTool", "LocalToolOption.MediaPlayer", "MEDIA", null, InvocationKind.STANDARD),
        Entry("send_email_intent", "local/SystemIntentTools.kt#sendEmailIntentTool", "LocalToolOption.SystemIntents", "DEVICE_CONTROL", "SendEmailIntentToolUI", InvocationKind.STANDARD),
        Entry("send_sms", "local/SmsSendTool.kt#smsSendTool", "LocalToolOption.SmsSend", "PERSONAL", "SendSmsToolUI", InvocationKind.STANDARD),
        Entry("send_sms_intent", "local/SystemIntentTools.kt#sendSmsIntentTool", "LocalToolOption.SystemIntents", "DEVICE_CONTROL", "SendSmsIntentToolUI", InvocationKind.STANDARD),
        Entry("set_brightness", "local/BrightnessTool.kt#setBrightnessTool", "LocalToolOption.Brightness", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("set_text", "local/FindNodeTool.kt#setTextTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "SetTextToolUI", InvocationKind.STANDARD),
        Entry("set_torch", "local/TorchTool.kt#torchTool", "LocalToolOption.Torch", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("set_volume", "local/VolumeTool.kt#setVolumeTool", "LocalToolOption.Volume", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("set_wallpaper", "local/SetWallpaperTool.kt#setWallpaperTool", "LocalToolOption.Wallpaper", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("settings_get", "local/ShizukuAppOpsTools.kt#settingsGetTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("settings_put", "local/ShizukuAppOpsTools.kt#settingsPutTool", "LocalToolOption.Shizuku", "REMOTE", null, InvocationKind.STANDARD),
        Entry("share", "local/ShareTool.kt#shareTool", "LocalToolOption.Share", "PERSONAL", null, InvocationKind.STANDARD),
        Entry("shizuku_exec", "local/ShizukuTool.kt#shizukuExecTool", "LocalToolOption.Shizuku", "REMOTE", "ShizukuExecToolUI", InvocationKind.STANDARD),
        Entry("show_image", "local/ShowImageTool.kt#showImageTool", "LocalToolOption.Files", "FILES", "ShowImageToolUI", InvocationKind.STANDARD),
        Entry("show_location_on_map", "local/SystemIntentTools.kt#showLocationOnMapTool", "LocalToolOption.SystemIntents", "DEVICE_CONTROL", "ShowLocationOnMapToolUI", InvocationKind.STANDARD),
        Entry("show_toast", "local/ToastTool.kt#toastTool", "LocalToolOption.Toast", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("skill_get_content", "SkillGetContentTool.kt#skillGetContentTool", "ChatToolFactory.workspaceAndSkillTools", "—", null, InvocationKind.STANDARD),
        Entry("skill_install_from_text", "skills/SkillInstallTools.kt#skillInstallFromTextTool", "LocalToolOption.SkillImport", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("skill_install_from_url", "skills/SkillInstallTools.kt#skillInstallFromUrlTool", "LocalToolOption.SkillImport", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("speech_to_text", "local/SpeechToTextTool.kt#speechToTextTool", "LocalToolOption.SpeechToText", "MEDIA", null, InvocationKind.STANDARD),
        Entry("ssh_download", "local/SshSftpTool.kt#sshDownloadTool", "LocalToolOption.Ssh", "REMOTE", "SshDownloadToolUI", InvocationKind.STANDARD),
        Entry("ssh_exec", "local/SshTool.kt#sshExecTool", "LocalToolOption.Ssh", "REMOTE", "SshExecToolUI", InvocationKind.STANDARD),
        Entry("ssh_exec_saved", "local/SshHostsTool.kt#sshExecSavedTool", "LocalToolOption.Ssh", "REMOTE", "SshExecSavedToolUI", InvocationKind.STANDARD),
        Entry("ssh_forget_host_key", "local/SshHostsTool.kt#forgetSshHostKeyTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("ssh_job_poll", "local/SshJobTool.kt#sshJobPollTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("ssh_presets", "local/SshHostsTool.kt#sshPresetsTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("ssh_upload", "local/SshSftpTool.kt#sshUploadTool", "LocalToolOption.Ssh", "REMOTE", "SshUploadToolUI", InvocationKind.STANDARD),
        Entry("stop_media", "local/MediaPlayerTool.kt#stopMediaTool", "LocalToolOption.MediaPlayer", "MEDIA", null, InvocationKind.STANDARD),
        Entry("subagent_cancel", "subagent/SubAgentTools.kt#subagentCancelTool", "LocalToolOption.SubAgents", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("subagent_dispatch", "subagent/SubAgentTools.kt#subagentDispatchTool", "LocalToolOption.SubAgents", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("subagent_get", "subagent/SubAgentTools.kt#subagentGetTool", "LocalToolOption.SubAgents", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("subagent_list", "subagent/SubAgentTools.kt#subagentListTool", "LocalToolOption.SubAgents", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("swipe", "local/SwipeTool.kt#swipeTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "SwipeToolUI", InvocationKind.STANDARD),
        Entry("take_photo", "local/CameraPhotoTool.kt#cameraPhotoTool", "LocalToolOption.CameraPhoto", "MEDIA", "TakePhotoToolUI", InvocationKind.STANDARD),
        Entry("take_screenshot", "local/ScreenshotTool.kt#takeScreenshotTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "ImageToolUI", InvocationKind.STANDARD),
        Entry("tap", "local/TapTool.kt#tapTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", "GestureToolUI", InvocationKind.STANDARD),
        Entry("telegram_add_whitelist", "local/TelegramTool.kt#telegramAddWhitelistTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramWhitelistToolUI", InvocationKind.STANDARD),
        Entry("telegram_delete_commands", "local/TelegramTool.kt#telegramDeleteCommandsTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramDeleteCommandsToolUI", InvocationKind.STANDARD),
        Entry("telegram_disable", "local/TelegramTool.kt#telegramDisableTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramDisableToolUI", InvocationKind.STANDARD),
        Entry("telegram_enable", "local/TelegramTool.kt#telegramEnableTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramEnableToolUI", InvocationKind.STANDARD),
        Entry("telegram_get_commands", "local/TelegramTool.kt#telegramGetCommandsTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramGetCommandsToolUI", InvocationKind.STANDARD),
        Entry("telegram_remove_whitelist", "local/TelegramTool.kt#telegramRemoveWhitelistTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramRemoveWhitelistToolUI", InvocationKind.STANDARD),
        Entry("telegram_send_document", "local/TelegramTool.kt#telegramSendDocumentTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramSendDocumentToolUI", InvocationKind.STANDARD),
        Entry("telegram_send_message", "local/TelegramTool.kt#telegramSendMessageTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramSendToolUI", InvocationKind.STANDARD),
        Entry("telegram_send_photo", "local/TelegramTool.kt#telegramSendPhotoTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramSendPhotoToolUI", InvocationKind.STANDARD),
        Entry("telegram_set_assistant", "local/TelegramTool.kt#telegramSetAssistantTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramSetAssistantToolUI", InvocationKind.STANDARD),
        Entry("telegram_set_commands", "local/TelegramTool.kt#telegramSetCommandsTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramCommandsToolUI", InvocationKind.STANDARD),
        Entry("telegram_set_default_chat", "local/TelegramTool.kt#telegramSetDefaultChatTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramSetDefaultChatToolUI", InvocationKind.STANDARD),
        Entry("telegram_set_token", "local/TelegramTool.kt#telegramSetTokenTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramSetTokenToolUI", InvocationKind.STANDARD),
        Entry("telegram_status", "local/TelegramTool.kt#telegramStatusTool", "LocalToolOption.TelegramBot", "AUTOMATION", "TelegramStatusToolUI", InvocationKind.STANDARD),
        Entry("termux_run_command", "local/TermuxTool.kt#termuxRunCommandTool", "LocalToolOption.Termux", "REMOTE", "TermuxRunCommandToolUI", InvocationKind.STANDARD),
        Entry("termux_session_kill", "local/TermuxSessionTool.kt#termuxSessionKillTool", "LocalToolOption.Termux", "REMOTE", "TermuxSessionKillToolUI", InvocationKind.STANDARD),
        Entry("termux_session_list", "local/TermuxSessionTool.kt#termuxSessionListTool", "LocalToolOption.Termux", "REMOTE", "TermuxSessionListToolUI", InvocationKind.STANDARD),
        Entry("termux_session_read", "local/TermuxSessionTool.kt#termuxSessionReadTool", "LocalToolOption.Termux", "REMOTE", "TermuxSessionReadToolUI", InvocationKind.STANDARD),
        Entry("termux_session_send", "local/TermuxSessionTool.kt#termuxSessionSendTool", "LocalToolOption.Termux", "REMOTE", "TermuxSessionSendToolUI", InvocationKind.STANDARD),
        Entry("termux_session_start", "local/TermuxSessionTool.kt#termuxSessionStartTool", "LocalToolOption.Termux", "REMOTE", "TermuxToolUI", InvocationKind.STANDARD),
        Entry("test_model", "local/AppDiagnosticsTools.kt#testModelTool", "LocalToolOption.ModelTesting", "WEB_AI", null, InvocationKind.STANDARD),
        Entry("text_to_speech", "LocalTools.kt#ttsTool", "LocalToolOption.Tts", "MEDIA", "TextToSpeechToolUI", InvocationKind.STANDARD),
        Entry("tool_surface_report", "costguards/ToolSurfaceReport.kt#toolSurfaceReportTool", "LocalToolOption.CostGuards", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("transcribe_audio_file", "local/TranscribeAudioTool.kt#transcribeAudioFileTool", "LocalToolOption.Termux", "REMOTE", null, InvocationKind.STANDARD),
        Entry("trigger_job_now", "local/CronJobTool.kt#triggerJobNowTool", "LocalToolOption.CronJobs", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("unzip_file", "local/ArchiveTools.kt#unzipFileTool", "LocalToolOption.Archive", "FILES", "UnzipFileToolUI", InvocationKind.STANDARD),
        Entry("use_skill", "SkillsTools.kt#createSkillTools", "ChatToolFactory.workspaceAndSkillTools", "—", "UseSkillToolUI", InvocationKind.STANDARD),
        Entry("vault_compare_loadcreds", "data/vault/VaultTools.kt#vaultCompareLoadCredsTool", "LocalToolOption.VaultExportEnv", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_audit", "data/vault/VaultTools.kt#vaultCredentialAuditTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_merge", "data/vault/VaultTools.kt#vaultCredentialMergeTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_meta", "data/vault/VaultTools.kt#vaultCredentialMetaTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_names", "data/vault/VaultTools.kt#vaultCredentialNamesTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_normalize_names", "data/vault/VaultTools.kt#vaultCredentialNormalizeTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_prepare", "data/vault/VaultTools.kt#vaultCredentialPrepareTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_credential_refs", "data/vault/VaultReferenceLocator.kt#vaultCredentialRefsTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_deploy_ssh_key", "local/SshHostsTool.kt#vaultDeployKeyTool", "LocalToolOption.Ssh", "REMOTE", null, InvocationKind.STANDARD),
        Entry("vault_export_env", "data/vault/VaultTools.kt#vaultExportEnvTool", "LocalToolOption.VaultExportEnv", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_export_loadcreds", "data/vault/VaultTools.kt#vaultExportLoadCredsTool", "LocalToolOption.VaultExportEnv", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_import_loadcreds", "data/vault/VaultTools.kt#vaultImportLoadCredsTool", "LocalToolOption.VaultExportEnv", "VAULT", null, InvocationKind.STANDARD),
        Entry("vault_public_key_entries", "data/vault/VaultTools.kt#vaultPublicKeyEntriesTool", "LocalToolOption.VaultTools", "VAULT", null, InvocationKind.STANDARD),
        Entry("verify_fingerprint", "local/FingerprintTool.kt#fingerprintTool", "LocalToolOption.Fingerprint", "VAULT", null, InvocationKind.STANDARD),
        Entry("vibrate", "local/VibrateTool.kt#vibrateTool", "LocalToolOption.Vibrate", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("wake_screen", "local/WakeScreenTool.kt#wakeScreenTool", "LocalToolOption.ScreenAutomation", "DEVICE_CONTROL", null, InvocationKind.STANDARD),
        Entry("web_fetch", "local/WebFetchTool.kt#webFetchTool", "LocalToolOption.WebFetch", "WEB_AI", "WebFetchToolUI", InvocationKind.STANDARD),
        Entry("whisper_status", "local/TranscribeAudioTool.kt#whisperStatusTool", "LocalToolOption.Termux", "REMOTE", null, InvocationKind.STANDARD),
        Entry("workflow_create", "workflow/tools/WorkflowTools.kt#workflowCreateTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workflow_delete", "workflow/tools/WorkflowTools.kt#workflowDeleteTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workflow_get", "workflow/tools/WorkflowTools.kt#workflowGetTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workflow_list", "workflow/tools/WorkflowTools.kt#workflowListTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workflow_run", "workflow/tools/WorkflowTools.kt#workflowRunTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workflow_set_enabled", "workflow/tools/WorkflowTools.kt#workflowSetEnabledTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workflow_update", "workflow/tools/WorkflowTools.kt#workflowUpdateTool", "LocalToolOption.Workflows", "AUTOMATION", null, InvocationKind.STANDARD),
        Entry("workspace_apply_edits", "WorkspaceApplyEdits.kt#createApplyEditsTool", "ChatToolFactory.workspaceAndSkillTools", "—", "WorkspaceApplyEditsToolUI", InvocationKind.STANDARD),
        Entry("workspace_background_kill", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "WorkspaceBackgroundKillToolUI", InvocationKind.STANDARD),
        Entry("workspace_background_status", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "WorkspaceBackgroundStatusToolUI", InvocationKind.STANDARD),
        Entry("workspace_create_folder", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", null, InvocationKind.STANDARD),
        Entry("workspace_edit_file", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "EditFileToolUI", InvocationKind.STANDARD),
        Entry("workspace_list", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", null, InvocationKind.STANDARD),
        Entry("workspace_read_file", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "ReadFileToolUI", InvocationKind.STANDARD),
        Entry("workspace_read_folder", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", null, InvocationKind.STANDARD),
        Entry("workspace_run_background", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "WorkspaceRunBackgroundToolUI", InvocationKind.STANDARD),
        Entry("workspace_search_code", "WorkspaceSearchTool.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "WorkspaceSearchCodeToolUI", InvocationKind.STANDARD),
        Entry("workspace_shell", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "ShellToolUI", InvocationKind.STANDARD),
        Entry("workspace_write_file", "WorkspaceTools.kt#createWorkspaceTools", "ChatToolFactory.workspaceAndSkillTools", "—", "WriteFileToolUI", InvocationKind.STANDARD),
        Entry("write_binary_file", "local/FileManagerTool.kt#writeBinaryFileTool", "LocalToolOption.Files", "FILES", "WriteBinaryFileToolUI", InvocationKind.STANDARD),
        Entry("write_text_file", "local/WriteFileTool.kt#writeTextFileTool", "LocalToolOption.Download", "MEDIA", "WriteTextFileToolUI", InvocationKind.STANDARD),
        Entry("zip_files", "local/ArchiveTools.kt#zipFilesTool", "LocalToolOption.Archive", "FILES", "ZipFilesToolUI", InvocationKind.STANDARD),
    )

    private val byName: Map<String, Entry> = entries.associateBy { it.toolName }

    /** 按工具名查找 */
    fun find(toolName: String): Entry? = byName[toolName]

    /** 按注册路径分组 */
    fun byRegistration(registeredVia: String): List<Entry> =
        entries.filter { it.registeredVia == registeredVia }

    /** 按分类分组 */
    fun byCategory(category: String): List<Entry> =
        entries.filter { it.category == category }

    /** 有专用渲染器的 */
    fun withDedicatedRenderer(): List<Entry> = entries.filter { it.renderer != null }

    /** 走默认渲染的 */
    fun defaultRendered(): List<Entry> = entries.filter { it.renderer == null }

    /** 已定义但未接入注入路径的（死代码或待处理） */
    fun unregistered(): List<Entry> = entries.filter { it.registeredVia == "UNREGISTERED" }

    /** 按温度档位分组 */
    fun byTier(tier: SurfaceTier): List<Entry> = entries.filter { it.tier == tier }
}
