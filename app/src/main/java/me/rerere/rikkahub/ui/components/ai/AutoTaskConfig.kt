package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import androidx.compose.runtime.Stable
import java.io.File
import java.io.IOException

/**
 * 可触发次数上限：次数 0 = 无限，默认 100，上限 100。
 */
const val MAX_AUTO_TASK_TRIGGER_COUNT = 100

/** 空闲触发秒数范围：60 秒（1 分钟）～ 43200 秒（12 小时） */
const val MIN_AUTO_TASK_IDLE_SECONDS = 60
const val MAX_AUTO_TASK_IDLE_SECONDS = 43200

/**
 * 自动任务配置：用户没空时 App 能自动发送消息激活会话继续任务。
 *
 * 触发模式（可多选组合，至少一个）：
 *  - [modeIdle]：会话空闲 [intervalSeconds] 秒（UI 以分钟填写）后到点触发
 *  - [modeDaily]：每日定时，在 [dailyTimes]（多个 HH:mm 时间点）触发
 *  - [useTaskList]：任务列表模式，每次触发按序推进一项，列表跑完自动停止
 *
 * 执行内容（可多选，至少一个）：
 *  - [useFixedMessage]：每次触发发送 [message] 固定消息
 *  - [useTaskList]：每次触发发送列表中下一个任务（与固定消息可同时勾选）
 *
 * @param triggerCount 可触发总次数：0 = 无限，默认 1，上限 100。
 *   任务列表跑完或达到次数上限时自动停止整个自动任务。
 * @param tasks 当前任务列表内容（每行一条），与任务列表文件（[taskFileName]）对应。
 * @param taskFileName 任务列表文件名（auto_tasks 目录下存储为 <name>.txt）；为空表示未保存到文件。
 * @param taskIndex 任务列表当前已执行到的序号（触发后递增；达到 tasks.size 时任务列表跑完）
 */
@Stable
data class AutoTaskConfig(
    val message: String = "",
    val modeIdle: Boolean = true,
    val modeDaily: Boolean = false,
    val dailyTimes: List<String> = emptyList(), // 多个 HH:mm 时间点
    val intervalSeconds: Int = 60, // 默认 1 分钟，范围 60-43200 秒
    val useFixedMessage: Boolean = true,
    val useTaskList: Boolean = false,
    val tasks: List<String> = emptyList(),
    val taskFileName: String = "",
    val triggerCount: Int = 1, // 0 = 无限，默认 1，上限 100
    val taskIndex: Int = 0,
)

// ---- SharedPreferences keys ----
private const val PREF_AUTO_TASK_MESSAGE = "auto_task_message"
private const val PREF_AUTO_TASK_MODE = "auto_task_mode" // 旧版：移除
private const val PREF_AUTO_TASK_RANDOM_MESSAGES = "auto_task_random_messages" // 旧版：移除
private const val PREF_AUTO_TASK_MODE_IDLE = "auto_task_mode_idle"
private const val PREF_AUTO_TASK_MODE_DAILY = "auto_task_mode_daily"
private const val PREF_AUTO_TASK_DAILY_TIMES = "auto_task_daily_times"
private const val PREF_AUTO_TASK_USE_FIXED = "auto_task_use_fixed"
private const val PREF_AUTO_TASK_USE_TASKLIST = "auto_task_use_tasklist"
private const val PREF_AUTO_TASK_TRIGGER_COUNT = "auto_task_trigger_count"
private const val PREF_AUTO_TASK_INTERVAL = "auto_task_interval"
private const val PREF_AUTO_TASK_TASKS = "auto_task_tasks"
private const val PREF_AUTO_TASK_TASK_INDEX = "auto_task_task_index"
private const val PREF_AUTO_TASK_TASK_FILE = "auto_task_task_file"

// ---- 任务列表文件管理：app 私有 filesDir/auto_tasks/<name>.txt ----

/** 任务列表保存目录（自动创建） */
fun taskListDir(context: Context): File =
    File(context.filesDir, "auto_tasks").apply {
        if (!exists()) mkdirs()
    }

/** 列出已保存的任务列表名（不含 .txt 后缀，按名称排序） */
fun listSavedTaskLists(context: Context): List<String> =
    taskListDir(context)
        .listFiles { f -> f.isFile && f.name.endsWith(".txt") }
        ?.map { it.name.removeSuffix(".txt") }
        ?.sortedBy { it }
        ?: emptyList()

/** 规范化文件名：去除非法字符、空白、路径分隔符，空名回退为 task_list */
fun sanitizeTaskListFileName(name: String): String =
    name
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .ifBlank { "task_list" }

/** 从文件加载任务列表（每行一条，过滤空行） */
fun loadTaskListFromFile(
    context: Context,
    name: String,
): List<String> {
    val file = File(taskListDir(context), "${sanitizeTaskListFileName(name)}.txt")
    if (!file.exists()) return emptyList()
    return try {
        file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: IOException) {
        emptyList()
    }
}

/** 将任务列表保存为文件，返回规范化后的文件名 */
fun saveTaskListToFile(
    context: Context,
    name: String,
    tasks: List<String>,
): String {
    val safeName = sanitizeTaskListFileName(name)
    val file = File(taskListDir(context), "$safeName.txt")
    try {
        file.writeText(tasks.joinToString("\n"), Charsets.UTF_8)
    } catch (_: IOException) {
        // 忽略写入失败（如目录异常），配置仍会在 prefs 中保留快照
    }
    return safeName
}

private fun autoTaskPrefs(context: Context) = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)

/**
 * 从 SharedPreferences 读取已保存的自动任务配置。
 * 旧版配置（mode 0/1/3 + randomMessages）会自动迁移到新版字段。
 * 可在非 Composable 上下文中使用（如 ChatVM）。
 */
fun readAutoTaskConfig(context: Context): AutoTaskConfig {
    val prefs = autoTaskPrefs(context)
    if (prefs.contains(PREF_AUTO_TASK_MODE)) {
        migrateLegacyAutoTaskConfig(context, prefs)
    }
    val storedTasks =
        prefs
            .getString(PREF_AUTO_TASK_TASKS, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    val taskFileName = prefs.getString(PREF_AUTO_TASK_TASK_FILE, "") ?: ""
    // 有文件名但快照为空时，尝试从文件恢复
    val tasks =
        if (storedTasks.isNotEmpty()) {
            storedTasks
        } else if (taskFileName.isNotBlank()) {
            loadTaskListFromFile(context, taskFileName)
        } else {
            emptyList()
        }
    return AutoTaskConfig(
        message =
            prefs.getString(PREF_AUTO_TASK_MESSAGE, context.getString(me.rerere.rikkahub.R.string.auto_task_default_message))
                ?: context.getString(me.rerere.rikkahub.R.string.auto_task_default_message),
        modeIdle = prefs.getBoolean(PREF_AUTO_TASK_MODE_IDLE, true),
        modeDaily = prefs.getBoolean(PREF_AUTO_TASK_MODE_DAILY, false),
        dailyTimes =
            prefs
                .getString(PREF_AUTO_TASK_DAILY_TIMES, "")
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList(),
        intervalSeconds = prefs.getInt(PREF_AUTO_TASK_INTERVAL, 60).coerceIn(MIN_AUTO_TASK_IDLE_SECONDS, MAX_AUTO_TASK_IDLE_SECONDS),
        useFixedMessage = prefs.getBoolean(PREF_AUTO_TASK_USE_FIXED, true),
        useTaskList = prefs.getBoolean(PREF_AUTO_TASK_USE_TASKLIST, false),
        tasks = tasks,
        taskFileName = taskFileName,
        triggerCount = prefs.getInt(PREF_AUTO_TASK_TRIGGER_COUNT, 1).coerceIn(0, MAX_AUTO_TASK_TRIGGER_COUNT),
        taskIndex = prefs.getInt(PREF_AUTO_TASK_TASK_INDEX, 0).coerceAtLeast(0),
    )
}

/**
 * 将自动任务配置持久化到 SharedPreferences。
 * 若配置带任务列表文件，同时将任务内容同步保存到该文件（保证文件最新）；
 * 并清除旧版已废弃的键（mode / randomMessages）。
 */
fun writeAutoTaskConfig(
    context: Context,
    config: AutoTaskConfig,
) {
    val prefs = autoTaskPrefs(context)
    // 任务列表文件同步：勾选了任务列表且有内容 → 保存/覆盖到文件，并补全有效文件名
    var effectiveFileName = config.taskFileName
    if (config.useTaskList && config.tasks.isNotEmpty()) {
        effectiveFileName = saveTaskListToFile(context, effectiveFileName.ifBlank { "task_list" }, config.tasks)
    }
    prefs
        .edit()
        .putString(PREF_AUTO_TASK_MESSAGE, config.message)
        .remove(PREF_AUTO_TASK_MODE)
        .remove(PREF_AUTO_TASK_RANDOM_MESSAGES)
        .putBoolean(PREF_AUTO_TASK_MODE_IDLE, config.modeIdle)
        .putBoolean(PREF_AUTO_TASK_MODE_DAILY, config.modeDaily)
        .putString(PREF_AUTO_TASK_DAILY_TIMES, config.dailyTimes.joinToString("\n"))
        .putBoolean(PREF_AUTO_TASK_USE_FIXED, config.useFixedMessage)
        .putBoolean(PREF_AUTO_TASK_USE_TASKLIST, config.useTaskList)
        .putInt(PREF_AUTO_TASK_TRIGGER_COUNT, config.triggerCount.coerceIn(0, MAX_AUTO_TASK_TRIGGER_COUNT))
        .putInt(PREF_AUTO_TASK_INTERVAL, config.intervalSeconds.coerceIn(MIN_AUTO_TASK_IDLE_SECONDS, MAX_AUTO_TASK_IDLE_SECONDS))
        .putString(PREF_AUTO_TASK_TASKS, config.tasks.joinToString("\n"))
        .putString(PREF_AUTO_TASK_TASK_FILE, effectiveFileName)
        .putInt(PREF_AUTO_TASK_TASK_INDEX, config.taskIndex.coerceAtLeast(0))
        .apply()
}

/** 旧版配置迁移：mode 0（定时×次数）→ 空闲触发+固定消息；mode 1（随机空闲）→ 空闲触发+固定消息(无限次数)；mode 3（任务列表）→ 任务列表模式 */
private fun migrateLegacyAutoTaskConfig(
    context: Context,
    prefs: android.content.SharedPreferences,
) {
    val legacyMode = prefs.getInt(PREF_AUTO_TASK_MODE, 0)
    val legacyTasks =
        prefs
            .getString(PREF_AUTO_TASK_TASKS, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    var taskFileName = ""
    if (legacyMode == 3 && legacyTasks.isNotEmpty()) {
        taskFileName = saveTaskListToFile(context, "migrated_task_list", legacyTasks)
    }
    val newTriggerCount =
        when (legacyMode) {
            1 -> 0 // 随机空闲 → 无限触发
            else -> prefs.getInt(PREF_AUTO_TASK_TRIGGER_COUNT, 1).coerceIn(1, MAX_AUTO_TASK_TRIGGER_COUNT)
        }
    prefs
        .edit()
        .remove(PREF_AUTO_TASK_MODE)
        .remove(PREF_AUTO_TASK_RANDOM_MESSAGES)
        .putBoolean(PREF_AUTO_TASK_MODE_IDLE, true)
        .putBoolean(PREF_AUTO_TASK_MODE_DAILY, false)
        .putBoolean(PREF_AUTO_TASK_USE_FIXED, legacyMode != 3)
        .putBoolean(PREF_AUTO_TASK_USE_TASKLIST, legacyMode == 3)
        .putInt(PREF_AUTO_TASK_TRIGGER_COUNT, newTriggerCount)
        .putInt(PREF_AUTO_TASK_INTERVAL, prefs.getInt(PREF_AUTO_TASK_INTERVAL, 60).coerceIn(MIN_AUTO_TASK_IDLE_SECONDS, MAX_AUTO_TASK_IDLE_SECONDS))
        .putString(PREF_AUTO_TASK_TASKS, legacyTasks.joinToString("\n"))
        .putString(PREF_AUTO_TASK_TASK_FILE, taskFileName)
        .apply()
}

/** 解析本次触发的消息：按执行内容勾选组合「固定消息 + 任务（带进度前缀）」。 */
fun resolveAutoTaskMessage(
    config: AutoTaskConfig,
    taskIndex: Int,
): String {
    val parts =
        buildList {
            if (config.useFixedMessage && config.message.isNotBlank()) {
                add(config.message)
            }
            if (config.useTaskList && config.tasks.isNotEmpty()) {
                val idx = taskIndex.coerceIn(0, config.tasks.lastIndex)
                add("【任务 ${idx + 1}/${config.tasks.size}】${config.tasks[idx]}")
            }
        }
    return if (parts.isEmpty()) config.message else parts.joinToString("\n")
}