package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import androidx.compose.runtime.Stable

/**
 * 可触发次数模式的上限：可设置次数上限为 100 次，到达设置次数或次数上限后自动停止触发。
 */
const val MAX_AUTO_TASK_TRIGGER_COUNT = 100

/**
 * 自动任务配置：用户没空时 App 能自动发送消息激活会话继续任务。
 *
 * @param message 固定回复消息内容（默认自动任务指令）
 * @param randomMessages 随机补充池（每行一条，触发时随机追加一条到固定内容后；空则仅用固定内容）
 * @param mode 触发模式：0 = 定时×次数（会话空闲 N 分钟后触发，共 M 次），1 = 随机空闲（空闲后 1 分钟内随机触发，持续直到停止）
 * @param triggerCount 可触发次数（仅 mode = 0 使用），上限 [MAX_AUTO_TASK_TRIGGER_COUNT]
 * @param intervalSeconds 定时触发模式下的会话空闲秒数（仅 mode = 0 使用；UI 以分钟填写，存储秒，默认 1 分钟）
 */
@Stable
data class AutoTaskConfig(
    val message: String = "",
    val randomMessages: List<String> = emptyList(),
    val mode: Int = 0, // 0: 定时×次数, 1: 随机空闲（1 分钟内随机）
    val triggerCount: Int = 1,
    val intervalSeconds: Int = 60, // 默认 1 分钟
)

// ---- SharedPreferences keys ----
private const val PREF_AUTO_TASK_MESSAGE = "auto_task_message"
private const val PREF_AUTO_TASK_RANDOM_MESSAGES = "auto_task_random_messages"
private const val PREF_AUTO_TASK_MODE = "auto_task_mode"
private const val PREF_AUTO_TASK_TRIGGER_COUNT = "auto_task_trigger_count"
private const val PREF_AUTO_TASK_INTERVAL = "auto_task_interval"

/**
 * 从 SharedPreferences 读取已保存的自动任务配置。
 * 可在非 Composable 上下文中使用（如 ChatVM）。
 */
fun readAutoTaskConfig(context: Context): AutoTaskConfig {
    val prefs = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
    return AutoTaskConfig(
        message = prefs.getString(PREF_AUTO_TASK_MESSAGE, context.getString(me.rerere.rikkahub.R.string.auto_task_default_message))
                ?: context.getString(me.rerere.rikkahub.R.string.auto_task_default_message),
        randomMessages =
            prefs
                .getString(PREF_AUTO_TASK_RANDOM_MESSAGES, "")
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList(),
        mode = prefs.getInt(PREF_AUTO_TASK_MODE, 0),
        triggerCount = prefs.getInt(PREF_AUTO_TASK_TRIGGER_COUNT, 1).coerceIn(1, MAX_AUTO_TASK_TRIGGER_COUNT),
        intervalSeconds = prefs.getInt(PREF_AUTO_TASK_INTERVAL, 300).coerceAtLeast(60),
    )
}

/**
 * 将自动任务配置持久化到 SharedPreferences。
 */
fun writeAutoTaskConfig(
    context: Context,
    config: AutoTaskConfig,
) {
    context
        .getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_AUTO_TASK_MESSAGE, config.message)
        .putString(PREF_AUTO_TASK_RANDOM_MESSAGES, config.randomMessages.joinToString("\n"))
        .putInt(PREF_AUTO_TASK_MODE, config.mode)
        .putInt(PREF_AUTO_TASK_TRIGGER_COUNT, config.triggerCount.coerceIn(1, MAX_AUTO_TASK_TRIGGER_COUNT))
        .putInt(PREF_AUTO_TASK_INTERVAL, config.intervalSeconds.coerceAtLeast(60))
        .apply()
}

/** 解析本次触发的消息：固定内容 + 随机池追加（若配置了随机池）。 */
fun resolveAutoTaskMessage(config: AutoTaskConfig): String {
    val random = config.randomMessages.randomOrNull()
    return if (random != null) "${config.message} $random" else config.message
}
