package me.rerere.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

private const val KILL_WAIT_MS = 2_000L
private const val KILL_ROUNDS = 10
private const val KILL_ROUND_WAIT_MS = 200L
private const val PID_WAIT_MS = 1_000L

/**
 * 终止 PRoot 及其中运行的所有进程, 阻塞到 PRoot 退出。
 *
 * PRoot 被 SIGKILL 后它跟踪的进程会脱离跟踪继续运行, 所以不能直接 [Process.destroyForcibly];
 * 也不能靠 PRoot 收到 SIGQUIT 后清理: 它从应用进程继承了屏蔽 SIGQUIT 的信号掩码, 信号一直挂起。
 * 做法是在 PRoot 还活着时 SIGKILL 它跟踪的所有进程, 最后一个退出后 PRoot 自己退出;
 * 命令可能正在派生新进程, 所以多扫几轮。
 * [graceMillis] 大于 0 时先给命令派生的进程发 SIGTERM, 留出这段时间让它们正常退出。
 *
 * 进程号由 shell 启动时写入 [pidFile] (`$$ $PPID`), 即 shell 自身与 PRoot 的进程号。
 */
internal fun terminateProot(process: Process, pidFile: File, graceMillis: Long = 0) {
    val pids = readPids(pidFile) { process.isAlive }
    if (pids == null) {
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        return
    }
    val (shellPid, prootPid) = pids
    killTraced(prootPid, shellPid, graceMillis) { timeoutMs ->
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    }
    if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
    }
}

/**
 * 后台取消路径：只有 [pidFile]、没有 Process 句柄时按 pidFile 终止整组进程。
 *
 * 返回 true 表示 pidFile 可读且已尝试终止；false 表示无事可做（文件不存在/内容非法/
 * PRoot 已退出）。直接杀 PRoot 前先核对 `/proc/<pid>/cmdline` 含 proot，避免 pid
 * 复用后误杀无关进程。
 */
internal fun terminateProotByPidFile(pidFile: File, graceMillis: Long = 0): Boolean {
    val pids = readPidFile(pidFile) ?: return false
    val (shellPid, prootPid) = pids
    if (!isProotProcess(prootPid)) return false
    killTraced(prootPid, shellPid, graceMillis) { timeoutMs -> waitForPidExit(prootPid, timeoutMs) }
    if (isPidAlive(prootPid)) {
        signal(prootPid, OsConstants.SIGKILL)
    }
    return true
}

/** 先给被跟踪进程发 SIGTERM（有宽限期时），再多轮 SIGKILL，直到 [waitForExit] 报告退出。 */
private fun killTraced(
    prootPid: Int,
    shellPid: Int,
    graceMillis: Long,
    waitForExit: (Long) -> Boolean,
) {
    if (graceMillis > 0) {
        // 命令本身先收到 SIGTERM; 直接结束 shell 会让 --kill-on-exit 立刻 SIGKILL 其余进程
        (traceesOf(prootPid) - shellPid).ifEmpty { listOf(shellPid) }.forEach { signal(it, OsConstants.SIGTERM) }
        if (waitForExit(graceMillis)) return
    }
    repeat(KILL_ROUNDS) {
        traceesOf(prootPid).forEach { signal(it, OsConstants.SIGKILL) }
        if (waitForExit(KILL_ROUND_WAIT_MS)) return
    }
}

// 刚启动时 shell 可能还没写入进程号, 稍等片刻
private fun readPids(pidFile: File, isAlive: () -> Boolean): Pair<Int, Int>? {
    val deadline = System.currentTimeMillis() + PID_WAIT_MS
    while (true) {
        val pids = readPidFile(pidFile)
        if (pids != null) return pids
        if (!isAlive() || System.currentTimeMillis() > deadline) return null
        Thread.sleep(50)
    }
}

/** 不等待：只解析一次 pidFile 内容，读不到或格式不对返回 null。 */
internal fun readPidFile(pidFile: File): Pair<Int, Int>? {
    val pids = runCatching { pidFile.readText() }.getOrNull()
        ?.trim()
        ?.split(' ')
        ?.mapNotNull { it.toIntOrNull() }
    if (pids != null && pids.size == 2) return pids[0] to pids[1]
    return null
}

internal fun isPidAlive(pid: Int): Boolean = File("/proc/$pid").exists()

private fun waitForPidExit(pid: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (!isPidAlive(pid)) return true
        Thread.sleep(20)
    }
    return !isPidAlive(pid)
}

/** `/proc/<pid>/cmdline` 首个参数含 proot 才认，避免 pid 复用后误杀无关进程。 */
private fun isProotProcess(pid: Int): Boolean {
    val cmdline = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull() ?: return false
    val argv0 = cmdline.decodeToString().substringBefore('\u0000')
    return "proot" in argv0
}

/** 被 [tracerPid] 跟踪的进程, 即 PRoot 内运行的所有进程 (包括脱离了进程树的守护进程) */
private fun traceesOf(tracerPid: Int): List<Int> =
    File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val tracer = runCatching {
            File(dir, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            }
        }.getOrNull()
        pid.takeIf { tracer == tracerPid }
    }

private fun signal(pid: Int, signal: Int) {
    try {
        Os.kill(pid, signal)
    } catch (_: ErrnoException) {
        // 进程已经退出
    }
}
