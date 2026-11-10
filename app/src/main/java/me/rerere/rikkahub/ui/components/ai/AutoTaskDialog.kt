package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem

/**
 * 自动任务设置弹窗（Material 3 Expressive 分组卡片样式）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoTaskDialog(
    config: AutoTaskConfig,
    onDismiss: () -> Unit,
    onConfirm: (AutoTaskConfig) -> Unit,
    onStop: (() -> Unit)? = null,
    hasActiveTask: Boolean = false,
) {
    val defaultAutoTaskMsg = stringResource(R.string.auto_task_default_message)
    val context = LocalContext.current
    val errNoTrigger = stringResource(R.string.auto_task_err_no_trigger)
    val errNoExec = stringResource(R.string.auto_task_err_no_exec)
    val errNoDailyTimes = stringResource(R.string.auto_task_err_no_daily_times)
    val errNoTasks = stringResource(R.string.auto_task_err_no_tasks)
    var currentMessage by remember { mutableStateOf(config.message) }
    var currentModeIdle by remember { mutableStateOf(config.modeIdle) }
    var currentModeDaily by remember { mutableStateOf(config.modeDaily) }
    var currentDailyTimes by remember { mutableStateOf(config.dailyTimes.toMutableList()) }
    var currentTaskListEnabled by remember { mutableStateOf(config.useTaskList) }
    var currentUseFixed by remember { mutableStateOf(config.useFixedMessage) }
    var currentCount by remember {
        mutableStateOf(config.triggerCount.coerceIn(0, MAX_AUTO_TASK_TRIGGER_COUNT).toString())
    }
    var currentIdleMinutes by remember {
        mutableStateOf((config.intervalSeconds.coerceIn(MIN_AUTO_TASK_IDLE_SECONDS, MAX_AUTO_TASK_IDLE_SECONDS) / 60).toString())
    }
    var currentTasks by remember { mutableStateOf(config.tasks.joinToString("\n")) }
    var currentTaskFileName by remember { mutableStateOf(config.taskFileName) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val savedLists = remember { listSavedTaskLists(context) }
    var showTimePicker by remember { mutableStateOf(false) }

    val defaultAutoTaskList = stringArrayResource(R.array.auto_task_default_list).toList()
    val aiPresetTaskList = stringArrayResource(R.array.auto_task_ai_preset_list).toList()

    fun setTaskListEnabled(enabled: Boolean) {
        currentTaskListEnabled = enabled
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        icon = {
            CardGroupIcon(
                icon = HugeIcons.Zap,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        title = { Text(stringResource(R.string.auto_task_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.auto_task_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ===== 触发模式 =====
                CardGroup(
                    title = { Text(stringResource(R.string.auto_task_mode_label)) },
                ) {
                    switchItem(
                        checked = currentModeIdle,
                        onCheckedChange = { currentModeIdle = it },
                        leadingContent = { CardGroupIcon(HugeIcons.Clock02) },
                        headlineContent = { Text(stringResource(R.string.auto_task_mode_idle)) },
                    )
                    if (currentModeIdle || currentTaskListEnabled) {
                        formItem {
                            OutlinedTextField(
                                value = currentIdleMinutes,
                                onValueChange = { value ->
                                    if (value.isEmpty() || value.matches(Regex("^\\d+$"))) {
                                        currentIdleMinutes = value
                                    }
                                },
                                label = { Text(stringResource(R.string.auto_task_idle_label)) },
                                suffix = { Text(stringResource(R.string.auto_task_idle_unit)) },
                                supportingText = { Text(stringResource(R.string.auto_task_idle_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }

                    switchItem(
                        checked = currentModeDaily,
                        onCheckedChange = { currentModeDaily = it },
                        leadingContent = { CardGroupIcon(HugeIcons.Time02) },
                        headlineContent = { Text(stringResource(R.string.auto_task_mode_daily)) },
                    )
                    if (currentModeDaily) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.auto_task_daily_times_label)) },
                        ) {
                            if (currentDailyTimes.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.auto_task_daily_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    currentDailyTimes.forEach { time ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(time) },
                                            )
                                            IconButton(
                                                onClick = {
                                                    currentDailyTimes = currentDailyTimes.filter { it != time }.toMutableList()
                                                },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(
                                                    imageVector = HugeIcons.Delete01,
                                                    contentDescription = stringResource(R.string.auto_task_daily_remove),
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Add01,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.auto_task_daily_add))
                            }
                        }
                    }

                    switchItem(
                        checked = currentTaskListEnabled,
                        onCheckedChange = { setTaskListEnabled(it) },
                        leadingContent = { CardGroupIcon(HugeIcons.CheckList) },
                        headlineContent = { Text(stringResource(R.string.auto_task_mode_tasklist)) },
                    )
                }

                // ===== 执行内容 =====
                CardGroup(
                    title = { Text(stringResource(R.string.auto_task_exec_label)) },
                ) {
                    switchItem(
                        checked = currentUseFixed,
                        onCheckedChange = { currentUseFixed = it },
                        leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                        headlineContent = { Text(stringResource(R.string.auto_task_exec_fixed)) },
                    )
                    if (currentUseFixed) {
                        formItem {
                            OutlinedTextField(
                                value = currentMessage,
                                onValueChange = { currentMessage = it },
                                label = { Text(stringResource(R.string.auto_task_reply_label)) },
                                placeholder = { Text(stringResource(R.string.auto_task_reply_placeholder)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                singleLine = false,
                                maxLines = 4,
                            )
                        }
                    }

                    switchItem(
                        checked = currentTaskListEnabled,
                        onCheckedChange = { setTaskListEnabled(it) },
                        leadingContent = { CardGroupIcon(HugeIcons.CheckList) },
                        headlineContent = { Text(stringResource(R.string.auto_task_exec_tasklist)) },
                    )
                    if (currentTaskListEnabled) {
                        formItem {
                            if (savedLists.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.auto_task_taskfile_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    savedLists.forEach { name ->
                                        FilterChip(
                                            selected = name == currentTaskFileName,
                                            onClick = {
                                                currentTaskFileName = name
                                                currentTasks = loadTaskListFromFile(context, name).joinToString("\n")
                                            },
                                            label = { Text(name) },
                                        )
                                    }
                                }
                            }
                            var newListName by remember { mutableStateOf("") }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = newListName,
                                    onValueChange = { newListName = it },
                                    label = { Text(stringResource(R.string.auto_task_taskfile_name_label)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                OutlinedButton(
                                    onClick = {
                                        val name = newListName.trim().ifEmpty { currentTaskFileName }
                                        currentTaskFileName =
                                            saveTaskListToFile(
                                                context,
                                                name.ifBlank { "task_list" },
                                                currentTasks.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                                            )
                                        newListName = ""
                                    },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.FloppyDisk,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.auto_task_taskfile_save))
                                }
                            }
                            OutlinedTextField(
                                value = currentTasks,
                                onValueChange = { currentTasks = it },
                                label = { Text(stringResource(R.string.auto_task_tasks_label)) },
                                supportingText = { Text(stringResource(R.string.auto_task_tasks_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 8,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { currentTasks = defaultAutoTaskList.joinToString("\n") },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.auto_task_tasks_default))
                                }
                                OutlinedButton(
                                    onClick = { currentTasks = aiPresetTaskList.joinToString("\n") },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.auto_task_tasks_ai_preset))
                                }
                            }
                        }
                    }
                }

                // ===== 触发次数 =====
                CardGroup {
                    formItem(
                        leadingContent = { CardGroupIcon(HugeIcons.Refresh01) },
                        headlineContent = { Text(stringResource(R.string.auto_task_count_label)) },
                        supportingContent = { Text(stringResource(R.string.auto_task_count_hint)) },
                    ) {
                        OutlinedTextField(
                            value = currentCount,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.matches(Regex("^\\d+$"))) {
                                    currentCount = value
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }

                validationError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = stringResource(R.string.auto_task_active_skip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = stringResource(R.string.auto_task_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ctx = context
                    val hasTriggerMode = currentModeIdle || currentModeDaily || currentTaskListEnabled
                    val hasExecContent = currentUseFixed || currentTaskListEnabled
                    validationError =
                        when {
                            !hasTriggerMode -> errNoTrigger
                            !hasExecContent -> errNoExec
                            currentModeDaily && currentDailyTimes.isEmpty() -> errNoDailyTimes
                            currentTaskListEnabled && currentTasks.lineSequence().map { it.trim() }.none { it.isNotEmpty() } ->
                                errNoTasks
                            else -> null
                        }
                    if (validationError != null) return@TextButton

                    val tasks =
                        currentTasks.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    val fileName =
                        if (currentTaskListEnabled) {
                            if (currentTaskFileName.isBlank()) {
                                saveTaskListToFile(ctx, "task_list", tasks)
                            } else {
                                currentTaskFileName
                            }
                        } else {
                            ""
                        }
                    onConfirm(
                        AutoTaskConfig(
                            message = currentMessage.ifBlank { defaultAutoTaskMsg },
                            modeIdle = currentModeIdle,
                            modeDaily = currentModeDaily,
                            dailyTimes = currentDailyTimes.distinct(),
                            intervalSeconds = (currentIdleMinutes.toIntOrNull()?.coerceIn(1, 720) ?: 1) * 60,
                            useFixedMessage = currentUseFixed,
                            useTaskList = currentTaskListEnabled,
                            tasks = tasks,
                            taskFileName = fileName,
                            triggerCount = currentCount.toIntOrNull()?.coerceIn(0, MAX_AUTO_TASK_TRIGGER_COUNT) ?: 1,
                            taskIndex = 0,
                        ),
                    )
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            Row {
                if (onStop != null && hasActiveTask) {
                    TextButton(
                        onClick = {
                            onStop()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.auto_task_stop), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { onDismiss() }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        },
    )

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.auto_task_time_picker_title)) },
            text = {
                TimePicker(state = timeState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val hh = timeState.hour.toString().padStart(2, '0')
                        val mm = timeState.minute.toString().padStart(2, '0')
                        val time = "$hh:$mm"
                        if (currentDailyTimes.none { it == time }) {
                            currentDailyTimes = (currentDailyTimes + time).sorted().toMutableList()
                        }
                        showTimePicker = false
                    },
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}