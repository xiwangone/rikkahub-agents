package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 单个工作区的执行环境, 屏蔽本地 PRoot 与远程等不同实现。
 *
 * 路径有两套寻址方式:
 * - 存储区 + 相对路径: 应用内文件浏览器使用, 见 [WorkspaceStorageArea]
 * - 环境内绝对路径: AI 工具与 shell 看到的路径, 如 /workspace/notes.md
 *
 * 方法都是阻塞 IO, 调用方负责切到 IO 线程; 取消时会中断调用线程。
 */
@Suppress("TooManyFunctions")
// 工作区后端契约：方法数随能力（文件读写 / 命令执行 / 目录树 / 挂载）自然增长，
// 按能力拆成多个接口会把同一类操作割裂到多处调用点。
interface WorkspaceBackend {
    val layout: WorkspaceLayout

    /** 执行命令实际使用的 shell 路径 */
    fun shell(): String

    /** 列出目录, [limit] 为空时用工作区配置的默认上限 */
    fun listFiles(area: WorkspaceStorageArea, path: String, limit: Int? = null): List<WorkspaceFileEntry>

    /** 与 [listFiles] 相同, 但额外返回是否因上限截断以及截断前的总数 */
    fun listFilesDetailed(area: WorkspaceStorageArea, path: String, limit: Int? = null): WorkspaceListResult

    /** 读取文件区内的文本, 自带大小保护 */
    fun readText(path: String): String

    /** 写入文件区内的文本 */
    fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry

    /** 导入文件, 目标已存在时自动改名 */
    fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry

    fun fileSize(area: WorkspaceStorageArea, path: String): Long

    fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream)

    fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean

    /** 移动或重命名文件区内的条目, [target] 为相对同一存储区的路径 */
    fun moveFile(area: WorkspaceStorageArea, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry

    /** 复制文件区内的条目, [target] 为相对同一存储区的路径 */
    fun copyFile(area: WorkspaceStorageArea, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry

    fun absoluteFileSize(path: String): Long

    fun exportAbsoluteFile(path: String, outputStream: OutputStream)

    /**
     * 按环境内绝对路径写入 UTF-8 文本, 父目录不存在时自动创建; 返回的条目 path 为绝对路径。
     * [mode] 为八进制权限(如 755), 由调用方校验后传入, 为空时保持环境默认。
     */
    fun writeAbsoluteText(path: String, text: String, overwrite: Boolean, mode: String? = null): WorkspaceFileEntry

    /** 按环境内绝对路径创建目录, 已是目录时直接返回该条目 */
    fun createAbsoluteFolder(path: String): WorkspaceFileEntry

    /** 宿主机上可直接访问的文件; 没有本地落点的环境返回 null */
    fun localFile(area: WorkspaceStorageArea, path: String): File?

    /** 按环境内绝对路径解析宿主机上的文件, 不检查是否存在; 没有本地落点的环境返回 null */
    fun localAbsoluteFile(path: String): File?

    /** 执行命令, [cwd] 为相对文件区的路径；[env] 由调用方注入, 各后端按自己的方式传入子进程 */
    fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        env: Map<String, String> = emptyMap(),
    ): WorkspaceCommandResult
}

/** 工作区在执行环境内的目录约定 */
data class WorkspaceLayout(
    /** 文件区在环境内的绝对路径, 也是 shell 的默认工作目录 */
    val workspaceDir: String,
    /** AI 写入时免强制审批的目录 */
    val writableRoots: List<String>,
) {
    /** 存储区内的相对路径转成环境内的绝对路径 */
    fun absolutePath(area: WorkspaceStorageArea, path: String): String {
        val relative = path.trim('/')
        return when (area) {
            WorkspaceStorageArea.FILES -> if (relative.isEmpty()) workspaceDir else "$workspaceDir/$relative"
            WorkspaceStorageArea.LINUX -> "/$relative"
        }
    }

    /** 文件区内的绝对路径转成相对文件区的路径, 不在文件区内时原样返回 */
    fun relativeToWorkspace(path: String): String =
        path.removePrefix("$workspaceDir/").removePrefix(workspaceDir)

    fun isWritable(path: String): Boolean {
        // 先折叠 .. 与 . 再做前缀比对, 否则 "/root/../etc" 这类路径能通过 startsWith 检查
        val normalized = normalizeWorkspacePath(path.trimEnd('/').ifBlank { "/" })
        return writableRoots.any { root ->
            val r = normalizeWorkspacePath(root)
            normalized == r || normalized.startsWith("$r/")
        }
    }
}

/**
 * 按字面规范化执行环境内的绝对路径: 去掉多余的 / 与 ., 逐段解析 .., 不跟随软链。
 *
 * 审批闸门（[WorkspaceLayout.isWritable] 与 app 侧的 workspace 工具）在比较前必须
 * 先经过这里, 否则 `/root/../etc` 这类路径可以绕过前缀检查跳过强制审批。
 * 公开给 app 模块复用；ssh 后端内的 [me.rerere.workspace.ssh.normalizeAbsolute]
 * 也委托到本函数，保证整条链路只有一种规范化语义。
 */
fun normalizeWorkspacePath(path: String): String {
    require(path.startsWith("/")) { "Path must be absolute: $path" }
    require(!path.contains('\u0000')) { "Path contains invalid character" }
    val segments = ArrayDeque<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}
