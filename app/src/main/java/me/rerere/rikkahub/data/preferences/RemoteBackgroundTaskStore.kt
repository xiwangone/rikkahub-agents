package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.ssh.RemoteBackgroundTask

private val Context.remoteBackgroundTasksDataStore by preferencesDataStore(name = "remote_background_tasks")

/** DataStore 里的单条记录形态（与 workspace 模块的 [RemoteBackgroundTask] 字段一一对应）。
 * 全部字段带默认值：旧 JSON 缺 key 时按缺省值解码，不抛 MissingFieldException。 */
@Serializable
private data class RemoteBackgroundTaskDto(
    val id: String = "",
    val workspaceId: String = "",
    val command: String = "",
    val cwd: String = "",
    val pid: Int = -1,
    val logPath: String = "",
    val pidPath: String = "",
    val exitPath: String = "",
    val startedAtMillis: Long = 0L,
)

private fun RemoteBackgroundTask.toDto() = RemoteBackgroundTaskDto(
    id = id,
    workspaceId = workspaceId,
    command = command,
    cwd = cwd,
    pid = pid,
    logPath = logPath,
    pidPath = pidPath,
    exitPath = exitPath,
    startedAtMillis = startedAtMillis,
)

private fun RemoteBackgroundTaskDto.toRecord() = RemoteBackgroundTask(
    id = id,
    workspaceId = workspaceId,
    command = command,
    cwd = cwd,
    pid = pid,
    logPath = logPath,
    pidPath = pidPath,
    exitPath = exitPath,
    startedAtMillis = startedAtMillis,
)

/**
 * 远程后台任务记录的持久化：整个列表一条 JSON 存在 DataStore。
 *
 * 选 DataStore 而不是 Room：记录只有定位信息（id / pid / 远端路径），量小、
 * 无查询需求、无迁移成本；App 重启后凭它认领远端仍在跑的任务。本地后台任务
 * 是内存表（见 WorkspaceBackgroundProcesses），不持久化——远程任务的进程
 * 活在远端宿主上，App 重启也不死，所以这里必须持久化，二者口径不同是故意的。
 *
 * JSON 损坏时按空表处理并记一条 w（与 WorkspaceEntity.storageMountList 同口径），
 * 不抛给调用方。
 */
class RemoteBackgroundTaskStore(private val context: Context) {

    private val store = context.remoteBackgroundTasksDataStore
    private val tasksKey = stringPreferencesKey("remote_background_tasks_json")

    val tasksFlow: Flow<List<RemoteBackgroundTask>> =
        store.data.map { prefs -> decode(prefs[tasksKey]) }.distinctUntilChanged()

    suspend fun snapshot(): List<RemoteBackgroundTask> = tasksFlow.first()

    suspend fun listForWorkspace(workspaceId: String): List<RemoteBackgroundTask> =
        snapshot().filter { it.workspaceId == workspaceId }.sortedBy { it.startedAtMillis }

    suspend fun get(workspaceId: String, taskId: String): RemoteBackgroundTask? =
        snapshot().firstOrNull { it.workspaceId == workspaceId && it.id == taskId }

    suspend fun upsert(task: RemoteBackgroundTask) {
        store.edit { prefs ->
            val current = decode(prefs[tasksKey])
                .filterNot { it.workspaceId == task.workspaceId && it.id == task.id }
            prefs[tasksKey] = JsonInstant.encodeToString((current + task).map { it.toDto() })
        }
    }

    suspend fun remove(workspaceId: String, taskId: String) {
        store.edit { prefs ->
            val current = decode(prefs[tasksKey])
                .filterNot { it.workspaceId == workspaceId && it.id == taskId }
            prefs[tasksKey] = JsonInstant.encodeToString(current.map { it.toDto() })
        }
    }

    suspend fun removeForWorkspace(workspaceId: String) {
        store.edit { prefs ->
            val current = decode(prefs[tasksKey]).filterNot { it.workspaceId == workspaceId }
            prefs[tasksKey] = JsonInstant.encodeToString(current.map { it.toDto() })
        }
    }

    private fun decode(json: String?): List<RemoteBackgroundTask> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            JsonInstant.decodeFromString<List<RemoteBackgroundTaskDto>>(json).map { it.toRecord() }
        }.getOrElse { e ->
            AppLog.w("RemoteBackgroundTaskStore", "tasks decode failed, treating as empty: ${e.message}")
            emptyList()
        }
    }
}
