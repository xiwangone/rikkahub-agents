package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class ShellDnsConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `normalizeMode is idempotent and defaults unknown or null values to auto`() {
        assertEquals(ShellDnsConfig.MODE_AUTO, ShellDnsConfig.normalizeMode(null))
        assertEquals(ShellDnsConfig.MODE_AUTO, ShellDnsConfig.normalizeMode(""))
        assertEquals(ShellDnsConfig.MODE_AUTO, ShellDnsConfig.normalizeMode("   "))
        assertEquals(ShellDnsConfig.MODE_AUTO, ShellDnsConfig.normalizeMode("system"))
        assertEquals(ShellDnsConfig.MODE_AUTO, ShellDnsConfig.normalizeMode("AUTO"))
        assertEquals(ShellDnsConfig.MODE_CUSTOM, ShellDnsConfig.normalizeMode("custom"))
        assertEquals(ShellDnsConfig.MODE_CUSTOM, ShellDnsConfig.normalizeMode("  CUSTOM \n"))

        // Idempotency across repeated migrations
        val once = ShellDnsConfig.normalizeMode(null)
        val twice = ShellDnsConfig.normalizeMode(once)
        assertEquals(ShellDnsConfig.MODE_AUTO, once)
        assertEquals(once, twice)

        val customOnce = ShellDnsConfig.normalizeMode("Custom")
        val customTwice = ShellDnsConfig.normalizeMode(customOnce)
        assertEquals(ShellDnsConfig.MODE_CUSTOM, customOnce)
        assertEquals(customOnce, customTwice)
    }

    @Test
    fun `parseServers splits commas newlines and spaces and deduplicates preserving order`() {
        val raw = """
            223.5.5.5, 119.29.29.29
            1.1.1.1   8.8.8.8
            223.5.5.5, 2400:3200::1, 2001:4860:4860::8888
        """.trimIndent()
        val parsed = ShellDnsConfig.parseServers(raw)
        assertEquals(
            listOf(
                "223.5.5.5",
                "119.29.29.29",
                "1.1.1.1",
                "8.8.8.8",
                "2400:3200::1",
                "2001:4860:4860::8888"
            ),
            parsed
        )
    }

    @Test
    fun `parseServers rejects invalid loopback and unspecified addresses`() {
        val raw = "127.0.0.1, 127.0.53.53, 0.0.0.0, ::1, ::, 999.1.1.1, not-an-ip, 01.1.1.1, 8.8.4.4"
        val parsed = ShellDnsConfig.parseServers(raw)
        assertEquals(listOf("8.8.4.4"), parsed)
        assertFalse(ShellDnsConfig.isValidDnsIp("127.0.0.1"))
        assertFalse(ShellDnsConfig.isValidDnsIp("::1"))
        assertTrue(ShellDnsConfig.isValidDnsIp("2400:3200:baba::1"))
    }

    @Test
    fun `effectiveServers returns empty list in auto mode or when custom list has no valid IPs`() {
        assertEquals(
            emptyList<String>(),
            ShellDnsConfig.effectiveServers(ShellDnsConfig.MODE_AUTO, "223.5.5.5, 1.1.1.1")
        )
        assertEquals(
            emptyList<String>(),
            ShellDnsConfig.effectiveServers(null, "223.5.5.5")
        )
        assertEquals(
            emptyList<String>(),
            ShellDnsConfig.effectiveServers(ShellDnsConfig.MODE_CUSTOM, "127.0.0.1, invalid")
        )
        assertEquals(
            listOf("223.5.5.5", "1.1.1.1"),
            ShellDnsConfig.effectiveServers(ShellDnsConfig.MODE_CUSTOM, "223.5.5.5\n1.1.1.1")
        )
    }

    @Test
    fun `RootfsPatcher replaces broken symlink and applies custom and auto DNS`() {
        val linuxDir = tmp.newFolder("linux")
        val etcDir = File(linuxDir, "etc").apply { mkdirs() }
        val resolv = File(etcDir, "resolv.conf")
        // Create a dangling symlink at /etc/resolv.conf -> /run/systemd/resolve/stub-resolv.conf
        Files.createSymbolicLink(resolv.toPath(), Paths.get("/nonexistent/systemd/resolv.conf"))
        assertTrue(Files.isSymbolicLink(resolv.toPath()))

        // 1. Custom mode overwrites broken symlink with real file containing custom DNS
        val patcherWithSystemDns = RootfsPatcher(systemDnsProvider = { listOf("127.0.0.1", "192.168.1.1", "::1") })
        patcherWithSystemDns.patch(
            linuxDir,
            RootfsPatchOptions(nameservers = listOf("9.9.9.9", "2400:3200::1")),
        )
        assertFalse(Files.isSymbolicLink(resolv.toPath()))
        assertTrue(resolv.isFile)
        val customText = resolv.readText()
        assertTrue(customText.contains("nameserver 9.9.9.9"))
        assertTrue(customText.contains("nameserver 2400:3200::1"))

        // 2. Switching back to Auto mode replaces custom DNS with live non-loopback system DNS
        patcherWithSystemDns.patch(linuxDir, RootfsPatchOptions(nameservers = emptyList()))
        val autoSysText = resolv.readText()
        assertTrue(autoSysText.contains("nameserver 192.168.1.1"))
        assertFalse(autoSysText.contains("127.0.0.1"))
        assertFalse(autoSysText.contains("9.9.9.9"))

        // 3. Auto mode with empty system DNS falls back to DEFAULT_DNS_SERVERS when coming from custom
        patcherWithSystemDns.patch(linuxDir, RootfsPatchOptions(nameservers = listOf("1.0.0.1")))
        val patcherEmptySys = RootfsPatcher(systemDnsProvider = { emptyList() })
        patcherEmptySys.patch(linuxDir, RootfsPatchOptions(nameservers = emptyList()))
        val fallbackText = resolv.readText()
        assertTrue(fallbackText.contains("nameserver 223.5.5.5"))
        assertTrue(fallbackText.contains("nameserver 119.29.29.29"))
    }

    @Test
    fun `ChrootShellRunner suCommand injects CHROOT_DNS when custom dnsServers provided`() {
        val base = "/data/user/0/excp.rikkahub.agents/files"
        val script = File("$base/chroot-run.sh")
        val linux = File("$base/workspaces/abc123/linux")
        val runner = ChrootShellRunner(script, File("$base/chroot-probe.sh"))

        assertEquals(
            "CHROOT_DNS='223.5.5.5 2400:3200::1' sh ${script.absolutePath} ${linux.absolutePath} exec setsid /bin/sh /.chroot-exec-1",
            runner.suCommand(
                linux,
                "exec setsid /bin/sh /.chroot-exec-1",
                listOf("223.5.5.5", "127.0.0.1", "2400:3200::1"),
            ),
        )
    }
}
