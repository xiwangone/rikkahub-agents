package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull


/**
 * `diff_files` 渲染器：把工具输出里的 diff 正文交给 [DiffView]（红绿高亮），
 * 不再回退到默认 JSON 平铺（方案见 `工具输出上限与diff渲染-20260918.md` P28）。
 * 内容仍由工具侧按 `settings.toolOutputMaxChars` 截断（默认 8K）。
 */
object DiffFilesToolUI : ToolUIRenderer {
    override val toolName: String = "diff_files"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val a = context.arguments.getStringContent("a").orEmpty().substringAfterLast('/')
        val b = context.arguments.getStringContent("b").orEmpty().substringAfterLast('/')
        return if (a.isNotBlank() && b.isNotBlank()) "$a ↔ $b" else stringResource(R.string.tool_ui_diff_files_title)
    }

    private fun diffOf(context: ToolUIContext): String? {
        val obj = context.content as? JsonObject ?: return null
        return obj["diff"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun isSame(context: ToolUIContext): Boolean {
        val obj = context.content as? JsonObject ?: return false
        return obj["same"]?.jsonPrimitive?.booleanOrNull == true
    }

    override fun hasSummary(context: ToolUIContext): Boolean = diffOf(context) != null || isSame(context)

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = diffOf(context)
        if (diff != null) {
            DiffView(diff = diff)
        } else if (isSame(context)) {
            Text(stringResource(R.string.tool_ui_diff_files_same))
        }
    }
}

/**
 * 工作空间后台任务：启动 / 状态 / 停止（三个工具共用终端风格展示）
 *
 * 输出结构（见 WorkspaceTools）：
 *  - `workspace_run_background` → `{id, status}`
 *  - `workspace_background_status` → `{processes:[{id, command, status, exitCode?, startedAt, stdout, stderr}]}`
 *  - `workspace_background_kill` → `{id, killed}`
 */
object WorkspaceRunBackgroundToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40

    override val toolName: String = "workspace_run_background"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Play

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command")?.replace("\n", " ")?.trim().orEmpty()
        val preview = if (command.length > TITLE_MAX_CHARS) command.take(TITLE_MAX_CHARS) + "…" else command
        return stringResource(R.string.tool_ui_bg_run, preview.ifBlank { toolName })
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content.getStringContent("id") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val taskId = context.content.getStringContent("id") ?: return
        Text(
            text = stringResource(R.string.tool_ui_bg_running) + " · " + taskId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val command = context.arguments.getStringContent("command").orEmpty()
        val taskId = context.content.getStringContent("id")
        Column(
            modifier =
                Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title(context), style = MaterialTheme.typography.headlineSmall)
            if (taskId != null) {
                Text(
                    text = stringResource(R.string.tool_ui_bg_running) + " · " + taskId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (command.isNotBlank()) {
                HighlightCodeBlock(code = command, language = "bash", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 后台任务状态：标题按有无 id 分叉，摘要逐条给出 状态 · id · 命令（最多 5 条） */
object WorkspaceBackgroundStatusToolUI : ToolUIRenderer {
    private const val MAX_TASKS = 5
    private const val MAX_LINE_CHARS = 70

    override val toolName: String = "workspace_background_status"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clock02

    @Composable
    override fun title(context: ToolUIContext): String {
        val taskId = context.arguments.getStringContent("id")
        return if (taskId.isNullOrBlank()) {
            stringResource(R.string.tool_ui_bg_tasks)
        } else {
            stringResource(R.string.tool_ui_bg_task, taskId)
        }
    }

    private fun processes(context: ToolUIContext): List<JsonObject> =
        (context.content?.jsonObjectOrNull?.get("processes") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()

    // 有 processes 字段就出摘要（哪怕空数组）——否则「没任务 / 查不到 id」时卡片只剩标题，看着像渲染坏了
    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content?.jsonObjectOrNull?.get("processes") is JsonArray

    /** 空态文案：带 id 查询（查不到）与不带 id（当前没有任务）分开说 */
    private fun emptyHintRes(context: ToolUIContext): Int =
        if (context.arguments.getStringContent("id").isNullOrBlank()) {
            R.string.tool_ui_bg_no_tasks
        } else {
            R.string.tool_ui_bg_task_not_found
        }

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val tasks = processes(context)
        Column(
            modifier =
                Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title(context), style = MaterialTheme.typography.headlineSmall)
            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(emptyHintRes(context)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tasks.forEach { task ->
                val running = task.getStringContent("status") == "running"
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(if (running) R.string.tool_ui_bg_running else R.string.tool_ui_bg_exited) +
                            " · " + task.getStringContent("id").orEmpty() +
                            task.getStringContent("exitCode")?.let { " (exit $it)" }.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.getStringContent("command")?.takeIf { it.isNotBlank() }?.let {
                        HighlightCodeBlock(code = it, language = "bash", modifier = Modifier.fillMaxWidth())
                    }
                    val out =
                        listOfNotNull(task.getStringContent("stdout"), task.getStringContent("stderr"))
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                    if (out.isNotBlank()) {
                        HighlightCodeBlock(
                            code = out.lines().takeLast(20).joinToString("\n"),
                            language = "text",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val tasks = processes(context)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(emptyHintRes(context)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tasks.take(MAX_TASKS).forEach { task ->
                val running = task.getStringContent("status") == "running"
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = stringResource(if (running) R.string.tool_ui_bg_running else R.string.tool_ui_bg_exited) +
                            " · " + task.getStringContent("id").orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val command = task.getStringContent("command")?.replace("\n", " ")?.trim().orEmpty()
                    if (command.isNotEmpty()) {
                        Text(
                            text = if (command.length > MAX_LINE_CHARS) command.take(MAX_LINE_CHARS) + "…" else command,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 停止后台任务：标题带 id，摘要给出是否真的停掉（未命中时用错误色提示） */
object WorkspaceBackgroundKillToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_background_kill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Stop

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.tool_ui_bg_kill, context.arguments.getStringContent("id").orEmpty())

    private fun killed(context: ToolUIContext): Boolean? =
        (context.content as? JsonObject)?.get("killed")?.jsonPrimitiveOrNull?.booleanOrNull

    override fun hasSummary(context: ToolUIContext): Boolean = killed(context) != null

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val ok = killed(context) == true
        Column(
            modifier =
                Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title(context), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(if (ok) R.string.tool_ui_bg_killed else R.string.tool_ui_bg_task_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            context.arguments.getStringContent("id")?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val ok = killed(context) == true
        Text(
            text = stringResource(if (ok) R.string.tool_ui_bg_killed else R.string.tool_ui_bg_task_not_found),
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
