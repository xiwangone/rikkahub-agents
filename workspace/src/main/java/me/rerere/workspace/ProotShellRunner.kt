package me.rerere.workspace

import java.io.File

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
        patcher.patch(context.linuxDir)
        val process =
            ProcessBuilder(buildCommand(context, proot))
                .directory(context.filesDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                    environment()["TMPDIR"] = context.tempDir.absolutePath
                    context.env.forEach { (k, v) -> environment()[k] = v }
                }.start()

        return process.readResult(context.timeoutMillis, context.stdin)
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
        patcher.patch(context.linuxDir)
        return ProcessBuilder(buildCommand(context, proot))
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
                context.env.forEach { (k, v) -> environment()[k] = v }
            }.start()
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
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
                "cd -- \"\$1\" && eval \"\$2\"",
                "rikkahub",
                context.prootCwd(),
                context.command,
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
        .filter { (key, _) -> key.isNotBlank() && !key.contains('=') && !key.contains('\u0000') }
        .map { (key, value) -> "$key=${value.replace("\u0000", "")}" }
