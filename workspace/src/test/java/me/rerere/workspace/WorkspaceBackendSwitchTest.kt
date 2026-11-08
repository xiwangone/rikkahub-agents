package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class WorkspaceBackendSwitchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingRunner(
        private val tag: String,
        private val probeResult: ChrootProbeResult? = ChrootProbeResult(pass = 6, fail = 0, warn = 0, raw = "summary: pass=6 fail=0 warn=0", branch = "A"),
        private val simulateSuUnavailable: Boolean = false,
    ) : WorkspaceShellRunner {
        var executeCount = 0
        var unmountCount = 0

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            executeCount++
            if (simulateSuUnavailable) {
                return WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "chroot backend unavailable: su not accessible (Permission denied).",
                )
            }
            return WorkspaceCommandResult(
                exitCode = 0,
                stdout = "$tag:${context.command}",
                stderr = "",
            )
        }

        override fun start(context: WorkspaceShellContext): Process =
            throw UnsupportedOperationException("not used in unit test")

        override fun probe(context: WorkspaceShellContext): ChrootProbeResult? = probeResult

        override fun unmount(context: WorkspaceShellContext): WorkspaceCommandResult {
            unmountCount++
            context.linuxDir.listFiles()?.filter { it.name.startsWith(".chroot-") }?.forEach { it.deleteRecursively() }
            return WorkspaceCommandResult(exitCode = 0, stdout = "unmounted clean", stderr = "")
        }
    }

    @Test
    fun `migration from legacy workspaceChrootEnabled is idempotent`() {
        assertEquals(
            WorkspaceShellBackend.PROOT,
            WorkspaceShellBackend.fromPreference(rawBackend = null, legacyChrootEnabled = null),
        )
        assertEquals(
            WorkspaceShellBackend.PROOT,
            WorkspaceShellBackend.fromPreference(rawBackend = null, legacyChrootEnabled = false),
        )
        assertEquals(
            WorkspaceShellBackend.ROOT_CHROOT,
            WorkspaceShellBackend.fromPreference(rawBackend = null, legacyChrootEnabled = true),
        )
        for (mode in WorkspaceShellBackend.entries) {
            val first = WorkspaceShellBackend.fromPreference(rawBackend = mode.name, legacyChrootEnabled = true)
            val second = WorkspaceShellBackend.fromPreference(rawBackend = first.name, legacyChrootEnabled = false)
            assertEquals(mode, first)
            assertEquals(first, second)
        }
    }

    @Test
    fun `dispatch routes between proot root_chroot and disabled and falls back when runner missing`() {
        val baseDir = tmp.newFolder("workspaces-dispatch")
        val prootRunner = RecordingRunner("proot")
        val chrootRunner = RecordingRunner("chroot")
        var currentBackend = WorkspaceShellBackend.PROOT
        val fallbackReasons = mutableListOf<String>()

        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = prootRunner,
            chrootRunner = chrootRunner,
            backend = { currentBackend },
        ).apply {
            onBackendFallback = { fallbackReasons.add(it) }
        }
        manager.ensureWorkspace("ws1")

        // 1. Default PROOT
        val r1 = manager.executeCommand("ws1", "id")
        assertEquals("proot:id", r1.stdout)
        assertEquals(1, prootRunner.executeCount)
        assertEquals(0, chrootRunner.executeCount)
        assertNull(manager.lastFallbackReason)

        // 2. Switch to ROOT_CHROOT (3 consecutive calls - #23 regression check)
        currentBackend = WorkspaceShellBackend.ROOT_CHROOT
        repeat(3) { idx ->
            val r = manager.executeCommand("ws1", "cmd-$idx")
            assertEquals("chroot:cmd-$idx", r.stdout)
        }
        assertEquals(3, chrootRunner.executeCount)
        assertEquals(WorkspaceShellBackend.ROOT_CHROOT, manager.effectiveBackend("ws1"))

        // 3. Switch to DISABLED
        currentBackend = WorkspaceShellBackend.DISABLED
        val rDisabled = manager.executeCommand("ws1", "id")
        assertEquals(-1, rDisabled.exitCode)
        assertTrue(rDisabled.stderr.contains("disabled"))

        // 4. Null chrootRunner auto-fallback to PROOT
        val managerNoChroot = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = prootRunner,
            chrootRunner = null,
            backend = { WorkspaceShellBackend.ROOT_CHROOT },
        ).apply {
            onBackendFallback = { fallbackReasons.add(it) }
        }
        val rFallback = managerNoChroot.executeCommand("ws1", "whoami")
        assertEquals("proot:whoami", rFallback.stdout)
        assertNotNull(managerNoChroot.lastFallbackReason)
        assertEquals(WorkspaceShellBackend.PROOT, managerNoChroot.effectiveBackend("ws1"))
        assertTrue(fallbackReasons.any { it.contains("runner_unavailable") })
    }

    @Test
    fun `probe failure and su unavailable automatically fall back to proot with reason`() {
        val baseDir = tmp.newFolder("workspaces-probe-fail")
        val prootRunner = RecordingRunner("proot")
        val failedProbeRunner = RecordingRunner(
            tag = "chroot",
            probeResult = ChrootProbeResult(
                pass = 4,
                fail = 1,
                warn = 0,
                raw = "FAIL: chroot(2): Function not implemented",
                branch = "B",
            ),
        )
        val reasons = mutableListOf<String>()
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = prootRunner,
            chrootRunner = failedProbeRunner,
            backend = { WorkspaceShellBackend.ROOT_CHROOT },
        ).apply {
            onBackendFallback = { reasons.add(it) }
        }
        manager.ensureWorkspace("ws1")

        val res = manager.executeCommand("ws1", "uname -a")
        assertEquals("proot:uname -a", res.stdout)
        assertEquals(0, failedProbeRunner.executeCount)
        assertEquals(1, prootRunner.executeCount)
        assertEquals(WorkspaceShellBackend.PROOT, manager.effectiveBackend("ws1"))
        assertTrue(reasons.single().contains("probe_failed(branch=B, fail=1)"))

        // Mid-session su unavailable fallback
        val suLostRunner = RecordingRunner(
            tag = "chroot",
            simulateSuUnavailable = true,
        )
        val managerSuLost = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = prootRunner,
            chrootRunner = suLostRunner,
            backend = { WorkspaceShellBackend.ROOT_CHROOT },
        )
        val res2 = managerSuLost.executeCommand("ws1", "ls")
        assertEquals("proot:ls", res2.stdout)
        assertEquals(WorkspaceShellBackend.PROOT, managerSuLost.effectiveBackend("ws1"))
        assertTrue(managerSuLost.lastFallbackReason.orEmpty().contains("su_unavailable"))
    }

    @Test
    fun `switching between proot and root_chroot does not modify rootfs or workspace files`() {
        val baseDir = tmp.newFolder("workspaces-lossless")
        val prootRunner = RecordingRunner("proot")
        val chrootRunner = RecordingRunner("chroot")
        var currentBackend = WorkspaceShellBackend.PROOT

        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = prootRunner,
            chrootRunner = chrootRunner,
            backend = { currentBackend },
        )
        manager.ensureWorkspace("ws1")

        val filesRoot = manager.filesDir("ws1")
        val linuxRoot = manager.linuxDir("ws1")
        File(filesRoot, "project").mkdirs()
        val userFile = File(filesRoot, "project/main.py").apply { writeText("print('hello world')\n") }
        File(linuxRoot, "etc").mkdirs()
        val osRelease = File(linuxRoot, "etc/os-release").apply { writeText("ID=alpine\nVERSION_ID=3.21.3\n") }
        val resolvConf = File(linuxRoot, "etc/resolv.conf").apply { writeText("nameserver 1.1.1.1\n") }
        // Simulate leftover state marker that unmountChroot must clean without touching real files
        val staleMarker = File(linuxRoot, ".chroot-mounted").apply { writeText("1") }

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        val beforeUserHash = sha256(userFile)
        val beforeOsHash = sha256(osRelease)
        val beforeResolvHash = sha256(resolvConf)

        // Switch PROOT -> ROOT_CHROOT -> PROOT
        currentBackend = WorkspaceShellBackend.ROOT_CHROOT
        assertEquals("chroot:pwd", manager.executeCommand("ws1", "pwd").stdout)

        currentBackend = WorkspaceShellBackend.PROOT
        manager.unmountChroot("ws1")
        manager.resetBackendState("ws1")
        assertEquals("proot:pwd", manager.executeCommand("ws1", "pwd").stdout)

        assertEquals(beforeUserHash, sha256(userFile))
        assertEquals(beforeOsHash, sha256(osRelease))
        assertEquals(beforeResolvHash, sha256(resolvConf))
        assertTrue(!staleMarker.exists())
        assertEquals(1, chrootRunner.unmountCount)
    }

    @Test
    fun `chroot-run script uses unshare -m with make-rprivate and avoids proc mounts string matching`() {
        val candidates = listOf(
            File("../deps/chroot/chroot-run.sh"),
            File("deps/chroot/chroot-run.sh"),
        )
        val script = candidates.firstOrNull { it.isFile } ?: return
        val codeLines = script.readLines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertTrue(codeLines.contains("unshare -m"))
        assertTrue(!codeLines.contains("--propagation"))
        val content = codeLines
        assertTrue(content.contains("mount --make-rprivate /"))
        assertTrue(content.contains("trap 'do_unmount_quiet' EXIT INT TERM"))
        assertTrue(!content.contains("is_mounted()"))
    }
}
