package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cpu
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
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import java.util.Locale

private fun formatTokens(value: Long): String = "%,d".format(Locale.US, value)

private fun formatCostCompact(cost: Double): String {
    val trimmed = "%.4f".format(Locale.US, cost).trimEnd('0').trimEnd('.')
    return "$$trimmed"
}

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
    val revision by GenerationRunTracker.revision.collectAsStateWithLifecycle()
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
                modifier =
                    Modifier
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
        val rows = remember(stats) { stats.byProviderModel.entries.sortedByDescending { it.value.runs } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    UsageRangeChips(range = range, onRangeChange = { range = it })
                    UsageOverviewCard(stats = stats)
                    UsageProviderPieCard(stats = stats)
                    Text(
                        text = stringResource(R.string.usage_stats_detail),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
            itemsIndexed(rows, key = { _, item -> item.key }) { index, (key, rowStats) ->
                UsageDetailRow(
                    key = key,
                    stats = rowStats,
                    shape = cardGroupItemShape(index, rows.size),
                    modifier = Modifier.padding(bottom = if (index == rows.lastIndex) 0.dp else 2.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.usage_stats_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier =
                Modifier
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
private fun UsageOverviewItem(
    label: String,
    value: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
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
    val byProvider =
        stats.byProviderModel.entries.groupBy(
            keySelector = { it.key.substringBefore("/") },
            valueTransform = { it.value },
        )
    val names =
        byProvider.keys.map { key ->
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
private fun UsageDetailRow(
    key: String,
    stats: ProviderModelStats,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val notReported = stringResource(R.string.usage_stats_cost_not_reported)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroupIcon(icon = HugeIcons.Cpu)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stats.displayTitle(key),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${formatTokens(stats.runs)} · ${formatTokens(stats.promptTokens + stats.completionTokens)} tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardGroupValue(
                text = if (stats.costReportedRuns > 0) formatCostCompact(stats.cost) else notReported,
            )
        }
    }
}
