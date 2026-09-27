package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * chroot 后端命令执行器（root 设备实验）：把命令包进 `su -c chroot-run.sh`。
 *
 * 安全模型（四铁律）：
 * 1. su 只用于挂载与进入（脚本内部），命令本体经 setpriv 降到 uid 1000
 * 2. 不 bind /system —— chroot 内没有 su 二进制，降权进程无法自行提权
 * 3. 对外只暴露 execute/start；四命令与参数由本类硬编码拼装
 * 4. chroot 内不出现 root 进程
 *
 * 脚本（deps/chroot/chroot-run.sh / chroot-probe.sh）由调用方释放到 App 私有
 * 目录后传入；路径不得含空格/引号/分号（App 私有目录固定结构保证）。
 */
class ChrootShellRunner(
    private val scriptFile: File,
    private val probeFile: File,
) : WorkspaceShellRunner {

    init {
        listOf(scriptFile, probeFile).forEach(::requireSafePath)
    }

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val cmdFile = writeCommandFile(context)
        try {
            val process = try {
                newSuProcess(context.linuxDir, "exec /bin/sh /$COMMAND_FILE")
            } catch (e: IOException) {
                // 无 root 设备：su 不可用时返回结构化失败，不让异常上抛穿调用链
                return WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "chroot backend unavailable: su not accessible (${e.message}). " +
                        "Root device required, or switch backend back to proot.",
                )
            }
            return process.readResult(context.timeoutMillis, context.stdin)
        } finally {
            cmdFile.delete()
        }
    }

    override fun start(context: WorkspaceShellContext): Process =
        try {
            newSuProcess(context.linuxDir, "shell")
        } catch (e: IOException) {
            throw IllegalStateException("chroot backend requires root: su not accessible (${e.message})", e)
        }

    /** 探测 root/chroot 能力（独立脚本 chroot-probe.sh，输出 summary 行）；失败或输出不完整返回 null。 */
    fun probe(context: WorkspaceShellContext): ChrootProbeResult? = runCatching {
        val process = ProcessBuilder(
            "su", "-c", "sh ${probeFile.absolutePath} ${context.linuxDir.absolutePath}",
        ).start()
        val result = process.readResult(30_000)
        PROBE_SUMMARY.find(result.stdout)?.groupValues?.let { g ->
            ChrootProbeResult(
                pass = g[1].toInt(),
                fail = g[2].toInt(),
                warn = g[3].toInt(),
                raw = result.stdout,
            )
        }
    }.getOrNull()

    /** 拼装最外层 `su -c` 命令（可测纯函数）。 */
    internal fun suCommand(
        linuxDir: File,
        inner: String,
    ): String {
        requireSafePath(linuxDir)
        return "sh ${scriptFile.absolutePath} ${linuxDir.absolutePath} $inner"
    }

    /** 命令写入 rootfs 根下固定文件（App 普通 uid 可写，chroot 内可读），避免引号地狱。 */
    internal fun writeCommandFile(context: WorkspaceShellContext): File {
        val cmdFile = File(context.linuxDir, "$COMMAND_FILE-${UUID.randomUUID()}")
        cmdFile.writeText(context.command)
        return cmdFile
    }

    private fun newSuProcess(
        linuxDir: File,
        inner: String,
    ): Process = ProcessBuilder("su", "-c", suCommand(linuxDir, inner)).start()

    private fun requireSafePath(file: File) {
        require(file.absolutePath.none { it == ' ' || it == '\'' || it == '"' || it == ';' || it == '$' }) {
            "chroot path must not contain spaces/quotes/semicolons/dollars: ${file.absolutePath}"
        }
    }

    companion object {
        /** 命令中转文件前缀（rootfs 根下，固定 ASCII 名）。 */
        const val COMMAND_FILE = ".chroot-exec"
        private val PROBE_SUMMARY = Regex("summary: pass=(\\d+) fail=(\\d+) warn=(\\d+)")
    }
}

/** chroot-probe.sh 的解析结果（pass/fail/warn 计数与原始输出）。 */
data class ChrootProbeResult(
    val pass: Int,
    val fail: Int,
    val warn: Int,
    val raw: String,
) {
    /** fail=0 视为该设备支持 chroot 后端。 */
    val supported: Boolean
        get() = fail == 0
}
