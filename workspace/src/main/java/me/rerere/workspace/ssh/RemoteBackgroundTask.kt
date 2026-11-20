package me.rerere.workspace.ssh

// 远程（SSH）工作区后台任务：纯 shell 命令拼装与输出解析，不触网络、不触 Android。
//
// 执行模型沿用 requireBackgroundShell 报错里给出的绕法：远端跑 detached 进程，
// pid 与日志落文件；App 侧只记一条定位记录（[RemoteBackgroundTask]），状态查询
// 时用一条 exec 探活 + 读退出码 + tail 日志。
//
// 约束（写死在拼装里，调用方不再重复判断）：
// - 只支持 POSIX 远端；Windows 远端由调用方先拦（见 RemoteBackgroundTasks）。
// - detached 进程由 setsid 起新会话（进程组 id == pid），杀时按组杀，子树一起收；
//   setsid 不可用（如 macOS）时退回 plain nohup，杀时先按组杀、失败再按单 pid 杀。
// - 启动必须在同一次 exec 里确认 pid（pidfile 落盘后读回）：拿不到 pid 就当启动
//   失败，绝不留一条没有 pid 的记录——否则那条记录永远查不到状态。

/** 远端任务文件所在目录名（工作区目录下的隐藏目录，用户文件列表里不打扰）。 */
const val REMOTE_BG_DIR_NAME = ".rikkahub-bg"

/** 单次状态查询从远端日志取回的最大字节数（与本地 TailBuffer 的 32KB 同口径）。 */
const val REMOTE_BG_LOG_TAIL_BYTES = 32 * 1024

/** 单个工作区同时运行的后台任务上限（与本地 MAX_BG_PROCESSES 同口径）。 */
const val REMOTE_BG_MAX_RUNNING = 5

/** 单个工作区保留的后台任务记录数上限（含已退出的），超出后淘汰最旧的已退出记录。 */
const val REMOTE_BG_MAX_ENTRIES = 16

/**
 * 远端后台任务的 App 侧记录：只存定位信息，不存输出；输出每次查询时从远端日志现取。
 * 记录持久化后（见 RemoteBackgroundTaskStore）App 重启、断线重连仍可凭 pid 与日志
 * 路径认领任务；记录本身不含进程句柄，存活状态一律以远端探活为准。
 */
data class RemoteBackgroundTask(
    val id: String,
    val workspaceId: String,
    val command: String,
    val cwd: String,
    /** 远端进程 pid；记录创建时必已确认（pidfile 落盘），不存 null。 */
    val pid: Int,
    val logPath: String,
    val pidPath: String,
    val exitPath: String,
    val startedAtMillis: Long,
)

/** 远端后台任务文件布局：pid / 退出码 / 日志三文件与任务 id 同名目录。 */
fun remoteBgTaskDir(workspaceDir: String, taskId: String): String =
    "$workspaceDir/$REMOTE_BG_DIR_NAME/$taskId"

/** POSIX shell 单引号转义（与 WorkspaceShellRunner 的同名实现同算法；此处单列一份， */
/** 使本文件不依赖 Android/其他模块，可由 kotlinc 直接编单测）。 */
private fun String.bgShellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

/**
 * 启动命令（一次 exec 内完成）：建任务目录 → detached 起进程 → 等 pidfile 落盘
 * （最多 2 秒）→ 打印 pid。标准输出最后一行即 pid；拿不到 pid 时输出为空。
 *
 * 内层 `sh -c` 不 exec：它先把自己的 pid 写进 pidfile，再跑用户命令、跑完把
 * `$?` 写进 exitfile——于是 pidfile 的 pid 在命令整个生命周期内都有效，
 * `kill -0` 探活与按组杀都以它为准；退出码也不会因进程被回收而丢失。
 */
fun buildLaunchCommand(
    taskDir: String,
    pidPath: String,
    exitPath: String,
    logPath: String,
    command: String,
): String {
    val inner = "echo \$\$ > ${pidPath.bgShellQuote()}; $command; echo \$? > ${exitPath.bgShellQuote()}"
    return "mkdir -p -- ${taskDir.bgShellQuote()} && " +
        "DETACH=nohup; command -v setsid >/dev/null 2>&1 && DETACH='setsid nohup'; " +
        "\$DETACH sh -c ${inner.bgShellQuote()} > ${logPath.bgShellQuote()} 2>&1 </dev/null & " +
        "for i in 1 2 3 4 5 6 7 8 9 10; do [ -s ${pidPath.bgShellQuote()} ] && break; sleep 0.2; done; " +
        "cat ${pidPath.bgShellQuote()}"
}

/** 启动输出解析：最后一行非空行是 pid；拿不到返回 null（调用方当启动失败）。 */
fun parseLaunchedPid(stdout: String): Int? =
    stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toIntOrNull()

/** 单次状态探活的输出解析结果。 */
data class RemoteTaskProbe(
    val running: Boolean,
    val exitCode: Int?,
    val logTail: String,
    /** 远端日志文件总字节数（wc -c）；-1 表示读不到（文件已被清掉）。 */
    val logSizeBytes: Long,
)

/**
 * 多任务状态查询命令：每个任务一段，以任务 id 打头的起止标记分隔；段内先探活
 * 再读退出码、tail 日志。标记只认行首整行匹配——登录 shell 的欢迎信息打到同
 * 一 stdout 时，只有标记之间的字段才是本次探活的结果（与 parseShellEntries
 * 同口径；日志正文本身含标记行是已知小概率误判，见 parseStatusOutput）。
 */
fun buildStatusCommand(tasks: List<RemoteBackgroundTask>): String {
    val blocks = tasks.joinToString(" ") { task ->
        if (task.pid <= 0) {
            // 无效 pid（旧数据缺省值）：kill -0 0 / 负数是进程组语义，会误报
            // 存活，直接报已退出，不发 kill 探活。
            "{ echo '__RIKKAHUB_BG_TASK_BEGIN__ ${task.id}'; echo 'BG_STATUS=EXITED'; " +
                "echo 'BG_EXITCODE='; echo 'BG_LOGSIZE=0'; " +
                "echo '__RIKKAHUB_BG_LOG_BEGIN__'; echo '__RIKKAHUB_BG_LOG_END__'; " +
                "echo '__RIKKAHUB_BG_TASK_END__'; }"
        } else {
            "{ echo '__RIKKAHUB_BG_TASK_BEGIN__ ${task.id}'; " +
            // 先认退出码文件：命令跑完就写它，而进程退出后可能长时间停在僵尸态
            //（父进程未回收时 kill -0 仍返回成功）—— 只按 kill -0 判会永远 RUNNING。
            "if [ -s ${task.exitPath.bgShellQuote()} ]; then echo 'BG_STATUS=EXITED'; " +
            "elif kill -0 ${task.pid} 2>/dev/null; then echo 'BG_STATUS=RUNNING'; " +
            "else echo 'BG_STATUS=EXITED'; fi; " +
            "echo \"BG_EXITCODE=\$(cat ${task.exitPath.bgShellQuote()} 2>/dev/null)\"; " +
            "echo \"BG_LOGSIZE=\$(wc -c < ${task.logPath.bgShellQuote()} 2>/dev/null | tr -d ' ')\"; " +
            "echo '__RIKKAHUB_BG_LOG_BEGIN__'; " +
            "tail -c $REMOTE_BG_LOG_TAIL_BYTES ${task.logPath.bgShellQuote()} 2>/dev/null; " +
            "echo '__RIKKAHUB_BG_LOG_END__'; " +
            "echo '__RIKKAHUB_BG_TASK_END__'; }"
        }
    }
    return blocks
}

/**
 * 状态输出解析：按任务 id 分段；缺段（任务文件已被外部清掉）时该任务不在结果里，
 * 调用方按“已退出、无日志”处理。`BG_EXITCODE=` 为空表示进程仍在跑或退出码文件
 * 尚未落盘；日志段原样返回（尾部截断由远端 tail -c 控制）。
 */
fun parseStatusOutput(stdout: String): Map<String, RemoteTaskProbe> {
    val result = mutableMapOf<String, RemoteTaskProbe>()
    var currentId: String? = null
    var running: Boolean? = null
    var exitCode: Int? = null
    var logSizeBytes: Long = -1
    var inLog = false
    val log = StringBuilder()
    fun flush() {
        val id = currentId ?: return
        val isRunning = running ?: false
        result[id] = RemoteTaskProbe(
            running = isRunning,
            exitCode = if (isRunning) null else exitCode,
            logTail = log.toString(),
            logSizeBytes = logSizeBytes,
        )
    }
    for (rawLine in stdout.lineSequence()) {
        val line = rawLine.trimEnd('\r')
        when {
            line.startsWith("__RIKKAHUB_BG_TASK_BEGIN__ ") -> {
                flush()
                currentId = line.removePrefix("__RIKKAHUB_BG_TASK_BEGIN__ ").trim()
                running = null
                exitCode = null
                logSizeBytes = -1
                inLog = false
                log.clear()
            }
            line == "__RIKKAHUB_BG_TASK_END__" -> {
                flush()
                currentId = null
            }
            currentId == null -> {
                // 段外输出（登录欢迎信息等）直接忽略。
            }
            line == "__RIKKAHUB_BG_LOG_BEGIN__" -> inLog = true
            line == "__RIKKAHUB_BG_LOG_END__" -> inLog = false
            inLog -> log.appendLine(line)
            line == "BG_STATUS=RUNNING" -> running = true
            line == "BG_STATUS=EXITED" -> running = false
            line.startsWith("BG_EXITCODE=") -> {
                exitCode = line.removePrefix("BG_EXITCODE=").trim().toIntOrNull()
            }
            line.startsWith("BG_LOGSIZE=") -> {
                logSizeBytes = line.removePrefix("BG_LOGSIZE=").trim().toLongOrNull() ?: -1
            }
        }
    }
    flush()
    return result
}

/**
 * 杀进程命令：先按进程组 TERM（setsid 起的新会话组 id == pid），1 秒后仍活着
 * 再按组 KILL；按组杀失败（如退回 nohup 的宿主）时各退回单 pid。成功只认
 * `kill -0` 已探不到；最后按需删任务目录（日志随记录一起走，见调用方）。
 */
fun buildKillCommand(pid: Int, taskDir: String?): String {
    val cleanup = if (taskDir != null) " rm -rf -- ${taskDir.bgShellQuote()};" else ""
    if (pid <= 0) {
        // 无效 pid（旧数据缺省值）：没有可杀的进程；kill -TERM -0/-1 是进程组
        // 语义，绝不能发。直接报成功并清理目录，让调用方删掉这条脏记录。
        return "echo 'BG_KILL=OK';$cleanup"
    }
    return "kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null; sleep 1; " +
        "if kill -0 $pid 2>/dev/null; then " +
        "kill -KILL -$pid 2>/dev/null || kill -KILL $pid 2>/dev/null; sleep 1; fi; " +
        "if kill -0 $pid 2>/dev/null; then echo 'BG_KILL=FAILED'; " +
        "else echo 'BG_KILL=OK';$cleanup fi"
}

/** 杀进程输出解析：只认 `BG_KILL=OK`。 */
fun parseKillOutput(stdout: String): Boolean =
    stdout.lineSequence().any { it.trim() == "BG_KILL=OK" }

/**
 * 记录淘汰（纯函数，与本地 WorkspaceBackgroundProcesses.evictOldestExited 同口径）：
 * 超出上限时只淘汰已退出的记录、按开始时间从旧到新；运行中的记录永不淘汰。
 * [runningIds] 是本次探活后仍在跑的任务 id 集合。
 */
fun evictOldestExited(
    tasks: List<RemoteBackgroundTask>,
    runningIds: Set<String>,
    maxEntries: Int = REMOTE_BG_MAX_ENTRIES,
): List<RemoteBackgroundTask> {
    val overflow = tasks.size - maxEntries
    if (overflow <= 0) return tasks
    val toEvict = tasks
        .filter { it.id !in runningIds }
        .sortedBy { it.startedAtMillis }
        .take(overflow)
        .map { it.id }
        .toSet()
    if (toEvict.isEmpty()) return tasks
    return tasks.filter { it.id !in toEvict }
}
