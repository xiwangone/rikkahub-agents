package me.rerere.workspace

import java.io.File

/**
 * Physical CPU architecture of the host this process runs on.
 *
 * This is the CPU that guest binaries executed through proot actually run
 * on, which decides which rootfs and apt layout are usable.
 */
enum class HostCpuArchitecture {
    X86_64,
    ARM64,
    UNKNOWN,
}

/**
 * Detects the host CPU architecture from `/proc/cpuinfo` text.
 *
 * `/proc/cpuinfo` is reported by the kernel, so it names the CPU the
 * process actually runs on. The architecture reported by the runtime
 * (`os.arch`) can disagree with it: a virtualized runtime may report an
 * ABI that differs from the physical one, and guest binaries run through
 * proot execute on the physical CPU, so a rootfs chosen by the reported ABI
 * would fail to start there.
 *
 * @param cpuinfoText contents of `/proc/cpuinfo`, or null when it cannot
 * be read.
 * @param reportedArch architecture reported by the runtime; only used
 * when `cpuinfoText` is null or carries no recognizable marker.
 */
fun detectHostCpuArchitecture(
    cpuinfoText: String?,
    reportedArch: String = System.getProperty("os.arch").orEmpty(),
): HostCpuArchitecture {
    cpuinfoText?.let { text ->
        if (isX86Cpuinfo(text)) return HostCpuArchitecture.X86_64
        if (isArmCpuinfo(text)) return HostCpuArchitecture.ARM64
    }
    return when (reportedArch.lowercase()) {
        "x86_64", "amd64" -> HostCpuArchitecture.X86_64
        "aarch64", "arm64" -> HostCpuArchitecture.ARM64
        else -> HostCpuArchitecture.UNKNOWN
    }
}

/** True when `cpuinfo` text describes an x86_64 CPU. */
private fun isX86Cpuinfo(text: String): Boolean {
    if (text.contains("GenuineIntel") || text.contains("AuthenticAMD")) return true
    // The model name line carries the vendor on x86; ARM model names never
    // contain these strings.
    val modelName =
        text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("model name") }
            ?.substringAfter(':', "")
            .orEmpty()
    return modelName.contains("Intel") || modelName.contains("AMD")
}

/** True when `cpuinfo` text describes an ARM64 CPU. */
private fun isArmCpuinfo(text: String): Boolean {
    // "CPU implementer" alone does not prove ARM64: 32-bit ARM (ARMv7)
    // cpuinfo carries the same line, only with "CPU architecture: 7".
    if (text.contains("AArch64")) return true
    return CPU_ARCHITECTURE_8_REGEX.containsMatchIn(text)
}

private val CPU_ARCHITECTURE_8_REGEX = Regex("""CPU architecture\s*:\s*8""")

/**
 * Host CPU architecture, detected once and cached: `/proc/cpuinfo` does not
 * change while the process runs.
 */
private val cachedHostCpuArchitecture: HostCpuArchitecture by lazy {
    val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrNull()
    detectHostCpuArchitecture(cpuinfo)
}

/** True when the host CPU is ARM64. */
fun isArm64Host(): Boolean = cachedHostCpuArchitecture == HostCpuArchitecture.ARM64

/** True when the host CPU is x86_64. */
fun isX86Host(): Boolean = cachedHostCpuArchitecture == HostCpuArchitecture.X86_64
