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
    // settingsFlow 本身已经是 StateFlow，且首帧就有磁盘值；再包一层 stateIn 会把初值变成
    // Settings(init = true)（全默认：头像为空、开关全默认），页面首帧就会拿它渲染，
    // 并且点击时拿这份默认快照整体写回，把头像与自定义设置覆盖掉。直接透传。
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    /**
     * 快捷入口解析结果：稳定流（有变动才变），卡片不再随任何无关设置写入而重建/消失。
     * 用户报：每次进设置页网格要滑动才出现。
     */
    val shortcutEntries: StateFlow<List<SettingEntry>> =
        settingsStore.settingsFlow
            .map { it.settingShortcutIds.mapNotNull { id -> SettingCatalog.byId(id) } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 同步取当前快捷入口（StateFlow 初值为空列表，进页首帧用它避免网格闪动）。 */
    fun shortcutEntriesNow(): List<SettingEntry> =
        settingsStore.settingsFlow.value.let { s -> s.settingShortcutIds.mapNotNull { id -> SettingCatalog.byId(id) } }

    /** Queue a change against the store's latest value, never a Compose snapshot. */
    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

}
