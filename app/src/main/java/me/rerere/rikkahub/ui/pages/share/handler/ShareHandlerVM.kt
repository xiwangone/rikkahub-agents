package me.rerere.rikkahub.ui.pages.share.handler

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

class ShareHandlerVM(
    text: String,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val shareText = checkNotNull(text)
    val settings = settingsStore.settingsFlow

    suspend fun updateAssistant(assistantId: Uuid) {
        settingsStore.updateAssistant(assistantId)
    }
}
