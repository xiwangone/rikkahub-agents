package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findChatModelFor
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.datastore.resolveAssistantFor
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    /** 待发送队列状态（条目 + 是否暂停）；非空时在输入区上方显示队列面板。 */
    messageQueue: MessageQueueState = MessageQueueState(),
    onRemoveQueuedMessage: (Uuid) -> Unit = {},
    onBeginEditQueuedMessage: (Uuid) -> QueuedMessage? = { null },
    onFinishEditQueuedMessage: (Uuid, List<UIMessagePart>?) -> Unit = { _, _ -> },
    onResumeMessageQueue: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
    settings: Settings,
    /** 当前会话的模型覆盖（null = 跟随助手默认）；用于在输入框正确显示会话实际使用的模型。 */
    conversationModelId: Uuid? = null,
    /** 当前会话绑定的助手；据此解析助手/模型口径（与生成链路一致）。 */
    conversationAssistantId: Uuid? = null,
    hazeState: HazeState,
    enableSearch: Boolean,
    sessionTotals: TokenBudgetTracker.Totals? = null,
    /** 会话累计用量（与压缩解耦）；为空时回退到 sessionTotals 的旧行为 */
    lifetimeTotals: me.rerere.rikkahub.costguards.LifetimeUsage? = null,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    /** 长按模型按钮 = 复位为助手模型；null = 当前会话没有独立模型（无可复位）。 */
    onResetConversationModel: (() -> Unit)? = null,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onVaultAuthorizeClick: () -> Unit = {},
    onAutoClick: () -> Unit = {},
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
) {
    val toaster = LocalToaster.current
    val assistant = settings.resolveAssistantFor(conversationAssistantId)
    val settingsStore = koinInject<SettingsStore>()
    val coroutineScope = rememberCoroutineScope()
    val modelListState =
        rememberModelListState(
            // 助手关掉会话级模型时，忽略会话覆盖值（与生成链路口径一致）
            modelId = conversationModelId.takeIf { assistant.allowPerConversationModel }
                ?: assistant.chatModelId ?: settings.chatModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
    // 功能按钮行（Zap/LockKey/ASR 等）折叠状态：默认折叠，用户展开后持久化记忆
    val featureBarCollapsed = settings.displaySetting.featureBarCollapsed
    val toggleFeatureBar: () -> Unit = {
        coroutineScope.launch {
            settingsStore.update { current ->
                current.copy(
                    displaySetting =
                        current.displaySetting.copy(
                            featureBarCollapsed = !featureBarCollapsed,
                        ),
                )
            }
        }
    }
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow
    val appDarkMode = LocalDarkMode.current
    val appHazeBackground =
        if (appDarkMode) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface
    val inputHazeStyle =
        HazeMaterials.thin(containerColor = hazeTintColor)
            .copy(backgroundColor = appHazeBackground)

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val containerShape = MaterialTheme.shapes.largeIncreased

    // 发送键在生成中不再变成「停止」：此时按下表示把消息排队（由 ChatService 在
    // 本轮结束后按序发出），停止改由左侧按钮承担。这样用户无需等待生成结束。
    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsStateWithLifecycle()
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    // 底部渐变遮罩：从输入区顶部一直过渡到屏幕底部（键盘弹出时到键盘上沿），避免消息透到系统导航栏
    val scrimColor = MaterialTheme.colorScheme.background
    val imeInsets = WindowInsets.ime
    Surface(
        color = Color.Transparent,
        modifier = Modifier.drawWithCache {
            val brush = Brush.verticalGradient(
                colors = listOf(scrimColor.copy(alpha = 0f), scrimColor),
                startY = 0f,
                endY = (size.height - imeInsets.getBottom(this)).coerceAtLeast(1f),
            )
            onDrawBehind { drawRect(brush) }
        },
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(containerShape)
                        .then(
                            if (settings.displaySetting.enableBlurEffect) {
                                Modifier.hazeEffect(
                                    state = hazeState,
                                ) {
                                    blurEffect {
                                        style = inputHazeStyle
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (voiceState.phase != VoicePhase.Off) {
                        VoiceModeRow(
                            state = voiceState,
                            onStop = onStopVoiceMode,
                            onRetry = { onStartVoiceMode?.invoke() },
                        )
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    MessageQueuePanel(
                        state = messageQueue,
                        onRemove = onRemoveQueuedMessage,
                        onBeginEdit = onBeginEditQueuedMessage,
                        onFinishEdit = onFinishEditQueuedMessage,
                        onResume = onResumeMessageQueue,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左钮双态：生成中＝停止当前生成；空闲＝功能栏折叠开关
                        // （收起时箭头向下＝可展开，展开时箭头向上＝可收起）。
                        // 生成中箭头位被停止占用，因此此时不提供折叠。
                        if (loading) {
                            ActionIconButton(
                                onClick = onCancelClick,
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.stop),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            ActionIconButton(
                                onClick = toggleFeatureBar,
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                // 纯折角 chevron（不带竖柄），收起/展开两态样式一致
                                Icon(
                                    imageVector =
                                        if (featureBarCollapsed) {
                                            HugeIcons.ArrowDown01
                                        } else {
                                            HugeIcons.ArrowUp01
                                        },
                                    contentDescription = stringResource(R.string.chat_input_toggle_toolbar),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        // 累计行数据源：优先「与压缩解耦」的累计值，无记录时回退到当前聚合（旧行为）。
                        // 消息下方那行（本轮/上下文占用）不受影响，仍随压缩变化。
                        val mergedTotals =
                            lifetimeTotals?.let { lt ->
                                val input = maxOf(lt.inputTokens, sessionTotals?.inputTokens ?: 0L)
                                val output = maxOf(lt.outputTokens, sessionTotals?.outputTokens ?: 0L)
                                val cached = maxOf(lt.cachedTokens, sessionTotals?.cachedTokens ?: 0L).coerceAtMost(input)
                                val cost = maxOf(lt.costUsd, sessionTotals?.costUsd ?: 0.0)
                                val count = maxOf(lt.turns, sessionTotals?.messageCount ?: 0)
                                TokenBudgetTracker.Totals(
                                    inputTokens = input,
                                    outputTokens = output,
                                    cachedTokens = cached,
                                    totalTokens = input + output,
                                    perMessageMax = sessionTotals?.perMessageMax ?: 0L,
                                    messageCount = count,
                                    lastRequestHitPct = sessionTotals?.lastRequestHitPct ?: 0.0,
                                    costUsd = cost,
                                )
                            } ?: sessionTotals
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                        ) {
                            TextInputRow(
                                state = state,
                                completionProviders = completionProviders,
                                sessionTotals = mergedTotals,
                                onSendMessage = { sendMessage() },
                            )
                        }
                        SendButton(
                            state = state,
                            loading = loading,
                            visible = !asrState.isRecording,
                            onSend = { sendMessage() },
                            onLongSend = { sendMessageWithoutAnswer() },
                        )
                    }

                    AnimatedVisibility(
                        visible = !featureBarCollapsed,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                // Model Picker
                                ModelSelectorButton(
                                    state = modelListState,
                                    onlyIcon = true,
                                    modifier = Modifier,
                                    allowClear = onResetConversationModel != null,
                                    onClear = { onResetConversationModel?.invoke() },
                                )

                                // Search
                                val enableSearchMsg = stringResource(R.string.web_search_enabled)
                                val disableSearchMsg = stringResource(R.string.web_search_disabled)
                                val chatModel = settings.findChatModelFor(conversationModelId, conversationAssistantId)
                                SearchPickerButton(
                                    enableSearch = enableSearch,
                                    settings = settings,
                                    onToggleSearch = { enabled ->
                                        onToggleSearch(enabled)
                                        toaster.show(
                                            message = if (enabled) enableSearchMsg else disableSearchMsg,
                                            duration = 1.seconds,
                                            type =
                                                if (enabled) {
                                                    ToastType.Success
                                                } else {
                                                    ToastType.Normal
                                                },
                                        )
                                    },
                                    onUpdateSearchService = onUpdateSearchService,
                                    model = chatModel,
                                )

                                // Reasoning
                                val model = settings.findChatModelFor(conversationModelId, conversationAssistantId)
                                // 模型信息来自异步加载的设置：首帧 model 可能为 null。
                                // 此时若不渲染，冷启动会看到该图标「稍后突然出现」（闪一下）。
                                // 改为「未确认不支持就渲染」，保持位置稳定；确认不支持时才隐藏。
                                if (model?.abilities?.contains(ModelAbility.REASONING) != false) {
                                    ReasoningButton(
                                        reasoningLevel = assistant.reasoningLevel,
                                        onUpdateReasoningLevel = {
                                            onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                        },
                                        onlyIcon = true,
                                    )
                                }
                            }

                            AgentQuickActionsButton(
                                state = state,
                            )

                            ActionIconButton(
                                onClick = onAutoClick,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = stringResource(R.string.auto_task_title),
                                )
                            }

                            // Vault 授权快捷入口（指纹签发 token，AI 直接读；可随时撤销）
                            ActionIconButton(
                                onClick = onVaultAuthorizeClick,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.LockKey,
                                    contentDescription = stringResource(R.string.vault_authorize_shortcut),
                                )
                            }

                            ActionIconButton(
                                onClick = onMoreClick,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Add01,
                                    contentDescription = stringResource(R.string.more_options),
                                )
                            }

                            if (!voiceState.isActive && (asrState.isAvailable || asrState.isRecording)) {
                                AsrButton(
                                    state = asrState,
                                    onClick = {
                                        when (asrState.status) {
                                            ASRStatus.Listening -> {
                                                asr.stop()
                                            }

                                            ASRStatus.Idle, ASRStatus.Error -> {
                                                if (!asrPermission.allRequiredPermissionsGranted) {
                                                    asrPermission.requestPermissions()
                                                } else {
                                                    asrBaseText = state.textContent.text.toString()
                                                    asr.start { transcript ->
                                                        val spacer =
                                                            if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                        state.setMessageText(asrBaseText + spacer + transcript)
                                                    }
                                                }
                                            }

                                            ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                        }
                                    },
                                )
                            }


                        }
                    }
                }
            }
        }
    }

    ModelListSheet(
        state = modelListState,
        onSelect = onUpdateChatModel,
    )
}


@Composable
private fun SendButton(
    state: ChatInputState,
    loading: Boolean,
    visible: Boolean,
    onSend: () -> Unit,
    onLongSend: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(48.dp)
                    .testTag("chat_send_button")
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = !state.isEmpty(),
                        onClick = onSend,
                        onLongClick = onLongSend,
                    ),
        ) {
            val containerColor =
                when {
                    state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.primary
                }
            val contentColor =
                when {
                    state.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.onPrimary
                }
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = containerColor,
                content = {},
            )
            if (loading) KeepScreenOn()
            // 生成中保持「发送」形态：按下即排队，不再是停止键。
            Icon(
                imageVector = HugeIcons.ArrowUp02,
                contentDescription = stringResource(R.string.send),
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
internal fun ActionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    sessionTotals: TokenBudgetTracker.Totals? = null,
    onSendMessage: () -> Unit,
) {
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    // 在组合作用域预先取好文案：粘贴回调不是 composable，在回调里取资源会踩
    // LocalContextGetResourceValueCall（只有 CI 的 Android Lint 能抓到）。
    val pasteLongTextAsFileMsg = stringResource(R.string.chat_input_paste_long_text_as_file)

    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val quickMessages =
        remember(settings.quickMessages, assistant.quickMessageIds) {
            settings.getQuickMessagesOfAssistant(assistant)
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() },
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        val receiveContentListener =
            remember(
                settings.displaySetting.pasteLongTextAsFile,
                settings.displaySetting.pasteLongTextThreshold,
            ) {
                ReceiveContentListener { transferableContent ->
                    when {
                        transferableContent.hasMediaType(MediaType.Image) -> {
                            transferableContent.consume { item ->
                                val uri = item.uri
                                if (uri != null) {
                                    state.addImages(
                                        filesManager.createChatFilesByContents(
                                            listOf(uri),
                                        ),
                                    )
                                }
                                uri != null
                            }
                        }

                        settings.displaySetting.pasteLongTextAsFile &&
                            transferableContent.hasMediaType(
                                MediaType.Text,
                            )
                        -> {
                            transferableContent.consume { item ->
                                val text = item.text?.toString()
                                if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                    val document = filesManager.createChatTextFile(text)
                                    state.addFiles(listOf(document))
                                    // 正文不入库输入框而是转为附件，不给提示会被当成“粘贴丢了内容”。
                                    toaster.show(
                                        message = pasteLongTextAsFileMsg,
                                        duration = 2.seconds,
                                        type = ToastType.Normal,
                                    )
                                    true
                                } else {
                                    false
                                }
                            }
                        }

                        else -> {
                            transferableContent
                        }
                    }
                }
            }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists =
                    completionProviders.mapNotNull { provider ->
                        try {
                            provider
                                .complete(context)
                                ?.takeIf { it.items.isNotEmpty() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                val primary = lists.firstOrNull()
                completionList =
                    primary?.let { list ->
                        val mergedItems =
                            lists
                                .filter { it.replacementRange == list.replacementRange }
                                .flatMap { it.items }
                                .distinctBy { it.label to it.insertText }
                                .sortedWith(
                                    compareByDescending<ChatCompletionItem> { it.sortScore }
                                        .thenBy { it.label.length }
                                        .thenBy { it.label.lowercase() },
                                ).take(8)
                        list.copy(items = mergedItems)
                    }
            }
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        TextField(
            state = state.textContent,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("chat_input")
                    .contentReceiver(receiveContentListener)
                    .onFocusChanged {
                        isFocused = it.isFocused
                    },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(R.string.chat_input_placeholder))
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default,
                ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors =
                TextFieldDefaults.colors().copy(
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(onClick = {
                        isFullScreen = !isFullScreen
                    }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon =
                if (quickMessages.isNotEmpty()) {
                    {
                        QuickMessageButton(quickMessages = quickMessages, state = state)
                    }
                } else {
                    null
                },
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

