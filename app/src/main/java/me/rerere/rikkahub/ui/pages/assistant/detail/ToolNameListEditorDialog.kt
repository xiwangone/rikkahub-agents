package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToasterState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.ai.tools.registry.ToolIndex

private val BASIC_READ_TOOLS = setOf("read_file", "workspace_read_file", "file_info", "list_files")

internal fun parseToolNameDraft(draft: String): List<String> =
    draft
        .split(',', '\n', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * Pure suggestion helper for one-click list population from local usage statistics.
 *
 * - For cold list (`isAllowList == false`): picks injected tools with 0 calls, excluding
 *   [ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES] and basic read tools.
 * - For allowlist (`isAllowList == true`): picks tools with >0 calls plus any basic read tools
 *   already enabled in [injected] so `/tool_outputs/` reads do not break.
 */
internal fun suggestToolNamesForList(
    injected: Set<String>,
    usageCounts: Map<String, Long>,
    isAllowList: Boolean,
): List<String> {
    val pool = injected.ifEmpty { ToolIndex.entries.map { it.toolName }.toSet() }
    return if (isAllowList) {
        val used = pool.filter { (usageCounts[it] ?: 0L) > 0L }
        val enabledReads = BASIC_READ_TOOLS.filter { it in pool }
        (used + enabledReads).distinct().sorted()
    } else {
        pool
            .filter { name ->
                name !in ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES &&
                    name !in BASIC_READ_TOOLS &&
                    (usageCounts[name] ?: 0L) == 0L
            }
            .sorted()
    }
}

internal fun findMissingEnabledReadTools(
    allowList: List<String>,
    injected: Set<String>,
): List<String> {
    if (allowList.isEmpty()) return emptyList()
    val allowed = allowList.toSet()
    return listOf("read_file", "workspace_read_file").filter { it in injected && it !in allowed }
}

@Composable
internal fun ToolListEditorContent(
    ctx: Context,
    toaster: ToasterState,
    draft: String,
    onDraftChange: (String) -> Unit,
    description: String,
    isAllowList: Boolean,
) {
    var checklistMode by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedSet = remember(draft) { parseToolNameDraft(draft).toSet() }
    val emptyStatsMsg = stringResource(R.string.assistant_page_tool_list_stats_empty)

    val groupedTools =
        remember(searchQuery) {
            val q = searchQuery.trim()
            ToolIndex.entries
                .asSequence()
                .filter { it.invocation != ToolIndex.InvocationKind.DISPLAY_ONLY }
                .filter {
                    q.isEmpty() ||
                        it.toolName.contains(q, ignoreCase = true) ||
                        it.category.contains(q, ignoreCase = true)
                }
                .groupBy { it.category }
                .toSortedMap()
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(description, style = MaterialTheme.typography.bodySmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = checklistMode,
                onClick = { checklistMode = true },
                label = { Text(stringResource(R.string.assistant_page_tool_list_mode_checklist)) },
            )
            FilterChip(
                selected = !checklistMode,
                onClick = { checklistMode = false },
                label = { Text(stringResource(R.string.assistant_page_tool_list_mode_text)) },
            )
            Text(
                text = stringResource(R.string.assistant_page_tool_list_selected_count, selectedSet.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    val snapshot = ToolUsageTracker.snapshot(ctx)
                    if (!ToolUsageTracker.isStatsEnabled() && snapshot.isEmpty()) {
                        toaster.show(emptyStatsMsg)
                    } else {
                        val counts = snapshot.associate { it.name to it.count }
                        val suggested =
                            suggestToolNamesForList(
                                injected = ToolUsageTracker.injectedNames(ctx),
                                usageCounts = counts,
                                isAllowList = isAllowList,
                            )
                        onDraftChange(suggested.joinToString("\n"))
                        toaster.show(
                            ctx.getString(R.string.assistant_page_tool_list_stats_filled, suggested.size),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.assistant_page_tool_list_fill_stats))
            }
            if (selectedSet.isNotEmpty()) {
                TextButton(onClick = { onDraftChange("") }) {
                    Text(stringResource(R.string.mcp_page_clear))
                }
            }
        }

        if (!checklistMode) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text(stringResource(R.string.assistant_page_extra_cold_hint)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp, max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                maxLines = 8,
            )
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.assistant_page_tool_list_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groupedTools.forEach { (category, entries) ->
                    val namesInGroup = entries.map { it.toolName }
                    val allChecked = namesInGroup.all { it in selectedSet }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val updated =
                                        if (allChecked) {
                                            selectedSet - namesInGroup.toSet()
                                        } else {
                                            selectedSet + namesInGroup
                                        }
                                    onDraftChange(updated.sorted().joinToString("\n"))
                                }
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = allChecked,
                            onCheckedChange = { checked ->
                                val updated =
                                    if (checked) {
                                        selectedSet + namesInGroup
                                    } else {
                                        selectedSet - namesInGroup.toSet()
                                    }
                                onDraftChange(updated.sorted().joinToString("\n"))
                            },
                        )
                        Text(
                            text = "$category (${namesInGroup.count { it in selectedSet }}/${namesInGroup.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    entries.forEach { entry ->
                        val checked = entry.toolName in selectedSet
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val updated =
                                            if (checked) selectedSet - entry.toolName else selectedSet + entry.toolName
                                        onDraftChange(updated.sorted().joinToString("\n"))
                                    }
                                    .padding(start = 16.dp, top = 1.dp, bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    val updated =
                                        if (isChecked) selectedSet + entry.toolName else selectedSet - entry.toolName
                                    onDraftChange(updated.sorted().joinToString("\n"))
                                },
                            )
                            Text(
                                text = entry.toolName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
