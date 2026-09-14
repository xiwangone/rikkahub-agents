package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * 设置页分组：**枚举顺序即界面顺序**，重排只需改这里，不再依赖代码位置。
 */
enum class SettingGroup(val id: String, val titleRes: Int) {
    GENERAL("general", R.string.setting_page_general_settings),
    MODEL("model", R.string.setting_page_section_model),
    CONNECTION("connection", R.string.setting_page_section_connection),
    TOOLS("tools", R.string.setting_page_section_tools),
    SECURITY("security", R.string.setting_page_section_security),
    AUTOMATION("automation", R.string.setting_page_section_automation),
    DATA("data", R.string.setting_page_data_settings),
    ABOUT("about", R.string.setting_page_about),
}

/**
 * 一个"跳转型"设置入口：[id] 稳定且与文案无关，用于快捷区与埋点；[screen] 为目标路由。
 *
 * 说明：带内联控件的项（如颜色模式的 Select）或动作型项（分享）不在此表内，仍在页面内单独渲染。
 */
data class SettingEntry(
    val id: String,
    val group: SettingGroup,
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
    val screen: Screen,
)

/** 快捷区最多可放的入口数（3×2 网格，上下对齐）。 */
const val SETTING_SHORTCUT_LIMIT = 6

/** 设置项注册表：分组的渲染顺序与组内顺序都由这份数据决定。 */
object SettingCatalog {

    val entries: List<SettingEntry> =
        listOf(
        SettingEntry(
            id = "preferences",
            group = SettingGroup.GENERAL,
            icon = HugeIcons.Settings03,
            titleRes = R.string.setting_page_preferences,
            descRes = R.string.setting_page_preferences_desc,
            screen = Screen.SettingPreferences,
        ),
        SettingEntry(
            id = "assistant",
            group = SettingGroup.GENERAL,
            icon = HugeIcons.LookTop,
            titleRes = R.string.setting_page_assistant,
            descRes = R.string.setting_page_assistant_desc,
            screen = Screen.Assistant,
        ),
        SettingEntry(
            id = "extensions",
            group = SettingGroup.GENERAL,
            icon = HugeIcons.Package,
            titleRes = R.string.setting_page_extensions,
            descRes = R.string.setting_page_extensions_desc,
            screen = Screen.Extensions,
        ),
        SettingEntry(
            id = "default_model",
            group = SettingGroup.MODEL,
            icon = HugeIcons.AiMagic,
            titleRes = R.string.setting_page_default_model,
            descRes = R.string.setting_page_default_model_desc,
            screen = Screen.SettingModels,
        ),
        SettingEntry(
            id = "providers",
            group = SettingGroup.MODEL,
            icon = HugeIcons.Brain02,
            titleRes = R.string.setting_page_providers,
            descRes = R.string.setting_page_providers_desc,
            screen = Screen.SettingProvider,
        ),
        SettingEntry(
            id = "search_service",
            group = SettingGroup.MODEL,
            icon = HugeIcons.GlobalSearch,
            titleRes = R.string.setting_page_search_service,
            descRes = R.string.setting_page_search_service_desc,
            screen = Screen.SettingSearch,
        ),
        SettingEntry(
            id = "tts_service",
            group = SettingGroup.MODEL,
            icon = HugeIcons.Megaphone01,
            titleRes = R.string.setting_page_tts_service,
            descRes = R.string.setting_page_tts_service_desc,
            screen = Screen.SettingSpeech,
        ),
        SettingEntry(
            id = "web_capability",
            group = SettingGroup.CONNECTION,
            icon = HugeIcons.ServerStack01,
            titleRes = R.string.setting_page_web_capability,
            descRes = R.string.setting_page_web_server_desc,
            screen = Screen.SettingWeb,
        ),
        SettingEntry(
            id = "backend_service",
            group = SettingGroup.CONNECTION,
            icon = HugeIcons.ServerStack01,
            titleRes = R.string.setting_page_backend_service,
            descRes = R.string.setting_page_backend_service_desc,
            screen = Screen.BackendService,
        ),
        SettingEntry(
            id = "mcp",
            group = SettingGroup.CONNECTION,
            icon = HugeIcons.McpServer,
            titleRes = R.string.setting_page_mcp,
            descRes = R.string.setting_page_mcp_desc,
            screen = Screen.SettingMcp,
        ),
        SettingEntry(
            id = "telegram",
            group = SettingGroup.CONNECTION,
            icon = HugeIcons.Telegram,
            titleRes = R.string.setting_page_telegram,
            descRes = R.string.setting_page_telegram_desc,
            screen = Screen.SettingTelegram,
        ),
        SettingEntry(
            id = "browser",
            group = SettingGroup.TOOLS,
            icon = HugeIcons.Earth,
            titleRes = R.string.setting_page_browser,
            descRes = R.string.setting_page_browser_desc,
            screen = Screen.SettingBrowser,
        ),
        SettingEntry(
            id = "ssh_hosts",
            group = SettingGroup.TOOLS,
            icon = HugeIcons.Console,
            titleRes = R.string.setting_page_ssh_hosts,
            descRes = R.string.setting_page_ssh_hosts_desc,
            screen = Screen.SettingSshHosts,
        ),
        SettingEntry(
            id = "shizuku",
            group = SettingGroup.TOOLS,
            icon = HugeIcons.Console,
            titleRes = R.string.setting_page_shizuku,
            descRes = R.string.setting_page_shizuku_desc,
            screen = Screen.SettingShizuku,
        ),
        SettingEntry(
            id = "termux",
            group = SettingGroup.TOOLS,
            icon = HugeIcons.Console,
            titleRes = R.string.setting_page_termux,
            descRes = R.string.setting_page_termux_desc,
            screen = Screen.SettingTermux,
        ),
        SettingEntry(
            id = "vault",
            group = SettingGroup.SECURITY,
            icon = HugeIcons.LockKey,
            titleRes = R.string.setting_page_vault,
            descRes = R.string.setting_page_vault_desc,
            screen = Screen.Vault,
        ),
        SettingEntry(
            id = "tool_approvals",
            group = SettingGroup.SECURITY,
            icon = HugeIcons.Tick01,
            titleRes = R.string.setting_page_tool_approvals,
            descRes = R.string.setting_page_tool_approvals_desc,
            screen = Screen.SettingToolApprovals,
        ),
        SettingEntry(
            id = "notifications",
            group = SettingGroup.SECURITY,
            icon = HugeIcons.Alert01,
            titleRes = R.string.setting_page_notifications,
            descRes = R.string.setting_page_notifications_desc,
            screen = Screen.SettingNotifications,
        ),
        SettingEntry(
            id = "permissions",
            group = SettingGroup.SECURITY,
            icon = HugeIcons.Shield01,
            titleRes = R.string.setting_page_permissions,
            descRes = R.string.setting_page_permissions_desc,
            screen = Screen.SettingPermissions,
        ),
        SettingEntry(
            id = "workflows",
            group = SettingGroup.AUTOMATION,
            icon = HugeIcons.Connect,
            titleRes = R.string.setting_page_workflows,
            descRes = R.string.setting_page_workflows_desc,
            screen = Screen.SettingWorkflows,
        ),
        SettingEntry(
            id = "scheduled_jobs",
            group = SettingGroup.AUTOMATION,
            icon = HugeIcons.Clock02,
            titleRes = R.string.setting_page_scheduled_jobs,
            descRes = R.string.setting_page_scheduled_jobs_desc,
            screen = Screen.SettingScheduledJobs,
        ),
        SettingEntry(
            id = "sub_agents",
            group = SettingGroup.AUTOMATION,
            icon = HugeIcons.Robot01,
            titleRes = R.string.setting_page_sub_agents,
            descRes = R.string.setting_page_sub_agents_desc,
            screen = Screen.SettingSubAgents,
        ),
        SettingEntry(
            id = "doctor",
            group = SettingGroup.AUTOMATION,
            icon = HugeIcons.Wrench01,
            titleRes = R.string.setting_page_doctor,
            descRes = R.string.setting_page_doctor_desc,
            screen = Screen.SettingDoctor,
        ),
        SettingEntry(
            id = "accessibility",
            group = SettingGroup.AUTOMATION,
            icon = HugeIcons.SmartPhone01,
            titleRes = R.string.setting_page_accessibility,
            descRes = R.string.setting_page_accessibility_desc,
            screen = Screen.SettingAccessibility,
        ),
        SettingEntry(
            id = "data_backup",
            group = SettingGroup.DATA,
            icon = HugeIcons.Database02,
            titleRes = R.string.setting_page_data_backup,
            descRes = R.string.setting_page_data_backup_desc,
            screen = Screen.Backup,
        ),
        SettingEntry(
            id = "request_logs",
            group = SettingGroup.AUTOMATION,
            icon = HugeIcons.Bookshelf01,
            titleRes = R.string.setting_page_request_logs,
            descRes = R.string.setting_page_request_logs_desc,
            screen = Screen.Log,
        ),
        )

    fun entriesOf(group: SettingGroup): List<SettingEntry> = entries.filter { it.group == group }

    fun byId(id: String): SettingEntry? = entries.firstOrNull { it.id == id }
}
