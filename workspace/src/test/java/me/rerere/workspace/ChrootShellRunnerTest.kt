package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChrootShellRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRunner(scriptDir: File = tmp.root): ChrootShellRunner =
        ChrootShellRunner(
            scriptFile = File(scriptDir, "chroot-run.sh"),
            probeFile = File(scriptDir, "chroot-probe.sh"),
        )

    private fun newContext(linuxDir: File): WorkspaceShellContext =
        WorkspaceShellContext(
            root = "test",
            command = "echo hi",
            cwd = linuxDir.absolutePath,
            filesDir = linuxDir,
            linuxDir = linuxDir,
            tempDir = linuxDir,
            workingDir = linuxDir,
            timeoutMillis = 1_000,
        )

    @Test
    fun `suCommand embeds script rootfs and inner command`() {
        val base = "/data/user/0/excp.rikkahub.agents/files"
        val script = File("$base/chroot-run.sh")
        val linux = File("$base/workspaces/abc123/linux")
        val runner = ChrootShellRunner(script, File("$base/chroot-probe.sh"))
        // 期望值用 absolutePath 现算，别硬编码正斜杠：Win/Unix 下一致（用例如按路径字面比会在 Windows 上假失败）
        assertEquals(
            "sh ${script.absolutePath} ${linux.absolutePath} exec /bin/sh /.chroot-exec-abc",
            runner.suCommand(linux, "exec /bin/sh /.chroot-exec-abc"),
        )
    }

    @Test
    fun `constructor rejects unsafe script paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChrootShellRunner(File("/data/data/app dir/chroot-run.sh"), File("/data/data/app/chroot-probe.sh"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChrootShellRunner(File("/data/data/app/chroot-run.sh"), File("/data/data/app/x;y/chroot-probe.sh"))
        }
    }

    @Test
    fun `suCommand rejects unsafe linuxDir`() {
        val runner = newRunner()
        assertThrows(IllegalArgumentException::class.java) {
            runner.suCommand(File("/data/data/app/workspaces/a b/linux"), "exec echo")
        }
    }

    @Test
    fun `writeCommandFile writes unique files under linuxDir`() {
        val runner = newRunner()
        val linux = tmp.newFolder("linux")
        val ctx = newContext(linux)

        val f1 = runner.writeCommandFile(ctx)
        val f2 = runner.writeCommandFile(ctx.copy(command = "echo two"))

        assertTrue(f1.parentFile == linux && f1.name.startsWith(".chroot-exec-"))
        assertEquals("echo hi", f1.readText())
        assertEquals("echo two", f2.readText())
        assertTrue(f1 != f2)
        f1.delete()
        f2.delete()
    }

    /**
     * 回归：exec 侧必须用**实际写入的文件名**（带 UUID），不能用固定常量。
     * 写/读不一致时 chroot 内找不到命令文件，每条命令都以 cannot open 失败后回退 proot。
     */
    @Test
    fun `execCommandFor uses the file actually written`() {
        val runner = newRunner()
        val linux = tmp.newFolder("linux-exec")
        val written = runner.writeCommandFile(newContext(linux))

        val inner = runner.execCommandFor(written)

        assertEquals("exec /bin/sh /${written.name}", inner)
        assertTrue(inner.endsWith("/${written.name}"))
        assertTrue(!inner.endsWith("/${ChrootShellRunner.COMMAND_FILE}"))
        written.delete()
    }

    @Test
    fun `probe result supported when no failures`() {
        val result = ChrootProbeResult(pass = 6, fail = 0, warn = 1, raw = "summary: pass=6 fail=0 warn=1")
        assertTrue(result.supported)
        assertTrue(!ChrootProbeResult(pass = 5, fail = 1, warn = 0, raw = "").supported)
    }
}
