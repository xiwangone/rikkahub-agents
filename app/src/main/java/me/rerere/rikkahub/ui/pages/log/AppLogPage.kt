package me.rerere.rikkahub.ui.pages.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.LogRedactor
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 独立路由入口（Screen.AppLog）：带顶栏与返回按钮。
 */
@Composable
fun AppLogPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var clearGen by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.log_page_app_logs_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(
                            imageVector = HugeIcons.ArrowLeft01,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            shareAppLogs(context, AppLog.exportText(""))
                        },
                    ) {
                        Icon(HugeIcons.Share03, stringResource(R.string.log_page_export))
                    }
                    IconButton(
                        onClick = {
                            AppLog.clear()
                            clearGen++
                        },
                    ) {
                        Icon(HugeIcons.Delete01, stringResource(R.string.accessibility_clear_logs))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        AppLogContent(
            clearGeneration = clearGen,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
        )
    }
}

/**
 * 应用层日志核心内容（供 [LogPage] 内嵌 Tab 与独立 [AppLogPage] 共用，避免嵌套出现双顶栏）。
 */
@Composable
internal fun AppLogContent(
    clearGeneration: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppLog.isEnabled(context)) }
    var verboseFile by remember { mutableStateOf(AppLog.isVerboseFileEnabled(context)) }
    var keyword by remember { mutableStateOf("") }
    var importantOnly by remember { mutableStateOf(false) }
    var logs by remember(clearGeneration) { mutableStateOf(AppLog.getLogs()) }
    // 已点眼睛展开原文的条目：按首现时间戳标识（重复累加不改变时间戳，刷新不漂移）。
    // 只存页面 state，不持久化；清空日志时随 clearGeneration 丢弃重建。
    var revealState by remember(clearGeneration) { mutableStateOf(LogRevealState()) }

    LaunchedEffect(clearGeneration) {
        while (true) {
            logs = AppLog.getLogs()
            delay(1_000L)
        }
    }

    val keywordTrimmed = keyword.trim()
    val filteredLogs =
        remember(logs, keywordTrimmed, importantOnly) {
            val byLevel = if (importantOnly) logs.filter { it.level != 'D' } else logs
            if (keywordTrimmed.isEmpty()) {
                byLevel
            } else {
                val filter = keywordTrimmed.lowercase(Locale.getDefault())
                byLevel.filter { entry ->
                    entry.tag.lowercase(Locale.getDefault()).contains(filter) ||
                        entry.message.lowercase(Locale.getDefault()).contains(filter)
                }
            }
        }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CardGroup {
                    switchItem(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            AppLog.setEnabled(context, newValue)
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Bug01) },
                        headlineContent = { Text(stringResource(R.string.log_page_record_app_logs)) },
                        supportingContent = { Text(stringResource(R.string.log_page_record_app_logs_desc)) },
                    )
                    switchItem(
                        checked = verboseFile,
                        onCheckedChange = { newValue ->
                            verboseFile = newValue
                            AppLog.setVerboseFileEnabled(context, newValue)
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Database02) },
                        headlineContent = { Text(stringResource(R.string.log_page_verbose_file)) },
                        supportingContent = { Text(stringResource(R.string.log_page_verbose_file_desc)) },
                    )
                }

                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text(stringResource(R.string.log_page_search_hint)) },
                    leadingIcon = { Icon(HugeIcons.Search01, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = importantOnly,
                        onClick = { importantOnly = !importantOnly },
                        label = { Text(stringResource(R.string.log_page_important_only)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.log_page_log_count, filteredLogs.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (filteredLogs.isNotEmpty()) {
                                copyToClipboard(context, AppLog.exportText(keywordTrimmed))
                            }
                        },
                    ) {
                        Text(stringResource(R.string.log_page_copy_all))
                    }
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.log_page_no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            itemsIndexed(filteredLogs, key = { index, _ -> index }) { index, entry ->
                AppLogCard(
                    entry = entry,
                    shape = cardGroupItemShape(index, filteredLogs.size),
                    modifier = Modifier.padding(bottom = if (index == filteredLogs.lastIndex) 0.dp else 2.dp),
                    onClick = {
                        copyToClipboard(context, formatEntry(entry))
                    },
                    revealed = revealState.isRevealed(entry.timestamp),
                    onToggleReveal = {
                        revealState = revealState.toggle(entry.timestamp)
                    },
                )
            }
        }
    }
}

@Composable
private fun AppLogCard(
    entry: AppLog.Entry,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val levelColor =
        when (entry.level) {
            'E', 'F' -> MaterialTheme.colorScheme.error
            'W' -> Color(0xFFB8860B)
            'D', 'V' -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onClick),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                    maxLines = 1,
                )
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 屏内默认显示脱敏文本；点眼睛只切换本条的显隐，不影响复制/分享出口（仍走脱敏的 formatEntry）。
            val rawText = if (entry.repeat > 1) "${entry.message} (×${entry.repeat})" else entry.message
            // 脱敏只做一次：按（文本，显隐）缓存，滚动重组不再重复跑正则。
            val displayText = remember(rawText, revealed) { displayLogText(rawText, revealed) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onToggleReveal,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (revealed) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = stringResource(
                            if (revealed) R.string.log_page_hide_original
                            else R.string.log_page_show_original,
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun formatEntry(entry: AppLog.Entry): String {
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(entry.timestamp)
    val repeatSuffix = if (entry.repeat > 1) " (×${entry.repeat})" else ""
    // Single-entry copy leaves the app via the clipboard like export/share,
    // so it goes through the same redaction instead of raw text.
    return LogRedactor.maskText("$time ${entry.level} ${entry.tag}: ${entry.message}$repeatSuffix")
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.resources.getString(R.string.log_page_clip_label), text))
    Toast.makeText(context, R.string.log_page_copied, Toast.LENGTH_SHORT).show()
}

internal fun shareAppLogs(
    context: Context,
    text: String,
) {
    if (text.isBlank()) {
        Toast.makeText(context, R.string.log_page_export_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val fileName = "rikkahub_app_log_${System.currentTimeMillis()}.txt"
    val file = File(context.cacheDir, fileName)
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.log_page_export)),
    )
}
