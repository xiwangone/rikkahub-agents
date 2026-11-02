package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.ProviderDescriptors
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        // 类型切换仅「添加提供商」时显示（新建可选类型）；
        // 编辑已有 provider 时不显示，避免在 OpenAI 配置页误切到 Backend 等丢配置
        if (showTypeSwitcher && !provider.builtIn) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ProviderSetting.Types.size,
                            ),
                        label = {
                            Text(providerTypeName(type))
                        },
                        selected = provider.providerKey == type,
                        onClick = { onEdit(provider.convertTo(type)) },
                    )
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

// 类型的显示名：与用户可自行改名的 ProviderSetting.name 分开，添加/切换类型处只出稳定的类型名。
// typeKey 是 providerKey 字符串（= @SerialName 字面量）：原先按 KClass 匹配在 R8 优化后的
// release 包里失效（when(x::class) 全落 else），故改为字符串 when；Types 给的也是同一串 key。
// 把我方现有 10 个类型逐条列出；将来新增类型未列时退回 key 本身，与改动前退回 simpleName 的行为同型。
// 订阅型（走 OAuth 登录、不需要 API key）显示成「<类型名> 订阅」。
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
        // 端侧/内置类型：保持原名，不加「订阅」后缀
        "aicore" -> "AICore"
        "local_litert" -> "LiteRtLocal"
        "local_llamacpp" -> "LlamaCppLocal"
        else -> typeKey
    }

// 每个分支是一次类型映射，线性穷举、无嵌套；拆成多组会把「哪个类型归哪组」变成隐式约定，
// 新增类型时更容易漏，故保持单层 when。
@Suppress("CyclomaticComplexMethod")
fun ProviderSetting.convertTo(typeKey: String): ProviderSetting {
    if (this.providerKey == typeKey) return this

    val apiKey =
        when (this) {
            is ProviderSetting.OpenAI -> this.apiKey

            is ProviderSetting.Google -> this.apiKey

            is ProviderSetting.Claude -> this.apiKey

            is ProviderSetting.AICore -> ""

            // on-device, no API key
            is ProviderSetting.LiteRtLocal -> ""

            // on-device, no API key
            is ProviderSetting.Codex -> ""

            // OAuth, no API key
            is ProviderSetting.Grok -> "" // OAuth, no API key

            // Basic Auth / Bearer, no API key
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

            // on-device, no base URL
            is ProviderSetting.LiteRtLocal -> ""

            // on-device, no base URL
            is ProviderSetting.Codex -> ""

            // OAuth, no base URL
            is ProviderSetting.Grok -> "" // OAuth, no base URL

            // Basic Auth / Bearer, no base URL
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

            // on-device, no base URL
            is ProviderSetting.LiteRtLocal -> return ""

            // on-device, no base URL
            is ProviderSetting.Codex -> return ""

            // OAuth, no base URL
            is ProviderSetting.Grok -> return "" // OAuth, no base URL

            // Basic Auth / Bearer, base URL is required
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

        // no base URL to reset
        is ProviderSetting.LiteRtLocal -> this

        // no base URL to reset
        is ProviderSetting.Codex -> this

        // no base URL to reset
        is ProviderSetting.Grok -> this // no base URL to reset

        // Basic Auth / Bearer, reset to default
        is ProviderSetting.Backend -> this.copy(baseUrl = defaultBaseUrl)

        // no base URL to reset
        is ProviderSetting.GeminiOAuth -> this

        // no base URL to reset
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

            // no base URL concept
            is ProviderSetting.LiteRtLocal -> return true

            // no base URL concept
            is ProviderSetting.Codex -> return true

            // no base URL concept
            is ProviderSetting.Grok -> return true // no base URL concept

            // Basic Auth / Bearer, has base URL
            is ProviderSetting.Backend -> this.baseUrl

            // no base URL concept
            is ProviderSetting.GeminiOAuth -> return true

            // no base URL concept
            is ProviderSetting.LlamaCppLocal -> return true
        }
    return baseUrl == defaultBaseUrlForReset()
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

    ProviderDescriptors.get(provider.id).description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // 统一改为"引用 / 粘贴即入库"输入：默认不在此处留存明文，
    // 引用（$$名字）由运行时解析层取真值
    SecretRefField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
        label = stringResource(R.string.setting_provider_page_api_key),
        nameHint = provider.name,
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
    )

    OutlinedTextField(
        value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
        onValueChange = {
            onEdit(
                if (provider.useResponseApi) {
                    provider.copy(responsesPath = it.trim())
                } else {
                    provider.copy(chatCompletionsPath = it.trim())
                }
            )
        },
        label = { Text(stringResource(R.string.setting_provider_page_api_path)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !provider.builtIn,
    )


    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_response_api))
        Switch(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            },
        )
    }

    // OpenRouter is the only OpenAI-compatible host where this does anything: it gates
    // the cache_control breakpoints required by Anthropic/Gemini/Qwen models routed
    // through it. Other models on OpenRouter cache automatically regardless.
    if (provider.baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(id = R.string.setting_provider_page_claude_prompt_caching),
                modifier = Modifier.weight(1f),
            )
            Checkbox(
                checked = provider.promptCaching,
                onCheckedChange = {
                    onEdit(provider.copy(promptCaching = it))
                },
            )
        }
        OpenRouterRoutingSection(
            routing = provider.routing,
            onChange = { onEdit(provider.copy(routing = it)) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_include_history_reasoning))
        Switch(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) },
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

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.openrouter_routing_section_title), style = MaterialTheme.typography.titleSmall)

    // Sort
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
                    // Equal-width segments are ~1/4 of the row, so the longest label
                    // ("Throughput") would wrap to two lines and break the pill on
                    // narrow screens. Keep it single-line and shrink to fit instead.
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

    RoutingToggle(stringResource(R.string.openrouter_routing_allow_fallbacks), routing.allowFallbacks) {
        onChange(routing.copy(allowFallbacks = it))
    }
    RoutingToggle(stringResource(R.string.openrouter_routing_require_parameters), routing.requireParameters) {
        onChange(routing.copy(requireParameters = it))
    }
    RoutingToggle(stringResource(R.string.openrouter_routing_block_data_collecting), routing.dataCollection == "deny") {
        onChange(routing.copy(dataCollection = if (it) "deny" else null))
    }
    RoutingToggle(stringResource(R.string.openrouter_routing_zdr), routing.zdr) {
        onChange(routing.copy(zdr = it))
    }

    Text(
        stringResource(R.string.openrouter_routing_max_price_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Local edit buffers so a partial decimal entry ("0.") isn't snapped away while typing:
    // the parsed Double drives routing, the raw text drives the field.
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

@Composable
private fun RoutingToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

