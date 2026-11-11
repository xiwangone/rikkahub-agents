package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceType(val capabilities: WorkspaceCapabilities) {
    /** 本机 PRoot Rootfs */
    LOCAL(
        WorkspaceCapabilities(
            rootfs = true,
            terminal = true,
            localFiles = true,
            fileWriteTools = true,
            backgroundShell = true,
        )
    ),

    /** 通过 SSH 连接的远程机器 */
    SSH(
        WorkspaceCapabilities(
            rootfs = false,
            terminal = true,
            localFiles = false,
            fileWriteTools = true,
            backgroundShell = false,
        )
    ),
}

/** 一种工作区能用哪些功能; 调用方按能力判断, 不直接比较类型 */
/** 执行环境信息, 用于向模型描述工作区实际可用的 shell 与发行版 */
data class WorkspaceEnvironmentInfo(
    val shellPath: String?,
    val distroName: String?,
)

data class WorkspaceCapabilities(
    /** 运行在本机 PRoot Rootfs 里: 需要安装 Rootfs, 挂载了 /skills、/upload、/tool_outputs 等应用目录 */
    val rootfs: Boolean,
    /** 支持交互式终端 */
    val terminal: Boolean,
    /** 文件在本机; 否则每次访问都要走网络, 也没法交给系统文件选择器 */
    val localFiles: Boolean,
    /**
     * AI 可以用写文件、编辑文件工具直接写入。远程同样支持：写路径走 SFTP 临时文件
     * 再改名到位（见 SshWorkspaceBackend.writeSftpBytes），中断不会留下半截文件，
     * 因此不再按“覆盖写入会清空原内容”关掉远程写工具。
     */
    val fileWriteTools: Boolean,
    /** shell 命令可以在后台运行, 由 AI 之后读取输出或终止；远程暂不支持 */
    val backgroundShell: Boolean,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxImportBytes: Long = 256L * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
    val maxCopyBytes: Long = 256L * 1024 * 1024,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

/** 递归目录树的一个条目, [depth] 相对于树的起点目录, 直接子项为 1 */
data class WorkspaceTreeEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val depth: Int,
    /** 是否符号链接；是则 [linkTarget] 为链接目标（readlink / readSymbolicLink 的原始值，可能相对） */
    val isSymlink: Boolean = false,
    val linkTarget: String? = null,
)

data class WorkspaceTreeResult(
    val entries: List<WorkspaceTreeEntry>,
    val truncated: Boolean,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
