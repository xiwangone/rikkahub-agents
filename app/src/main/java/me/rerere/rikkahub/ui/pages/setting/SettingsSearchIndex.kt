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
