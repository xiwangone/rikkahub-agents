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
import me.rerere.rikkahub.ui.components.message.stripApprovalProvenance
import me.rerere.rikkahub.ui.components.message.tools.generic.BatchCopyToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.BatchDeleteToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.BatchMoveToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ClickNodeToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.CreateDirectoryToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.DeleteFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.DownloadFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.FileOpToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.FindFilesToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.GestureToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ImageToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.LaunchActivityToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.LaunchAppToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ListFilesToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.LocalReadFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.LocationToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.LongPressToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.MoveFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.OpenFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ScrollToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SearchSmsToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SendSmsToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SetTextToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ShizukuExecToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ShowImageToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SmsInboxToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SshDownloadToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SshExecSavedToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SshExecToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SshUploadToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.SwipeToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TakePhotoToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.WebFetchToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramCommandsToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramDeleteCommandsToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramDisableToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramEnableToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramGetCommandsToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramRemoveWhitelistToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramSendDocumentToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramSendPhotoToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramSendToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramSetAssistantToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramSetDefaultChatToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramSetTokenToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramStatusToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TelegramWhitelistToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TermuxRunCommandToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TermuxSessionKillToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TermuxSessionListToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TermuxSessionReadToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TermuxSessionSendToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.TermuxToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.UnzipFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.WriteBinaryFileToolUI
import me.rerere.rikkahub.ui.components.message.tools.generic.ZipFilesToolUI
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

    @Composable
    override fun title(context: ToolUIContext): String {
        val detail = defaultTitleDetail(context)
        return if (detail != null) {
            stringResource(R.string.chat_message_tool_call_generic_detail, context.tool.toolName, detail)
        } else {
            stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }

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
        // 设备 / 自动化 / 工作区类工具的返回键（按各工具返回结构核对补齐：
        // 这些工具没有专用渲染器，键名不在表里时退化成裸 JSON）
        "workspaces", "directories", "volumes", "contacts", "calls", "notifications",
        "keys", "sensors", "activities", "links", "nodes",
        "jobs", "runs", "tags", "history", "failed", "touchedFiles",
        "memories", "available_skills", "enabled_skills", "permissions", "whitelist", "commands",
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

/** 列表元素 → 多行摘要（最多 N 条，超出标注总数） */
private fun listSummary(items: List<JsonElement>): String {
    val names = items.take(GENERIC_SUMMARY_MAX_ITEMS).map { el -> elementLabel(el) }
    val more = if (items.size > GENERIC_SUMMARY_MAX_ITEMS) "\n… (${items.size})" else ""
    return names.joinToString("\n") + more
}

/** 标题参数取信息时优先看的键（取第一个非空值，截断） */
private val GENERIC_TITLE_KEYS =
    listOf(
        "path", "file_path", "filepath",
        "package_name", "packageName",
        "command", "cmd",
        "url",
        "query", "keyword", "keywords",
        "name", "title",
        "text", "message", "content",
    )

private const val GENERIC_TITLE_MAX_LEN = 48

/** 从工具入参提炼标题关键信息；返回 null = 无可提炼信息，仍走通用标题 */
private fun defaultTitleDetail(context: ToolUIContext): String? {
    val args = context.arguments.jsonObjectOrNull ?: return null
    for (key in GENERIC_TITLE_KEYS) {
        val v = args[key]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        if (!v.isNullOrBlank()) {
            return if (v.length > GENERIC_TITLE_MAX_LEN) v.take(GENERIC_TITLE_MAX_LEN) + "…" else v
        }
    }
    return null
}

/**
 * 从工具输出提炼可读摘要（返回 null = 不给摘要，仍走默认 JSON 详情）：
 *  ① 终端类（含 `stdout` / `exit_code`）→ 输出前若干行（非零退出码带一行标记）
 *  ② 列表类（`files` / `items` / `results` / `matches` / `children` 等数组）→ 逐条列名
 */
private fun defaultSummaryText(context: ToolUIContext): String? = summaryForContent(context.content)

/** 从工具输出**内容**提炼可读摘要（内联摘要与详情页共用）；返回 null = 不给摘要，仍走默认 JSON 详情 */
private fun summaryForContent(content: JsonElement?): String? {
    content ?: return null
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
        return listSummary(arr)
    }
    // ③b 兜底：对象**只有一个字段**且它是数组（未登记键名的列表型工具，如
    //     workspaces / jobs / keys / volumes）→ 同样逐条列出，不再退化成裸 JSON
    if (obj.size == 1) {
        val only = obj.values.first().let { runCatching { it.jsonArray }.getOrNull() }
        if (!only.isNullOrEmpty()) return listSummary(only)
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
            MemorySearchToolUI,
            SearchWebToolUI,
            ScrapeWebToolUI,
            GetTimeInfoToolUI,
            ClipboardToolUI,
            TextToSpeechToolUI,
            GetScreenTimeToolUI,
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
            WorkspaceRunBackgroundToolUI,
            WorkspaceBackgroundStatusToolUI,
            WorkspaceBackgroundKillToolUI,
            // generic: 图片类（3）
            ImageToolUI,
            TakePhotoToolUI,
            ShowImageToolUI,
            // generic: 文件操作类（12 + 2 SSH 复用）
            FileOpToolUI,
            MoveFileToolUI,
            DeleteFileToolUI,
            CreateDirectoryToolUI,
            BatchCopyToolUI,
            BatchMoveToolUI,
            BatchDeleteToolUI,
            ZipFilesToolUI,
            UnzipFileToolUI,
            DownloadFileToolUI,
            OpenFileToolUI,
            WriteBinaryFileToolUI,
            SshDownloadToolUI,
            SshUploadToolUI,
            // generic: Termux 会话类（6）
            TermuxToolUI,
            TermuxSessionSendToolUI,
            TermuxSessionReadToolUI,
            TermuxSessionListToolUI,
            TermuxSessionKillToolUI,
            TermuxRunCommandToolUI,
            // generic: Telegram 发送类（4）
            TelegramSendToolUI,
            TelegramSendPhotoToolUI,
            TelegramSendDocumentToolUI,
            TelegramSetDefaultChatToolUI,
            // generic: Telegram 命令管理类（4）
            TelegramCommandsToolUI,
            TelegramGetCommandsToolUI,
            TelegramDeleteCommandsToolUI,
            TelegramSetAssistantToolUI,
            // generic: Telegram 白名单类（2）
            TelegramWhitelistToolUI,
            TelegramRemoveWhitelistToolUI,
            // generic: Telegram 状态配置类（4）
            TelegramStatusToolUI,
            TelegramEnableToolUI,
            TelegramDisableToolUI,
            TelegramSetTokenToolUI,
            // generic: 手势自动化类（6）
            GestureToolUI,
            SwipeToolUI,
            LongPressToolUI,
            ScrollToolUI,
            ClickNodeToolUI,
            SetTextToolUI,
            // generic: SSH/终端执行类（3）
            SshExecToolUI,
            ShizukuExecToolUI,
            SshExecSavedToolUI,
            // generic: 位置 / 短信 / 文件列表 / 外发 / 启动 / 抓取 / 读文件（7）
            LocationToolUI,
            SmsInboxToolUI,
            SearchSmsToolUI,
            ListFilesToolUI,
            FindFilesToolUI,
            SendSmsToolUI,
            LaunchAppToolUI,
            LaunchActivityToolUI,
            WebFetchToolUI,
            LocalReadFileToolUI,
        ).associateBy { it.toolName }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer = renderers[toolName] ?: DefaultToolUIRenderer

    /** 已注册的渲染器 key（只读；供诊断做"注册了但无对应工具"的覆盖自检）。 */
    val registeredKeys: Set<String> get() = renderers.keys

    /** 渲染器映射（内部可见；供工具统一索引做定义/注册/渲染三方对齐自检）。 */
    internal val rendererMap: Map<String, ToolUIRenderer> get() = renderers
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this
        ?.jsonObjectOrNull
        ?.get(key)
        ?.jsonPrimitiveOrNull
        ?.contentOrNull

/**
 * 读取工具错误信封中的业务码。优先新形状 `data.error`，兼容旧扁平形状顶层 `error`。
 */
internal fun JsonElement?.getErrorCode(): String? =
    this?.jsonObjectOrNull?.let { obj ->
        obj["data"]?.jsonObjectOrNull?.get("error")?.jsonPrimitiveOrNull?.contentOrNull
            ?: obj["error"]?.jsonPrimitiveOrNull?.contentOrNull
    }

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
                                // 先剥审批来源标记：否则整段解析必失败 → 退化成原始文本（用户可见的“裸 JSON”）
                                val raw = stripApprovalProvenance(part.text)
                                val parsed = runCatching { JsonInstant.parseToJsonElement(raw) }.getOrNull()
                                // 面向用户的可读摘要（终端 / 列表 / 长文本 / 键值对），与原始 JSON 并存
                                summaryForContent(parsed)?.let { summary ->
                                    HighlightCodeBlock(
                                        code = summary,
                                        language = "text",
                                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                                    )
                                }
                                HighlightCodeBlock(
                                    code = parsed?.let { JsonInstantPretty.encodeToString(it) } ?: raw,
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
