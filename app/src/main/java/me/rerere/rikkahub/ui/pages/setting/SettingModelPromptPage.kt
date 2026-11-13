package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Heading
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.ScanImage
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog

@Composable
internal fun PromptSettingsPage(
    settings: Settings,
    vm: SettingVM,
    contentPadding: PaddingValues,
) {
    var editing by remember { mutableStateOf<PromptSlot?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup {
                promptItem(
                    slot = PromptSlot.TRANSLATION,
                    settings = settings,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    onClick = { editing = it },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                            onUpdateReasoningLevel = {
                                vm.updateSettings { latest -> latest.copy(translateThinkingBudget = it.budgetTokens) }
                            },
                        )
                    },
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup {
                PromptSlot.entries.fastForEach { slot ->
                    if (slot != PromptSlot.TRANSLATION) {
                        promptItem(
                            slot = slot,
                            settings = settings,
                            iconContainerColor = containerColor,
                            iconContentColor = contentColor,
                            onClick = { editing = it },
                        )
                    }
                }
            }
        }
    }

    editing?.let { slot ->
        PromptEditSheet(
            title = stringResource(slot.title),
            description = stringResource(slot.variables),
            value = slot.get(settings),
            defaultValue = slot.default,
            onSave = { value -> vm.updateSettings { latest -> slot.set(latest, value) } },
            onDismiss = { editing = null },
        )
    }
}

private enum class PromptSlot(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val variables: Int,
    val default: String,
    val get: (Settings) -> String,
    val set: (Settings, String) -> Settings,
) {
    TRANSLATION(
        icon = HugeIcons.Translate,
        title = R.string.setting_model_page_prompt_translation,
        variables = R.string.setting_model_page_translate_prompt_vars,
        default = DEFAULT_TRANSLATION_PROMPT,
        get = { it.translatePrompt },
        set = { settings, value -> settings.copy(translatePrompt = value) },
    ),
    TITLE(
        icon = HugeIcons.Heading,
        title = R.string.setting_model_page_prompt_title,
        variables = R.string.setting_model_page_suggestion_prompt_vars,
        default = DEFAULT_TITLE_PROMPT,
        get = { it.titlePrompt },
        set = { settings, value -> settings.copy(titlePrompt = value) },
    ),
    SUGGESTION(
        icon = HugeIcons.Idea01,
        title = R.string.setting_model_page_prompt_suggestion,
        variables = R.string.setting_model_page_suggestion_prompt_vars,
        default = DEFAULT_SUGGESTION_PROMPT,
        get = { it.suggestionPrompt },
        set = { settings, value -> settings.copy(suggestionPrompt = value) },
    ),
    OCR(
        icon = HugeIcons.ScanImage,
        title = R.string.setting_model_page_prompt_ocr,
        variables = R.string.setting_model_page_ocr_prompt_vars,
        default = DEFAULT_OCR_PROMPT,
        get = { it.ocrPrompt },
        set = { settings, value -> settings.copy(ocrPrompt = value) },
    ),
    COMPRESS(
        icon = HugeIcons.Package01,
        title = R.string.setting_model_page_prompt_compress,
        variables = R.string.setting_model_page_compress_prompt_vars,
        default = DEFAULT_COMPRESS_PROMPT,
        get = { it.compressPrompt },
        set = { settings, value -> settings.copy(compressPrompt = value) },
    ),
}

private val Whitespace = Regex("\\s+")

private fun CardGroupScope.promptItem(
    slot: PromptSlot,
    settings: Settings,
    iconContainerColor: Color,
    iconContentColor: Color,
    onClick: (PromptSlot) -> Unit,
) = item(
    onClick = { onClick(slot) },
    leadingContent = {
        CardGroupIcon(slot.icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
    headlineContent = { Text(stringResource(slot.title)) },
    supportingContent = {
        val prompt = slot.get(settings)
        Text(
            text = remember(prompt) { prompt.trim().replace(Whitespace, " ") },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
    trailingContent = {
        Icon(
            HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    },
)

@Composable
private fun PromptEditSheet(
    title: String,
    description: String,
    value: String,
    defaultValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 草稿态：改完点“保存”才写库；之前 onValueChange 直连持久化，每敲一字就写库，
    // 滑走就算“保存”了。
    var draft by remember(value) { mutableStateOf(value) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val dirty = draft != value

    ModalBottomSheet(
        onDismissRequest = {
            if (dirty) showDiscardDialog = true else onDismiss()
        },
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                maxLines = 15,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showResetDialog = true },
                ) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (dirty) showDiscardDialog = true else onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            onSave(draft)
                            onDismiss()
                        },
                        enabled = dirty,
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    // 重置二次确认：重置是破坏性操作，之前一点就把手写的长提示词覆盖掉，找不回来。
    // 只改草稿（点“保存”才写库），取消弹层可撤销。
    RikkaConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.setting_model_page_reset_to_default),
        confirmText = stringResource(R.string.setting_model_page_reset_to_default),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showResetDialog = false
            draft = defaultValue
        },
        onDismiss = { showResetDialog = false },
        text = { Text(stringResource(R.string.setting_model_prompt_reset_confirm_text)) },
    )

    // 未保存离开提示：放弃修改即离开，继续编辑则留在弹层里，保存走弹层里的“保存”按钮。
    RikkaConfirmDialog(
        show = showDiscardDialog,
        title = stringResource(R.string.setting_model_prompt_unsaved_title),
        confirmText = stringResource(R.string.setting_model_prompt_discard),
        dismissText = stringResource(R.string.setting_model_prompt_continue_editing),
        onConfirm = {
            showDiscardDialog = false
            onDismiss()
        },
        onDismiss = { showDiscardDialog = false },
        text = { Text(stringResource(R.string.setting_model_prompt_unsaved_text)) },
    )
}
