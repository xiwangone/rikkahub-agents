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
    fun `armv7 cpuinfo with implementer is not arm64`() {
        // 32 位 ARM 的 cpuinfo 同样有 "CPU implementer" 行，只是架构号是 7；
        // 它不能把 guest rootfs 判成 ARM64，否则会下错 arm64 rootfs。
        val cpuinfo =
            """
            processor	: 0
            model name	: ARMv7 Processor rev 4 (v7l)
            BogoMIPS	: 38.40
            Features	: half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm
            CPU implementer	: 0x41
            CPU architecture: 7
            CPU variant	: 0x0
            CPU part	: 0xd03
            CPU revision	: 4
            """.trimIndent()
        assertEquals(
            HostCpuArchitecture.X86_64,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "x86_64"),
        )
        assertEquals(
            HostCpuArchitecture.UNKNOWN,
            detectHostCpuArchitecture(cpuinfo, reportedArch = "riscv64"),
        )
    }

    @Test
    fun `aarch64 processor line identifies arm64 without architecture line`() {
        val cpuinfo =
            """
            processor	: 0
            Processor	: AArch64 Processor rev 4 (aarch64)
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
