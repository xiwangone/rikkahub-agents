package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM =
        koinViewModel(
            parameters = {
                parametersOf(id)
            },
        )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantBasicContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            tags = tags,
            workspaces = workspaces,
            onUpdate = { vm.update(it) },
            onUpdateAssistant = { vm.updateAssistant(it) },
            vm = vm,
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    onUpdate: (Assistant) -> Unit,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit = { onUpdate(it(assistant)) },
    vm: AssistantDetailVM,
) {
    val modelListState =
        rememberModelListState(
            modelId = assistant.chatModelId,
            providers = providers,
            type = ModelType.CHAT,
        )
    ModelListSheet(
        state = modelListState,
        onSelect = { onUpdateAssistant { current -> current.copy(chatModelId = it.id) } },
    )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .padding(bottom = 16.dp)
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdateAssistant { current ->
                        current.copy(avatar = avatar)
                    }
                },
                modifier =
                    Modifier
                        .size(96.dp)
                        .heroAnimation("assistant_${assistant.id}"),
            )
        }

        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_name)) },
            ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdateAssistant { current -> current.copy(
                                name = it,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_tags)) },
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_workspace)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_workspace_desc)) },
            ) {
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdateAssistant { current -> current.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace ->
                        workspace?.name ?: stringResource(R.string.workspace_no_binding)
                    },
                )
            }
            switchItem(
                checked = assistant.useAssistantAvatar,
                onCheckedChange = {
                    onUpdateAssistant { current -> current.copy(
                            useAssistantAvatar = it,
                        )
                    }
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_use_assistant_avatar)) },
            )
            switchItem(
                checked = assistant.allowPerConversationModel,
                onCheckedChange = {
                    onUpdateAssistant { current -> current.copy(
                            allowPerConversationModel = it,
                        )
                    }
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_allow_conversation_model_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_allow_conversation_model)) },
            )
        }

        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_chat_model)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_chat_model_desc)) },
            ) {
                ModelField(
                    model = modelListState.currentModel,
                    onClick = { modelListState.open() },
                )
            }
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                trailingContent = {
                    ReasoningButton(
                        reasoningLevel = assistant.reasoningLevel,
                        onUpdateReasoningLevel = { level ->
                            onUpdateAssistant { current -> current.copy(reasoningLevel = level) }
                        },
                    )
                },
            )
            switchItem(
                checked = assistant.streamOutput,
                onCheckedChange = {
                    onUpdateAssistant { current -> current.copy(
                            streamOutput = it,
                        )
                    }
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_stream_output_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_stream_output)) },
            )
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_stream_idle_timeout)) },
                supportingContent = {
                    Text(
                        if (assistant.streamIdleTimeoutMs <= 0L) {
                            stringResource(R.string.assistant_page_stream_idle_timeout_off)
                        } else {
                            stringResource(
                                R.string.assistant_page_stream_idle_timeout_desc,
                                (assistant.streamIdleTimeoutMs / 1000L).toInt(),
                            )
                        },
                    )
                },
                trailingContent = {
                    if (assistant.streamIdleTimeoutMs > 0L) {
                        CardGroupValue("${(assistant.streamIdleTimeoutMs / 1000L).toInt()}s")
                    }
                },
            ) {
                // 拖动中只改本地 draft、松手才落库：原来每帧写一次设置，写入风暴 + 回写跳动。
                var timeoutDraft by remember(assistant.streamIdleTimeoutMs) {
                    mutableFloatStateOf((assistant.streamIdleTimeoutMs / 1000L).toFloat())
                }
                Slider(
                    value = timeoutDraft,
                    onValueChange = { timeoutDraft = it },
                    onValueChangeFinished = {
                        onUpdateAssistant { current ->
                            current.copy(streamIdleTimeoutMs = timeoutDraft.roundToInt().toLong() * 1000L)
                        }
                    },
                    valueRange = 0f..300f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            switchItem(
                checked = assistant.fastPathRouterEnabled,
                onCheckedChange = {
                    onUpdateAssistant { current -> current.copy(fastPathRouterEnabled = it) }
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_fast_path_router_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_fast_path_router)) },
            )
        }

        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_temperature)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_temperature_warning)) },
                trailingContent = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdateAssistant { current -> current.copy(
                                    temperature = if (enabled) 1.0f else null,
                                )
                            }
                        },
                    )
                },
            ) {
                AnimatedVisibility(visible = assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdateAssistant { current -> current.copy(
                                        temperature = temperature,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        },
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_top_p)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_top_p_warning)) },
                trailingContent = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdateAssistant { current -> current.copy(
                                    topP = if (enabled) 1.0f else null,
                                )
                            }
                        },
                    )
                },
            ) {
                AnimatedVisibility(visible = assistant.topP != null) {
                    var topPInput by remember(assistant.id) {
                        mutableStateOf((assistant.topP ?: 1.0f).toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdateAssistant { current -> current.copy(
                                        topP = nextTopP,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        },
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_frequency_penalty)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_frequency_penalty_warning)) },
                trailingContent = {
                    Switch(
                        checked = assistant.frequencyPenalty != null,
                        onCheckedChange = { enabled ->
                            onUpdateAssistant { current -> current.copy(
                                    frequencyPenalty = if (enabled) 0.5f else null,
                                )
                            }
                        },
                    )
                },
            ) {
                AnimatedVisibility(visible = assistant.frequencyPenalty != null) {
                    var frequencyPenaltyInput by remember(assistant.id) {
                        mutableStateOf((assistant.frequencyPenalty ?: 0.5f).toString())
                    }
                    val frequencyPenaltyValue = frequencyPenaltyInput.toFloatOrNull()
                    OutlinedTextField(
                        value = frequencyPenaltyInput,
                        onValueChange = { value ->
                            frequencyPenaltyInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { next ->
                                onUpdateAssistant { current -> current.copy(
                                        frequencyPenalty = next,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = frequencyPenaltyValue == null || frequencyPenaltyValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        },
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_presence_penalty)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_presence_penalty_warning)) },
                trailingContent = {
                    Switch(
                        checked = assistant.presencePenalty != null,
                        onCheckedChange = { enabled ->
                            onUpdateAssistant { current -> current.copy(
                                    presencePenalty = if (enabled) 0.3f else null,
                                )
                            }
                        },
                    )
                },
            ) {
                AnimatedVisibility(visible = assistant.presencePenalty != null) {
                    var presencePenaltyInput by remember(assistant.id) {
                        mutableStateOf((assistant.presencePenalty ?: 0.3f).toString())
                    }
                    val presencePenaltyValue = presencePenaltyInput.toFloatOrNull()
                    OutlinedTextField(
                        value = presencePenaltyInput,
                        onValueChange = { value ->
                            presencePenaltyInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { next ->
                                onUpdateAssistant { current -> current.copy(
                                        presencePenalty = next,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = presencePenaltyValue == null || presencePenaltyValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        },
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_context_message_limit)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_context_message_limit_desc)) },
            ) {
                var contextMessageLimitInput by remember(
                    assistant.id,
                    assistant.contextMessageLimit,
                ) {
                    mutableStateOf(assistant.contextMessageLimit.toString())
                }
                var contextMessageLimitFocused by remember(assistant.id) {
                    mutableStateOf(false)
                }
                val focusManager = LocalFocusManager.current

                fun commitContextMessageLimit() {
                    val value = contextMessageLimitInput.toIntOrNull()
                    if (value == null) {
                        contextMessageLimitInput = assistant.contextMessageLimit.toString()
                        return
                    }

                    contextMessageLimitInput = value.toString()
                    if (value != assistant.contextMessageLimit) {
                        onUpdateAssistant { current -> current.copy(contextMessageLimit = value) }
                    }
                }

                OutlinedTextField(
                    value = contextMessageLimitInput,
                    onValueChange = { input ->
                        if (input.all(Char::isDigit) &&
                            (input.isEmpty() || input.toIntOrNull() != null)
                        ) {
                            contextMessageLimitInput = input
                            input.toIntOrNull()
                                ?.takeIf { it != assistant.contextMessageLimit }
                                ?.let { onUpdateAssistant { current -> current.copy(contextMessageLimit = it) } }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (contextMessageLimitFocused && !focusState.isFocused) {
                                    commitContextMessageLimit()
                                }
                                contextMessageLimitFocused = focusState.isFocused
                            },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { focusManager.clearFocus() },
                        ),
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(R.string.assistant_page_context_message_limit_hint),
                        )
                    },
                )

                if (assistant.contextMessageLimit > 0) {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_token_budget)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_token_budget_desc)) },
            ) {
                fun parseTokens(raw: String): Int? {
                    val t = raw.trim().uppercase()
                    if (t.isEmpty()) return null
                    val mult =
                        when {
                            t.endsWith("M") -> 1_000_000
                            t.endsWith("K") -> 1_000
                            else -> 1
                        }
                    val digits = if (mult == 1) t else t.dropLast(1)
                    val n = digits.toIntOrNull() ?: return null
                    return if (n <= Int.MAX_VALUE / mult) n * mult else null
                }

                fun formatTokens(v: Int): String =
                    when {
                        v >= 1_000_000 && v % 1_000_000 == 0 -> "${v / 1_000_000}M"
                        v >= 1_000 && v % 1_000 == 0 -> "${v / 1_000}K"
                        else -> v.toString()
                    }

                fun commitTokenBudget(soft: String, hard: String) {
                    val s = parseTokens(soft)
                    val h = parseTokens(hard)
                    if (s != assistant.tokenBudgetSoftCap || h != assistant.tokenBudgetHardCap) {
                        onUpdateAssistant { current -> current.copy(tokenBudgetSoftCap = s, tokenBudgetHardCap = h) }
                    }
                }
                var tokenSoftInput by remember(assistant.id, assistant.tokenBudgetSoftCap) {
                    mutableStateOf(assistant.tokenBudgetSoftCap?.let { formatTokens(it) }.orEmpty())
                }
                var tokenHardInput by remember(assistant.id, assistant.tokenBudgetHardCap) {
                    mutableStateOf(assistant.tokenBudgetHardCap?.let { formatTokens(it) }.orEmpty())
                }
                OutlinedTextField(
                    value = tokenSoftInput,
                    onValueChange = { input ->
                        if (input.matches(Regex("^[0-9]{0,9}[KkMm]?$"))) {
                            tokenSoftInput = input
                            commitTokenBudget(input, tokenHardInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_token_budget_soft)) },
                    supportingText = {
                        val hint = stringResource(R.string.assistant_page_token_budget_soft_hint)
                        val n = parseTokens(tokenSoftInput)
                        Text(if (n == null) hint else "$hint  ·  = ${formatTokens(n)} tokens")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tokenHardInput,
                    onValueChange = { input ->
                        if (input.matches(Regex("^[0-9]{0,9}[KkMm]?$"))) {
                            tokenHardInput = input
                            commitTokenBudget(tokenSoftInput, input)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_token_budget_hard)) },
                    supportingText = {
                        val hint = stringResource(R.string.assistant_page_token_budget_hard_hint)
                        val n = parseTokens(tokenHardInput)
                        Text(if (n == null) hint else "$hint  ·  = ${formatTokens(n)} tokens")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    singleLine = true,
                )
                val softValue = parseTokens(tokenSoftInput)
                val hardValue = parseTokens(tokenHardInput)
                if (softValue != null && hardValue != null && hardValue < softValue) {
                    Text(
                        text = stringResource(R.string.assistant_page_token_budget_order_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_max_tokens)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_max_tokens_desc)) },
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens =
                            if (text.isBlank()) {
                                null
                            } else {
                                text.toIntOrNull()?.takeIf { it > 0 }
                            }
                        onUpdateAssistant { current -> current.copy(
                                maxTokens = tokens,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    },
                )
            }
        }

        CardGroup {
            switchItem(
                checked = assistant.useGradientBackground,
                onCheckedChange = {
                    onUpdateAssistant { current -> current.copy(
                            useGradientBackground = it,
                        )
                    }
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_gradient_background_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_gradient_background)) },
            )

            if (!assistant.useGradientBackground) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.assistant_page_chat_background)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_chat_background_desc)) },
                ) {
                    BackgroundPicker(
                        background = assistant.background,
                        backgroundOpacity = assistant.backgroundOpacity,
                        onUpdate = { background ->
                            onUpdateAssistant { current -> current.copy(
                                    background = background,
                                )
                            }
                        },
                    )
                }
            }

            if (!assistant.useGradientBackground && assistant.background != null) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                formItem(
                    headlineContent = { Text(stringResource(R.string.assistant_page_background_opacity)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_background_opacity_desc)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(
                                R.string.assistant_page_background_opacity_value,
                                (backgroundOpacity * 100).roundToInt(),
                            ),
                        )
                    },
                ) {
                    var opacityDraft by remember(backgroundOpacity) { mutableFloatStateOf(backgroundOpacity) }
                    Slider(
                        value = opacityDraft,
                        onValueChange = {
                            opacityDraft = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                        },
                        onValueChangeFinished = {
                            onUpdateAssistant { current -> current.copy(backgroundOpacity = opacityDraft) }
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
