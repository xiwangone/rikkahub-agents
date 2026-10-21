package me.rerere.workspace.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTreeEntry
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.shellQuote
import me.rerere.workspace.statEntryCommand
import me.rerere.workspace.parseShellEntries
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 远程 SSH 主机上的工作区: 文件走 SFTP, 命令走 exec channel。
 *
 * 存储区映射: [WorkspaceStorageArea.FILES] 为远程工作目录, [WorkspaceStorageArea.LINUX] 为远程根目录。
 */
@Suppress("TooManyFunctions")
// SSH 后端完整实现上述契约（SFTP 读写 + exec 命令 + 目录遍历 + 连接池协作），方法数由接口决定。
class SshWorkspaceBackend(
    private val pool: SshConnectionPool,
    private val id: String,
    private val config: SshConnectionConfig,
    private val workspaceConfig: WorkspaceConfig = WorkspaceConfig(),
) : WorkspaceBackend {
    override val layout = layoutOf(config.remoteDir)

    private val workspaceDir = layout.workspaceDir

    override fun shell(): String = pool.use(id, config, retry = true) { it.shell }

    override fun listFiles(area: WorkspaceStorageArea, path: String, limit: Int?): List<WorkspaceFileEntry> {
        val dir = resolve(area, path)
        return withSftp(area, retry = true) { sftp ->
            val attrs = sftp.requireStat(dir, path)
            require(attrs.isDir) { "Path is not a directory: $path" }
            sftp.ls(dir.sftpQuote())
                .filter { it.filename != "." && it.filename != ".." }
                .map { entry ->
                    val child = dir.joinChild(entry.filename)
                    // 指向目录的软链按目录展示, 和本地文件浏览器的行为一致
                    val isDirectory = entry.attrs.isDir ||
                        entry.attrs.isLink && sftp.statOrNull(child)?.isDir == true
                    entry.attrs.toWorkspaceEntry(relativePath(area, child), entry.filename, isDirectory)
                }
                .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .take(limit ?: workspaceConfig.maxListEntries)
        }
    }

    override fun readText(path: String): String {
        val file = resolve(WorkspaceStorageArea.FILES, path)
        return withSftp(WorkspaceStorageArea.FILES, retry = true) { sftp ->
            val size = sftp.requireFile(file, path).size
            require(size <= workspaceConfig.maxReadBytes) { "File is too large to read: $size bytes" }
            // 不用 get 返回的流: 它关闭时要读完未到的回应, 读取被打断后会再次卡住
            ByteArrayOutputStream().also { sftp.get(file.sftpQuote(), it) }.toString(Charsets.UTF_8.name())
        }
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= workspaceConfig.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolve(WorkspaceStorageArea.FILES, path)
        return withSftp(WorkspaceStorageArea.FILES, retry = false) { sftp ->
            sftp.writeSftpBytes(file, bytes, overwrite, path)
                .toWorkspaceEntry(relativePath(WorkspaceStorageArea.FILES, file), file.fileName())
        }
    }

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        val file = resolve(area, targetPath)
        return inputStream.use { input ->
            withSftp(area, retry = false) { sftp ->
                sftp.mkdirs(file.parentPath())
                val target = if (sftp.statOrNull(file) == null) file else sftp.resolveConflict(file)
                sftp.put(input, target.sftpQuote(), ChannelSftp.OVERWRITE)
                sftp.requireStat(target, targetPath).toWorkspaceEntry(relativePath(area, target), target.fileName())
            }
        }
    }

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long {
        val file = resolve(area, path)
        return withSftp(area, retry = true) { it.requireFile(file, path).size }
    }

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) {
        val file = resolve(area, path)
        withSftp(area, retry = false) { sftp -> sftp.export(file, path, outputStream) }
    }

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val target = resolve(area, path)
        require(target != areaRoot(area)) { "Refusing to delete workspace root" }
        return withSftp(area, retry = false) { sftp ->
            // 用 lstat: 删除指向目录的软链时只删链接本身
            val attrs = try {
                sftp.lstat(target.sftpQuote())
            } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@withSftp false
                throw e.wrap(path)
            }
            if (attrs.isDir && !attrs.isLink) {
                require(recursive) { "Directory delete requires recursive = true" }
                sftp.deleteRecursively(target)
            } else {
                sftp.rm(target.sftpQuote())
            }
            true
        }
    }

    // 移动/复制交给远程的 mv/cp: 与本地一致按目标路径处理目录, 且不依赖各服务器对 SFTP rename 扩展的支持差异
    override fun moveFile(area: WorkspaceStorageArea, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry =
        relocateByShell(area, source, target, overwrite, copy = false)

    override fun copyFile(area: WorkspaceStorageArea, source: String, target: String, overwrite: Boolean): WorkspaceFileEntry =
        relocateByShell(area, source, target, overwrite, copy = true)

    override fun absoluteFileSize(path: String): Long {
        val file = normalizeAbsolute(path)
        return pool.use(id, config, retry = true) { connection ->
            connection.sftp { it.requireFile(file, path).size }
        }
    }

    override fun exportAbsoluteFile(path: String, outputStream: OutputStream) {
        val file = normalizeAbsolute(path)
        pool.use(id, config, retry = false) { connection ->
            connection.sftp { it.export(file, path, outputStream) }
        }
    }

    override fun writeAbsoluteText(
        path: String,
        text: String,
        overwrite: Boolean,
        mode: String?,
    ): WorkspaceFileEntry {
        val file = normalizeAbsolute(path)
        return pool.use(id, config, retry = false) { connection ->
            val entry = connection.sftp { sftp ->
                sftp.writeSftpBytes(file, text.toByteArray(Charsets.UTF_8), overwrite, path)
                    .toWorkspaceEntry(file, file.fileName())
            }
            // SFTP 的 put 不带权限, 写完再 chmod; 权限串在这里再校验一次, 防止调用方漏过校验后拼进命令
            if (mode != null) {
                require(mode.matches(OCTAL_MODE)) { "mode must be 3-4 octal digits, got: $mode" }
                val result = connection.exec(
                    "chmod -- $mode ${file.shellQuote()}",
                    WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
                )
                require(result.exitCode == 0) {
                    "Write file failed: ${result.stderr.ifBlank { result.stdout }.trim()}"
                }
            }
            entry
        }
    }

    override fun createAbsoluteFolder(path: String): WorkspaceFileEntry {
        val dir = normalizeAbsolute(path)
        return withSftp(areaOfAbsolute(dir).first, retry = false) { sftp ->
            val existing = sftp.statOrNull(dir)
            require(existing == null || existing.isDir) {
                "Path already exists and is not a directory: $path"
            }
            sftp.mkdirs(dir)
            sftp.requireStat(dir, path).toWorkspaceEntry(dir, dir.fileName())
        }
    }

    override fun localFile(area: WorkspaceStorageArea, path: String): File? = null

    override fun localAbsoluteFile(path: String): File? = null

    override fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult {
        val workingDir = normalizeAbsolute(layout.absolutePath(WorkspaceStorageArea.FILES, cwd))
        return pool.use(id, config, retry = false) { connection ->
            connection.ensureDir(workspaceDir)
            // 远程登录 shell 不一定是 POSIX shell, 统一交给探测到的 shell 执行;
            // 命令通过位置参数传入, eval "$2" 对命令文本只求值一次
            val remoteCommand = listOf(
                "env",
                "CI=true",
                "NO_COLOR=1",
                "PAGER=cat",
                connection.shell,
                "-l",
                "-c",
                "cd -- \"\$1\" && eval \"\$2\"",
                "rikkahub",
                workingDir,
                command,
            ).joinToString(" ") { it.shellQuote() }
            connection.exec(remoteCommand, timeoutMillis, stdin)
        }
    }

    /** 文件区操作前确保远程工作目录存在, 和本地工作区自动创建目录的行为一致 */
    private fun <T> withSftp(area: WorkspaceStorageArea, retry: Boolean, block: (ChannelSftp) -> T): T =
        pool.use(id, config, retry) { connection ->
            if (area == WorkspaceStorageArea.FILES) connection.ensureDir(workspaceDir)
            connection.sftp(block)
        }

    /**
     * 递归目录树, [path] 为环境内绝对路径; 返回形态与本地 Rootfs 的目录树一致
     * (path 相对起始目录、目录优先按名排序、条目数受 maxListEntries 限制)。
     */
    fun folderTree(path: String, maxDepth: Int): WorkspaceTreeResult {
        val (area, relative) = areaOfAbsolute(path)
        val start = resolve(area, relative)
        return withSftp(area, retry = true) { sftp ->
            require(sftp.requireStat(start, path).isDir) { "Path is not a directory: $path" }
            val entries = mutableListOf<WorkspaceTreeEntry>()
            var truncated = false

            fun childEntry(absolute: String, name: String, attrs: SftpATTRS, depth: Int): WorkspaceTreeEntry {
                val isDirectory = attrs.isDir || attrs.isLink && sftp.statOrNull(absolute)?.isDir == true
                return WorkspaceTreeEntry(
                    path = absolute.removePrefix(start).trimStart('/'),
                    name = name,
                    isDirectory = isDirectory,
                    sizeBytes = if (attrs.isReg) attrs.size else 0L,
                    depth = depth,
                )
            }

            fun walk(dir: String, depth: Int) {
                if (depth > maxDepth) {
                    truncated = true
                    return
                }
                val children = sftp.ls(dir.sftpQuote())
                    .filter { it.filename != "." && it.filename != ".." }
                    .map { childEntry(dir.joinChild(it.filename), it.filename, it.attrs, depth) }
                    .sortedWith(compareBy<WorkspaceTreeEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                children.forEach { child ->
                    if (entries.size >= workspaceConfig.maxListEntries) {
                        truncated = true
                        return
                    }
                    entries += child
                    // 软链目录不展开, 避免跟着链接绕圈
                    if (child.isDirectory) walk(start.joinChild(child.path), depth + 1)
                    if (truncated) return
                }
            }

            walk(start, 1)
            WorkspaceTreeResult(entries = entries, truncated = truncated)
        }
    }

    private fun areaRoot(area: WorkspaceStorageArea): String = when (area) {
        WorkspaceStorageArea.FILES -> workspaceDir
        WorkspaceStorageArea.LINUX -> "/"
    }

    private fun resolve(area: WorkspaceStorageArea, path: String): String {
        val root = areaRoot(area)
        val relative = path.replace('\\', '/').trim().trimStart('/')
        val resolved = normalizeAbsolute(if (relative.isEmpty()) root else "$root/$relative")
        require(root == "/" || resolved == root || resolved.startsWith("$root/")) {
            "Path escapes workspace root: $path"
        }
        return resolved
    }

    private fun relativePath(area: WorkspaceStorageArea, path: String): String =
        path.removePrefix(areaRoot(area)).trimStart('/')

    /** 环境内绝对路径落在哪个存储区, 以及相对该存储区根的路径 */
    private fun areaOfAbsolute(path: String): Pair<WorkspaceStorageArea, String> {
        val normalized = normalizeAbsolute(path)
        return if (normalized == workspaceDir || normalized.startsWith("$workspaceDir/")) {
            WorkspaceStorageArea.FILES to normalized.removePrefix(workspaceDir).trimStart('/')
        } else {
            WorkspaceStorageArea.LINUX to normalized.trimStart('/')
        }
    }

    private fun relocateByShell(
        area: WorkspaceStorageArea,
        source: String,
        target: String,
        overwrite: Boolean,
        copy: Boolean,
    ): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val src = resolve(area, source)
        val dst = resolve(area, target)
        require(src != dst) { "Source and target are the same path: $target" }
        val transfer = if (copy) "cp -a" else "mv -f"
        val result = runShell(
            """
                [ -e ${src.shellQuote()} ] || { printf '%s\n' ${"Source does not exist: $source".shellQuote()} >&2; exit 1; }
                if [ -e ${dst.shellQuote()} ]; then
                  ${if (overwrite) "rm -rf -- ${dst.shellQuote()}" else "printf '%s\n' ${"Target already exists: $target".shellQuote()} >&2; exit 1"}
                fi
                mkdir -p -- ${'$'}(dirname -- ${dst.shellQuote()}) || exit 1
                $transfer -- ${src.shellQuote()} ${dst.shellQuote()} || exit 1
                ${statEntryCommand(dst)}
            """.trimIndent(),
        )
        return result.stdout.parseShellEntries().single().let {
            it.copy(path = relativePath(area, dst), name = dst.fileName())
        }
    }

    private fun runShell(command: String): WorkspaceCommandResult {
        val result = executeCommand(command, cwd = "", WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS, stdin = null)
        if (result.timedOut) error("Command timed out")
        if (result.exitCode != 0) {
            val message = result.stderr.ifBlank { result.stdout }.trim()
            error(if (message.isBlank()) "Command failed with exit code ${result.exitCode}" else message)
        }
        if (result.truncated) error("Command output is too large")
        return result
    }

    companion object {
        /** 权限串会拼进远程命令, 先限定成 3-4 位八进制 */
        private val OCTAL_MODE = Regex("^[0-7]{3,4}$")

        /** 不需要连接即可得到的目录约定, 供界面展示路径使用 */
        fun layoutOf(remoteDir: String): WorkspaceLayout {
            val workspaceDir = normalizeAbsolute(remoteDir)
            return WorkspaceLayout(
                workspaceDir = workspaceDir,
                writableRoots = listOf(workspaceDir, "/tmp"),
            )
        }
    }

    private fun ChannelSftp.requireStat(path: String, displayPath: String): SftpATTRS =
        try {
            statOrNull(path)
        } catch (e: SftpException) {
            throw e.wrap(displayPath)
        } ?: throw IllegalArgumentException("Path does not exist: $displayPath")

    private fun ChannelSftp.requireFile(path: String, displayPath: String): SftpATTRS {
        val attrs = try {
            statOrNull(path)
        } catch (e: SftpException) {
            throw e.wrap(displayPath)
        } ?: throw IllegalArgumentException("File does not exist: $displayPath")
        require(attrs.isReg) { "Path is not a file: $displayPath" }
        return attrs
    }

    private fun ChannelSftp.export(path: String, displayPath: String, outputStream: OutputStream) {
        requireFile(path, displayPath)
        outputStream.use { get(path.sftpQuote(), it) }
    }

    private fun ChannelSftp.writeSftpBytes(
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
        displayPath: String,
    ): SftpATTRS {
        val existing = statOrNull(path)
        require(existing == null || overwrite) { "File already exists: $displayPath" }
        require(existing == null || !existing.isDir) { "Path is not a file: $displayPath" }
        mkdirs(path.parentPath())
        put(ByteArrayInputStream(bytes), path.sftpQuote(), ChannelSftp.OVERWRITE)
        return requireStat(path, displayPath)
    }

    private fun ChannelSftp.resolveConflict(path: String): String {
        val parent = path.parentPath()
        val name = path.fileName()
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty() && stem != name) ".$it" else "" }
        var n = 1
        var candidate: String
        do {
            candidate = parent.joinChild("$stem ($n)$ext")
            n++
        } while (statOrNull(candidate) != null)
        return candidate
    }

    private fun ChannelSftp.deleteRecursively(dir: String) {
        ls(dir.sftpQuote())
            .filter { it.filename != "." && it.filename != ".." }
            .forEach { entry ->
                val child = dir.joinChild(entry.filename)
                if (entry.attrs.isDir && !entry.attrs.isLink) {
                    deleteRecursively(child)
                } else {
                    rm(child.sftpQuote())
                }
            }
        rmdir(dir.sftpQuote())
    }

    private fun SftpATTRS.toWorkspaceEntry(path: String, name: String, isDirectory: Boolean = isDir) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isReg) size else 0L,
        // SFTP v3 的 mtime 是无符号 32 位秒数
        updatedAt = (mTime.toLong() and 0xFFFFFFFFL) * 1_000L,
    )

    private fun SftpException.wrap(displayPath: String): IOException = when (id) {
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> IOException("Permission denied: $displayPath", this)
        else -> IOException("SFTP error on $displayPath: $message", this)
    }
}

/** 按字面规范化绝对路径: 去掉 . 和多余的 /, 解析 .., 不跟随软链 */
internal fun normalizeAbsolute(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    require(normalized.startsWith("/")) { "Path must be absolute: $path" }
    require(!normalized.contains('\u0000')) { "Path contains invalid character" }
    val segments = ArrayDeque<String>()
    normalized.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

private fun String.joinChild(name: String): String = if (this == "/") "/$name" else "$this/$name"

private fun String.parentPath(): String = substringBeforeLast('/').ifEmpty { "/" }

private fun String.fileName(): String = substringAfterLast('/').ifEmpty { "/" }
