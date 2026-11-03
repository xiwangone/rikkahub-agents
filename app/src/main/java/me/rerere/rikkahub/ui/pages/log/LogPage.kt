package me.rerere.rikkahub.ui.pages.log

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Text
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.LogRedactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogPage() {
    LogPageContent()
}

@Composable
private fun LogPageContent() {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(Logging.getRecentLogs()) }
    var requestLoggingEnabled by remember { mutableStateOf(Logging.isRequestLoggingEnabled()) }
    var appLogClearGeneration by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState { LogTab.entries.size }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val onAppTab = pagerState.currentPage == LogTab.App.ordinal

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.log_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (onAppTab) {
                        IconButton(
                            onClick = {
                                shareAppLogs(context, AppLog.exportText(""))
                            },
                        ) {
                            Icon(HugeIcons.Share03, stringResource(R.string.log_page_export))
                        }
                    }
                    IconButton(
                        onClick = {
                            if (onAppTab) {
                                AppLog.clear()
                                appLogClearGeneration++
                            } else {
                                Logging.clear()
                                logs = Logging.getRecentLogs()
                            }
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = LogTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) { page ->
                when (LogTab.entries[page]) {
                    LogTab.Request -> {
                        RequestLogList(
                            logs = logs,
                            requestLoggingEnabled = requestLoggingEnabled,
                            onRequestLoggingChange = {
                                requestLoggingEnabled = it
                                Logging.setRequestLoggingEnabled(it)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    LogTab.Text -> {
                        TextLogList(
                            logs = logs,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    LogTab.App -> {
                        AppLogContent(
                            clearGeneration = appLogClearGeneration,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** 日志分类 Tab */
enum class LogTab(
    override val icon: ImageVector,
    @StringRes val labelRes: Int,
) : PagerTab {
    Request(HugeIcons.ServerStack01, R.string.log_tab_request),
    Text(HugeIcons.Text, R.string.log_tab_text),
    App(HugeIcons.Bug01, R.string.log_tab_app),
    ;

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
private fun RequestLogList(
    logs: List<LogEntry>,
    requestLoggingEnabled: Boolean,
    onRequestLoggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedLog by remember { mutableStateOf<LogEntry.RequestLog?>(null) }
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val requestLogs = remember(logs) { logs.filterIsInstance<LogEntry.RequestLog>().sortedByDescending { it.timestamp } }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            CardGroup(
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                switchItem(
                    checked = requestLoggingEnabled,
                    onCheckedChange = onRequestLoggingChange,
                    leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                    headlineContent = { Text(stringResource(R.string.log_page_record_requests)) },
                    supportingContent = { Text(stringResource(R.string.log_page_record_requests_desc)) },
                )
            }
        }

        itemsIndexed(requestLogs, key = { _, it -> it.id }, contentType = { _, _ -> "request" }) { index, log ->
            RequestLogCard(
                log = log,
                shape = cardGroupItemShape(index, requestLogs.size),
                modifier = Modifier.padding(bottom = if (index == requestLogs.lastIndex) 0.dp else 2.dp),
                onClick = {
                    selectedLog = log
                    scope.launch { sheetState.show() }
                },
            )
        }
    }

    selectedLog?.let { log ->
        ModalBottomSheet(
            onDismissRequest = { selectedLog = null },
            sheetState = sheetState,
        ) {
            RequestLogDetail(log)
        }
    }
}

@Composable
private fun TextLogList(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier,
) {
    var keyword by remember { mutableStateOf("") }
    val textLogs =
        remember(logs, keyword) {
            val kw = keyword.trim().lowercase(Locale.getDefault())
            logs.filterIsInstance<LogEntry.TextLog>()
                .sortedByDescending { it.timestamp }
                .filter {
                    kw.isEmpty() ||
                        it.tag.lowercase(Locale.getDefault()).contains(kw) ||
                        it.message.lowercase(Locale.getDefault()).contains(kw)
                }
        }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text(stringResource(R.string.log_page_search_hint)) },
            leadingIcon = { Icon(HugeIcons.Search01, null) },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            stringResource(R.string.log_page_log_count, textLogs.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            itemsIndexed(textLogs, key = { _, it -> it.id }, contentType = { _, _ -> "text" }) { index, log ->
                TextLogCard(
                    log = log,
                    shape = cardGroupItemShape(index, textLogs.size),
                    modifier = Modifier.padding(bottom = if (index == textLogs.lastIndex) 0.dp else 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RequestLogCard(
    log: LogEntry.RequestLog,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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
                    text = log.method,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = log.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                maxLines = 2,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                log.responseCode?.let { code ->
                    Text(
                        text = stringResource(R.string.log_page_status, code),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (code in 200..299) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
                log.durationMs?.let { duration ->
                    Text(
                        text = "${duration}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            log.error?.let { error ->
                Text(
                    text = stringResource(R.string.log_page_error, error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RequestLogDetail(log: LogEntry.RequestLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.log_page_request_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item {
                DetailSection(stringResource(R.string.log_page_label_time), dateFormat.format(Date(log.timestamp)))
            }

            item {
                DetailSection(stringResource(R.string.log_page_label_url), LogRedactor.maskUrl(log.url))
            }

            item {
                DetailSection(stringResource(R.string.log_page_label_method), log.method)
            }

            log.responseCode?.let { code ->
                item {
                    DetailSection(stringResource(R.string.log_page_label_status_code), code.toString())
                }
            }

            log.durationMs?.let { duration ->
                item {
                    DetailSection(stringResource(R.string.log_page_label_duration), "${duration}ms")
                }
            }

            log.error?.let { error ->
                item {
                    DetailSection(stringResource(R.string.log_page_label_error), error)
                }
            }

            if (log.requestHeaders.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.log_page_request_headers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                log.requestHeaders.forEach { (key, value) ->
                    item {
                        HeaderItem(key, value)
                    }
                }
            }

            log.requestBody?.let { body ->
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.log_page_request_body),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    val maskedBody = remember(body) { LogRedactor.maskText(body) }
                    val jsonElement =
                        remember(maskedBody) {
                            runCatching { JsonInstantPretty.parseToJsonElement(maskedBody) }.getOrNull()
                        }
                    if (jsonElement != null) {
                        JsonTree(
                            json = jsonElement,
                            modifier = Modifier.padding(top = 4.dp),
                            initialExpandLevel = 2,
                        )
                    } else {
                        Text(
                            text = maskedBody,
                            fontFamily = JetbrainsMono,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (log.responseHeaders.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.log_page_response_headers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                log.responseHeaders.forEach { (key, value) ->
                    item {
                        HeaderItem(key, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetbrainsMono,
        )
    }
}

@Composable
private fun HeaderItem(
    key: String,
    value: String,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = LogRedactor.maskHeader(key, value),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono,
        )
    }
}

@Composable
private fun TextLogCard(
    log: LogEntry.TextLog,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = log.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = dateFormat.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                )
            }
        }
    }
}
