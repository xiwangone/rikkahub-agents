package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.rikkahub.R
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject


/**
 * 记忆工具: 按 action 区分标题/图标, 摘要显示记忆内容, 详情附带删除按钮
 */
object MemoryToolUI : ToolUIRenderer {
    private const val ACTION_CREATE = "create"
    private const val ACTION_EDIT = "edit"
    private const val ACTION_DELETE = "delete"
    private const val ACTION_LIST = "list"
    private const val MAX_LIST_PREVIEW = 3
    private const val MAX_LIST_CHARS = 90

    override val toolName: String = "memory_tool"

    private fun memoryAction(context: ToolUIContext): String? = context.arguments.getStringContent("action")

    override fun icon(context: ToolUIContext): ImageVector =
        when (memoryAction(context)) {
            ACTION_DELETE -> HugeIcons.Eraser
            ACTION_LIST -> HugeIcons.Book02
            else -> HugeIcons.QuillWrite01
        }

    @Composable
    override fun title(context: ToolUIContext): String =
        when (memoryAction(context)) {
            ACTION_CREATE -> stringResource(R.string.chat_message_tool_create_memory)
            ACTION_EDIT -> stringResource(R.string.chat_message_tool_edit_memory)
            ACTION_DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
            ACTION_LIST -> stringResource(R.string.tool_ui_memory_list)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }

    private fun memories(context: ToolUIContext): List<JsonObject> =
        (context.content as? JsonObject)?.get("memories")
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        when (memoryAction(context)) {
            ACTION_LIST ->
                (context.content as? JsonObject)?.get("memories")
                    ?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.isNotEmpty() == true
            ACTION_CREATE, ACTION_EDIT -> context.content.getStringContent("content") != null
            ACTION_DELETE -> context.content.getStringContent("id") != null
            else -> false
        }

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (memoryAction(context) == ACTION_LIST) {
            val list = memories(context)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.tool_ui_memory_total, list.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                list.take(MAX_LIST_PREVIEW).forEach { item ->
                    val line = item.getStringContent("content")?.replace("\n", " ")?.trim().orEmpty()
                    Text(
                        text = if (line.length > MAX_LIST_CHARS) line.take(MAX_LIST_CHARS) + "…" else line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            return
        }
        if (memoryAction(context) == ACTION_DELETE) {
            val deletedId = context.content.getStringContent("id")
            if (deletedId != null) {
                Text(
                    text = stringResource(R.string.tool_ui_memory_deleted_id, deletedId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        context.content.getStringContent("content")?.let { memoryContent ->
            Text(
                text = memoryContent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // UI boundary maps any repository failure to an actionable error state; cancellation is rethrown above.
    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        val memoryRepo: MemoryRepository = koinInject()
        val scope = rememberCoroutineScope()
        val deleteFailureMessage = stringResource(R.string.tool_ui_memory_delete_failed)
        val saveFailureMessage = stringResource(R.string.tool_ui_memory_save_failed)
        val memoryId = (context.content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull
        val memoryScopeId = (context.content as? JsonObject)?.get("scope_id")?.jsonPrimitiveOrNull?.contentOrNull
        // 就地编辑：工具卡正文是历史快照，改完用本地态显示新内容（否则看起来"没保存"）
        var editedContent by remember(memoryId) { mutableStateOf<String?>(null) }
        var isEditing by remember(memoryId, memoryScopeId) { mutableStateOf(false) }
        var editDraft by remember(memoryId, memoryScopeId) { mutableStateOf("") }
        var isSaving by remember(memoryId, memoryScopeId) { mutableStateOf(false) }
        var isDeleting by remember(memoryId, memoryScopeId) { mutableStateOf(false) }
        var operationError by remember(memoryId, memoryScopeId) { mutableStateOf<String?>(null) }
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
                Text(title(context), style = MaterialTheme.typography.headlineSmall)
                if (memoryAction(context) in listOf(ACTION_CREATE, ACTION_EDIT) && memoryId != null && memoryScopeId != null) {
                    IconButton(
                        onClick = {
                            editDraft = editedContent ?: context.content.getStringContent("content").orEmpty()
                            operationError = null
                            isEditing = true
                        },
                        enabled = !isSaving && !isDeleting,
                    ) {
                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = stringResource(R.string.tool_ui_edit_memory),
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                isDeleting = true
                                operationError = null
                                try {
                                    memoryRepo.deleteMemory(memoryScopeId, memoryId)
                                    onDismissRequest()
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    operationError = error.message?.takeIf { it.isNotBlank() } ?: deleteFailureMessage
                                } finally {
                                    isDeleting = false
                                }
                            }
                        },
                        enabled = !isSaving && !isDeleting,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.tool_ui_delete_memory),
                        )
                    }
                }
            }
            operationError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            if (memoryAction(context) == ACTION_LIST) {
                val list = memories(context)
                Text(
                    text = stringResource(R.string.tool_ui_memory_total, list.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                list.forEach { item -> MemoryPreviewRow(item) }
            } else {
                val body = editedContent ?: context.content.getStringContent("content")
                if (body.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.tool_ui_memory_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
                memoryId?.let { id ->
                    Text(
                        text = stringResource(R.string.tool_ui_memory_id, id.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (isEditing) {
            AlertDialog(
                onDismissRequest = { if (!isSaving) isEditing = false },
                title = { Text(stringResource(R.string.tool_ui_edit_memory)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = editDraft,
                            onValueChange = { editDraft = it },
                            minLines = 3,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        if (isSaving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        operationError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !isSaving,
                        onClick = {
                            val scopeId = memoryScopeId
                            val id = memoryId
                            if (scopeId != null && id != null) {
                                scope.launch {
                                    isSaving = true
                                    operationError = null
                                    try {
                                        memoryRepo.updateContentKeepingTier(scopeId, id, editDraft)
                                        editedContent = editDraft
                                        isEditing = false
                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        operationError = error.message?.takeIf { it.isNotBlank() } ?: saveFailureMessage
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !isSaving, onClick = { isEditing = false }) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                },
            )
        }
    }
}

/**
 * 记忆检索: 标题带关键词, 摘要给出命中条数与前 3 条内容（条件记忆按关键词取回）
 */
object MemorySearchToolUI : ToolUIRenderer {
    private const val MAX_HITS = 3
    private const val MAX_CHARS = 90

    override val toolName: String = "memory_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiSearch02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.tool_ui_memory_search, context.arguments.getStringContent("keyword").orEmpty())

    private fun hits(context: ToolUIContext): List<JsonObject> =
        (context.content as? JsonObject)?.get("results")
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        (context.content as? JsonObject)?.get("results")
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.isNotEmpty() == true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val list = hits(context)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.tool_ui_memory_hits, list.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            list.take(MAX_HITS).forEach { item ->
                val hit = item.getStringContent("content")?.replace("\n", " ")?.trim().orEmpty()
                Text(
                    text = if (hit.length > MAX_CHARS) hit.take(MAX_CHARS) + "…" else hit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(
        context: ToolUIContext,
        onDismissRequest: () -> Unit,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title(context), style = MaterialTheme.typography.headlineSmall)
            val list = hits(context)
            if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_memory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.tool_ui_memory_hits, list.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                list.forEach { item -> MemoryPreviewRow(item) }
            }
        }
    }
}

/** 详情页里的单条记忆：tier 标签 + 完整内容（列表与检索共用）。 */
@Composable
private fun MemoryPreviewRow(item: JsonObject) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item.getStringContent("tier")?.let { tier ->
            Text(
                text = tier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = item.getStringContent("content").orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

