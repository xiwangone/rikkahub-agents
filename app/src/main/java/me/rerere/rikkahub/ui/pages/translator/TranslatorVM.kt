@file:Suppress("TooGenericExceptionCaught") // 翻译请求跨越网络/解析/取消等多类异常；取消已单独透传，其余统一上报

package me.rerere.rikkahub.ui.pages.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import java.util.Locale

private const val TAG = "TranslatorVM"

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationLoop,
    private val historyRepository: TranslationHistoryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow
            .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val history = historyRepository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    // 翻译状态
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating

    // 输入文本
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    // 翻译结果
    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    // 翻译目标语言
    private val _targetLanguage = MutableStateFlow(Locale.SIMPLIFIED_CHINESE)
    val targetLanguage: StateFlow<Locale> = _targetLanguage

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 当前任务
    private var currentJob: Job? = null

    fun updateSettings(update: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(update)
        }
    }

    fun reuseHistory(entry: TranslationHistoryEntity) {
        _inputText.value = entry.sourceText
        _translatedText.value = entry.translatedText
        _targetLanguage.value = Locale.forLanguageTag(entry.targetLanguage)
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch { historyRepository.delete(id) }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun updateTargetLanguage(language: Locale) {
        _targetLanguage.value = language
    }

    fun translate() {
        val inputText = _inputText.value
        if (inputText.isBlank()) return

        // 取消当前任务
        currentJob?.cancel()

        // 设置翻译中状态
        _translating.value = true
        _translatedText.value = ""

        val requestSettings = settings.value
        val requestedLanguage = targetLanguage.value
        currentJob =
            viewModelScope.launch {
                try {
                    generationHandler
                        .translateText(
                            settings = requestSettings,
                            sourceText = inputText,
                            targetLanguage = requestedLanguage,
                        ) { translatedText ->
                            // Preserve the existing streaming translation chain.
                            _translatedText.value = translatedText
                        }.collect { /* Final translation is delivered by onStreamUpdate. */ }
                    val result = _translatedText.value
                    if (result.isNotBlank()) {
                        historyRepository.add(
                            sourceText = inputText,
                            translatedText = result,
                            targetLanguage = requestedLanguage.toLanguageTag(),
                            modelId = requestSettings.translateModeId,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error.printStackTrace()
                    errorFlow.emit(error)
                } finally {
                    if (currentJob == coroutineContext[Job]) {
                        currentJob = null
                        _translating.value = false
                    }
                }
            }
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        currentJob = null
        _translating.value = false
    }
}
