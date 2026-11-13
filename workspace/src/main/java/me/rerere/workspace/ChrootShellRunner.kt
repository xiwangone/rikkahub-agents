package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.util.UUID
import me.rerere.common.log.AppLogger

/**
 * chroot 后端命令执行器（root 设备实验）：把命令包进 `su -c chroot-run.sh`。
 *
 * 安全模型（命令本体以 root 在 chroot 内运行，仅限已 root 设备）：
 * 1. su 用于挂载与进入（脚本内部）
 * 2. 不 bind /system —— chroot 内没有 su 二进制
 * 3. 对外只暴露 execute/start；四命令与参数由本类硬编码拼装
 * 4. 默认关 + probe 自检通过才可启用；rootfs 内 /workspace = 工作区文件区（bind）
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
        val token = cmdFile.name.removePrefix("$COMMAND_FILE-")
        try {
            val process = try {
                newSuProcess(context.linuxDir, execCommandFor(cmdFile), context.dnsServers, chrootWorkdirFor(context.cwd))
            } catch (e: IOException) {
                // 无 root 设备：su 不可用时返回结构化失败，不让异常上抛穿调用链；
                // 记一条 w，否则「chroot 后端不可用」只出现在返回的 stderr 里，
                // 时间线里看不到这次回退。
                AppLogger.w(TAG, "execute: su not accessible, chroot backend unavailable", e)
                return WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "chroot backend unavailable: su not accessible (${e.message}). " +
                        "Root device required, or switch backend back to proot.",
                )
            }
            val result = process.readResult(context.timeoutMillis, context.stdin)
            // 前台超时：只杀 su 客户端不够（root 侧进程由 magiskd fork，与 su 无父子关系），
            // 必须主动整组清理，否则命令会在后台继续跑。
            if (result.timedOut) cancel(token)
            return result
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 任何异常都得先清理 root 侧进程树再原样抛出（否则命令会在后台变成孤儿进程）。
            cancel(token)
            AppLogger.e(TAG, "execute failed", e)
            throw e
        } finally {
            cmdFile.delete()
        }
    }

    override fun start(context: WorkspaceShellContext): Process {
        // 后台任务必须真的执行 command：早前这里只传 "shell"，在 chroot 下会起一个等 stdin 的
        // 交互 bash——命令从未执行，且该进程 hasAlive 恒真，5 个就把每工作区名额占满。
        val cmdFile = writeCommandFile(context)
        return try {
            newSuProcess(context.linuxDir, execCommandFor(cmdFile), context.dnsServers, chrootWorkdirFor(context.cwd)).also { process ->
                // 只删自己的命令文件：同字符串服务的并发任务共用同一前缀，
                // 用通配符清理会互删对方尚未读取的文件。
                Thread {
                    runCatching { process.waitFor() }
                    cmdFile.delete()
                }.apply { isDaemon = true }.start()
            }
        } catch (e: IOException) {
            cmdFile.delete()
            AppLogger.e(TAG, "start failed: su not accessible", e)
            throw IllegalStateException("chroot backend requires root: su not accessible (${e.message})", e)
        }
    }

    /** 探测 root/chroot 能力（独立脚本 chroot-probe.sh，输出 summary 行）；失败或输出不完整返回 null。 */
    override fun probe(context: WorkspaceShellContext): ChrootProbeResult? = runCatching {
        requireSafePath(probeFile)
        requireSafePath(context.linuxDir)
        val process = ProcessBuilder(
            "su", "-c", "sh ${probeFile.absolutePath} ${context.linuxDir.absolutePath}",
        ).start()
        val result = process.readResult(30_000)
        val branch = PROBE_BRANCH.find(result.stdout)?.groupValues?.getOrNull(1)
        PROBE_SUMMARY.find(result.stdout)?.groupValues?.let { g ->
            val pass = g[1].toInt()
            val fail = g[2].toInt()
            val warn = g[3].toInt()
            ChrootProbeResult(
                pass = pass,
                fail = fail,
                warn = warn,
                raw = result.stdout,
                branch = branch ?: if (fail == 0) "A" else "B",
            )
        }
    }.getOrNull()

    /**
     * 兜底卸载与清理：`sh chroot-run.sh <linuxDir> unmount`（幂等，执行 umount -R 并清理 .chroot-*）。
     */
    override fun unmount(context: WorkspaceShellContext): WorkspaceCommandResult =
        runCatching {
            newSuProcess(context.linuxDir, "unmount").readResult(30_000)
        }.getOrElse { e ->
            WorkspaceCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "chroot unmount failed: ${e.message}",
            )
        }

    /** 数据区属主修复：`sh chroot-run.sh <linuxDir> fixowner`（只碰 HOME 与 /workspace）。 */
    override fun fixOwner(context: WorkspaceShellContext): WorkspaceCommandResult =
        runCatching {
            newSuProcess(context.linuxDir, "fixowner").readResult(120_000)
        }.getOrElse { e ->
            WorkspaceCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "chroot fixowner failed: ${e.message}",
            )
        }

    /** 拼装最外层 `su -c` 命令（可测纯函数）。 */
    internal fun suCommand(
        linuxDir: File,
        inner: String,
        dnsServers: List<String> = emptyList(),
    ): String {
        requireSafePath(linuxDir)
        val validDns = ShellDnsConfig.parseServers(dnsServers.joinToString(" "))
        val dnsPrefix =
            if (validDns.isEmpty()) {
                ""
            } else {
                "CHROOT_DNS=${shellQuote(validDns.joinToString(" "))} "
            }
        return "${dnsPrefix}sh ${scriptFile.absolutePath} ${linuxDir.absolutePath} $inner"
    }

    /**
     * chroot 内要执行的命令：文件名取**实际写入的那个**（带 UUID），不能写死常量。
     * 写 `.chroot-exec-<UUID>`、却让 chroot 去读 `/.chroot-exec`，会让每条命令都以
     * `cannot open /.chroot-exec` 失败（2026-09-29 记录）；本函数与下方回归用例把两者钉在一起。
     */
    internal fun execCommandFor(cmdFile: File): String = "exec setsid /bin/sh /${cmdFile.name}"

    /**
     * 杀整棵任务树：`setsid` 让命令自成进程组（pgid = 其 pid）。
     *
     * 遍历所有匹配 `/`.chroot-exec-<token> 的进程——不能只取 `head -1`：命中列表里往往先出现
     * 还没执行 setsid 的 `chroot-run.sh` 包装进程，只杀它就会漏掉里层真正的组首。
     * 对每个 pid 同时向 `-pid`、`-pgid` 与 `pid` 发 SIGKILL。
     */
    override fun cancel(token: String): Boolean {
        if (token.isBlank() || !TOKEN_SAFE.matches(token)) return false
        // `[/][.]` 隔离写法：避免 pgrep 匹配到 cancel 自己的 su -c 命令行。
        val pattern = "[/][.]chroot-exec-$token"
        val script =
            "pids=\$(pgrep -f '$pattern' 2>/dev/null || true); " +
                "[ -n \"\$pids\" ] || exit 1; " +
                "for p in \$pids; do " +
                // 杀之前再核对一次 /proc/<pid>/cmdline：pgrep 命中后、动手前 pid 被复用的窗口里，
                // 新进程的命令行不再含该 token 就跳过，避免误杀无关进程组
                "grep -a -q \"chroot-exec-$token\" \"/proc/\$p/cmdline\" 2>/dev/null || continue; " +
                "g=\$(sed 's/.*) //' \"/proc/\$p/stat\" 2>/dev/null | awk '{print \$3}'); " +
                "kill -9 -\$p 2>/dev/null || true; " +
                "[ -n \"\$g\" ] && [ \"\$g\" -gt 1 ] 2>/dev/null && kill -9 -\$g 2>/dev/null || true; " +
                "kill -9 \"\$p\" 2>/dev/null || true; " +
                "done"
        return runCatching {
            ProcessBuilder("su", "-c", script).start().waitFor() == 0
        }.getOrDefault(false)
    }

    /**
     * 命令写入 rootfs 根下唯一文件（App 普通 uid 可写，chroot 内可读），避免引号地狱。
     *
     * 写入 **cwd + env + 命令**：chroot 侧不继承调用方的进程环境，若不写进文件，
     * 后台/前台命令都会跑在默认目录且拿不到 tools 注入的变量（与 proot 行为不一致）。
     */
    internal fun writeCommandFile(context: WorkspaceShellContext): File {
        val token = context.taskToken.ifBlank { UUID.randomUUID().toString() }
        val cmdFile = File(context.linuxDir, "$COMMAND_FILE-$token")
        val body = buildString {
            if (context.cwd.isNotBlank()) {
                // 与 ProotShellRunner.prootCwd 对齐：cwd 是「相对工作区根」的路径（如 subdir 或 /subdir），
                // 在 chroot 内必须解析到 /workspace/<path>；直接 cd 原始值会跑到 rootfs 根目录下。
                val target = chrootWorkdirFor(context.cwd)
                append("cd -- ").append(shellQuote(target)).append(" 2>/dev/null || cd -- ")
                    .append(shellQuote(context.cwd)).append(" 2>/dev/null || true\n")
            }
            for ((k, v) in context.env) {
                if (!ENV_NAME.matches(k)) continue
                append("export ").append(k).append('=').append(shellQuote(v)).append('\n')
            }
            append(context.command)
        }
        // 原子写 + 仅属主可读写：先写同目录临时文件（0600），再改名到位；
        // 崩溃时只留临时文件（仍可被清理），不留含 env/命令的半成品明文文件
        val tmpFile = File(context.linuxDir, "$COMMAND_FILE-$token.tmp")
        try {
            tmpFile.writeText(body)
            tmpFile.setReadable(true, true)
            tmpFile.setWritable(true, true)
            tmpFile.setExecutable(false, false)
            java.nio.file.Files.move(
                tmpFile.toPath(),
                cmdFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            AppLogger.e(TAG, "writeCommandFile failed", e)
            throw e
        }
        return cmdFile
    }

    /**
     * 调用方 cwd（相对工作区根，如 subdir 或 /subdir）映射到 chroot 内的工作目录。
     * 空 cwd 落到 ROOTFS_WORKSPACE_DIR；前后斜杠都去掉，避免拼出 `//` 或尾随 `/`。
     */
    internal fun chrootWorkdirFor(cwd: String): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalized"
        }
    }

    /** POSIX 单引号转义（内嵌单引号拆成 `'\''`）。 */
    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    private fun newSuProcess(
        linuxDir: File,
        inner: String,
        dnsServers: List<String> = emptyList(),
        workdir: String? = null,
    ): Process {
        val validDns = ShellDnsConfig.parseServers(dnsServers.joinToString(" "))
        return ProcessBuilder("su", "-c", suCommand(linuxDir, inner, validDns))
            .apply {
                if (validDns.isNotEmpty()) {
                    environment()["CHROOT_DNS"] = validDns.joinToString(" ")
                } else {
                    environment().remove("CHROOT_DNS")
                }
                // chroot-run.sh 用 $WORKDIR（默认 /workspace）做入口 cd；命令文件里另有一次 cd 兜底，
                // 两者同值：入口目录先对，后面即使 cd 行失败也不会跑到 rootfs 根目录。
                if (!workdir.isNullOrBlank()) {
                    environment()["CHROOT_WORKDIR"] = workdir
                } else {
                    environment().remove("CHROOT_WORKDIR")
                }
            }.start()
    }

    private fun requireSafePath(file: File) {
        // 允许字符白名单：路径里只应出现字母/数字与 ._/-:，其余（空格/引号/分号/$/反引号/
        // 管道/换行/括号/&/重定向等）一律拒绝，避免拼进 `su -c` 字符串后被解释执行
        require(file.absolutePath.all { it.isLetterOrDigit() || it in "._/-:" }) {
            "chroot path must only contain letters, digits and ._/-: : ${file.absolutePath}"
        }
    }

    companion object {
        private const val TAG = "ChrootShellRunner"
        /** 命令中转文件**前缀**（rootfs 根下；实际名 = 前缀 + UUID，见 writeCommandFile）。 */
        const val COMMAND_FILE = ".chroot-exec"
        private val PROBE_SUMMARY = Regex("summary: pass=(\\d+) fail=(\\d+) warn=(\\d+)")
        private val PROBE_BRANCH = Regex("branch:\\s*([ABC])")
        /** 任务令牌只允许 UUID 形态字符：拼进 pgrep 模式前先挡住正则元字符。 */
        private val TOKEN_SAFE = Regex("[A-Za-z0-9-]{8,64}")
        /** 环境变量名合法性（不合法的键直接丢弃，避免拼出入侵语句）。 */
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/** chroot-probe.sh 的解析结果（pass/fail/warn 计数、S1 分支判定与原始输出）。 */
data class ChrootProbeResult(
    val pass: Int,
    val fail: Int,
    val warn: Int,
    val raw: String,
    val branch: String = if (fail == 0) "A" else "C",
) {
    /**
     * 仅分支 A（chroot 可用且无失败）视为支持：分支 B 表示 chroot(2) 不可用，
     * 即使 fail 计数为 0（探测目录建失败时 fail 不涨）也不能启用该后端。
     */
    val supported: Boolean
        get() = fail == 0 && branch == "A"
}
