package me.rerere.workspace

import me.rerere.workspace.ssh.REMOTE_BG_LOG_TAIL_BYTES
import me.rerere.workspace.ssh.RemoteBackgroundTask
import me.rerere.workspace.ssh.buildKillCommand
import me.rerere.workspace.ssh.buildLaunchCommand
import me.rerere.workspace.ssh.buildStatusCommand
import me.rerere.workspace.ssh.evictOldestExited
import me.rerere.workspace.ssh.parseKillOutput
import me.rerere.workspace.ssh.parseLaunchedPid
import me.rerere.workspace.ssh.parseStatusOutput
import me.rerere.workspace.ssh.remoteBgTaskDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程后台任务纯逻辑回归（不连接远端）：
 * - 启动命令形态：建目录、setsid/nohup 回退、pidfile 等待、单引号转义；
 * - 启动 pid 解析（正常 / 空输出 / 噪声行）；
 * - 状态查询拼装与解析（运行中 / 已退出带退出码 / 段外欢迎信息忽略 / 多任务分段 / 缺段）；
 * - 杀进程命令形态与输出解析；
 * - 记录淘汰（只淘汰已退出的最旧记录，运行中的永不淘汰）。
 * SSH 实连、断线重连、App 重启后认领不在本测试覆盖范围。
 */
class RemoteBackgroundTaskTest {

    private fun task(id: String, pid: Int, startedAt: Long) = RemoteBackgroundTask(
        id = id,
        workspaceId = "ws1",
        command = "sleep 60",
        cwd = "",
        pid = pid,
        logPath = "/remote/ws/.rikkahub-bg/$id/log",
        pidPath = "/remote/ws/.rikkahub-bg/$id/pid",
        exitPath = "/remote/ws/.rikkahub-bg/$id/exit",
        startedAtMillis = startedAt,
    )

    @Test
    fun `task dir is a hidden subdir of the workspace dir`() {
        assertEquals(
            "/remote/ws/.rikkahub-bg/rbg_ab12cd34",
            remoteBgTaskDir("/remote/ws", "rbg_ab12cd34"),
        )
    }

    @Test
    fun `launch command creates dir detaches and waits for pidfile`() {
        val cmd = buildLaunchCommand(
            taskDir = "/remote/ws/.rikkahub-bg/rbg_1",
            pidPath = "/remote/ws/.rikkahub-bg/rbg_1/pid",
            exitPath = "/remote/ws/.rikkahub-bg/rbg_1/exit",
            logPath = "/remote/ws/.rikkahub-bg/rbg_1/log",
            command = "python3 server.py",
        )
        assertTrue(cmd.contains("mkdir -p -- '/remote/ws/.rikkahub-bg/rbg_1'"))
        assertTrue(cmd.contains("command -v setsid"))
        assertTrue(cmd.contains("setsid nohup"))
        // 内层脚本整体又包了一层单引号，里面的路径引号被转义成 '"'"'，
        // 故只断言语义片段，不按字面单引号匹配。
        assertTrue(cmd.contains("echo \$\$ > "))
        assertTrue(cmd.contains("/remote/ws/.rikkahub-bg/rbg_1/pid"))
        assertTrue(cmd.contains("python3 server.py"))
        assertTrue(cmd.contains("echo \$? > "))
        assertTrue(cmd.contains("/remote/ws/.rikkahub-bg/rbg_1/exit"))
        assertTrue(cmd.contains("> '/remote/ws/.rikkahub-bg/rbg_1/log' 2>&1 </dev/null &"))
        assertTrue(cmd.contains("[ -s '/remote/ws/.rikkahub-bg/rbg_1/pid' ]"))
        assertTrue(cmd.endsWith("cat '/remote/ws/.rikkahub-bg/rbg_1/pid'"))
    }

    @Test
    fun `launch command escapes single quotes in the user command`() {
        val cmd = buildLaunchCommand(
            taskDir = "/d",
            pidPath = "/d/pid",
            exitPath = "/d/exit",
            logPath = "/d/log",
            command = "echo 'don't'",
        )
        // 用户命令里的单引号必须转义成 '"'"'，否则内层 sh -c 的引号提前闭合。
        assertFalse(cmd.contains("echo 'don't'"))
        assertTrue(cmd.contains("'\"'\"'"))
    }

    @Test
    fun `parse launched pid takes the last non blank line`() {
        assertEquals(12345, parseLaunchedPid("12345\n"))
        assertEquals(12345, parseLaunchedPid("noise\n12345\n"))
        assertEquals(12345, parseLaunchedPid("12345\n\n"))
        assertNull(parseLaunchedPid(""))
        assertNull(parseLaunchedPid("   \n"))
        assertNull(parseLaunchedPid("not-a-pid\n"))
    }

    @Test
    fun `status command probes each task and tails its log`() {
        val cmd = buildStatusCommand(listOf(task("rbg_1", 111, 1000), task("rbg_2", 222, 2000)))
        assertTrue(cmd.contains("__RIKKAHUB_BG_TASK_BEGIN__ rbg_1"))
        assertTrue(cmd.contains("__RIKKAHUB_BG_TASK_BEGIN__ rbg_2"))
        assertTrue(cmd.contains("kill -0 111 2>/dev/null"))
        assertTrue(cmd.contains("kill -0 222 2>/dev/null"))
        assertTrue(cmd.contains("tail -c $REMOTE_BG_LOG_TAIL_BYTES '/remote/ws/.rikkahub-bg/rbg_1/log'"))
        assertTrue(cmd.contains("BG_EXITCODE=\$(cat '/remote/ws/.rikkahub-bg/rbg_2/exit' 2>/dev/null)"))
        assertTrue(cmd.contains("BG_LOGSIZE=\$(wc -c < '/remote/ws/.rikkahub-bg/rbg_1/log' 2>/dev/null | tr -d ' ')"))
    }

    @Test
    fun `status command reports invalid pid as exited without probing`() {
        val cmd = buildStatusCommand(listOf(task("rbg_bad", -1, 1000)))
        assertTrue(cmd.contains("BG_STATUS=EXITED"))
        assertFalse(cmd.contains("kill -0"))
    }

    @Test
    fun `parse status output handles running and exited tasks`() {
        val stdout = """
            Welcome to Ubuntu 22.04.3 LTS
            __RIKKAHUB_BG_TASK_BEGIN__ rbg_1
            BG_STATUS=RUNNING
            BG_EXITCODE=
            BG_LOGSIZE=18
            __RIKKAHUB_BG_LOG_BEGIN__
            serving on :8000
            __RIKKAHUB_BG_LOG_END__
            __RIKKAHUB_BG_TASK_END__
            __RIKKAHUB_BG_TASK_BEGIN__ rbg_2
            BG_STATUS=EXITED
            BG_EXITCODE=3
            BG_LOGSIZE=34
            __RIKKAHUB_BG_LOG_BEGIN__
            Traceback (most recent call last)
            __RIKKAHUB_BG_LOG_END__
            __RIKKAHUB_BG_TASK_END__
        """.trimIndent()
        val parsed = parseStatusOutput(stdout)
        assertEquals(2, parsed.size)
        val running = parsed.getValue("rbg_1")
        assertTrue(running.running)
        assertNull(running.exitCode)
        assertEquals(18, running.logSizeBytes)
        assertTrue(running.logTail.contains("serving on :8000"))
        val exited = parsed.getValue("rbg_2")
        assertFalse(exited.running)
        assertEquals(3, exited.exitCode)
        assertEquals(34, exited.logSizeBytes)
        assertTrue(exited.logTail.contains("Traceback"))
        // 段外欢迎信息不进日志。
        assertFalse(running.logTail.contains("Welcome"))
    }

    @Test
    fun `parse status output ignores tasks without a block`() {
        val stdout = """
            __RIKKAHUB_BG_TASK_BEGIN__ rbg_1
            BG_STATUS=EXITED
            BG_EXITCODE=0
            __RIKKAHUB_BG_LOG_BEGIN__
            done
            __RIKKAHUB_BG_LOG_END__
            __RIKKAHUB_BG_TASK_END__
        """.trimIndent()
        val parsed = parseStatusOutput(stdout)
        assertEquals(1, parsed.size)
        assertNull(parsed["rbg_missing"])
    }

    @Test
    fun `parse status output keeps the log even when the exit file is missing`() {
        val stdout = """
            __RIKKAHUB_BG_TASK_BEGIN__ rbg_1
            BG_STATUS=EXITED
            BG_EXITCODE=
            __RIKKAHUB_BG_LOG_BEGIN__
            killed
            __RIKKAHUB_BG_LOG_END__
            __RIKKAHUB_BG_TASK_END__
        """.trimIndent()
        val parsed = parseStatusOutput(stdout)
        val probe = parsed.getValue("rbg_1")
        assertFalse(probe.running)
        assertNull(probe.exitCode)
        assertTrue(probe.logTail.contains("killed"))
    }

    @Test
    fun `kill command kills the process group then falls back to single pid`() {
        val cmd = buildKillCommand(1234, "/remote/ws/.rikkahub-bg/rbg_1")
        assertTrue(cmd.contains("kill -TERM -1234 2>/dev/null || kill -TERM 1234 2>/dev/null"))
        assertTrue(cmd.contains("kill -KILL -1234 2>/dev/null || kill -KILL 1234 2>/dev/null"))
        assertTrue(cmd.contains("echo 'BG_KILL=OK'"))
        assertTrue(cmd.contains("rm -rf -- '/remote/ws/.rikkahub-bg/rbg_1'"))
    }

    @Test
    fun `kill command without a task dir does not remove anything`() {
        val cmd = buildKillCommand(1234, null)
        assertFalse(cmd.contains("rm -rf"))
        assertTrue(cmd.contains("echo 'BG_KILL=OK'"))
    }

    @Test
    fun `kill command with invalid pid never signals a process group`() {
        val cmd = buildKillCommand(-1, "/remote/ws/.rikkahub-bg/rbg_1")
        assertFalse(cmd.contains("kill -TERM"))
        assertFalse(cmd.contains("kill -KILL"))
        assertFalse(cmd.contains("kill -0"))
        assertTrue(cmd.contains("echo 'BG_KILL=OK'"))
        assertTrue(cmd.contains("rm -rf -- '/remote/ws/.rikkahub-bg/rbg_1'"))
    }

    @Test
    fun `parse kill output only accepts the OK marker`() {
        assertTrue(parseKillOutput("BG_KILL=OK\n"))
        assertTrue(parseKillOutput("some noise\nBG_KILL=OK\n"))
        assertFalse(parseKillOutput("BG_KILL=FAILED\n"))
        assertFalse(parseKillOutput(""))
        assertFalse(parseKillOutput("BG_KILL=OKAY\n"))
    }

    @Test
    fun `eviction only drops the oldest exited records past the cap`() {
        val tasks = listOf(
            task("rbg_old_exited", 11, 1000),
            task("rbg_running", 22, 2000),
            task("rbg_mid_exited", 33, 3000),
            task("rbg_new_exited", 44, 4000),
        )
        val kept = evictOldestExited(tasks, runningIds = setOf("rbg_running"), maxEntries = 2)
        assertEquals(listOf("rbg_running", "rbg_new_exited"), kept.map { it.id })
    }

    @Test
    fun `eviction keeps running tasks even when over the cap`() {
        val tasks = listOf(
            task("rbg_r1", 11, 1000),
            task("rbg_r2", 22, 2000),
        )
        val kept = evictOldestExited(tasks, runningIds = setOf("rbg_r1", "rbg_r2"), maxEntries = 1)
        assertEquals(listOf("rbg_r1", "rbg_r2"), kept.map { it.id })
    }

    @Test
    fun `eviction is a no-op within the cap`() {
        val tasks = listOf(task("rbg_1", 11, 1000))
        assertEquals(tasks, evictOldestExited(tasks, runningIds = emptySet()))
    }
}
