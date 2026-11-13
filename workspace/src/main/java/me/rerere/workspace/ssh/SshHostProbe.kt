package me.rerere.workspace.ssh

import kotlinx.serialization.Serializable
import me.rerere.workspace.WorkspaceCommandResult
import java.util.Base64

/**
 * 首次连接主机探针的结果（可序列化，供 DataStore 缓存）。
 *
 * @param platform "LINUX" 或 "WINDOWS"（与 [SshConnectionConfig.platform] 同口径）。
 * @param platformSource 平台结论的来源：探针（"probe"）是真实执行，比 banner 字符串更可靠。
 * @param os 系统描述：Linux 取 /etc/os-release 的 PRETTY_NAME（无则取 uname 内核名+版本），
 * Windows 取 Win32_OperatingSystem.Caption（去 "Microsoft " 前缀）。
 * @param arch 架构：POSIX 取 uname -m；Windows 取 Win32_Processor.Architecture 映射（9=x64、12=ARM64）。
 * @param shell 登录 shell（POSIX 为 `$SHELL`，Windows 为 "PowerShell <版本>"）。
 * @param cpuCores CPU 核数（POSIX 取 nproc/getconf/sysctl，Windows 取各处理器 NumberOfCores 之和）。
 * @param memoryMb 内存总量（MB；POSIX 取 free -m 的 Mem 行，macOS 取 sysctl hw.memsize 折算）。
 * @param diskFreeMb 根盘（POSIX 为 `/`，Windows 为 C 盘）可用空间（MB）。
 * @param tools 探到的常用工具（按 python、python3、node、docker、git 顺序，只列已安装的）。
 */
@Serializable
data class SshHostProbeResult(
    val platform: String,
    val platformSource: String = "probe",
    val os: String? = null,
    val arch: String? = null,
    val shell: String? = null,
    val cpuCores: Int? = null,
    val memoryMb: Long? = null,
    val diskFreeMb: Long? = null,
    val tools: List<String> = emptyList(),
    val probedAtMs: Long = 0L,
) {
    val isWindows: Boolean get() = platform == "WINDOWS"

    /** AI 可读摘要，供工具描述/主机详情/工作区提示词使用；缺失项自动省略。 */
    fun summary(): String {
        val label = if (isWindows) "Windows" else "Linux"
        val parts = mutableListOf<String>()
        parts += if (!arch.isNullOrBlank()) "$label $arch" else label
        if (!os.isNullOrBlank()) parts += os
        if (cpuCores != null) parts += "$cpuCores CPU"
        if (memoryMb != null) parts += "${formatMb(memoryMb)} RAM"
        if (diskFreeMb != null) parts += "${formatMb(diskFreeMb)} disk free"
        if (tools.isNotEmpty()) parts += "tools: ${tools.joinToString(", ")}"
        return "Host: " + parts.joinToString(", ")
    }

    private fun formatMb(mb: Long): String = if (mb >= 1024) "${mb / 1024}GB" else "${mb}MB"
}

/**
 * 首次连接主机探针：app SSH（SshTool / SshHostsTool）与远程工作区后端
 * （[SshWorkspaceBackend]）共用同一份实现，禁止各写一份。
 *
 * 探针命令只读、无副作用、不写文件；整体超时默认 15 秒，任一命令失败/超时只记该项为空，
 * 不抛错、不影响本次连接。执行与解析分离：[runProbe] 只依赖一个 `(命令, 超时) -> 结果` 的
 * 执行闭包，两边的连接原语（app 侧 `runOnSession`、工作区侧 `SshConnection.exec`）各包
 * 一层适配器即可。
 *
 * 平台包装差异由 [execWrapsWindows] 一处收敛：工作区侧 `SshConnection.exec` 在
 * `config.platform == "WINDOWS"` 时自动把 pwsh 脚本包成 EncodedCommand，此时传原始脚本；
 * app 侧 `runOnSession` 只按命令形状包装（已编码形式原样通过），此时传 [windowsProbeCommand]
 * 的预编码形式。POSIX 脚本两种原语都原样发送。
 */
object SshHostProbe {
    /** 探针整体超时（毫秒），单次 exec 即全量探针的超时。 */
    const val PROBE_TIMEOUT_MS = 15_000L

    private val SECTION_MARKER = Regex("^---HOSTPROBE-([A-Z]+)---$")

    /**
     * POSIX 探针脚本：以 `---HOSTPROBE-XXX---` 分段输出，各段命令失败不阻断后续
     * （用 `;`/`||` 连接，整体以最后一个 echo 的 0 退出；登录 shell 的 profile/MOTD
     * 输出落在段外，解析时忽略）。
     */
    fun posixProbeScript(): String = """
        echo '---HOSTPROBE-OS---'; uname -a 2>/dev/null; echo '---HOSTPROBE-OSRELEASE---'; cat /etc/os-release 2>/dev/null; echo '---HOSTPROBE-ARCH---'; uname -m 2>/dev/null; echo '---HOSTPROBE-CPU---'; nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null; echo '---HOSTPROBE-MEM---'; free -m 2>/dev/null; sysctl -n hw.memsize 2>/dev/null; echo '---HOSTPROBE-DISK---'; df -m / 2>/dev/null; echo '---HOSTPROBE-TOOLS---'; command -v python python3 node docker git 2>/dev/null; echo '---HOSTPROBE-SHELL---'; echo "${'$'}SHELL"; echo '---HOSTPROBE-END---'
    """.trimIndent()

    /** Windows 探针脚本（原始 pwsh 形式）：CIM 只查两次（系统 + 处理器），不用全量 systeminfo。 */
    fun windowsProbeScript(): String = """
        ${'$'}ErrorActionPreference='SilentlyContinue'
        Write-Output '---HOSTPROBE-PS---'
        ${'$'}PSVersionTable.PSVersion.ToString()
        Write-Output '---HOSTPROBE-OS---'
        ${'$'}os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
        ${'$'}os.Caption
        Write-Output '---HOSTPROBE-ARCH---'
        ${'$'}cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue
        (${'$'}cpu | Select-Object -First 1).Architecture
        Write-Output '---HOSTPROBE-CPU---'
        (${'$'}cpu | Measure-Object -Property NumberOfCores -Sum).Sum
        Write-Output '---HOSTPROBE-MEM---'
        [math]::Round(${'$'}os.TotalVisibleMemorySize / 1024)
        Write-Output '---HOSTPROBE-DISK---'
        [math]::Round((Get-PSDrive C -ErrorAction SilentlyContinue).Free / 1MB)
        Write-Output '---HOSTPROBE-TOOLS---'
        (Get-Command python,python3,node,docker,git -ErrorAction SilentlyContinue).Name
        Write-Output '---HOSTPROBE-END---'
    """.trimIndent()

    /**
     * Windows 探针命令（预编码形式）：`powershell -EncodedCommand <UTF-16LE base64>`。
     * 供执行原语不自动包 pwsh 的场合（app 侧 runOnSession、AUTO 回退）；远端默认 shell
     * 是 cmd 也能跑，`preparePowerShellCommand` 见到 `-EncodedCommand` 会原样通过，不二次包装。
     */
    fun windowsProbeCommand(): String {
        val fullScript = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " + windowsProbeScript()
        val b64 = Base64.getEncoder().encodeToString(fullScript.toByteArray(Charsets.UTF_16LE))
        return "powershell -NoProfile -NonInteractive -EncodedCommand $b64"
    }

    /**
     * 跑一次探针并解析。`platform` 为已决议平台（"LINUX"/"WINDOWS"/"AUTO"）：
     * 已知平台只跑对应脚本；AUTO 先跑 POSIX，解析不到有效信号再跑 Windows。
     * 任一 exec 抛错/超时/非 0 退出/解析不到有效信号都返回 null（调用方不缓存、不写回平台）。
     */
    fun runProbe(
        exec: (String, Long) -> WorkspaceCommandResult,
        platform: String,
        execWrapsWindows: Boolean,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): SshHostProbeResult? {
        return when (platform.uppercase()) {
            "WINDOWS" -> {
                val script = if (execWrapsWindows) windowsProbeScript() else windowsProbeCommand()
                val result = runCatching { exec(script, timeoutMs) }.getOrNull() ?: return null
                if (result.exitCode != 0) return null
                parseWindows(result.stdout)
            }
            "LINUX" -> {
                val result = runCatching { exec(posixProbeScript(), timeoutMs) }.getOrNull() ?: return null
                if (result.exitCode != 0) return null
                parsePosix(result.stdout)
            }
            else -> { // AUTO：POSIX 先，拿不到有效信号再试 Windows
                val posixResult = runCatching { exec(posixProbeScript(), timeoutMs) }.getOrNull()
                if (posixResult != null && posixResult.exitCode == 0) {
                    parsePosix(posixResult.stdout)?.let { return it }
                }
                val winScript = if (execWrapsWindows) windowsProbeScript() else windowsProbeCommand()
                val winResult = runCatching { exec(winScript, timeoutMs) }.getOrNull() ?: return null
                if (winResult.exitCode != 0) return null
                parseWindows(winResult.stdout)
            }
        }
    }

    /** 按 `---HOSTPROBE-XXX---` 标记切分 stdout，标记外的行（profile/MOTD）忽略。 */
    internal fun splitSections(stdout: String): Map<String, String> {
        val sections = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        for (line in stdout.lines()) {
            val marker = SECTION_MARKER.find(line.trim())?.groupValues?.getOrNull(1)
            if (marker != null) {
                current = marker
                sections.getOrPut(marker) { StringBuilder() }
            } else if (current != null) {
                sections.getOrPut(current) { StringBuilder() }.appendLine(line)
            }
        }
        return sections.mapValues { it.value.toString().trim() }
    }

    /** 解析 POSIX 探针输出；uname 与 arch 都拿不到时返回 null（信号不足，不算一次有效探针）。 */
    internal fun parsePosix(stdout: String): SshHostProbeResult? {
        val sections = splitSections(stdout)
        val uname = sections["OS"].orEmpty().trim()
        val arch = sections["ARCH"].orEmpty().trim().takeIf { it.isNotEmpty() }
        if (uname.isEmpty() && arch == null) return null
        var os: String? = parsePrettyName(sections["OSRELEASE"].orEmpty())
        if (os == null && uname.isNotEmpty()) {
            val parts = uname.split(Regex("\\s+"))
            // Git Bash/MSYS 下 uname -a 形如 "MINGW64_NT-10.0 ..."：平台仍按 POSIX 包装，
            // os 保留内核名以便摘要看得出是 Windows 上的 POSIX 环境。
            os = if (parts.size >= 3) "${parts[0]} ${parts[2]}" else parts.getOrNull(0)
        }
        val cpu = sections["CPU"].orEmpty().lines().firstNotNullOfOrNull { it.trim().toIntOrNull() }
        val mem = parsePosixMem(sections["MEM"].orEmpty())
        val disk = parsePosixDisk(sections["DISK"].orEmpty())
        val tools = sections["TOOLS"].orEmpty().lines()
            .mapNotNull { it.trim().takeIf { line -> line.isNotEmpty() }?.substringAfterLast('/') }
            .distinct()
        val shell = sections["SHELL"].orEmpty().trim().takeIf { it.isNotEmpty() }
        return SshHostProbeResult(
            platform = "LINUX",
            os = os,
            arch = arch,
            shell = shell,
            cpuCores = cpu,
            memoryMb = mem,
            diskFreeMb = disk,
            tools = tools,
            probedAtMs = System.currentTimeMillis(),
        )
    }

    /** 解析 Windows 探针输出；PSVersion 与系统 Caption 都拿不到时返回 null。 */
    internal fun parseWindows(stdout: String): SshHostProbeResult? {
        val sections = splitSections(stdout)
        val ps = sections["PS"].orEmpty().trim()
        val caption = sections["OS"].orEmpty().trim()
        if (ps.isEmpty() && caption.isEmpty()) return null
        val archCode = sections["ARCH"].orEmpty().trim().toIntOrNull()
        val arch = when (archCode) {
            9 -> "x64"
            12 -> "ARM64"
            0 -> "x86"
            5 -> "ARM"
            6 -> "ia64"
            else -> null
        }
        val cpu = sections["CPU"].orEmpty().trim().toDoubleOrNull()?.toInt()
        val mem = sections["MEM"].orEmpty().trim().toDoubleOrNull()?.toLong()
        val disk = sections["DISK"].orEmpty().trim().toDoubleOrNull()?.toLong()
        val tools = sections["TOOLS"].orEmpty().lines()
            .mapNotNull { it.trim().takeIf { line -> line.isNotEmpty() } }
            .distinct()
        val os = caption.removePrefix("Microsoft ").takeIf { it.isNotEmpty() }
        val shell = if (ps.isNotEmpty()) "PowerShell $ps" else null
        return SshHostProbeResult(
            platform = "WINDOWS",
            os = os,
            arch = arch,
            shell = shell,
            cpuCores = cpu,
            memoryMb = mem,
            diskFreeMb = disk,
            tools = tools,
            probedAtMs = System.currentTimeMillis(),
        )
    }

    private fun parsePrettyName(osRelease: String): String? {
        for (line in osRelease.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("PRETTY_NAME=")) {
                return trimmed.substringAfter("PRETTY_NAME=").trim().trim('"', '\'')
                    .takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun parsePosixMem(section: String): Long? {
        for (line in section.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Mem:")) {
                return trimmed.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
            }
        }
        // macOS 没有 free：hw.memsize 是字节数，折算成 MB。
        val bareBytes = section.lines().firstNotNullOfOrNull { it.trim().toLongOrNull() }
        return bareBytes?.let { it / 1024 / 1024 }
    }

    private fun parsePosixDisk(section: String): Long? {
        val lines = section.lines().filter { it.isNotBlank() }
        val data = lines.firstOrNull { !it.trimStart().startsWith("Filesystem") } ?: return null
        val tokens = data.trim().split(Regex("\\s+"))
        // df -m /：Filesystem 1M-blocks Used Available Use% Mounted on，第 4 列为可用 MB。
        return tokens.getOrNull(3)?.toLongOrNull()
    }
}

/**
 * 探针单次执行预算（毫秒）：默认 [SshHostProbe.PROBE_TIMEOUT_MS]；调用方有 deadline 时取
 * min(15s, deadline-now-1s)——给调用方留 1 秒收尾；预算耗尽返回 null（跳过本次探测）。
 * AUTO 模式最多两次执行，每次各取自己的预算。
 * 放顶层而非 [SshHostProbe] 的成员：对象内函数数有 detekt 阈值，成员再多一个即超限。
 */
fun probeBudgetMs(deadlineMs: Long?, nowMs: Long = System.currentTimeMillis()): Long? {
    val budget = deadlineMs?.let { minOf(SshHostProbe.PROBE_TIMEOUT_MS, it - nowMs - 1_000) }
        ?: SshHostProbe.PROBE_TIMEOUT_MS
    return budget.takeIf { it > 0 }
}
