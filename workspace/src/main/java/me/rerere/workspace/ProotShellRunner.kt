package me.rerere.workspace

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /** AI 写入该目录时免强制审批 */
    val writable: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    /** 后台任务 token → pidFile（start() 时登记，cancel() 时消费并删除）。 */
    private val backgroundPidFiles = ConcurrentHashMap<String, File>()

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir, RootfsPatchOptions(nameservers = context.dnsServers))
        val pidFile = newPidFile(context, UUID.randomUUID().toString())
        val process =
            ProcessBuilder(buildCommand(context, proot, pidFileGuestPath(pidFile)))
                .directory(context.filesDir)
                .redirectErrorStream(false)
                .apply {
                    if (context.shellCompatibilityMode) {
                        environment()["PROOT_NO_SECCOMP"] = "1"
                    } else {
                        environment().remove("PROOT_NO_SECCOMP")
                    }
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                    environment()["TMPDIR"] = context.tempDir.absolutePath
                    context.env.forEach { (k, v) ->
                        if (isValidEnvKey(k)) environment()[k] = v.replace("\u0000", "")
                    }
                }.start()

        return try {
            // 超时或取消时直接结束, 不留宽限期: 调用方已经不再等这条命令
            process.readResult(context.timeoutMillis, context.stdin) { terminateProot(this, pidFile) }
        } finally {
            pidFile.delete()
        }
    }

    override fun start(context: WorkspaceShellContext): Process {
        if (!context.linuxDir.hasUsableRootfs()) {
            throw IllegalStateException("Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            throw IllegalStateException("proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            throw IllegalStateException("proot loader not found: ${loader.absolutePath}")
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir, RootfsPatchOptions(nameservers = context.dnsServers))
        // 后台任务的 pidFile 以 taskToken 命名, cancel(token) 时据此终止整组进程;
        // taskToken 为空时不登记, 取消退回调用方的本地 destroyForcibly。
        val pidFile = newPidFile(context, context.taskToken.ifBlank { UUID.randomUUID().toString() })
        if (context.taskToken.isNotBlank()) {
            backgroundPidFiles[context.taskToken] = pidFile
            pruneBackgroundPidFiles(context.taskToken)
        }
        return ProcessBuilder(buildCommand(context, proot, pidFileGuestPath(pidFile)))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
                context.env.forEach { (k, v) ->
                    if (isValidEnvKey(k)) environment()[k] = v.replace("\u0000", "")
                }
            }.start()
    }

    /**
     * 后台取消：按 pidFile 终止整组 proot 进程。返回 false 表示本后端没登记该 token，
     * 调用方仍会本地 destroyForcibly 兜底。
     */
    override fun cancel(token: String): Boolean {
        if (token.isBlank()) return false
        val pidFile = backgroundPidFiles.remove(token) ?: return false
        val terminated = terminateProotByPidFile(pidFile)
        pidFile.delete()
        return terminated
    }

    /** pidFile 登记表上限：只清“pidFile 可读且 PRoot 已退出”的旧条目，缺文件的一律保留（可能刚启动还没写入）。 */
    private fun pruneBackgroundPidFiles(exceptToken: String) {
        if (backgroundPidFiles.size <= MAX_TRACKED_PID_FILES) return
        backgroundPidFiles.entries.removeIf { (token, pidFile) ->
            token != exceptToken &&
                readPidFile(pidFile)?.second?.let { !isPidAlive(it) } == true
        }
    }

    private fun newPidFile(context: WorkspaceShellContext, name: String): File {
        val dir = File(context.linuxDir, EXEC_DIR.trimStart('/'))
        dir.mkdirs()
        return File(dir, "$name.pid")
    }

    private fun pidFileGuestPath(pidFile: File): String = "$EXEC_DIR/${pidFile.name}"

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
        pidFileGuestPath: String,
    ): List<String> {
        val command =
            mutableListOf(
                proot.absolutePath,
                "--root-id",
                "--link2symlink",
                "--kill-on-exit",
                "-r",
                context.linuxDir.absolutePath,
                "-w",
                context.prootCwd(),
                "-b",
                "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
            )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command +=
            listOf(
                "/usr/bin/env",
                "-i",
                "HOME=${WorkspaceManager.ROOTFS_HOME_DIR}",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
                "CI=true",
                "NO_COLOR=1",
                "PAGER=cat",
            )

        // ⚠️ 注入的变量必须作为 env 的参数传入：`env -i` 会清空继承来的整个环境，
        // 只在 ProcessBuilder.environment() 里 set 是无效的：
        // workspace_shell(env=…) 静默失败，命令里 $VAR 恒为空）。
        command += buildEnvAssignments(context.env)

        // shell 入口按 rootfs 实际形态解析：bash 优先，缺失则回落 sh。
        // 【为何不能硬编码 /bin/bash】Alpine 系 minirootfs 只带 busybox
        // （/bin/sh → /bin/busybox），根本没有 bash——硬编码 bash 会让 Alpine
        // 工作区每条命令 exit 127（2026-10-22 e2b run31 实证：补丁 D 修好 rootfs
        // 判定后，ws-beta 的子代理汇报原话「the underlying ws-beta shell
        // environment is broken ( /bin/bash missing → exit 127 on every
        // command), which blocked both the file creation and the cat
        // commands」——写文件与 cat 全被堵死）。Ubuntu 系自带 bash 所以此前
        // 无一例失败，与补丁 D 的 symlink 坑同属「Ubuntu 假设」。
        // [WorkspaceManager.isUsableRootfs] 本就接受 bin/bash || bin/sh 二者
        // 其一，这里按同一优先级解析，两处口径合一。busybox ash 对
        // `-l` / `cd --` / `eval` 均支持（exit 0），Ubuntu 路径不变。
        val shellPath =
            if (WorkspaceManager.hasRootfsEntry(context.linuxDir, "bin/bash")) "/bin/bash" else "/bin/sh"

        command +=
            listOf(
                shellPath,
                "-l",
                "-c",
                // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
                TRACKED_SCRIPT,
                "rikkahub",
                context.prootCwd(),
                context.command,
                pidFileGuestPath,
            )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    /**
     * rootfs 可用性判定——必须复用 [WorkspaceManager.isUsableRootfs] 的符号链接感知逻辑。
     *
     * 【为什么不能自己写】老实现 `isDirectory && File(this, "bin/sh").isFile` 按**宿主机**
     * 解析 symlink：Alpine 系发行版的 `bin/sh` 是指向**绝对路径** `/bin/busybox` 的链接，
     * 在 Android 宿主机上必然不存在 → `isFile` 恒 false → 每个 workspace 工具都报
     * `IllegalStateException: Rootfs is not installed`（2026-10-22 e2b run29 实证：
     * ws-beta/Alpine 的 write_file 与 shell 全废，而 UI 状态是 READY、W4/W5 断言全过——
     * 安装完成判定用的是 [WorkspaceManager.hasRootfs]，两套口径不一致）。
     * Ubuntu 系 `bin/sh → dash` 是**相对**链接所以侥幸无事——这正是「多发行版共存」
     * 场景下只有 Alpine 中招的原因。
     *
     * [WorkspaceManager.isUsableRootfs] 用 `Files.readSymbolicLink` + NOFOLLOW_LINKS，
     * 并把绝对 symlink 按 chroot 内路径解析（越界/循环拒绝），与 proot `-r` 的视角一致。
     */
    private fun File.hasUsableRootfs(): Boolean = isDirectory && WorkspaceManager.isUsableRootfs(this)

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        // 先把 shell 与 PRoot 的进程号写入 $3, 终止时据此给整组进程发信号, 见 terminateProot
        private const val TRACKED_SCRIPT = "echo \"\$\$ \$PPID\" > \"\$3\"; cd -- \"\$1\" && eval \"\$2\""

        /** Rootfs 内存放前台/后台命令进程号文件的目录 */
        private const val EXEC_DIR = "/tmp/rikkahub/exec"

        /** 后台 pidFile 登记表上限（自然退出且未取消的任务会留一条记录，超限时清掉已退出的）。 */
        private const val MAX_TRACKED_PID_FILES = 64
    }
}

/**
 * 把待注入的环境变量渲染成 `K=V` 参数列表，供 `/usr/bin/env` 直接作为参数接收。
 *
 * 为什么是参数而不是 `ProcessBuilder.environment()`：proot 启动链用的是 `env -i`，
 * 它会**清空**继承环境，只保留命令行上显式给出的 `K=V`。见 [ProotShellRunner.buildCommand]。
 *
 * 非法键（空 / 含 `=` / 含 NUL）会被丢弃——`env` 对它们会报错并中止整条命令。
 */
fun buildEnvAssignments(env: Map<String, String>): List<String> =
    env.entries
        .filter { (key, _) -> isValidEnvKey(key) }
        .map { (key, value) -> "$key=${value.replace("\u0000", "")}" }

/** 环境变量键合法性：与 [buildEnvAssignments] 同一口径，供直接写入 ProcessBuilder 的调用方复用。 */
internal fun isValidEnvKey(key: String): Boolean =
    key.isNotBlank() && !key.contains('=') && !key.contains('\u0000')
