package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 覆盖 [terminateProot] / [terminateProotByPidFile] 的兜底路径。
 *
 * 真实的孤儿进程清理（向被跟踪进程发信号）需要 Android + proot 环境，JVM 单测
 * 覆盖不到；这里只钉住不依赖信号发送的分支：pidFile 缺失/非法/
 * 进程已退出时不得抛异常、不得误杀。
 */
class ProotTerminationTest {

    @Test
    fun `missing pidFile falls back to destroyForcibly`() {
        val process = ProcessBuilder("sh", "-c", "sleep 30").start()
        try {
            terminateProot(process, File("/tmp/does-not-exist-rikkahub-test.pid"))
            assertFalse(process.isAlive)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `terminateProot with exited process and stale pidFile does not throw`() {
        val process = ProcessBuilder("sh", "-c", "true").start()
        process.waitFor()
        val pidFile = File.createTempFile("rikkahub-proot-test", ".pid")
        try {
            pidFile.writeText("1999999999 1999999998")
            terminateProot(process, pidFile)
            assertFalse(process.isAlive)
        } finally {
            pidFile.delete()
        }
    }

    @Test
    fun `byPidFile returns false when pidFile is missing`() {
        assertFalse(terminateProotByPidFile(File("/tmp/does-not-exist-rikkahub-test.pid")))
    }

    @Test
    fun `byPidFile returns false when proot pid is gone`() {
        val pidFile = File.createTempFile("rikkahub-proot-test", ".pid")
        try {
            pidFile.writeText("1999999999 1999999998")
            assertFalse(terminateProotByPidFile(pidFile))
        } finally {
            pidFile.delete()
        }
    }

    @Test
    fun `byPidFile returns false for malformed pidFile`() {
        val pidFile = File.createTempFile("rikkahub-proot-test", ".pid")
        try {
            pidFile.writeText("not-a-pid")
            assertFalse(terminateProotByPidFile(pidFile))
        } finally {
            pidFile.delete()
        }
    }
}
