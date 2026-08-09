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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用层日志页面（LogPage 的三级页面）：
 * 记录 / 展示 ChatService 等应用层 logcat 日志，支持开关、导出（txt/分享）、
 * 关键字搜索、单条/全部复制。
 */
@Composable
fun AppLogPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppLog.isEnabled(context)) }
    var keyword by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(AppLog.getLogs()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 开启记录时每 1s 刷新一次日志快照
    LaunchedEffect(Unit) {
        while (true) {
            logs = AppLog.getLogs()
            delay(1_000L)
        }
    }

    val keywordTrimmed = keyword.trim()
    val filteredLogs =
        remember(logs, keywordTrimmed) {
            if (keywordTrimmed.isEmpty()) {
                logs
            } else {
                val filter = keywordTrimmed.lowercase(Locale.getDefault())
                logs.filter { entry ->
                    entry.tag.lowercase(Locale.getDefault()).contains(filter) ||
                        entry.message.lowercase(Locale.getDefault()).contains(filter)
                }
            }
        }

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
                            shareLogs(context, AppLog.exportText(keywordTrimmed))
                        },
                    ) {
                        Icon(HugeIcons.Share03, stringResource(R.string.log_page_export))
                    }
                    IconButton(
                        onClick = {
                            AppLog.clear()
                            logs = AppLog.getLogs()
                        },
                    ) {
                        Icon(HugeIcons.Delete01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 记录开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.log_page_record_app_logs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.log_page_record_app_logs_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            AppLog.setEnabled(context, newValue)
                        },
                    )
                }
            }

            // 搜索框
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text(stringResource(R.string.log_page_search_hint)) },
                leadingIcon = { Icon(HugeIcons.Search01, null) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )

            // 数量 + 复制全部
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

            if (filteredLogs.isEmpty()) {
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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(filteredLogs, key = { index, _ -> index }) { _, entry ->
                        AppLogCard(
                            entry = entry,
                            onClick = {
                                copyToClipboard(context, formatEntry(entry))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLogCard(
    entry: AppLog.Entry,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val levelColor =
        when (entry.level) {
            'E', 'F' -> MaterialTheme.colorScheme.error
            'W' -> Color(0xFFB8860B)
            'D', 'V' -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
            )
        }
    }
}

private fun formatEntry(entry: AppLog.Entry): String {
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(entry.timestamp)
    return "$time ${entry.level} ${entry.tag}: ${entry.message}"
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("RikkaHub App Log", text))
    Toast.makeText(context, R.string.log_page_copied, Toast.LENGTH_SHORT).show()
}

private fun shareLogs(
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
