package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * One searchable destination in the settings hub. Entries backed by [SettingCatalog] use the
 * same localized headline, description, and group as the home page; [searchTermsRes] preserves
 * alternate page-title wording without maintaining a second copy of the whole catalog.
 */
data class SettingsSearchEntry(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    @param:StringRes val groupRes: Int,
    val route: Screen,
    val searchTermsRes: List<Int> = emptyList(),
)

/**
 * Searchable destinations shown by [SettingPage]. Most entries come directly from
 * [SettingCatalog], keeping search coverage and grouping aligned with the home page. Inline and
 * action-only rows are intentionally omitted; page destinations not represented by the catalog
 * (such as chat storage and preference sub-pages) are added below.
 */
fun settingsSearchIndex(developerMode: Boolean): List<SettingsSearchEntry> {
    val entries = SettingCatalog.entries.map { entry ->
        SettingsSearchEntry(
            titleRes = entry.titleRes,
            descriptionRes = entry.descRes,
            groupRes = entry.group.titleRes,
            route = entry.screen,
            searchTermsRes = entry.searchTermsRes,
        )
    }.toMutableList()

    entries += listOf(
        SettingsSearchEntry(
            titleRes = R.string.setting_page_chat_storage,
            groupRes = R.string.setting_page_data_settings,
            route = Screen.SettingFiles,
            searchTermsRes = listOf(R.string.setting_files_page_title),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_about,
            descriptionRes = R.string.setting_page_about_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.SettingAbout,
            searchTermsRes = listOf(R.string.about_page_title),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_theme,
            descriptionRes = R.string.setting_page_preferences_theme_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesTheme,
            searchTermsRes = listOf(R.string.setting_page_color_mode),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_notification,
            descriptionRes = R.string.setting_page_preferences_notification_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesNotification,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_general,
            descriptionRes = R.string.setting_page_preferences_general_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesGeneral,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_ui,
            descriptionRes = R.string.setting_page_preferences_ui_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesUI,
        ),
        // 网络子页：偏好设置 → 网络下的 5 个子页。Catalog 只登记首页分组入口，
        // 子页按本文件 KDoc 约定走硬编码区（与上方偏好子页同款）。
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_network_routing,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingNetworkRouting,
            searchTermsRes = listOf(R.string.setting_page_preferences_network),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_network_proxy,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingNetworkProxy,
            searchTermsRes = listOf(R.string.setting_page_preferences_network),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_network_dns_title,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingNetworkDns,
            searchTermsRes = listOf(R.string.setting_page_preferences_network),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_network_host_overrides,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingNetworkHostOverrides,
            searchTermsRes = listOf(R.string.setting_page_preferences_network),
        ),
        SettingsSearchEntry(
            titleRes = R.string.tunnel_enable_title,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingNetworkTunnel,
            searchTermsRes = listOf(R.string.setting_page_preferences_network),
        ),
    )

    if (developerMode) {
        entries += SettingsSearchEntry(
            titleRes = R.string.accessibility_developer_options,
            groupRes = R.string.settings,
            route = Screen.Developer,
        )
    }

    return entries
}
