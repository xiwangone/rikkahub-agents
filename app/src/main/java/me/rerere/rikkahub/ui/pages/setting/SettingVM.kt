package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
) : ViewModel() {
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow
            .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    /**
     * 快捷入口解析结果：稳定流（有变动才变），卡片不再随任何无关设置写入而重建/消失。
     * 用户报：每次进设置页网格要滑动才出现（2026-09-26）。
     */
    val shortcutEntries: StateFlow<List<SettingEntry>> =
        settingsStore.settingsFlow
            .map { it.settingShortcutIds.mapNotNull { id -> SettingCatalog.byId(id) } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }
}
