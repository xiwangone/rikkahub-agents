package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** 输出文本部件解析出的 JSON, 工具未执行时为 null */
    val content: JsonElement?,
    /** 该工具调用是否在生成中 */
    val loading: Boolean,
)

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        DefaultToolPreview(context = context)
    }
}

/** 未注册工具使用的默认渲染器。
 * 在接口默认行为（通用标题 + JSON 详情）之上，按**输出特征**给一段内联摘要 ——
 * 覆盖未注册工具里最常见的两类形态（终端输出 / 列表），免得它们一律只显示一串 JSON。
 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""

    override fun hasSummary(context: ToolUIContext): Boolean = defaultSummaryText(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = defaultSummaryText(context) ?: return
        HighlightCodeBlock(
            code = text,
            language = "text",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val GENERIC_SUMMARY_MAX_LINES = 8
private const val GENERIC_SUMMARY_MAX_ITEMS = 8
private val GENERIC_LIST_KEYS =
    listOf(
        // 文件 / 通用集合
        "files", "items", "results", "matches", "children", "list",
        // 高频工具的实际键名（2026-10-05 按 usage 统计补：均 >100 次调用）
        "processes",  // workspace_background_status / _list
        "sessions",   // termux_session_list
        "hosts",      // list_ssh_hosts
        "packages",   // list_installed_apps
        "apps", "agents", "servers", "entries", "records", "top", "tools",
    )

/** 列表元素取标签时优先看的键（对象元素：如 shizuku 的 {command, result}、list_files 的 {name, path}） */
private val GENERIC_ITEM_KEYS =
    listOf("name", "path", "command", "key", "title", "label", "id", "url", "message")

/** 列表元素 → 一行标签：字符串直接用；对象取常见键，再退到首个标量字段 */
private fun elementLabel(el: JsonElement): String {
    el.jsonPrimitiveOrNull?.contentOrNull?.let { return it }
    val o = el.jsonObjectOrNull ?: return "…"
    GENERIC_ITEM_KEYS.forEach { k ->
        o[k]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return o.values.firstOrNull { it.jsonPrimitiveOrNull != null }?.jsonPrimitiveOrNull?.contentOrNull ?: "…"
}

/**
 * 从工具输出提炼可读摘要（返回 null = 不给摘要，仍走默认 JSON 详情）：
 *  ① 终端类（含 `stdout` / `exit_code`）→ 输出前若干行（非零退出码带一行标记）
 *  ② 列表类（`files` / `items` / `results` / `matches` / `children` 等数组）→ 逐条列名
 */
private fun defaultSummaryText(context: ToolUIContext): String? {
    val content = context.content ?: return null
    val stdout = content.getStringContent("stdout")
    val exit = content.getStringContent("exit_code") ?: content.getStringContent("exitCode")
    if (stdout != null || exit != null) {
        val body = (stdout ?: "").lineSequence().take(GENERIC_SUMMARY_MAX_LINES).joinToString("\n")
        if (exit != null && exit != "0") return "[exit $exit]\n$body"
        return body.ifBlank { null }
    }
    val obj = content.jsonObjectOrNull ?: return null
    GENERIC_LIST_KEYS.forEach { key ->
        val arr = obj[key]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return@forEach
        if (arr.isEmpty()) return@forEach
        val names = arr.take(GENERIC_SUMMARY_MAX_ITEMS).map { el -> elementLabel(el) }
        val more = if (arr.size > GENERIC_SUMMARY_MAX_ITEMS) "\n… (${arr.size})" else ""
        return names.joinToString("\n") + more
    }
    // ③ 长文本字段（如 read_file 的 content / web_fetch 正文）→ 展示前若干行
    val longText =
        obj.entries
            .mapNotNull { (_, v) -> v.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.length > 200 } }
            .maxByOrNull { it.length }
    if (longText != null) {
        return longText.lineSequence().take(GENERIC_SUMMARY_MAX_LINES).joinToString("\n")
    }
    // ④ 键值对：输出是「标量字段」组成的小对象 → 逐行 k: v
    //    （覆盖 memory_tool / settings_get / device_info / appops_get 这类未注册工具）
    val scalars = obj.entries.filter { it.value.jsonPrimitiveOrNull != null }.take(GENERIC_SUMMARY_MAX_ITEMS)
    if (scalars.isNotEmpty()) {
        return scalars.joinToString("\n") { (k, v) -> "$k: " + (v.jsonPrimitiveOrNull?.contentOrNull ?: "").take(120) }
    }
    return null
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> =
        listOf(
            MemoryToolUI,
            SearchWebToolUI,
            ScrapeWebToolUI,
            GetTimeInfoToolUI,
            ClipboardToolUI,
            TextToSpeechToolUI,
            GetScreenTimeToolUI,
            CalendarQueryToolUI,
            CalendarCreateToolUI,
            ChartDisplayToolUI,
            UseSkillToolUI,
            RecentChatsToolUI,
            ConversationSearchToolUI,
            EditFileToolUI,
            ReadFileToolUI,
            WriteFileToolUI,
            WriteTextFileToolUI,
            DiffFilesToolUI,
            ShellToolUI,
            RunJsToolUI,
            CreateCalendarEventToolUI,
            CreateContactToolUI,
            SendSmsIntentToolUI,
            SendEmailIntentToolUI,
            OpenWifiSettingsToolUI,
            ShowLocationOnMapToolUI,
            WorkspaceApplyEditsToolUI,
            WorkspaceSearchCodeToolUI,
        ).associateBy { it.toolName }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer = renderers[toolName] ?: DefaultToolUIRenderer
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this
        ?.jsonObjectOrNull
        ?.get(key)
        ?.jsonPrimitiveOrNull
        ?.contentOrNull

/**
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            headerActions?.invoke()
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, context.tool.toolName))
            },
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
            )
        }
        if (context.tool.output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                HighlightCodeBlock(
                                    code =
                                        runCatching {
                                            JsonInstantPretty.encodeToString(
                                                JsonInstant.parseToJsonElement(part.text),
                                            )
                                        }.getOrElse { part.text },
                                    language = "json",
                                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                )
                            }

                            is UIMessagePart.Image -> {
                                ZoomableAsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
