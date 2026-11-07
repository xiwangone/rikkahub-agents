package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.rikkahub.R
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ai.estimateAssistantToolSurface
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatK
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import org.koin.compose.koinInject
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                val usagePresentation = remember(message.usage, message.lastUsage, message.stepCount) {
                    message.messageUsagePresentation()
                }
                val usage = usagePresentation.messageTotal
                if (settings.showTokenUsage && usage != null) {
                    val latestUsageForDetails =
                        usagePresentation.latestRequest
                            ?: usage.takeIf { usagePresentation.multipleModelRequestCount == null }
                    val configuredContextWindow = appSettings.contextWindowSize.takeIf { it > 0L }
                    val modelContextWindow =
                        appSettings.providers
                            .flatMap { it.models }
                            .firstOrNull { it.id == message.modelId }
                            ?.contextLength
                            ?.takeIf { it > 0 }
                            ?.toLong()
                    val contextWindowTokens = configuredContextWindow ?: modelContextWindow
                    val detailsDurationMillis =
                        message.finishedAt?.let { finishedAt ->
                            (message.generationTimeMs
                                ?: Duration.between(
                                    message.createdAt.toJavaLocalDateTime(),
                                    finishedAt.toJavaLocalDateTime(),
                                ).toMillis())
                                .takeIf { it > 0L }
                        }
                    val tooltipState = rememberTooltipState(isPersistent = true)
                    val tooltipScope = rememberCoroutineScope()
                    val tooltipVisible = tooltipState.isVisible
                    val tooltipActionLabel =
                        stringResource(
                            if (tooltipVisible) R.string.chat_message_usage_close else R.string.chat_message_usage_open,
                        )
                    val tooltipStateDescription =
                        stringResource(
                            if (tooltipVisible) R.string.chat_message_usage_expanded else R.string.chat_message_usage_collapsed,
                        )
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val layoutDirection = LocalLayoutDirection.current
                    val windowInfo = LocalWindowInfo.current
                    val safeInsets = WindowInsets.safeDrawing
                    val leftInset = safeInsets.getLeft(density, layoutDirection)
                    val topInset = safeInsets.getTop(density)
                    val rightInset = safeInsets.getRight(density, layoutDirection)
                    val bottomInset = safeInsets.getBottom(density)
                    val screenWidth =
                        windowInfo.containerSize.width.takeIf { it > 0 }
                            ?: with(density) { configuration.screenWidthDp.dp.roundToPx() }
                    val screenHeight =
                        windowInfo.containerSize.height.takeIf { it > 0 }
                            ?: with(density) { configuration.screenHeightDp.dp.roundToPx() }
                    val edgeMarginPx = with(density) { 8.dp.roundToPx() }
                    val safeLeft = leftInset + edgeMarginPx
                    val safeTop = topInset + edgeMarginPx
                    val safeRight = (screenWidth - rightInset - edgeMarginPx).coerceAtLeast(safeLeft)
                    val safeBottom = (screenHeight - bottomInset - edgeMarginPx).coerceAtLeast(safeTop)
                    val maxTooltipWidth = with(density) { (safeRight - safeLeft).toDp() }
                    val maxTooltipHeight = with(density) { (safeBottom - safeTop).toDp() }
                    val maxDetailsHeight = (maxTooltipHeight - 88.dp).coerceAtLeast(0.dp)
                    val defaultPositionProvider =
                        TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above)
                    val safePositionProvider =
                        remember(defaultPositionProvider, safeLeft, safeTop, safeRight, safeBottom) {
                            object : PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): IntOffset {
                                    val preferred =
                                        defaultPositionProvider.calculatePosition(
                                            anchorBounds,
                                            windowSize,
                                            layoutDirection,
                                            popupContentSize,
                                        )
                                    val maxX = (safeRight - popupContentSize.width).coerceAtLeast(safeLeft)
                                    val maxY = (safeBottom - popupContentSize.height).coerceAtLeast(safeTop)
                                    return IntOffset(
                                        x = preferred.x.coerceIn(safeLeft, maxX),
                                        y = preferred.y.coerceIn(safeTop, maxY),
                                    )
                                }
                            }
                        }
                    TooltipBox(
                        positionProvider = safePositionProvider,
                        tooltip = {
                            RichTooltip(
                                modifier =
                                    Modifier
                                        .widthIn(max = maxTooltipWidth)
                                        .heightIn(max = maxTooltipHeight),
                                title = { Text(stringResource(R.string.chat_message_usage_title)) },
                            ) {
                                val assistant = appSettings.getCurrentAssistant()
                                val toolSurfaceEstimate =
                                    remember(
                                        assistant.localTools,
                                        assistant.workspaceId,
                                        assistant.enableMemory,
                                        assistant.mcpServers,
                                        settings.toolSurfaceTrimming,
                                    ) {
                                        estimateAssistantToolSurface(
                                            assistant = assistant,
                                            trimmingEnabled = settings.toolSurfaceTrimming,
                                        )
                                    }
                                MessageUsageDetails(
                                    totalUsage = usage,
                                    latestRequestUsage = latestUsageForDetails,
                                    multipleModelRequestCount = usagePresentation.multipleModelRequestCount,
                                    contextWindowTokens = contextWindowTokens,
                                    durationMillis = detailsDurationMillis,
                                    maxWidth = maxTooltipWidth,
                                    maxHeight = maxDetailsHeight,
                                    sessionTotals = sessionTotals,
                                    toolSurfaceEstimate = toolSurfaceEstimate,
                                )
                            }
                        },
                        state = tooltipState,
                        enableUserInput = false,
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = tooltipActionLabel,
                                    ) {
                                        tooltipScope.launch {
                                            if (tooltipState.isVisible) {
                                                tooltipState.dismiss()
                                            } else {
                                                tooltipState.show()
                                            }
                                        }
                                    }
                                    .semantics {
                                        stateDescription = tooltipStateDescription
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                    // 单行用量摘要：图标内联在文字里，行高由文字决定
                    val summaryText = buildAnnotatedString {
                        appendInlineContent("u_up", "↑")
                        append(usage.promptTokens.formatNumber())
                        if (usage.cachedTokens > 0 && usage.promptTokens > 0) {
                            val pct = usage.cachedTokens.toDouble() / usage.promptTokens.toDouble() * 100.0
                            append(" (")
                            append(usage.cachedTokens.formatNumber())
                            append(" · ")
                            append(String.format(java.util.Locale.US, "%.1f%%", pct))
                            append(")")
                        }
                        append(" ")
                        appendInlineContent("u_down", "↓")
                        append(usage.completionTokens.formatNumber())
                        if (message.finishedAt != null) {
                            val durationMillis = message.generationTimeMs ?: Duration.between(
                                message.createdAt.toJavaLocalDateTime(),
                                message.finishedAt!!.toJavaLocalDateTime(),
                            ).toMillis()
                            if (durationMillis > 0) {
                                val tps = usage.completionTokens.toFloat() / durationMillis * 1000
                                val seconds = (durationMillis / 1000f).toFixed(1)
                                append(" ")
                                appendInlineContent("u_zap", "Z")
                                append("${tps.toFixed(1)} tok/s ")
                                appendInlineContent("u_clock", "T")
                                append("${seconds}s")
                            }
                        }
                    }
                    val iconEdge = 13.sp
                    val inlineIcons = mapOf(
                        "u_up" to InlineTextContent(Placeholder(iconEdge, iconEdge, PlaceholderVerticalAlign.Center)) {
                            Icon(HugeIcons.Upload02, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        "u_down" to InlineTextContent(Placeholder(iconEdge, iconEdge, PlaceholderVerticalAlign.Center)) {
                            Icon(HugeIcons.Download04, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        "u_zap" to InlineTextContent(Placeholder(iconEdge, iconEdge, PlaceholderVerticalAlign.Center)) {
                            Icon(HugeIcons.Zap, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        "u_clock" to InlineTextContent(Placeholder(iconEdge, iconEdge, PlaceholderVerticalAlign.Center)) {
                            Icon(HugeIcons.Clock02, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                    Text(
                        text = summaryText,
                        inlineContent = inlineIcons,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                        }
                    }
                    // 一键复制统计信息（与截图格式一致，方便直接粘贴给 AI 分析）
                    var pendingCopyText by remember { mutableStateOf("") }
                    Box(
                        modifier =
                            Modifier
                                .clickable(role = Role.Button, onClick = {
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
                                                val durationMillis = message.generationTimeMs ?: Duration.between(
                                                    message.createdAt.toJavaLocalDateTime(),
                                                    finish.toJavaLocalDateTime(),
                                                ).toMillis()
                                                if (durationMillis > 0) {
                                                    val tps = usage.completionTokens.toFloat() / durationMillis * 1000
                                                    val seconds = (durationMillis / 1000f).toFixed(1)
                                                    append(" ⚡${tps.toFixed(1)} tok/s 🕐${seconds}s")
                                                }
                                            }
                                        }
                                    pendingCopyText = statsText
                                })
                                .padding(horizontal = 2.dp),
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

            // 上下文占用：已用 / 窗口 · 已用比例（进度条）。点击可填写窗口大小（选填）
            // Context usage is a per-request prompt size, not cumulative usage across tool turns.
            val contextUsage = message.lastRequestUsage()
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
                // <50% 常规／50~80% 提醒／≥80% 警示。颜色只出现在进度条上，
                // 文字保持中性色，避免同一信息重复着色。
                val contextColor = when {
                    usedRatio == null -> color
                    usedRatio >= 0.8 -> MaterialTheme.colorScheme.error
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
                                    // <50% 用半强对比中性色，50%~80% 及 ≥80% 激活告警色
                                    color =
                                        if (usedRatio != null && usedRatio >= 0.5) {
                                            contextColor
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                        },
                                )
                                if (usedRatio != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(96.dp)
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                // 底色用中性槽位：未填充部分清晰可见
                                                // （原先用"同色 25% 透明"，导致剩余量几乎看不出）
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        // 三段构成：缓存 / 未命中输入 / 输出（与图例同色）
                                        val cachedSeg = contextUsage.cachedTokens.toLong().coerceAtLeast(0L)
                                        val uncachedSeg = (ctxTokens - cachedSeg).coerceAtLeast(0L)
                                        val outputSeg = contextUsage.completionTokens.toLong().coerceAtLeast(0L)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth(barRatio?.toFloat() ?: 0f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(4.dp)),
                                        ) {
                                            if (cachedSeg > 0L) {
                                                Box(
                                                    Modifier
                                                        .weight(cachedSeg.toFloat())
                                                        .fillMaxHeight()
                                                        .background(MaterialTheme.colorScheme.primary),
                                                )
                                            }
                                            if (uncachedSeg > 0L) {
                                                Box(
                                                    Modifier
                                                        .weight(uncachedSeg.toFloat())
                                                        .fillMaxHeight()
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                                )
                                            }
                                            if (outputSeg > 0L) {
                                                Box(
                                                    Modifier
                                                        .weight(outputSeg.toFloat())
                                                        .fillMaxHeight()
                                                        .background(MaterialTheme.colorScheme.tertiary),
                                                )
                                            }
                                            if (cachedSeg + uncachedSeg + outputSeg == 0L) {
                                                Box(Modifier.fillMaxSize().background(contextColor))
                                            }
                                        }
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
