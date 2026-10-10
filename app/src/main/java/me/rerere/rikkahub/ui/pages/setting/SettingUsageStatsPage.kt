package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationRunTracker
import me.rerere.rikkahub.data.ai.ProviderModelStats
import me.rerere.rikkahub.data.ai.RangeUsageStats
import me.rerere.rikkahub.data.ai.UsageRange
import me.rerere.rikkahub.ui.components.charts.ChartAxis
import me.rerere.rikkahub.ui.components.charts.ChartCard
import me.rerere.rikkahub.ui.components.charts.ChartSeries
import me.rerere.rikkahub.ui.components.charts.ChartSpec
import me.rerere.rikkahub.ui.components.charts.ChartStyle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.util.Locale

private fun formatTokens(value: Long): String = "%,d".format(Locale.US, value)

private fun formatCostCompact(cost: Double): String {
    val trimmed = "%.4f".format(Locale.US, cost).trimEnd('0').trimEnd('.')
    return "$$trimmed"
}

/** 一行的展示名：优先用落盘快照，provider 删除/改名后仍可读。 */
private fun ProviderModelStats.displayTitle(key: String): String {
    val providerKey = key.substringBefore("/")
    val modelId = key.substringAfter("/", "")
    val provider = providerName?.takeIf { it.isNotBlank() } ?: providerKey
    val model = modelDisplayName?.takeIf { it.isNotBlank() } ?: modelId.takeIf { it.isNotBlank() }.orEmpty()
    return if (model.isEmpty()) provider else "$provider · $model"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUsageStatsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var range by rememberSaveable { mutableStateOf(UsageRange.ALL_TIME) }
    val revision by GenerationRunTracker.revision.collectAsState()
    val stats = remember(range, revision) { GenerationRunTracker.snapshot(context).rangeStats(range) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_usage_stats)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (stats.runs == 0L) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.usage_stats_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                UsageRangeChips(range = range, onRangeChange = { range = it })
            }
            item {
                UsageOverviewCard(stats = stats)
            }
            item {
                UsageProviderPieCard(stats = stats)
            }
            item {
                Text(
                    text = stringResource(R.string.usage_stats_detail),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val rows = stats.byProviderModel.entries.sortedByDescending { it.value.runs }
            items(rows, key = { it.key }) { (key, rowStats) ->
                UsageDetailRow(key = key, stats = rowStats)
            }
            item {
                Text(
                    text = stringResource(R.string.usage_stats_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageRangeChips(
    range: UsageRange,
    onRangeChange: (UsageRange) -> Unit,
) {
    val options =
        listOf(
            UsageRange.TODAY to R.string.usage_stats_range_today,
            UsageRange.LAST_7_DAYS to R.string.usage_stats_range_7d,
            UsageRange.LAST_30_DAYS to R.string.usage_stats_range_30d,
            UsageRange.ALL_TIME to R.string.usage_stats_range_all,
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (option, labelRes) ->
            FilterChip(
                selected = range == option,
                onClick = { onRangeChange(option) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun UsageOverviewCard(stats: RangeUsageStats) {
    val notReported = stringResource(R.string.usage_stats_cost_not_reported)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            UsageOverviewItem(
                label = stringResource(R.string.usage_stats_runs),
                value = formatTokens(stats.runs),
            )
            UsageOverviewItem(
                label = stringResource(R.string.usage_stats_tokens),
                value = formatTokens(stats.promptTokens + stats.completionTokens),
            )
            UsageOverviewItem(
                label = stringResource(R.string.usage_stats_cost),
                value = if (stats.costReportedRuns > 0) formatCostCompact(stats.cost) else notReported,
            )
        }
    }
}

@Composable
private fun UsageOverviewItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageProviderPieCard(stats: RangeUsageStats) {
    val hasCost = stats.costReportedRuns > 0
    // 按 provider 卷起（同一 provider 的多个模型合并为一片）
    val byProvider = stats.byProviderModel.entries.groupBy(
        keySelector = { it.key.substringBefore("/") },
        valueTransform = { it.value },
    )
    val names = byProvider.keys.map { key ->
        byProvider.getValue(key).firstNotNullOfOrNull { it.providerName?.takeIf { name -> name.isNotBlank() } } ?: key
    }
    val values =
        byProvider.keys.map { key ->
            val rows = byProvider.getValue(key)
            if (hasCost) rows.sumOf { it.cost } else rows.sumOf { (it.promptTokens + it.completionTokens).toDouble() }
        }
    if (values.all { it <= 0.0 }) return
    val spec =
        ChartSpec(
            style = ChartStyle.Pie,
            title =
                stringResource(
                    if (hasCost) R.string.usage_stats_by_provider_cost else R.string.usage_stats_by_provider_tokens,
                ),
            xAxis = ChartAxis(data = names),
            series = listOf(ChartSeries(values = values)),
        )
    ChartCard(spec = spec)
}

@Composable
private fun UsageDetailRow(key: String, stats: ProviderModelStats) {
    val notReported = stringResource(R.string.usage_stats_cost_not_reported)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stats.displayTitle(key),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${formatTokens(stats.runs)} · ${formatTokens(stats.promptTokens + stats.completionTokens)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (stats.costReportedRuns > 0) formatCostCompact(stats.cost) else notReported,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
