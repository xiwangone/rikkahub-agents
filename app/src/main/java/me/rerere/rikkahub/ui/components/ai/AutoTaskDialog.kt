package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * 自动任务设置弹窗。
 *
 * 触发模式（多选组合，至少一个）：
 *  - 空闲触发：会话空闲 N 分钟（1-720 分钟 = 60-43200 秒）后到点触发
 *  - 每日定时：在多个 HH:mm 时间点触发（24 小时制 TimePicker 添加）
 *  - 任务列表：每次触发按序推进任务列表，列表跑完自动停止
 *
 * 执行内容（多选，至少一个）：
 *  - 固定消息 / 任务列表（与触发模式中的任务列表联动勾选）
 *
 * 次数：0 = 无限，默认 100，上限 [MAX_AUTO_TASK_TRIGGER_COUNT]；任务列表跑完或到达次数即自动停止。
 * 活跃监测：会话正在生成回复或用户 60 秒内有操作时，跳过本次触发。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    // 校验错误文案（在 Composable 上下文预取，供 onClick 使用）
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

    // 已保存的任务列表文件（用于复用）
    val savedLists = remember { listSavedTaskLists(context) }

    // TimePicker（24 小时制）弹窗状态
    var showTimePicker by remember { mutableStateOf(false) }

    /**
     * 任务列表预设（可一键应用）。
     *
     * 内容取自资源数组，且**刻意保持通用措辞**（不绑定具体服务名/路径）：
     * 预设一旦写死环境（如某个已停用的备份目标），就会随基础设施变动而过时。
     */
    val DEFAULT_AUTO_TASK_LIST = stringArrayResource(R.array.auto_task_default_list).toList()
    val AI_PRESET_TASK_LIST = stringArrayResource(R.array.auto_task_ai_preset_list).toList()

    /** 同步任务列表联动开关（触发模式区 与 执行内容区共用） */
    fun setTaskListEnabled(enabled: Boolean) {
        currentTaskListEnabled = enabled
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.auto_task_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.auto_task_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ===== 触发模式（多选，至少一个） =====
                Text(
                    text = stringResource(R.string.auto_task_mode_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                // ① 空闲触发
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = currentModeIdle, onCheckedChange = { currentModeIdle = it })
                    Text(
                        text = stringResource(R.string.auto_task_mode_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (currentModeIdle || currentTaskListEnabled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = currentIdleMinutes,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.matches(Regex("^\\d+$"))) {
                                    currentIdleMinutes = value
                                }
                            },
                            label = { Text(stringResource(R.string.auto_task_idle_label)) },
                            supportingText = { Text(stringResource(R.string.auto_task_idle_hint)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.auto_task_idle_unit),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                // ② 每日定时（多时间点 HH:mm）
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = currentModeDaily, onCheckedChange = { currentModeDaily = it })
                    Text(
                        text = stringResource(R.string.auto_task_mode_daily),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (currentModeDaily) {
                    Text(
                        text = stringResource(R.string.auto_task_daily_times_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (currentDailyTimes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.auto_task_daily_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            currentDailyTimes.forEach { time ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = time,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { currentDailyTimes = currentDailyTimes.filter { it != time }.toMutableList() }) {
                                        Text(stringResource(R.string.auto_task_daily_remove), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showTimePicker = true }) {
                        Text(stringResource(R.string.auto_task_daily_add))
                    }
                }

                // ③ 任务列表（触发模式/执行内容共用）
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = currentTaskListEnabled, onCheckedChange = { setTaskListEnabled(it) })
                    Text(
                        text = stringResource(R.string.auto_task_mode_tasklist),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ===== 执行内容（多选，至少一个） =====
                Text(
                    text = stringResource(R.string.auto_task_exec_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                // 固定消息
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = currentUseFixed, onCheckedChange = { currentUseFixed = it })
                    Text(
                        text = stringResource(R.string.auto_task_exec_fixed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (currentUseFixed) {
                    OutlinedTextField(
                        value = currentMessage,
                        onValueChange = { currentMessage = it },
                        label = { Text(stringResource(R.string.auto_task_reply_label)) },
                        placeholder = { Text(stringResource(R.string.auto_task_reply_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // 任务列表（与触发模式联动）
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = currentTaskListEnabled, onCheckedChange = { setTaskListEnabled(it) })
                    Text(
                        text = stringResource(R.string.auto_task_exec_tasklist),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ===== 任务列表管理（复用/新建存文件） =====
                if (currentTaskListEnabled) {
                    // 已有列表复用
                    if (savedLists.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.auto_task_taskfile_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        savedLists.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (name == currentTaskFileName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        currentTaskFileName = name
                                        currentTasks = loadTaskListFromFile(context, name).joinToString("\n")
                                    },
                                ) {
                                    Text(stringResource(R.string.auto_task_taskfile_use))
                                }
                            }
                        }
                    }
                    // 新建/命名 + 保存到文件
                    var newListName by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newListName,
                            onValueChange = { newListName = it },
                            label = { Text(stringResource(R.string.auto_task_taskfile_name_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                val name = newListName.trim().ifEmpty { currentTaskFileName }
                                currentTaskFileName = saveTaskListToFile(
                                    context,
                                    name.ifBlank { "task_list" },
                                    currentTasks.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                                )
                                newListName = ""
                            },
                        ) {
                            Text(stringResource(R.string.auto_task_taskfile_save))
                        }
                    }
                    // 编辑区
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
                        TextButton(onClick = { currentTasks = DEFAULT_AUTO_TASK_LIST.joinToString("\n") }) {
                            Text(stringResource(R.string.auto_task_tasks_default))
                        }
                        TextButton(onClick = { currentTasks = AI_PRESET_TASK_LIST.joinToString("\n") }) {
                            Text(stringResource(R.string.auto_task_tasks_ai_preset))
                        }
                    }
                }

                // ===== 触发次数（0=无限，默认 100，上限 100） =====
                OutlinedTextField(
                    value = currentCount,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.matches(Regex("^\\d+$"))) {
                            currentCount = value
                        }
                    },
                    label = { Text(stringResource(R.string.auto_task_count_label)) },
                    supportingText = { Text(stringResource(R.string.auto_task_count_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                // 校验错误提示
                validationError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 活跃监测说明
                Text(
                    text = stringResource(R.string.auto_task_active_skip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )

                Text(
                    text = stringResource(R.string.auto_task_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ctx = context
                    // 联动校验：任务列表勾选时，执行内容自动包含任务列表
                    val hasTriggerMode = currentModeIdle || currentModeDaily || currentTaskListEnabled
                    val hasExecContent = currentUseFixed || currentTaskListEnabled
                    validationError = when {
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
                            triggerCount = currentCount.toIntOrNull()?.coerceIn(0, MAX_AUTO_TASK_TRIGGER_COUNT) ?: MAX_AUTO_TASK_TRIGGER_COUNT,
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

    // 24 小时制 TimePicker（添加每日定时时间点）
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