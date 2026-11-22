package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class HostCpuTest {
    @Test
    fun `intel cpuinfo wins over reported arm arch`() {
        // 虚拟化环境：/proc/cpuinfo 报真 CPU，os.arch 可能被伪装成另一种 ABI。
        val cpuinfo =
            """
            processor	: 0
            vendor_id	: GenuineIntel
            cpu family	: 6
            model		: 158
            model name	: Intel(R) Core(TM) i7-8700K CPU @ 3.70GHz
            stepping	: 10
            cpu MHz		: 3700.000
            cache size	: 12288 KB
            """.trimIndent()
        assertEquals(
            HostCpuArchitecture.X86_64,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "aarch64"),
        )
    }

    @Test
    fun `amd cpuinfo is x86`() {
        val cpuinfo =
            """
            processor	: 0
            vendor_id	: AuthenticAMD
            cpu family	: 25
            model		: 33
            model name	: AMD Ryzen 7 5800X 8-Core Processor
            stepping	: 0
            cpu MHz		: 3800.000
            cache size	: 512 KB
            """.trimIndent()
        assertEquals(
            HostCpuArchitecture.X86_64,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "x86_64"),
        )
    }

    @Test
    fun `model name alone identifies x86 when vendor id is absent`() {
        val cpuinfo =
            """
            processor	: 0
            model name	: Intel(R) Xeon(R) CPU E5-2673 v4 @ 2.30GHz
            cpu MHz		: 2300.000
            """.trimIndent()
        assertEquals(
            HostCpuArchitecture.X86_64,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "aarch64"),
        )
    }

    @Test
    fun `arm64 cpuinfo wins over reported x86 arch`() {
        val cpuinfo =
            """
            processor	: 0
            BogoMIPS	: 38.40
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32
            CPU implementer	: 0x41
            CPU architecture: 8
            CPU variant	: 0x0
            CPU part	: 0xd03
            CPU revision	: 4
            """.trimIndent()
        assertEquals(
            HostCpuArchitecture.ARM64,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "x86_64"),
        )
    }

    @Test
    fun `cpu architecture 8 alone identifies arm64`() {
        val cpuinfo =
            """
            processor	: 0
            CPU architecture: 8
            """.trimIndent()
        assertEquals(
            HostCpuArchitecture.ARM64,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "x86_64"),
        )
    }

    @Test
    fun `empty cpuinfo falls back to reported arch`() {
        assertEquals(
            HostCpuArchitecture.ARM64,
            detectHostCpuArchitecture("", reportedArch = "aarch64"),
        )
        assertEquals(
            HostCpuArchitecture.X86_64,
            detectHostCpuArchitecture("", reportedArch = "x86_64"),
        )
    }

    @Test
    fun `unreadable cpuinfo falls back to reported arch`() {
        assertEquals(
            HostCpuArchitecture.ARM64,
            detectHostCpuArchitecture(null, reportedArch = "arm64"),
        )
        assertEquals(
            HostCpuArchitecture.X86_64,
            detectHostCpuArchitecture(null, reportedArch = "amd64"),
        )
    }

    @Test
    fun `unrecognized cpuinfo and arch is unknown`() {
        assertEquals(
            HostCpuArchitecture.UNKNOWN,
            detectHostCpuArchitecture("garbage without markers", reportedArch = "riscv64"),
        )
        assertEquals(
            HostCpuArchitecture.UNKNOWN,
            detectHostCpuArchitecture(null, reportedArch = ""),
        )
    }
}
