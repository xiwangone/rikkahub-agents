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
 * @param message 要自动发送的回复消息内容（如「继续」）
 * @param mode 触发模式：0 = 可触发次数，1 = 定时触发（会话空闲），2 = 随机空闲（5-15 秒随机间隔）
 * @param triggerCount 可触发次数（仅 mode = 0 使用），上限 [MAX_AUTO_TASK_TRIGGER_COUNT]
 * @param intervalSeconds 定时触发模式下的会话空闲秒数（仅 mode = 1 使用）
 */
@Stable
data class AutoTaskConfig(
    val message: String = "继续",
    val mode: Int = 0, // 0: 可触发次数, 1: 定时触发, 2: 随机空闲（5-15s 随机）
    val triggerCount: Int = 1,
    val intervalSeconds: Int = 60,
)

// ---- SharedPreferences keys ----
private const val PREF_AUTO_TASK_MESSAGE = "auto_task_message"
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
        message = prefs.getString(PREF_AUTO_TASK_MESSAGE, "继续") ?: "继续",
        mode = prefs.getInt(PREF_AUTO_TASK_MODE, 0),
        triggerCount = prefs.getInt(PREF_AUTO_TASK_TRIGGER_COUNT, 1).coerceIn(1, MAX_AUTO_TASK_TRIGGER_COUNT),
        intervalSeconds = prefs.getInt(PREF_AUTO_TASK_INTERVAL, 60),
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
        .putInt(PREF_AUTO_TASK_MODE, config.mode)
        .putInt(PREF_AUTO_TASK_TRIGGER_COUNT, config.triggerCount.coerceIn(1, MAX_AUTO_TASK_TRIGGER_COUNT))
        .putInt(PREF_AUTO_TASK_INTERVAL, config.intervalSeconds)
        .apply()
}
