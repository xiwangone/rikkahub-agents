package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationRunTracker
import me.rerere.rikkahub.data.datastore.ModelPrice
import me.rerere.rikkahub.data.datastore.ProviderPricing
import me.rerere.rikkahub.data.datastore.UsagePricingSettings
import me.rerere.rikkahub.data.datastore.usagePricingKey
import me.rerere.rikkahub.data.datastore.visibleModelKeys
import me.rerere.rikkahub.data.datastore.visibleProviderKeys
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.SettingTextField
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private const val UNKNOWN_PROVIDER_KEY = "unknown"

/** 小数输入：空白 = 清除；非法输入不写入（避免敲字中途误删已存值）；负数按 0 计。 */
private fun parseDecimalOrNull(text: String): Double? =
    if (text.isBlank()) null else text.toDoubleOrNull()?.coerceAtLeast(0.0)

private fun parseHourOrNull(text: String): Int? =
    text.toIntOrNull()?.coerceIn(0, 23)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUsagePricingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pricing = settings.usagePricing
    val context = LocalContext.current
    val revision by GenerationRunTracker.revision.collectAsStateWithLifecycle()
    val statsKeys = remember(revision) { GenerationRunTracker.snapshot(context).byProviderModel.keys }
    val providers = settings.providers
    var showAddModelDialog by remember { mutableStateOf(false) }
    // 本页新加的模型：只记页面内状态，不写脏数据；用户填了价才进 modelPrices 持久化。
    var addedModelKeys by remember { mutableStateOf(setOf<String>()) }

    // 默认只展示有记录的模型：已有自填价 + 统计里出现过 + 本页新加；不再枚举全部提供商的全部模型。
    val modelKeys =
        remember(pricing.modelPrices, statsKeys, addedModelKeys) {
            visibleModelKeys(pricing.modelPrices.keys, statsKeys, addedModelKeys)
        }
    // 提供商行同规则：已有额度/用量配置 + 统计里出现过 + 已展示模型的提供商。
    val providerKeys =
        remember(pricing.providerPricing, statsKeys, modelKeys) {
            visibleProviderKeys(pricing.providerPricing.keys, statsKeys, modelKeys)
        }

    fun updatePricing(transform: (UsagePricingSettings) -> UsagePricingSettings) {
        vm.updateSettings { current -> current.copy(usagePricing = transform(current.usagePricing)) }
    }

    fun modelTitle(key: String): String {
        val providerKey = key.substringBefore("/")
        val modelId = key.substringAfter("/", "")
        val provider = providers.firstOrNull { (it.providerKey ?: UNKNOWN_PROVIDER_KEY) == providerKey }
        val model = provider?.models?.firstOrNull { it.id.toString() == modelId }
        val providerName = provider?.name?.takeIf { it.isNotBlank() } ?: providerKey
        val modelName = model?.displayName?.takeIf { it.isNotBlank() } ?: modelId
        return "$providerName · $modelName"
    }

    fun providerTitle(key: String): String {
        val provider = providers.firstOrNull { (it.providerKey ?: UNKNOWN_PROVIDER_KEY) == key }
        return provider?.name?.takeIf { it.isNotBlank() } ?: key
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.usage_pricing_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.usage_pricing_currency_title)) },
                ) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.usage_pricing_currency_label)) },
                    ) {
                        var text by remember { mutableStateOf(pricing.currencySymbol) }
                        SettingTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                updatePricing { current -> current.copy(currencySymbol = it) }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.usage_pricing_points_name_label)) },
                    ) {
                        var text by remember { mutableStateOf(pricing.pointsName) }
                        SettingTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                updatePricing { current -> current.copy(pointsName = it) }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.usage_pricing_points_rate_label)) },
                        supportingContent = { Text(stringResource(R.string.usage_pricing_points_rate_hint)) },
                    ) {
                        var text by remember {
                            mutableStateOf(if (pricing.pointsPerCurrency > 0.0) pricing.pointsPerCurrency.toString() else "")
                        }
                        SettingTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                val rate = parseDecimalOrNull(it)?.coerceAtLeast(0.0)
                                when {
                                    // 清空输入 = 不换算积分（0），是用户意图，不是配置缺失
                                    it.isBlank() ->
                                        updatePricing { current -> current.copy(pointsPerCurrency = 0.0) }
                                    rate != null ->
                                        updatePricing { current -> current.copy(pointsPerCurrency = rate) }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.usage_pricing_peak_title)) },
                ) {
                    switchItem(
                        checked = pricing.peakEnabled,
                        onCheckedChange = { updatePricing { current -> current.copy(peakEnabled = it) } },
                        headlineContent = { Text(stringResource(R.string.usage_pricing_peak_label)) },
                        supportingContent = { Text(stringResource(R.string.usage_pricing_peak_hint)) },
                    )
                    if (pricing.peakEnabled) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.usage_pricing_peak_start_label)) },
                        ) {
                            var text by remember { mutableStateOf(pricing.peakStartHour.toString()) }
                            SettingTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    parseHourOrNull(it)?.let { hour ->
                                        updatePricing { current -> current.copy(peakStartHour = hour) }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.usage_pricing_peak_end_label)) },
                        ) {
                            var text by remember { mutableStateOf(pricing.peakEndHour.toString()) }
                            SettingTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    parseHourOrNull(it)?.let { hour ->
                                        updatePricing { current -> current.copy(peakEndHour = hour) }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.usage_pricing_models_title)) },
                ) {
                    if (modelKeys.isEmpty()) {
                        formItem {
                            Text(
                                text = stringResource(R.string.usage_pricing_empty_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        modelKeys.forEach { key ->
                            ModelPriceRow(
                                title = modelTitle(key),
                                price = pricing.modelPrices[key],
                                peakEnabled = pricing.peakEnabled,
                                onPriceChange = { next ->
                                    updatePricing { current ->
                                        val prices =
                                            if (next == null) {
                                                current.modelPrices - key
                                            } else {
                                                current.modelPrices + (key to next)
                                            }
                                        current.copy(modelPrices = prices)
                                    }
                                },
                            )
                        }
                    }
                    formItem {
                        if (modelKeys.isEmpty()) {
                            // 无记录时按钮用实底样式，更醒目。
                            Button(
                                onClick = { showAddModelDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                AddModelButtonContent()
                            }
                        } else {
                            OutlinedButton(
                                onClick = { showAddModelDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                AddModelButtonContent()
                            }
                        }
                    }
                }
            }
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.usage_pricing_providers_title)) },
                ) {
                    if (providerKeys.isEmpty()) {
                        formItem {
                            Text(
                                text = stringResource(R.string.usage_pricing_empty_providers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        providerKeys.forEach { key ->
                            ProviderPricingRow(
                                title = providerTitle(key),
                                pricing = pricing.providerPricing[key],
                                onPricingChange = { next ->
                                    updatePricing { current ->
                                        val entries =
                                            if (next == null) {
                                                current.providerPricing - key
                                            } else {
                                                current.providerPricing + (key to next)
                                            }
                                        current.copy(providerPricing = entries)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddModelDialog) {
        AddModelDialog(
            providers = providers,
            listedKeys = modelKeys.toSet(),
            onAdd = { key ->
                addedModelKeys = addedModelKeys + key
                showAddModelDialog = false
            },
            onDismiss = { showAddModelDialog = false },
        )
    }
}

@Composable
private fun CardGroupScope.ModelPriceRow(
    title: String,
    price: ModelPrice?,
    peakEnabled: Boolean,
    onPriceChange: (ModelPrice?) -> Unit,
) {
    // 三栏有一个非空即保留该模型的价格项；全空则删项（无自填价 = 不估算）。
    fun merged(base: Double?, peak: Double?, offPeak: Double?): ModelPrice? {
        if (base == null && peak == null && offPeak == null) return null
        return ModelPrice(
            pricePerMillion = base ?: 0.0,
            peakPricePerMillion = peak,
            offPeakPricePerMillion = offPeak,
        )
    }

    formItem(
        headlineContent = { Text(title) },
    ) {
        var baseText by remember { mutableStateOf(price?.pricePerMillion?.toString() ?: "") }
        SettingTextField(
            value = baseText,
            onValueChange = {
                baseText = it
                val base = parseDecimalOrNull(it)
                if (it.isBlank() || base != null) {
                    onPriceChange(merged(base, price?.peakPricePerMillion, price?.offPeakPricePerMillion))
                }
            },
            label = { Text(stringResource(R.string.usage_pricing_price_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        if (peakEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var peakText by remember { mutableStateOf(price?.peakPricePerMillion?.toString() ?: "") }
                SettingTextField(
                    value = peakText,
                    onValueChange = {
                        peakText = it
                        val peak = parseDecimalOrNull(it)
                        if (it.isBlank() || peak != null) {
                            onPriceChange(merged(price?.pricePerMillion, peak, price?.offPeakPricePerMillion))
                        }
                    },
                    label = { Text(stringResource(R.string.usage_pricing_peak_price_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                var offPeakText by remember { mutableStateOf(price?.offPeakPricePerMillion?.toString() ?: "") }
                SettingTextField(
                    value = offPeakText,
                    onValueChange = {
                        offPeakText = it
                        val offPeak = parseDecimalOrNull(it)
                        if (it.isBlank() || offPeak != null) {
                            onPriceChange(merged(price?.pricePerMillion, price?.peakPricePerMillion, offPeak))
                        }
                    },
                    label = { Text(stringResource(R.string.usage_pricing_offpeak_price_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CardGroupScope.ProviderPricingRow(
    title: String,
    pricing: ProviderPricing?,
    onPricingChange: (ProviderPricing?) -> Unit,
) {
    fun merged(quota: Double?, manualUsage: Double?): ProviderPricing? {
        if (quota == null && manualUsage == null) return null
        return ProviderPricing(quota = quota, manualUsage = manualUsage)
    }

    formItem(
        headlineContent = { Text(title) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var quotaText by remember { mutableStateOf(pricing?.quota?.toString() ?: "") }
            SettingTextField(
                value = quotaText,
                onValueChange = {
                    quotaText = it
                    val quota = parseDecimalOrNull(it)
                    if (it.isBlank() || quota != null) {
                        onPricingChange(merged(quota, pricing?.manualUsage))
                    }
                },
                label = { Text(stringResource(R.string.usage_pricing_quota_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            var usageText by remember { mutableStateOf(pricing?.manualUsage?.toString() ?: "") }
            SettingTextField(
                value = usageText,
                onValueChange = {
                    usageText = it
                    val usage = parseDecimalOrNull(it)
                    if (it.isBlank() || usage != null) {
                        onPricingChange(merged(pricing?.quota, usage))
                    }
                },
                label = { Text(stringResource(R.string.usage_pricing_manual_usage_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowScope.AddModelButtonContent() {
    Icon(HugeIcons.Add01, contentDescription = null)
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(stringResource(R.string.usage_pricing_add_model))
}

/**
 * 添加模型对话框：列出全部可用模型（按提供商分组、可搜索），点一个就加进本页列表。
 * 只记进页面内状态，不写脏数据；用户填了价才进 modelPrices 持久化。
 */
@Composable
private fun AddModelDialog(
    providers: List<ProviderSetting>,
    listedKeys: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 按提供商分组的待添加模型：已在列表里的不再列出；搜索按提供商名/显示名/模型 ID 匹配。
    val groups =
        remember(providers, listedKeys, query) {
            val keywords = query.split(" ").filter { it.isNotBlank() }
            providers.mapNotNull { provider ->
                val providerName = provider.name.takeIf { it.isNotBlank() } ?: provider.providerKey
                val models =
                    provider.models.filter { model ->
                        val key = usagePricingKey(provider.providerKey, model.id.toString())
                        if (key in listedKeys) return@filter false
                        if (keywords.isEmpty()) return@filter true
                        val haystack = "$providerName ${model.displayName} ${model.modelId}"
                        keywords.all { haystack.contains(it, ignoreCase = true) }
                    }
                if (models.isEmpty()) null else Triple(provider, providerName, models)
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usage_pricing_add_model)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.usage_pricing_add_model_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isEmpty()) {
                    Text(
                        text =
                            stringResource(
                                if (query.isBlank()) R.string.usage_pricing_add_model_all_added
                                else R.string.usage_pricing_add_model_no_match,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        groups.forEach { (provider, providerName, models) ->
                            item(key = "header-${provider.id}") {
                                Text(
                                    text = providerName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            items(models, key = { it.id }) { model ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onAdd(usagePricingKey(provider.providerKey, model.id.toString()))
                                            }
                                            .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.displayName.ifBlank { model.modelId },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (model.displayName.isNotBlank() && model.displayName != model.modelId) {
                                            Text(
                                                text = model.modelId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Icon(HugeIcons.Add01, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
