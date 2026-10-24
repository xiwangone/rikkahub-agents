package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.formatK
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.text.NumberFormat
import java.util.Locale

/** Details for one assistant message. Session totals deliberately stay outside this card. */
@Composable
internal fun MessageUsageDetails(
    totalUsage: TokenUsage,
    latestRequestUsage: TokenUsage?,
    multipleModelRequestCount: Int?,
    contextWindowTokens: Long?,
    durationMillis: Long?,
) {
    val latest = latestRequestUsage
    val latestCached = latest?.let { it.cachedTokens.coerceIn(0, it.promptTokens.coerceAtLeast(0)) } ?: 0
    val latestCacheRate = latest?.let { cacheHitRate(it.promptTokens, latestCached) }
    val totalCached = totalUsage.cachedTokens.coerceIn(0, totalUsage.promptTokens.coerceAtLeast(0))
    val totalCacheRate = cacheHitRate(totalUsage.promptTokens, totalCached)
    val tokensPerSecond =
        durationMillis
            ?.takeIf { it > 0L }
            ?.let { totalUsage.completionTokens * 1000f / it }

    Column(
        modifier =
            Modifier
                .widthIn(max = 360.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContextUsageBar(
            latestRequestUsage = latest,
            contextWindowTokens = contextWindowTokens,
        )

        if (multipleModelRequestCount != null) {
            if (latest != null) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.chat_message_usage_last_request),
                    style = MaterialTheme.typography.labelLarge,
                )
                UsageDetailRow(
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = latest.promptTokens.formatNumber(),
                )
                if (latestCached > 0) {
                    UsageDetailRow(
                        label = stringResource(R.string.stats_page_cached_tokens),
                        value = latestCached.formatNumber(),
                    )
                    UsageDetailRow(
                        label = stringResource(R.string.stats_page_cache_hit_rate),
                        value =
                            stringResource(
                                R.string.chat_nerd_cached_hit,
                                latestCached.formatNumber(),
                                latestCacheRate.orEmpty(),
                            ),
                    )
                }
                UsageDetailRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = latest.completionTokens.formatNumber(),
                )
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.chat_nerd_message_total),
                style = MaterialTheme.typography.labelLarge,
            )
            UsageDetailRow(
                label = stringResource(R.string.stats_page_input_tokens),
                value = totalUsage.promptTokens.formatNumber(),
            )
            if (totalCached > 0) {
                UsageDetailRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = totalCached.formatNumber(),
                )
                UsageDetailRow(
                    label = stringResource(R.string.stats_page_cache_hit_rate),
                    value =
                        stringResource(
                            R.string.chat_nerd_cached_hit,
                            totalCached.formatNumber(),
                            totalCacheRate.orEmpty(),
                        ),
                )
            }
            UsageDetailRow(
                label = stringResource(R.string.stats_page_output_tokens),
                value = totalUsage.completionTokens.formatNumber(),
            )
            Text(
                text =
                    pluralStringResource(
                        R.plurals.chat_nerd_model_request_count,
                        multipleModelRequestCount,
                        multipleModelRequestCount,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        } else {
            val singleRequestUsage = latest ?: totalUsage
            val cachedTokens =
                singleRequestUsage.cachedTokens
                    .coerceIn(0, singleRequestUsage.promptTokens.coerceAtLeast(0))
            UsageDetailRow(
                label = stringResource(R.string.stats_page_input_tokens),
                value = singleRequestUsage.promptTokens.formatNumber(),
            )
            if (cachedTokens > 0) {
                UsageDetailRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = cachedTokens.formatNumber(),
                )
                UsageDetailRow(
                    label = stringResource(R.string.stats_page_cache_hit_rate),
                    value =
                        stringResource(
                            R.string.chat_nerd_cached_hit,
                            cachedTokens.formatNumber(),
                            cacheHitRate(singleRequestUsage.promptTokens, cachedTokens).orEmpty(),
                        ),
                )
            }
            UsageDetailRow(
                label = stringResource(R.string.stats_page_output_tokens),
                value = singleRequestUsage.completionTokens.formatNumber(),
            )
        }

        tokensPerSecond?.let { speed ->
            UsageDetailRow(
                label = stringResource(R.string.accessibility_speed),
                value = "${speed.toFixed(1)} tok/s",
            )
        }
        durationMillis?.takeIf { it > 0L }?.let { duration ->
            UsageDetailRow(
                label = stringResource(R.string.accessibility_duration),
                value = "${(duration / 1000f).toFixed(1)}s",
            )
        }
    }
}

@Composable
private fun ContextUsageBar(
    latestRequestUsage: TokenUsage?,
    contextWindowTokens: Long?,
) {
    val latest = latestRequestUsage ?: return
    val promptTokens = latest.promptTokens.coerceAtLeast(0)
    val cachedTokens = latest.cachedTokens.coerceIn(0, promptTokens)
    val uncachedInputTokens = promptTokens - cachedTokens
    val outputTokens = latest.completionTokens.coerceAtLeast(0)
    val contextTokens = promptTokens.toLong() + outputTokens.toLong()
    val window = contextWindowTokens?.takeIf { it > 0L }
    val ratio = window?.let { contextTokens.toDouble() / it.toDouble() }
    val percent =
        ratio?.let {
            NumberFormat
                .getPercentInstance(Locale.getDefault())
                .apply {
                    minimumFractionDigits = 0
                    maximumFractionDigits = 0
                }.format(it)
        }
    val contextLabel =
        if (window != null && percent != null) {
            stringResource(
                R.string.chat_nerd_context_window,
                contextTokens.formatK(),
                window.formatK(),
                percent,
            )
        } else {
            stringResource(R.string.chat_nerd_context_only, contextTokens.formatK())
        }
    val capacity = maxOf(window ?: 0L, contextTokens, 1L)
    val remaining = (capacity - contextTokens).coerceAtLeast(0L)
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = contextLabel,
            style = MaterialTheme.typography.labelMedium,
        )
        if (window != null && ratio != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceContainerHighest)
                        .semantics {
                            contentDescription = contextLabel
                            progressBarRangeInfo = ProgressBarRangeInfo(ratio.toFloat().coerceIn(0f, 1f), 0f..1f)
                        },
            ) {
                if (cachedTokens > 0) {
                    Box(
                        Modifier
                            .weight(cachedTokens / capacity.toFloat())
                            .fillMaxHeight()
                            .background(colors.primary),
                    )
                }
                if (uncachedInputTokens > 0) {
                    Box(
                        Modifier
                            .weight(uncachedInputTokens / capacity.toFloat())
                            .fillMaxHeight()
                            .background(colors.primary.copy(alpha = 0.45f)),
                    )
                }
                if (outputTokens > 0) {
                    Box(
                        Modifier
                            .weight(outputTokens / capacity.toFloat())
                            .fillMaxHeight()
                            .background(colors.tertiary),
                    )
                }
                if (remaining > 0L) {
                    Spacer(Modifier.weight(remaining / capacity.toFloat()).fillMaxHeight())
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            UsageLegend(
                label = stringResource(R.string.stats_page_cached_tokens),
                value = cachedTokens.formatNumber(),
                color = colors.primary,
            )
            UsageLegend(
                label = stringResource(R.string.chat_message_usage_uncached_input),
                value = uncachedInputTokens.formatNumber(),
                color = colors.primary.copy(alpha = 0.45f),
            )
            UsageLegend(
                label = stringResource(R.string.stats_page_output_tokens),
                value = outputTokens.formatNumber(),
                color = colors.tertiary,
            )
        }
    }
}

@Composable
private fun UsageLegend(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Text(
            text = "$label $value",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun UsageDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(text = value)
    }
}

private fun cacheHitRate(
    promptTokens: Int,
    cachedTokens: Int,
): String? {
    if (promptTokens <= 0 || cachedTokens <= 0) return null
    val rate = cachedTokens.coerceAtMost(promptTokens).toDouble() / promptTokens.toDouble()
    return NumberFormat
        .getPercentInstance(Locale.getDefault())
        .apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(rate)
}
