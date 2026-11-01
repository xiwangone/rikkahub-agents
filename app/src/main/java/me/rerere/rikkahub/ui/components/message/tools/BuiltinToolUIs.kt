package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ChartScatter
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


/**
 * 获取时间信息
 */
object GetTimeInfoToolUI : ToolUIRenderer {
    override val toolName: String = "get_time_info"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(R.string.chat_message_tool_get_time)
}

/**
 * 剪贴板: 按 action 区分读/写标题
 */
object ClipboardToolUI : ToolUIRenderer {
    private const val ACTION_READ = "read"
    private const val ACTION_WRITE = "write"

    override val toolName: String = "clipboard_tool"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clipboard

    @Composable
    override fun title(context: ToolUIContext): String =
        when (context.arguments.getStringContent("action")) {
            ACTION_READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ACTION_WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
}

/**
 * 文本转语音: 摘要显示朗读文本与重播按钮
 */
object TextToSpeechToolUI : ToolUIRenderer {
    override val toolName: String = "text_to_speech"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.VolumeHigh

    @Composable
    override fun title(context: ToolUIContext): String {
        val preview =
            context.arguments.getStringContent("text")?.let { text ->
                if (text.length > 24) text.take(24) + "…" else text
            } ?: ""
        return stringResource(R.string.tool_ui_speaking, preview)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.arguments.getStringContent("text") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = context.arguments.getStringContent("text") ?: ""
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.tool_ui_replay),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 技能调用: 标题显示技能名与路径
 */
object UseSkillToolUI : ToolUIRenderer {
    override val toolName: String = "use_skill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("name") ?: ""
        val path = context.arguments.getStringContent("path")
        return if (path != null) {
            stringResource(R.string.tool_ui_skill_path_title, skillName, path)
        } else {
            stringResource(R.string.tool_ui_skill_title, skillName)
        }
    }
}

/**
 * 最近聊天: 标题固定, 摘要列出最近对话的标题
 */
object RecentChatsToolUI : ToolUIRenderer {
    override val toolName: String = "recent_chats"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(R.string.chat_message_tool_recent_chats)

    private fun chats(context: ToolUIContext): List<JsonElement> = (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = chats(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val titles = chats(context).mapNotNull { it.getStringContent("title") }
        if (titles.isEmpty()) return
        Text(
            text = titles.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 对话历史搜索: 标题带查询词, 摘要显示命中数
 */
object ConversationSearchToolUI : ToolUIRenderer {
    override val toolName: String = "conversation_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(
            R.string.chat_message_tool_conversation_search,
            context.arguments.getStringContent("query") ?: "",
        )

    private fun results(context: ToolUIContext): List<JsonElement> = (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = results(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val results = results(context)
        if (results.isEmpty()) return
        Text(
            text = stringResource(R.string.chat_message_tool_search_results_count, results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

/**
 * 屏幕使用时间: 摘要显示总时长与用时最多的应用, 详情为按时长排序的应用列表 (带占比条);
 * 无权限时回退到默认 JSON 详情
 */
object GetScreenTimeToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_APPS = 3

    override val toolName: String = "get_screen_time"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(R.string.chat_message_tool_screen_time)

    private fun apps(context: ToolUIContext): List<JsonElement> =
        context.content
            ?.jsonObjectOrNull
            ?.get("apps")
            ?.let { it as? JsonArray } ?: emptyList()

    private fun isNoPermission(context: ToolUIContext): Boolean =
        context.content.getErrorCode() == "NO_PERMISSION"

    override fun hasSummary(context: ToolUIContext): Boolean = isNoPermission(context) || apps(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (isNoPermission(context)) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        val apps = apps(context)
        if (apps.isEmpty()) return
        val totalMinutes =
            context.content
                ?.jsonObjectOrNull
                ?.get("total_minutes")
                ?.jsonPrimitiveOrNull
                ?.longOrNull ?: 0
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_screen_time_total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMinutes(totalMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            apps.take(SUMMARY_MAX_APPS).forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text =
                            app.getStringContent("app_name")
                                ?: app.getStringContent("package") ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val apps = apps(context)
        if (apps.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        ScreenTimePreview(content = context.content!!, apps = apps)
    }
}

object ChartDisplayToolUI : ToolUIRenderer {
    override val toolName: String = "chart_display"

    override fun icon(context: ToolUIContext): ImageVector =
        when (context.arguments.getStringContent("style")) {
            "bar" -> HugeIcons.ChartColumn
            "scatter" -> HugeIcons.ChartScatter
            else -> HugeIcons.ChartLineData01
        }

    @Composable
    override fun title(context: ToolUIContext): String {
        val chartTitle = context.arguments.getStringContent("title")
        return if (chartTitle.isNullOrBlank()) {
            stringResource(R.string.chat_message_tool_chart_display)
        } else {
            stringResource(R.string.chat_message_tool_chart_display_with_title, chartTitle)
        }
    }
}

@Composable
private fun ScreenTimePreview(
    content: JsonElement,
    apps: List<JsonElement>,
) {
    val totalMinutes =
        content.jsonObjectOrNull
            ?.get("total_minutes")
            ?.jsonPrimitiveOrNull
            ?.longOrNull ?: 0
    val maxAppMs = apps.maxOfOrNull { it.appMs() }?.takeIf { it > 0 } ?: 1L
    LazyColumn(
        modifier =
            Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tool_ui_screen_time_total),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(totalMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val begin = content.getStringContent("start")
                val finish = content.getStringContent("end")
                if (begin != null && finish != null) {
                    Text(
                        text = "${formatRangeTime(begin)} → ${formatRangeTime(finish)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        items(apps) { app ->
            val name =
                app.getStringContent("app_name")
                    ?: app.getStringContent("package") ?: return@items
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                LinearProgressIndicator(
                    progress = { (app.appMs().toFloat() / maxAppMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 读取单个应用条目的前台时长 (毫秒) */
private fun JsonElement.appMs(): Long = jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0

/** 读取单个应用条目的前台时长 (分钟) */
private fun JsonElement.appMinutes(): Long =
    jsonObjectOrNull?.get("total_minutes")?.jsonPrimitiveOrNull?.longOrNull ?: (appMs() / 60000)

private val SCREEN_TIME_RANGE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 将工具返回的 ISO 时间字符串格式化为 "MM-dd HH:mm", 解析失败时原样返回.
 *
 * 工具用 ZonedDateTime.toString() 输出, 区域 ID 时会带 "[Asia/Shanghai]" 后缀,
 * 故优先用 ZonedDateTime.parse, 再回退到 offset / 本地日期时间.
 */
private fun formatRangeTime(iso: String): String =
    runCatching {
        ZonedDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
    }.recoverCatching {
        OffsetDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
    }.recoverCatching {
        LocalDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
    }.getOrDefault(iso)

/** 将分钟数格式化为 "Xh Ym" / "Xh" / "Ym" */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

// --- Fork tools: per-step icon + readable title ---------------------------------
// These tools are fully wired and invocable; without a renderer their chat steps
// fell through to the generic wrench icon + raw tool name. Ported from the fork's
// pre-merge getToolIcon/title branches so the steps read the same as before.

/** JS skill execution (run_js). */
object RunJsToolUI : ToolUIRenderer {
    override val toolName: String = "run_js"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("skill_name").orEmpty()
        return if (skillName.isNotBlank()) {
            stringResource(R.string.tool_ui_js_skill_title, skillName)
        } else {
            stringResource(R.string.tool_ui_js_skill)
        }
    }
}

// System-intent tools fire a system intent the user finalises in the destination
// app. The title prefers the executed result's `summary` field (e.g. "Calendar
// event: Lunch") and falls back to an args-derived preview pre-execution.

object CreateCalendarEventToolUI : ToolUIRenderer {
    override val toolName: String = "create_calendar_event"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clock02

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val t = context.arguments.getStringContent("title").orEmpty()
        return if (t.isNotBlank()) {
            stringResource(R.string.tool_ui_calendar_event_title, t)
        } else {
            stringResource(R.string.tool_ui_create_calendar_event)
        }
    }
}

object CreateContactToolUI : ToolUIRenderer {
    override val toolName: String = "create_contact"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val first = context.arguments.getStringContent("first_name").orEmpty()
        val last = context.arguments.getStringContent("last_name").orEmpty()
        val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        return if (name.isNotBlank()) {
            stringResource(R.string.tool_ui_contact_title, name)
        } else {
            stringResource(R.string.tool_ui_create_contact)
        }
    }
}

object SendSmsIntentToolUI : ToolUIRenderer {
    override val toolName: String = "send_sms_intent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val ph = context.arguments.getStringContent("phone_number").orEmpty()
        return if (ph.isNotBlank()) {
            stringResource(R.string.tool_ui_sms_to, ph)
        } else {
            stringResource(R.string.tool_ui_compose_sms)
        }
    }
}

object SendEmailIntentToolUI : ToolUIRenderer {
    override val toolName: String = "send_email_intent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val to = context.arguments.getStringContent("to").orEmpty()
        return if (to.isNotBlank()) {
            stringResource(R.string.tool_ui_email_to, to)
        } else {
            stringResource(R.string.tool_ui_compose_email)
        }
    }
}

object OpenWifiSettingsToolUI : ToolUIRenderer {
    override val toolName: String = "open_wifi_settings"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Settings03

    @Composable
    override fun title(context: ToolUIContext): String =
        context.content.getStringContent("summary") ?: stringResource(R.string.tool_ui_wifi_settings)
}

object ShowLocationOnMapToolUI : ToolUIRenderer {
    override val toolName: String = "show_location_on_map"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Pin

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val q = context.arguments.getStringContent("query").orEmpty()
        return if (q.isNotBlank()) {
            stringResource(R.string.tool_ui_map_query, q)
        } else {
            stringResource(R.string.tool_ui_open_map)
        }
    }
}
