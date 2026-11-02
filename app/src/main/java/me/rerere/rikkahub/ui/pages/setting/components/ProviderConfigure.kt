package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.OpenRouterRouting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.ProviderDescriptors
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalToaster
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    showTypeSwitcher: Boolean = false,
    onEdit: (provider: ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        if (showTypeSwitcher && !provider.builtIn) {
            CardGroup {
                formItem {
                    ConnectedChoiceButtons(
                        options = ProviderSetting.Types,
                        selected = provider.providerKey,
                        onSelect = { onEdit(provider.convertTo(it)) },
                    ) { type, selected ->
                        ConnectedChoiceLabel(text = providerTypeName(type), selected = selected)
                    }
                }
            }
        }

        when (provider) {
            is ProviderSetting.OpenAI -> {
                ProviderConfigureOpenAI(provider, onEdit)
            }

            is ProviderSetting.Google -> {
                ProviderConfigureGoogle(provider, onEdit)
            }

            is ProviderSetting.Claude -> {
                ProviderConfigureClaude(provider, onEdit)
            }

            is ProviderSetting.AICore -> {
                ProviderConfigureAICore(provider, onEdit)
            }

            is ProviderSetting.LiteRtLocal -> {
                ProviderConfigureLiteRT(provider, onEdit)
            }

            is ProviderSetting.Codex -> {
                Unit
            }

            is ProviderSetting.Grok -> {
                Unit
            }

            is ProviderSetting.Backend -> {
                BackendProviderConfigure(
                    provider = provider,
                    onEdit = onEdit,
                )
            }

            is ProviderSetting.GeminiOAuth -> {
                GeminiProviderConfigure(provider, onEdit)
            }

            is ProviderSetting.LlamaCppLocal -> {
                Unit
            }
        }
    }
}

@Composable
internal fun providerTypeName(typeKey: String): String =
    when (typeKey) {
        "openai" -> "OpenAI"
        "google" -> "Google"
        "claude" -> "Claude"
        "backend" -> stringResource(R.string.backend_service)
        "codex" -> stringResource(R.string.setting_provider_page_type_subscription, "Codex")
        "grok" -> stringResource(R.string.setting_provider_page_type_subscription, "Grok")
        "gemini_oauth" -> stringResource(R.string.setting_provider_page_type_subscription, "Gemini")
        "aicore" -> "AICore"
        "local_litert" -> "LiteRtLocal"
        "local_llamacpp" -> "LlamaCppLocal"
        else -> typeKey
    }

@Suppress("CyclomaticComplexMethod")
fun ProviderSetting.convertTo(typeKey: String): ProviderSetting {
    if (this.providerKey == typeKey) return this

    val apiKey =
        when (this) {
            is ProviderSetting.OpenAI -> this.apiKey
            is ProviderSetting.Google -> this.apiKey
            is ProviderSetting.Claude -> this.apiKey
            is ProviderSetting.AICore -> ""
            is ProviderSetting.LiteRtLocal -> ""
            is ProviderSetting.Codex -> ""
            is ProviderSetting.Grok -> ""
            is ProviderSetting.Backend -> ""
            is ProviderSetting.GeminiOAuth -> ""
            is ProviderSetting.LlamaCppLocal -> ""
        }
    val sourceBaseUrl =
        when (this) {
            is ProviderSetting.OpenAI -> this.baseUrl
            is ProviderSetting.Google -> this.baseUrl
            is ProviderSetting.Claude -> this.baseUrl
            is ProviderSetting.AICore -> ""
            is ProviderSetting.LiteRtLocal -> ""
            is ProviderSetting.Codex -> ""
            is ProviderSetting.Grok -> ""
            is ProviderSetting.Backend -> ""
            is ProviderSetting.GeminiOAuth -> ""
            is ProviderSetting.LlamaCppLocal -> ""
        }
    val targetDefaultBaseUrl =
        when (typeKey) {
            "openai" -> ProviderSetting.OpenAI().baseUrl
            "google" -> ProviderSetting.Google().baseUrl
            "claude" -> ProviderSetting.Claude().baseUrl
            "aicore" -> ""
            "local_litert" -> ""
            "backend" -> ProviderSetting.Backend().baseUrl
            else -> error("Unsupported provider type: $typeKey")
        }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (typeKey) {
        "openai" -> {
            ProviderSetting.OpenAI(
                id = this.id,
                enabled = this.enabled,
                name = this.name,
                models = this.models,
                balanceOption = this.balanceOption,
                customHeaders = this.customHeaders,
                builtIn = this.builtIn,
                apiKey = apiKey,
                baseUrl = convertedBaseUrl,
            )
        }

        "google" -> {
            ProviderSetting.Google(
                id = this.id,
                enabled = this.enabled,
                name = this.name,
                models = this.models,
                balanceOption = this.balanceOption,
                customHeaders = this.customHeaders,
                builtIn = this.builtIn,
                apiKey = apiKey,
                baseUrl = convertedBaseUrl,
            )
        }

        "claude" -> {
            ProviderSetting.Claude(
                id = this.id,
                enabled = this.enabled,
                name = this.name,
                models = this.models,
                balanceOption = this.balanceOption,
                customHeaders = this.customHeaders,
                builtIn = this.builtIn,
                apiKey = apiKey,
                baseUrl = convertedBaseUrl,
            )
        }

        "aicore" -> {
            ProviderSetting.AICore(
                id = this.id,
                enabled = this.enabled,
                name = this.name,
                models = this.models,
                balanceOption = this.balanceOption,
                builtIn = this.builtIn,
            )
        }

        "local_litert" -> {
            ProviderSetting.LiteRtLocal(
                id = this.id,
                enabled = this.enabled,
                name = this.name,
                models = this.models,
                balanceOption = this.balanceOption,
                builtIn = this.builtIn,
            )
        }

        "backend" -> {
            ProviderSetting.Backend(
                id = this.id,
                enabled = this.enabled,
                name = this.name,
                models = this.models,
                balanceOption = this.balanceOption,
                builtIn = this.builtIn,
                baseUrl = convertedBaseUrl,
            )
        }

        else -> {
            error("Unsupported provider type: $typeKey")
        }
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
            is ProviderSetting.AICore -> return ""
            is ProviderSetting.LiteRtLocal -> return ""
            is ProviderSetting.Codex -> return ""
            is ProviderSetting.Grok -> return ""
            is ProviderSetting.Backend -> return ""
            is ProviderSetting.GeminiOAuth -> return ""
            is ProviderSetting.LlamaCppLocal -> return ""
        }
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
        is ProviderSetting.AICore -> ""
        is ProviderSetting.LiteRtLocal -> ""
        is ProviderSetting.Codex -> ""
        is ProviderSetting.Grok -> ""
        is ProviderSetting.Backend -> ProviderSetting.Backend().baseUrl
        is ProviderSetting.GeminiOAuth -> ""
        is ProviderSetting.LlamaCppLocal -> ""
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.AICore -> this
        is ProviderSetting.LiteRtLocal -> this
        is ProviderSetting.Codex -> this
        is ProviderSetting.Grok -> this
        is ProviderSetting.Backend -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.GeminiOAuth -> this
        is ProviderSetting.LlamaCppLocal -> this
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl =
        when (this) {
            is ProviderSetting.OpenAI -> this.baseUrl
            is ProviderSetting.Google -> this.baseUrl
            is ProviderSetting.Claude -> this.baseUrl
            is ProviderSetting.AICore -> return true
            is ProviderSetting.LiteRtLocal -> return true
            is ProviderSetting.Codex -> return true
            is ProviderSetting.Grok -> return true
            is ProviderSetting.Backend -> this.baseUrl
            is ProviderSetting.GeminiOAuth -> return true
            is ProviderSetting.LlamaCppLocal -> return true
        }
    return baseUrl == defaultBaseUrlForReset()
}

internal fun resetBaseUrlButton(
    provider: ProviderSetting,
    onReset: () -> Unit,
): (@Composable () -> Unit)? {
    if (provider.isUsingDefaultBaseUrl()) return null
    return {
        IconButton(onClick = onReset) {
            Icon(
                imageVector = HugeIcons.Refresh03,
                contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
            )
        }
    }
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl
        .newBuilder()
        .encodedPath(convertedPath)
        .build()
        .toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced =
        when {
            source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
            source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
            source.isBlank() -> target
            else -> source + target
        }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

internal fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS =
    setOf(
        OPENAI_OFFICIAL_HOST,
        GOOGLE_OFFICIAL_HOST,
        CLAUDE_OFFICIAL_HOST,
    )

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit,
) {
    val toaster = LocalToaster.current
    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)

    ProviderDescriptors.get(provider.id).description()

    CardGroup {
        switchItem(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
        )
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_name)) },
        ) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        formItem {
            SecretRefField(
                value = provider.apiKey,
                onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
                label = stringResource(R.string.setting_provider_page_api_key),
                nameHint = provider.name,
            )
        }
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        ) {
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
                trailingIcon =
                    resetBaseUrlButton(provider) {
                        onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
                    },
            )
        }
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_api_path)) },
        ) {
            OutlinedTextField(
                value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
                onValueChange = {
                    onEdit(
                        if (provider.useResponseApi) {
                            provider.copy(responsesPath = it.trim())
                        } else {
                            provider.copy(chatCompletionsPath = it.trim())
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !provider.builtIn,
            )
        }
    }

    CardGroup {
        switchItem(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_response_api)) },
        )
        switchItem(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_include_history_reasoning)) },
        )
        if (provider.baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai") {
            switchItem(
                checked = provider.promptCaching,
                onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_caching)) },
            )
        }
    }

    if (provider.baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai") {
        OpenRouterRoutingSection(
            routing = provider.routing,
            onChange = { onEdit(provider.copy(routing = it)) },
        )
    }
}

@Composable
private fun OpenRouterRoutingSection(
    routing: OpenRouterRouting,
    onChange: (OpenRouterRouting) -> Unit,
) {
    fun listToText(list: List<String>) = list.joinToString(", ")

    fun textToList(text: String) = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    CardGroup(title = { Text(stringResource(R.string.openrouter_routing_section_title)) }) {
        formItem {
            val sortOptions = listOf(null, "price", "throughput", "latency")
            val sortLabels =
                listOf(
                    stringResource(R.string.openrouter_routing_sort_auto),
                    stringResource(R.string.openrouter_routing_sort_price),
                    stringResource(R.string.openrouter_routing_sort_throughput),
                    stringResource(R.string.openrouter_routing_sort_latency),
                )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sortOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sortOptions.size),
                        selected = routing.sort == option,
                        onClick = { onChange(routing.copy(sort = option)) },
                        label = {
                            Text(
                                sortLabels[index],
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                autoSize =
                                    TextAutoSize.StepBased(
                                        minFontSize = 9.sp,
                                        maxFontSize = 14.sp,
                                        stepSize = 1.sp,
                                    ),
                            )
                        },
                    )
                }
            }
        }
        formItem {
            OutlinedTextField(
                value = listToText(routing.order),
                onValueChange = { onChange(routing.copy(order = textToList(it))) },
                label = { Text(stringResource(R.string.openrouter_routing_provider_order_label)) },
                placeholder = { Text(stringResource(R.string.openrouter_routing_order_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = listToText(routing.only),
                onValueChange = { onChange(routing.copy(only = textToList(it))) },
                label = { Text(stringResource(R.string.openrouter_routing_only_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = listToText(routing.ignore),
                onValueChange = { onChange(routing.copy(ignore = textToList(it))) },
                label = { Text(stringResource(R.string.openrouter_routing_ignore_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        switchItem(
            checked = routing.allowFallbacks,
            onCheckedChange = { onChange(routing.copy(allowFallbacks = it)) },
            headlineContent = { Text(stringResource(R.string.openrouter_routing_allow_fallbacks)) },
        )
        switchItem(
            checked = routing.requireParameters,
            onCheckedChange = { onChange(routing.copy(requireParameters = it)) },
            headlineContent = { Text(stringResource(R.string.openrouter_routing_require_parameters)) },
        )
        switchItem(
            checked = routing.dataCollection == "deny",
            onCheckedChange = { onChange(routing.copy(dataCollection = if (it) "deny" else null)) },
            headlineContent = { Text(stringResource(R.string.openrouter_routing_block_data_collecting)) },
        )
        switchItem(
            checked = routing.zdr,
            onCheckedChange = { onChange(routing.copy(zdr = it)) },
            headlineContent = { Text(stringResource(R.string.openrouter_routing_zdr)) },
        )
        formItem(
            supportingContent = { Text(stringResource(R.string.openrouter_routing_max_price_hint)) },
        ) {
            var promptPriceText by remember { mutableStateOf(routing.maxPricePrompt?.toString() ?: "") }
            var completionPriceText by remember { mutableStateOf(routing.maxPriceCompletion?.toString() ?: "") }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = promptPriceText,
                    onValueChange = {
                        promptPriceText = it
                        onChange(routing.copy(maxPricePrompt = it.toDoubleOrNull()))
                    },
                    label = { Text(stringResource(R.string.openrouter_routing_max_prompt_label)) },
                    singleLine = true,
                    trailingIcon = {
                        if (promptPriceText.isNotEmpty()) {
                            IconButton(onClick = {
                                promptPriceText = ""
                                onChange(routing.copy(maxPricePrompt = null))
                            }) {
                                Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.openrouter_routing_clear))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = completionPriceText,
                    onValueChange = {
                        completionPriceText = it
                        onChange(routing.copy(maxPriceCompletion = it.toDoubleOrNull()))
                    },
                    label = { Text(stringResource(R.string.openrouter_routing_max_completion_label)) },
                    singleLine = true,
                    trailingIcon = {
                        if (completionPriceText.isNotEmpty()) {
                            IconButton(onClick = {
                                completionPriceText = ""
                                onChange(routing.copy(maxPriceCompletion = null))
                            }) {
                                Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.openrouter_routing_clear))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = listToText(routing.quantizations),
                onValueChange = { onChange(routing.copy(quantizations = textToList(it))) },
                label = { Text(stringResource(R.string.openrouter_routing_quantizations_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
