package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.preferences.RemoteBackgroundTaskStore
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.ssh.REMOTE_BG_MAX_RUNNING
import me.rerere.workspace.ssh.RemoteBackgroundTask
import me.rerere.workspace.ssh.buildKillCommand
import me.rerere.workspace.ssh.buildLaunchCommand
import me.rerere.workspace.ssh.buildStatusCommand
import me.rerere.workspace.ssh.evictOldestExited
import me.rerere.workspace.ssh.parseKillOutput
import me.rerere.workspace.ssh.parseLaunchedPid
import me.rerere.workspace.ssh.parseStatusOutput
import me.rerere.workspace.ssh.RemoteTaskProbe
import me.rerere.workspace.ssh.remoteBgTaskDir
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.util.UUID

private const val REMOTE_BG_LAUNCH_TIMEOUT_MS = 30_000L
private const val REMOTE_BG_STATUS_TIMEOUT_MS = 30_000L
private const val REMOTE_BG_KILL_TIMEOUT_MS = 15_000L

/**
 * 远程（SSH）工作区后台任务管理器：与本地 [me.rerere.workspace.WorkspaceManager]
 * 的后台任务对等的落点，但执行模型不同——
 *
 * - 本地：App 侧直接持有 proot 进程对象（见 WorkspaceBackgroundProcesses），
 *   状态现成、输出由 drain 线程收；
 * - 远程：进程活在远端宿主上，App 侧只存一条定位记录（见 [RemoteBackgroundTaskStore]），
 *   每次查询用一条 exec 探活 + 读退出码 + tail 日志；杀进程按 setsid 会话组杀。
 *
 * 方法都是阻塞的 exec 调用，调用方（见 WorkspaceRepository）负责包在
 * Dispatchers.IO 里；启动方法还包在 NonCancellable 里——与本地 startBackground
 * 同口径：取消绝不能打断启动或丢弃刚拿到的 id。
 */
class RemoteBackgroundTasks(
    private val store: RemoteBackgroundTaskStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    suspend fun start(
        backend: SshWorkspaceBackend,
        workspaceId: String,
        command: String,
        cwd: String,
        env: Map<String, String>,
    ): BackgroundStatus {
        require(command.isNotBlank()) { "Command is required" }
        require(!backend.isWindows) {
            "Background tasks are not supported for Windows remote workspaces yet: $workspaceId"
        }
        // 运行中上限：先探活一次（顺带拿到最新列表），超了直接拒；与本地同口径，
        // 报错文案也与本地一致，模型侧不用分本地/远程两套说法。
        val running = list(backend, workspaceId).count { it.running }
        if (running >= REMOTE_BG_MAX_RUNNING) {
            throw IllegalStateException(
                "Too many running background processes for this workspace (max $REMOTE_BG_MAX_RUNNING). " +
                    "Kill one with workspace_background_kill first."
            )
        }
        val taskId = "rbg_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val dir = remoteBgTaskDir(backend.layout.workspaceDir, taskId)
        val launchCommand = buildLaunchCommand(dir, "$dir/pid", "$dir/exit", "$dir/log", command)
        val launched = backend.executeCommand(launchCommand, cwd, REMOTE_BG_LAUNCH_TIMEOUT_MS, null, env)
        val pid = parseLaunchedPid(launched.stdout)
            ?: throw IllegalStateException(
                "Failed to start background task on remote workspace $workspaceId " +
                    "(exit=${launched.exitCode}, stderr=${launched.stderr.trim().take(500)})"
            )
        val record = RemoteBackgroundTask(
            id = taskId,
            workspaceId = workspaceId,
            command = command,
            cwd = cwd,
            pid = pid,
            logPath = "$dir/log",
            pidPath = "$dir/pid",
            exitPath = "$dir/exit",
            startedAtMillis = nowMillis(),
        )
        store.upsert(record)
        return BackgroundStatus(
            id = taskId,
            command = command,
            cwd = cwd,
            running = true,
            exitCode = null,
            startedAtMillis = record.startedAtMillis,
            stdout = "",
            stderr = "",
            droppedStdout = 0,
            droppedStderr = 0,
            logPath = record.logPath,
        )
    }

    suspend fun status(
        backend: SshWorkspaceBackend,
        workspaceId: String,
        taskId: String,
    ): BackgroundStatus? {
        val record = store.get(workspaceId, taskId) ?: return null
        return toStatus(record, probe(backend, listOf(record))[record.id])
    }

    suspend fun list(backend: SshWorkspaceBackend, workspaceId: String): List<BackgroundStatus> {
        val records = store.listForWorkspace(workspaceId)
        if (records.isEmpty()) return emptyList()
        val probes = probe(backend, records)
        val statuses = records.map { toStatus(it, probes[it.id]) }.sortedBy { it.startedAtMillis }
        // 淘汰超出上限的最旧已退出记录（与本地同口径；运行中的永不淘汰）。
        val runningIds = statuses.filter { it.running }.map { it.id }.toSet()
        val kept = evictOldestExited(records, runningIds)
        if (kept.size != records.size) {
            val keptIds = kept.map { it.id }.toSet()
            records.filter { it.id !in keptIds }.forEach { store.remove(workspaceId, it.id) }
            return statuses.filter { it.id in keptIds }
        }
        return statuses
    }

    /**
     * 杀进程并删记录。远端杀失败（进程仍活着）时记录保留、返回 false，调用方可重试；
     * exec 本身抛错（SSH 断了）时记录同样保留——与本地 kill 同口径：杀的过程中
     * 抛异常时记录仍在，不会留下无句柄的孤儿进程。
     */
    suspend fun kill(backend: SshWorkspaceBackend, workspaceId: String, taskId: String): Boolean {
        val record = store.get(workspaceId, taskId) ?: return false
        val dir = remoteBgTaskDir(backend.layout.workspaceDir, taskId)
        val killed = backend.executeCommand(
            buildKillCommand(record.pid, dir),
            "",
            REMOTE_BG_KILL_TIMEOUT_MS,
            null,
        )
        if (!parseKillOutput(killed.stdout)) return false
        store.remove(workspaceId, taskId)
        return true
    }

    /**
     * 工作区删除时清掉它的全部远程任务：逐个杀，杀不掉的记一条 w（进程可能
     * 残留在远端宿主上，记录没了就再也查不到——删工作区是用户主动行为，
     * 这里只留痕，不阻断删除）。
     */
    suspend fun killAllForWorkspace(backend: SshWorkspaceBackend, workspaceId: String) {
        for (record in store.listForWorkspace(workspaceId)) {
            runCatching { kill(backend, workspaceId, record.id) }.onFailure {
                if (it is CancellationException) throw it
                AppLog.w(
                    "RemoteBackgroundTasks",
                    "killAllForWorkspace: failed to kill ${record.id} (pid=${record.pid}) on $workspaceId: ${it.message}",
                )
            }
        }
    }

    /** 工作区删除后必调：无论远端杀没杀掉，指向已删工作区的记录都清掉，不留悬空记录。 */
    suspend fun dropAllRecords(workspaceId: String) {
        store.removeForWorkspace(workspaceId)
    }

    private fun probe(
        backend: SshWorkspaceBackend,
        records: List<RemoteBackgroundTask>,
    ): Map<String, RemoteTaskProbe> {
        if (records.isEmpty()) return emptyMap()
        val result = backend.executeCommand(
            buildStatusCommand(records),
            "",
            REMOTE_BG_STATUS_TIMEOUT_MS,
            null,
        )
        if (result.timedOut) {
            throw IllegalStateException("Background task status check timed out")
        }
        return parseStatusOutput(result.stdout)
    }

    private fun toStatus(record: RemoteBackgroundTask, probe: RemoteTaskProbe?): BackgroundStatus {
        if (probe == null) {
            // 任务文件已被外部清掉：按已退出、无日志处理；下次 list 时按淘汰口径清记录。
            return BackgroundStatus(
                id = record.id,
                command = record.command,
                cwd = record.cwd,
                running = false,
                exitCode = null,
                startedAtMillis = record.startedAtMillis,
                stdout = "",
                stderr = "",
                droppedStdout = 0,
                droppedStderr = 0,
                logPath = record.logPath,
            )
        }
        // 远端 stdout/stderr 合并进同一个日志文件：stdout 放 tail，stderr 为空；
        // 丢弃量按日志总大小减去本次取回的字节数算（与本地 droppedStdout 同义）。
        val tailBytes = probe.logTail.toByteArray(Charsets.UTF_8).size.toLong()
        val dropped = if (probe.logSizeBytes > 0) {
            (probe.logSizeBytes - tailBytes).coerceAtLeast(0)
        } else {
            0
        }
        return BackgroundStatus(
            id = record.id,
            command = record.command,
            cwd = record.cwd,
            running = probe.running,
            exitCode = probe.exitCode,
            startedAtMillis = record.startedAtMillis,
            stdout = probe.logTail,
            stderr = "",
            droppedStdout = dropped,
            droppedStderr = 0,
            logPath = record.logPath,
        )
    }
}
