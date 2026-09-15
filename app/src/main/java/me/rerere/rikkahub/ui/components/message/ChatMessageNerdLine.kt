package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.rikkahub.R
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatK
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import org.koin.compose.koinInject
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    sessionTotals: TokenBudgetTracker.Totals? = null,
) {
    val appSettings = LocalSettings.current
    val settings = appSettings.displaySetting

    @Suppress("DEPRECATION") // 与项目现有 LocalClipboardManager 用法保持一致
    val clipboardManager = LocalClipboardManager.current

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = {
                            Text(text = stringResource(R.string.chat_nerd_input_tokens, usage.promptTokens.formatNumber()))
                            // Cached tokens (count + hit-%)
                            if (usage.cachedTokens > 0 && usage.promptTokens > 0) {
                                val pct = usage.cachedTokens.toDouble() / usage.promptTokens.toDouble() * 100.0
                                Text(
                                    text = stringResource(R.string.chat_nerd_cached_hit, message.usage?.cachedTokens?.formatNumber() ?: "0", String.format(java.util.Locale.US, "%.1f%%", pct)),
                                )
                            }
                        },
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = {
                            Text(text = stringResource(R.string.chat_nerd_output_tokens, usage.completionTokens.formatNumber()))
                        },
                    )
                    // Cost (USD) — shown when the provider reports it (e.g. OpenRouter usage.cost)
                    val cost = usage.cost
                    if (cost != null && cost > 0.0) {
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.CoinsDollar,
                                    contentDescription = "Cost",
                                    tint = color,
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            content = {
                                Text(text = formatCost(cost))
                            },
                        )
                    }
                    // TPS
                    if (message.finishedAt != null) {
                        val duration =
                            Duration.between(
                                message.createdAt.toJavaLocalDateTime(),
                                message.finishedAt!!.toJavaLocalDateTime(),
                            )
                        val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
                        val seconds = (duration.toMillis() / 1000f).toFixed(1)
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            content = {
                                Text(text = "${tps.toFixed(1)} tok/s")
                            },
                        )

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            },
                        )
                    }
                    // 一键复制统计信息（与截图格式一致，方便直接粘贴给 AI 分析）
                    var pendingCopyText by remember { mutableStateOf("") }
                    Box(
                        modifier =
                            Modifier
                                .clickable(onClick = {
                                    val statsText =
                                        buildString {
                                            append("↑${usage.promptTokens.formatNumber()} tokens")
                                            if (usage.cachedTokens > 0 && usage.promptTokens > 0) {
                                                val pct = usage.cachedTokens.toDouble() / usage.promptTokens.toDouble() * 100.0
                                                append(" (${usage.cachedTokens.formatNumber()} cached · ${String.format(java.util.Locale.US, "%.1f%%", pct)})")
                                            }
                                            append(" ↓${usage.completionTokens.formatNumber()} tokens")
                                            val finish = message.finishedAt
                                            if (finish != null) {
                                                val duration =
                                                    Duration.between(
                                                        message.createdAt.toJavaLocalDateTime(),
                                                        finish.toJavaLocalDateTime(),
                                                    )
                                                val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
                                                val seconds = (duration.toMillis() / 1000f).toFixed(1)
                                                append(" ⚡${tps.toFixed(1)} tok/s 🕐${seconds}s")
                                            }
                                        }
                                    pendingCopyText = statsText
                                })
                                .padding(2.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.stats_copy),
                            tint = color,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    LaunchedEffect(pendingCopyText) {
                        if (pendingCopyText.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(pendingCopyText))
                        }
                    }
                }
            }
            if (sessionTotals != null) {
                var sessionPendingCopy by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.stats_format, sessionTotals.inputTokens.toInt().formatNumber(), sessionTotals.cachedTokens.toInt().formatNumber(), sessionTotals.outputTokens.toInt().formatNumber()),
                            )
                        },
                    )
                    Box(
                        modifier =
                            Modifier
                                .clickable(onClick = {
                                    sessionPendingCopy =
                                        "↑${sessionTotals.inputTokens.toInt().formatNumber()} tokens (${sessionTotals.cachedTokens.toInt().formatNumber()} cached) ↓${sessionTotals.outputTokens.toInt().formatNumber()} tokens"
                                })
                                .padding(2.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.stats_copy_total),
                            tint = color,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    LaunchedEffect(sessionPendingCopy) {
                        if (sessionPendingCopy.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(sessionPendingCopy))
                        }
                    }
                }
            }
            // 上下文占用：已用 / 窗口 · 已用比例（进度条）。点击可填写窗口大小（选填）
            val contextUsage = message.usage
            if (settings.showTokenUsage && contextUsage != null && contextUsage.promptTokens > 0) {
                val ctxTokens = contextUsage.promptTokens.toLong()
                // 窗口取手动填写值，其次是模型上报值；两者都没有时只显示占用量。
                val configuredWindow = appSettings.contextWindowSize.takeIf { it > 0 }
                val reportedWindow =
                    appSettings.providers
                        .flatMap { it.models }
                        .firstOrNull { it.id == message.modelId }
                        ?.contextLength
                        ?.takeIf { it > 0 }
                        ?.toLong()
                val windowTokens = configuredWindow ?: reportedWindow
                // 真实占比可能大于 1（已超出窗口）；进度条封顶到 1，文字保留真实值
                val usedRatio = windowTokens?.let { ctxTokens.toDouble() / it.toDouble() }
                val barRatio = usedRatio?.coerceIn(0.0, 1.0)
                // 占用分三档，取值来自主题色槽位，随主题与深浅色自适应：
                // <50% 常规／50~75% 提醒／≥75% 警示。颜色只出现在进度条上，
                // 文字保持中性色，避免同一信息重复着色。
                val contextColor = when {
                    usedRatio == null -> color
                    usedRatio >= 0.75 -> MaterialTheme.colorScheme.error
                    usedRatio >= 0.5 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                var showWindowDialog by remember { mutableStateOf(false) }
                var windowInput by remember(configuredWindow) {
                    mutableStateOf(configuredWindow?.let { (it / 1000).toString() } ?: "")
                }
                val settingsStore = koinInject<SettingsStore>()
                val coroutineScope = rememberCoroutineScope()
                Box(
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .clickable {
                                // 每次打开都回显当前已保存的值，避免显示上一次未保存的草稿
                                windowInput = configuredWindow?.let { (it / 1000).toString() } ?: ""
                                showWindowDialog = true
                            }
                            .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.DashboardSquare01,
                                // 紧邻的文本已表达同一信息，图标不再单独朗读，避免重复
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        content = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text =
                                        if (windowTokens != null && usedRatio != null) {
                                            stringResource(
                                                R.string.chat_nerd_context_window,
                                                formatTokensAsK(ctxTokens),
                                                formatTokensAsK(windowTokens),
                                                String.format(java.util.Locale.US, "%.0f%%", usedRatio * 100.0),
                                            )
                                        } else {
                                            stringResource(R.string.chat_nerd_context_only, formatTokensAsK(ctxTokens))
                                        },
                                    // 比同组统计大一号，便于一眼看到当前上下文占用
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (usedRatio != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(72.dp)
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                // 底色用中性槽位：未填充部分清晰可见
                                                // （原先用"同色 25% 透明"，导致剩余量几乎看不出）
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth(barRatio?.toFloat() ?: 0f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(contextColor),
                                        )
                                    }
                                    // 与自动压缩阈值联动（与"窗口占用"解耦）：达到阈值即给出可行动的建议，
                                    // 而不是让用户自己换算"多少才算多"。
                                    val compactBase = appSettings.autoCompressTokenBase
                                    if (compactBase > 0 && ctxTokens >= compactBase) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.chat_nerd_suggest_compact),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                if (showWindowDialog) {
                    AlertDialog(
                        onDismissRequest = { showWindowDialog = false },
                        title = { Text(stringResource(R.string.chat_context_window_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.chat_context_window_hint))
                                OutlinedTextField(
                                    value = windowInput,
                                    onValueChange = { input -> windowInput = input.filter { it.isDigit() } },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    suffix = { Text("K") },
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val k = windowInput.toLongOrNull() ?: 0L
                                    val newBase = if (k > 0L) k * 1000L else 0L
                                    showWindowDialog = false
                                    coroutineScope.launch {
                                        settingsStore.update { current -> current.copy(contextWindowSize = newBase) }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.save))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWindowDialog = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}

// Generation cost is often a tiny fraction of a cent, so a fixed decimal count would show
// "$0.0000". Render up to 6 decimals and trim trailing zeros (e.g. "$0.0123", "$0.000045").
// A positive cost smaller than 1e-6 would round to zero at 6dp and read as "$0" (free), which
// is misleading; clamp those to a "<$0.000001" form so a real charge never displays as free.
@VisibleForTesting
internal fun formatCost(cost: Double): String {
    val rounded =
        java.math
            .BigDecimal(cost)
            .setScale(6, java.math.RoundingMode.HALF_UP)
    if (cost > 0.0 && rounded.signum() == 0) {
        return "<$0.000001"
    }
    val s = rounded.stripTrailingZeros().toPlainString()
    return "$" + s
}

/** 以 K / M 展示 token 数：不足 100 万按 K，达到 100 万进位到 M，避免 1810K 这类数值。 */
private fun formatTokensAsK(tokens: Long): String = tokens.formatK()

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
