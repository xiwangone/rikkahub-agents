package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant

@Composable
fun rememberAssistantState(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
): AssistantState =
    remember(settings, onUpdateSettings) {
        AssistantState(settings, onUpdateSettings)
    }

class AssistantState(
    private val settings: Settings,
    private val onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant(),
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        onUpdateSettings { current -> current.copy(assistantId = assistant.id) }
    }
}
