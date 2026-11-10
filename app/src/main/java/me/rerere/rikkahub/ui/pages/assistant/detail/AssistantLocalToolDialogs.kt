package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToasterState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.TOOL_OUTPUT_DIGEST_DEFAULT_KEYWORDS
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.writeClipboardText

private const val TAG = "AssistantLocalToolPage"


@Composable
internal fun AssistantLocalToolDialogs(
    ctx: android.content.Context,
    toaster: ToasterState,
    sourceAssistants: List<Assistant>,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onImportToolConfig: (sourceId: String, includeMcp: Boolean, includeSkills: Boolean, includeWebSearch: Boolean) -> Unit,
    termuxCommand: String,
    termuxCopiedText: String,
    showTermuxPostGrantDialog: Boolean,
    onDismissTermuxPostGrantDialog: () -> Unit,
    showExtraColdDialog: Boolean,
    onDismissExtraColdDialog: () -> Unit,
    extraColdDraft: String,
    onExtraColdDraftChange: (String) -> Unit,
    showCompactOutputDialog: Boolean,
    onDismissCompactOutputDialog: () -> Unit,
    compactOutputDraft: String,
    onCompactOutputDraftChange: (String) -> Unit,
    showDigestDialog: Boolean,
    onDismissDigestDialog: () -> Unit,
    digestToolsDraft: String,
    onDigestToolsDraftChange: (String) -> Unit,
    digestKeywordsDraft: String,
    onDigestKeywordsDraftChange: (String) -> Unit,
    showOnlyToolsDialog: Boolean,
    onDismissOnlyToolsDialog: () -> Unit,
    onlyToolsDraft: String,
    onOnlyToolsDraftChange: (String) -> Unit,
    pendingOnlyTools: List<String>?,
    onPendingOnlyToolsChange: (List<String>?) -> Unit,
    showTelegramNoTokenDialog: Boolean,
    onDismissTelegramNoTokenDialog: () -> Unit,
    showWorkflowsHintDialog: Boolean,
    onDismissWorkflowsHintDialog: () -> Unit,
    showKeyboardSetupDialog: Boolean,
    onDismissKeyboardSetupDialog: () -> Unit,
    showImportSourcePicker: Boolean,
    onDismissImportSourcePicker: () -> Unit,
    importCandidate: Assistant?,
    onImportCandidateChange: (Assistant?) -> Unit,
    importIncludeMcp: Boolean,
    onImportIncludeMcpChange: (Boolean) -> Unit,
    importIncludeSkills: Boolean,
    onImportIncludeSkillsChange: (Boolean) -> Unit,
    importIncludeWebSearch: Boolean,
    onImportIncludeWebSearchChange: (Boolean) -> Unit,
) {
    if (showTermuxPostGrantDialog) {
        AlertDialog(
            onDismissRequest = { onDismissTermuxPostGrantDialog() },
            title = { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_message))
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = termuxCommand,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.writeClipboardText(termuxCommand)
                    toaster.show(termuxCopiedText)
                    onDismissTermuxPostGrantDialog()
                }) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissTermuxPostGrantDialog() }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showExtraColdDialog) {
        AlertDialog(
            onDismissRequest = { onDismissExtraColdDialog() },
            title = { Text(stringResource(R.string.assistant_page_extra_cold_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.assistant_page_extra_cold_dialog_desc))
                    OutlinedTextField(
                        value = extraColdDraft,
                        onValueChange = onExtraColdDraftChange,
                        placeholder = { Text(stringResource(R.string.assistant_page_extra_cold_hint)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .verticalScroll(rememberScrollState()),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val names =
                            extraColdDraft
                                .split(',', '\n', ' ', '\t')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        onUpdateAssistant { it.copy(extraColdTools = names) }
                        onDismissExtraColdDialog()
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissExtraColdDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCompactOutputDialog) {
        AlertDialog(
            onDismissRequest = { onDismissCompactOutputDialog() },
            title = { Text(stringResource(R.string.assistant_page_compact_output_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.assistant_page_compact_output_dialog_desc))
                    OutlinedTextField(
                        value = compactOutputDraft,
                        onValueChange = onCompactOutputDraftChange,
                        placeholder = { Text(stringResource(R.string.assistant_page_extra_cold_hint)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .verticalScroll(rememberScrollState()),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val names =
                            compactOutputDraft
                                .split(',', '\n', ' ', '\t')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        onUpdateAssistant { it.copy(toolOutputCompactTools = names) }
                        onDismissCompactOutputDialog()
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissCompactOutputDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDigestDialog) {
        AlertDialog(
            onDismissRequest = { onDismissDigestDialog() },
            title = { Text(stringResource(R.string.assistant_page_digest_output_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.assistant_page_digest_output_dialog_desc))
                    OutlinedTextField(
                        value = digestToolsDraft,
                        onValueChange = onDigestToolsDraftChange,
                        placeholder = { Text(stringResource(R.string.assistant_page_digest_output_hint)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp)
                                .verticalScroll(rememberScrollState()),
                        maxLines = 4,
                    )
                    OutlinedTextField(
                        value = digestKeywordsDraft,
                        onValueChange = onDigestKeywordsDraftChange,
                        label = { Text(stringResource(R.string.assistant_page_digest_output_keywords_label)) },
                        placeholder = { Text(stringResource(R.string.assistant_page_digest_output_keywords_hint)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp)
                                .verticalScroll(rememberScrollState()),
                        maxLines = 4,
                    )
                    // 一键填入内置默认集（error / FAILED / Exception / ✗ / 失败 / 异常）—— 可改可存
                    TextButton(
                        onClick = {
                            onDigestKeywordsDraftChange(TOOL_OUTPUT_DIGEST_DEFAULT_KEYWORDS.joinToString("\n"))
                        },
                    ) {
                        Text(stringResource(R.string.assistant_page_digest_output_keywords_fill))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val names =
                            digestToolsDraft
                                .split(',', '\n', ' ', '\t')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        // 关键词用换行/逗号分隔（不用空格：关键词里可能带空格）
                        val keywords =
                            digestKeywordsDraft
                                .split(',', '\n')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        onUpdateAssistant {
                            it.copy(
                                toolOutputDigestTools = names,
                                toolOutputDigestKeywords = keywords,
                            )
                        }
                        onDismissDigestDialog()
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissDigestDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showOnlyToolsDialog) {
        AlertDialog(
            onDismissRequest = { onDismissOnlyToolsDialog() },
            title = { Text(stringResource(R.string.assistant_page_only_tools_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.assistant_page_only_tools_dialog_desc))
                    OutlinedTextField(
                        value = onlyToolsDraft,
                        onValueChange = onOnlyToolsDraftChange,
                        placeholder = { Text(stringResource(R.string.assistant_page_extra_cold_hint)) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .verticalScroll(rememberScrollState()),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val names =
                            onlyToolsDraft
                                .split(',', '\n', ' ', '\t')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        onDismissOnlyToolsDialog()
                        if (names.isEmpty()) {
                            onUpdateAssistant { it.copy(onlyTools = emptyList()) }
                        } else {
                            // 白名单会让助手丢掉名单外的能力，保存前再确认一次。
                            onPendingOnlyToolsChange(names)
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissOnlyToolsDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingOnlyTools?.let { names ->
        AlertDialog(
            onDismissRequest = { onPendingOnlyToolsChange(null) },
            title = { Text(stringResource(R.string.assistant_page_only_tools_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.assistant_page_only_tools_desc, names.size))
                    Text(stringResource(R.string.assistant_page_only_tools_dialog_desc))
                    Text(names.joinToString("\n"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateAssistant { it.copy(onlyTools = names) }
                        onPendingOnlyToolsChange(null)
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onPendingOnlyToolsChange(null) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showTelegramNoTokenDialog) {
        AlertDialog(
            onDismissRequest = { onDismissTelegramNoTokenDialog() },
            title = { Text(stringResource(R.string.assistant_page_local_tools_telegram_notoken_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_telegram_notoken_message)) },
            confirmButton = {
                TextButton(onClick = { onDismissTelegramNoTokenDialog() }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showWorkflowsHintDialog) {
        AlertDialog(
            onDismissRequest = { onDismissWorkflowsHintDialog() },
            title = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_message)) },
            confirmButton = {
                TextButton(onClick = { onDismissWorkflowsHintDialog() }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showKeyboardSetupDialog) {
        AlertDialog(
            onDismissRequest = { onDismissKeyboardSetupDialog() },
            title = { Text(stringResource(R.string.assistant_page_local_tools_keyboard_setup_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_keyboard_setup_message)) },
            confirmButton = {
                TextButton(onClick = { onDismissKeyboardSetupDialog() }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showImportSourcePicker) {
        AlertDialog(
            onDismissRequest = { onDismissImportSourcePicker() },
            title = { Text(stringResource(R.string.assistant_page_local_tools_import_select_source)) },
            text = {
                if (sourceAssistants.isEmpty()) {
                    Text(stringResource(R.string.assistant_page_local_tools_import_no_source))
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        sourceAssistants.forEach { source ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onImportCandidateChange(source)
                                            onDismissImportSourcePicker()
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        source.name.ifBlank {
                                            stringResource(R.string.assistant_page_local_tools_import_unnamed)
                                        },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onDismissImportSourcePicker() }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    importCandidate?.let { candidate ->
        val importDoneText = stringResource(R.string.assistant_page_local_tools_import_done)
        AlertDialog(
            onDismissRequest = { onImportCandidateChange(null) },
            title = {
                Text(
                    stringResource(
                        R.string.assistant_page_local_tools_import_confirm_title,
                        candidate.name.ifBlank { stringResource(R.string.assistant_page_local_tools_import_unnamed) },
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_page_local_tools_import_scope))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = null, enabled = false)
                        Text(stringResource(R.string.assistant_page_local_tools_import_local_tools))
                    }
                    Row(
                        modifier = Modifier.clickable { onImportIncludeMcpChange(!importIncludeMcp) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = importIncludeMcp, onCheckedChange = onImportIncludeMcpChange)
                        Text(stringResource(R.string.assistant_page_local_tools_import_mcp))
                    }
                    Row(
                        modifier = Modifier.clickable { onImportIncludeSkillsChange(!importIncludeSkills) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = importIncludeSkills, onCheckedChange = onImportIncludeSkillsChange)
                        Text(stringResource(R.string.assistant_page_local_tools_import_skills))
                    }
                    Row(
                        modifier = Modifier.clickable { onImportIncludeWebSearchChange(!importIncludeWebSearch) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = importIncludeWebSearch, onCheckedChange = onImportIncludeWebSearchChange)
                        Text(stringResource(R.string.assistant_page_local_tools_import_web_search))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportToolConfig(
                            candidate.id.toString(),
                            importIncludeMcp,
                            importIncludeSkills,
                            importIncludeWebSearch,
                        )
                        onImportCandidateChange(null)
                        toaster.show(importDoneText)
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_local_tools_import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { onImportCandidateChange(null) }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

}
