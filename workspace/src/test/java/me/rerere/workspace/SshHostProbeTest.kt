package me.rerere.workspace

import me.rerere.workspace.ssh.SshHostProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 首次连接主机探针的纯逻辑回归（不连接远端）：
 * - POSIX / Windows 探针输出解析（分段、字段、缺失项）；
 * - 摘要拼装（缺失项省略、MB/GB 换算）；
 * - runProbe 的平台分发与 AUTO 回退（用假 exec，不走网络）。
 * Windows 远端 pwsh 包装、首次连接行为、缓存命中不再重跑，不在本测试覆盖范围。
 */
class SshHostProbeTest {

    private val posixSample = """
        Welcome to Ubuntu 22.04.3 LTS (motd 行应被忽略)
        ---HOSTPROBE-OS---
        Linux myhost 6.5.0-35-generic #35-Ubuntu SMP x86_64 GNU/Linux
        ---HOSTPROBE-OSRELEASE---
        PRETTY_NAME="Ubuntu 22.04.3 LTS"
        NAME="Ubuntu"
        VERSION_ID="22.04"
        ---HOSTPROBE-ARCH---
        x86_64
        ---HOSTPROBE-CPU---
        4
        ---HOSTPROBE-MEM---
        Mem:           7984         512        7472
        ---HOSTPROBE-DISK---
        Filesystem     1M-blocks  Used Available Use% Mounted on
        /dev/sda1         20480   1024     19456   5% /
        ---HOSTPROBE-TOOLS---
        /usr/bin/python3
        /usr/bin/node
        /usr/bin/docker
        /usr/bin/git
        ---HOSTPROBE-SHELL---
        /bin/bash
        ---HOSTPROBE-END---
    """.trimIndent()

    private val windowsSample = """
        ---HOSTPROBE-PS---
        7.4.1
        ---HOSTPROBE-OS---
        Microsoft Windows 11 Pro
        ---HOSTPROBE-ARCH---
        9
        ---HOSTPROBE-CPU---
        8
        ---HOSTPROBE-MEM---
        16384
        ---HOSTPROBE-DISK---
        20480
        ---HOSTPROBE-TOOLS---
        python.exe
        node.exe
        git.exe
        ---HOSTPROBE-END---
    """.trimIndent()

    @Test
    fun `posix probe parses a full Ubuntu output`() {
        val result = SshHostProbe.parsePosix(posixSample)!!
        assertEquals("LINUX", result.platform)
        assertEquals("Ubuntu 22.04.3 LTS", result.os)
        assertEquals("x86_64", result.arch)
        assertEquals("/bin/bash", result.shell)
        assertEquals(4, result.cpuCores)
        assertEquals(7984L, result.memoryMb)
        assertEquals(19456L, result.diskFreeMb)
        assertEquals(listOf("python3", "node", "docker", "git"), result.tools)
        assertFalse(result.isWindows)
        assertEquals(
            "Host: Linux x86_64, Ubuntu 22.04.3 LTS, 4 CPU, 7GB RAM, 19GB disk free, tools: python3, node, docker, git",
            result.summary(),
        )
    }

    @Test
    fun `posix probe falls back to uname when os-release is missing`() {
        val noRelease = posixSample.replace(
            "---HOSTPROBE-OSRELEASE---\nPRETTY_NAME=\"Ubuntu 22.04.3 LTS\"\nNAME=\"Ubuntu\"\nVERSION_ID=\"22.04\"\n",
            "",
        )
        val result = SshHostProbe.parsePosix(noRelease)!!
        assertEquals("Linux 6.5.0-35-generic", result.os)
        assertEquals("x86_64", result.arch)
    }

    @Test
    fun `posix probe returns null when there is no usable signal`() {
        assertNull(SshHostProbe.parsePosix(""))
        assertNull(SshHostProbe.parsePosix("some motd without any marker\n"))
        // 只有工具段、没有 uname/arch：信号不足，不算一次有效探针
        assertNull(SshHostProbe.parsePosix("---HOSTPROBE-TOOLS---\n/usr/bin/git\n---HOSTPROBE-END---\n"))
    }

    @Test
    fun `posix probe reads macOS memory from hw memsize`() {
        val mac = """
            ---HOSTPROBE-OS---
            Darwin MacBook 23.1.0 Darwin Kernel Version 23.1.0 arm64
            ---HOSTPROBE-ARCH---
            arm64
            ---HOSTPROBE-CPU---
            8
            ---HOSTPROBE-MEM---
            17179869184
            ---HOSTPROBE-DISK---
            Filesystem   1M-blocks Used Available Capacity Mounted on
            /dev/disk1s1    460000  100000    360000    22%    /
            ---HOSTPROBE-END---
        """.trimIndent()
        val result = SshHostProbe.parsePosix(mac)!!
        assertEquals("Darwin 23.1.0", result.os)
        assertEquals(16384L, result.memoryMb)
        assertEquals(360000L, result.diskFreeMb)
    }

    @Test
    fun `windows probe parses a full output`() {
        val result = SshHostProbe.parseWindows(windowsSample)!!
        assertEquals("WINDOWS", result.platform)
        assertEquals("Windows 11 Pro", result.os)
        assertEquals("x64", result.arch)
        assertEquals("PowerShell 7.4.1", result.shell)
        assertEquals(8, result.cpuCores)
        assertEquals(16384L, result.memoryMb)
        assertEquals(20480L, result.diskFreeMb)
        assertEquals(listOf("python.exe", "node.exe", "git.exe"), result.tools)
        assertTrue(result.isWindows)
        assertEquals(
            "Host: Windows x64, Windows 11 Pro, 8 CPU, 16GB RAM, 20GB disk free, tools: python.exe, node.exe, git.exe",
            result.summary(),
        )
    }

    @Test
    fun `windows probe returns null when there is no usable signal`() {
        assertNull(SshHostProbe.parseWindows(""))
        assertNull(SshHostProbe.parseWindows("---HOSTPROBE-TOOLS---\ngit.exe\n---HOSTPROBE-END---\n"))
    }

    @Test
    fun `summary omits missing fields and keeps MB under 1GB`() {
        val sparse = SshHostProbe.parsePosix(
            "---HOSTPROBE-OS---\nLinux tiny 6.1.0 x86_64 GNU/Linux\n---HOSTPROBE-ARCH---\nx86_64\n---HOSTPROBE-MEM---\nMem:            512          10         502\n---HOSTPROBE-END---\n",
        )!!
        assertEquals("Host: Linux x86_64, Linux 6.1.0, 512MB RAM", sparse.summary())
    }

    @Test
    fun `windows probe command is a well-formed EncodedCommand`() {
        val command = SshHostProbe.windowsProbeCommand()
        assertTrue(command.startsWith("powershell -NoProfile -NonInteractive -EncodedCommand "))
        val b64 = command.substringAfter("-EncodedCommand ").trim()
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_16LE)
        assertTrue(decoded.contains("[Console]::OutputEncoding=[Text.Encoding]::UTF8"))
        assertTrue(decoded.contains("Get-CimInstance Win32_OperatingSystem"))
        assertTrue(decoded.contains("---HOSTPROBE-OS---"))
    }

    @Test
    fun `runProbe on linux runs the posix script once`() {
        val seen = mutableListOf<String>()
        val result = SshHostProbe.runProbe(
            exec = { script, _ ->
                seen += script
                WorkspaceCommandResult(exitCode = 0, stdout = posixSample, stderr = "")
            },
            platform = "LINUX",
            execWrapsWindows = false,
        )
        assertEquals(1, seen.size)
        assertTrue(seen[0].contains("---HOSTPROBE-OS---"))
        assertEquals("Ubuntu 22.04.3 LTS", result?.os)
    }

    @Test
    fun `runProbe on windows sends the raw script when the exec wraps and the encoded form otherwise`() {
        val wrapping = mutableListOf<String>()
        SshHostProbe.runProbe(
            exec = { script, _ ->
                wrapping += script
                WorkspaceCommandResult(exitCode = 0, stdout = windowsSample, stderr = "")
            },
            platform = "WINDOWS",
            execWrapsWindows = true,
        )
        assertTrue(wrapping[0].contains("Get-CimInstance Win32_OperatingSystem"))
        assertFalse(wrapping[0].contains("-EncodedCommand"))

        val nonWrapping = mutableListOf<String>()
        val result = SshHostProbe.runProbe(
            exec = { script, _ ->
                nonWrapping += script
                WorkspaceCommandResult(exitCode = 0, stdout = windowsSample, stderr = "")
            },
            platform = "WINDOWS",
            execWrapsWindows = false,
        )
        assertTrue(nonWrapping[0].startsWith("powershell -NoProfile -NonInteractive -EncodedCommand "))
        assertEquals("Windows 11 Pro", result?.os)
    }

    @Test
    fun `runProbe on auto falls back to windows when posix yields nothing`() {
        val seen = mutableListOf<String>()
        val result = SshHostProbe.runProbe(
            exec = { script, _ ->
                seen += script
                if (script.contains("HOSTPROBE-OS") && !script.contains("powershell")) {
                    WorkspaceCommandResult(exitCode = 1, stdout = "", stderr = "uname: not found")
                } else {
                    WorkspaceCommandResult(exitCode = 0, stdout = windowsSample, stderr = "")
                }
            },
            platform = "AUTO",
            execWrapsWindows = false,
        )
        assertEquals(2, seen.size)
        assertEquals("WINDOWS", result?.platform)
        assertEquals("Windows 11 Pro", result?.os)
    }

    @Test
    fun `runProbe returns null when the exec throws or exits non-zero`() {
        val throwing = SshHostProbe.runProbe(
            exec = { _, _ -> throw RuntimeException("boom") },
            platform = "LINUX",
            execWrapsWindows = false,
        )
        assertNull(throwing)
        val failing = SshHostProbe.runProbe(
            exec = { _, _ -> WorkspaceCommandResult(exitCode = 1, stdout = "", stderr = "x") },
            platform = "WINDOWS",
            execWrapsWindows = false,
        )
        assertNull(failing)
    }
}
