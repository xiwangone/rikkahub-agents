package me.rerere.workspace.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceListResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTreeEntry
import me.rerere.workspace.WorkspaceTreeResult
import me.rerere.workspace.buildEnvAssignments
import me.rerere.workspace.normalizeWorkspacePath
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

    override fun listFiles(area: WorkspaceStorageArea, path: String, limit: Int?): List<WorkspaceFileEntry> =
        listFilesDetailed(area, path, limit).entries

    /**
     * 与 [listFiles] 相同, 但额外返回是否因上限截断以及截断前的总数,
     * 调用方据此判断目录是否还有未列出的条目。
     */
    override fun listFilesDetailed(area: WorkspaceStorageArea, path: String, limit: Int?): WorkspaceListResult {
        val dir = resolve(area, path)
        return withSftp(area, retry = true) { sftp ->
            val attrs = sftp.requireStat(dir, path)
            require(attrs.isDir) { "Path is not a directory: $path" }
            val all =
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
            val capped = limit ?: workspaceConfig.maxListEntries
            WorkspaceListResult(
                entries = all.take(capped),
                truncated = all.size > capped,
                totalCount = all.size,
            )
        }
    }

    override fun readText(path: String): String {
        val file = resolve(WorkspaceStorageArea.FILES, path)
        return withSftp(WorkspaceStorageArea.FILES, retry = true) { sftp ->
            val size = sftp.requireFile(file, path, WorkspaceStorageArea.FILES).size
            require(size <= workspaceConfig.maxReadBytes) {
                "File is too large to read: $size bytes (limit ${workspaceConfig.maxReadBytes} bytes). " +
                    "Read it in parts (start_line/end_line), or narrow it down with grep, " +
                    "or use shell head/tail to inspect a slice."
            }
            // 有界读取：不先信 stat 的大小，读到上限+1 字节即报错，避免 TOCTOU 下文件
            // 变大撑满内存（本地侧 readText 同口径）；不用 get 返回的流：它关闭时要读完
            // 未到的回应，读取被打断后会再次卡住。
            val out = ByteArrayOutputStream()
            sftp.get(file.sftpQuote(), BoundedOutputStream(out, workspaceConfig.maxReadBytes))
            val bytes = out.toByteArray()
            // 二进制按 UTF-8 解码只会得到替换字符，读出来"看着像文本"其实已损坏；
            // 显式拒绝，让调用方改用导出/下载工具取原文件。
            require(!bytes.hasBinaryContent()) {
                "File looks binary: $path (${bytes.size} bytes). " +
                    "Export or download it instead of reading it as text."
            }
            bytes.toString(Charsets.UTF_8)
        }
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= workspaceConfig.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes (limit ${workspaceConfig.maxWriteBytes} bytes). " +
                "Split it into smaller writes, or write it via shell in chunks."
        }
        val file = resolve(WorkspaceStorageArea.FILES, path)
        return withSftp(WorkspaceStorageArea.FILES, retry = false) { sftp ->
            sftp.writeSftpBytes(file, bytes, overwrite, path, area = WorkspaceStorageArea.FILES)
                .toWorkspaceEntry(relativePath(WorkspaceStorageArea.FILES, file), file.fileName())
        }
    }

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        // fileName 是单个文件名，不是路径：含 / 时会静默建成嵌套子目录。
        // 需要子目录请走 destinationPath。
        require(fileName.isNotBlank()) { "File name is required" }
        require(!fileName.contains('/') && !fileName.contains('\\')) {
            "File name must not contain path separators: $fileName"
        }
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        val file = resolve(area, targetPath)
        return inputStream.use { input ->
            withSftp(area, retry = false) { sftp ->
                // mkdirs 会跟随父目录软链，先校验父目录的实际位置。
                sftp.requireWithinRoot(area, file.parentPath(), targetPath)
                sftp.mkdirs(file.parentPath())
                val target = if (sftp.lstatOrNull(file) == null) file else sftp.resolveConflict(file)
                require(sftp.lstatOrNull(target)?.isLink != true) {
                    "Refusing to import through symbolic link: $targetPath"
                }
                val counting = CountingInputStream(input, workspaceConfig.maxImportBytes)
                // 原子写入：先传同目录临时文件再 rename，避免中断留下半截文件
                val tempTarget = target.parentPath().joinChild(".${target.fileName()}.import-tmp-${System.nanoTime()}")
                try {
                    sftp.put(counting, tempTarget.sftpQuote(), ChannelSftp.OVERWRITE)
                    if (sftp.lstatOrNull(target) != null) runCatching { sftp.rm(target.sftpQuote()) }
                    sftp.rename(tempTarget.sftpQuote(), target.sftpQuote())
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    runCatching { sftp.rm(tempTarget.sftpQuote()) }
                    throw e
                }
                sftp.requireStat(target, targetPath).toWorkspaceEntry(relativePath(area, target), target.fileName())
            }
        }
    }

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long {
        val file = resolve(area, path)
        return withSftp(area, retry = true) { it.requireFile(file, path, area).size }
    }

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) {
        val file = resolve(area, path)
        withSftp(area, retry = false) { sftp -> sftp.export(file, path, outputStream, area) }
    }

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val target = resolve(area, path)
        require(target != areaRoot(area)) { "Refusing to delete workspace root" }
        return withSftp(area, retry = false) { sftp ->
            // 删的是链接本身还是目标由 lstat 决定；父目录分量里的软链会让 rm 落到根外，
            // 先校验父目录的实际位置。
            sftp.requireWithinRoot(area, target.parentPath(), path)
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
        // mode 先校验再写：之前是写完才校验，非法 mode 报错时文件已落地。
        require(mode == null || mode.matches(OCTAL_MODE)) { "mode must be 3-4 octal digits, got: $mode" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= workspaceConfig.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes (limit ${workspaceConfig.maxWriteBytes} bytes). " +
                "Split it into smaller writes, or write it via shell in chunks."
        }
        return pool.use(id, config, retry = false) { connection ->
            connection.sftp { sftp ->
                sftp.writeSftpBytes(file, bytes, overwrite, path, mode)
                    .toWorkspaceEntry(file, file.fileName())
            }
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
        env: Map<String, String>,
    ): WorkspaceCommandResult {
        val workingDir = normalizeAbsolute(layout.absolutePath(WorkspaceStorageArea.FILES, cwd))
        return pool.use(id, config, retry = false) { connection ->
            connection.ensureDir(workspaceDir)
            if (config.platform == "WINDOWS") {
                // Windows 远端：跳过 POSIX shell 包装，直接拼 PowerShell 脚本；
                // UTF-8 前缀与 pwsh -EncodedCommand 包装由 SshConnection.exec 统一处理。
                // env 由 $env: 赋值传入（单引号内 ' 写成 ''，与 Set-Location 同口径）；
                // 值会出现在远端命令文本里，见 POSIX 分支的注释。
                val envAssignments = buildEnvAssignments(env).joinToString("") { assignment ->
                    val key = assignment.substringBefore('=')
                    val value = assignment.substringAfter('=', "")
                    "\$env:$key='${value.replace("'", "''")}'; "
                }
                val script = "\$env:CI='true'; \$env:NO_COLOR='1'; \$env:PAGER='cat'; " +
                    envAssignments +
                    "Set-Location -LiteralPath '${workingDir.replace("'", "''")}'; $command"
                connection.exec(script, timeoutMillis, stdin)
            } else {
                // 非登录 shell：远程登录 shell 会 source /etc/profile、~/.profile，
                // 里面的 echo/欢迎信息直接打到 stdout，机器解析的输出（搜索、
                // stat 元数据）会被污染；本机 Rootfs 的 profile 由应用控制且静默，
                // 所以本地仍用 -l，远程这里只用 -c。
                // 命令通过位置参数传入, eval "$2" 对命令文本只求值一次。
                // env 由外层 env 命令的参数传入（与本地 buildEnvAssignments 同口径，
                // 键非法或含 NUL 的直接丢弃）；值会出现在远端进程的命令行里，
                // 同台机器的其他用户 ps 可见——不要把高敏感凭据只当作普通 env 传。
                val remoteCommand = (
                    listOf(
                        "env",
                        "CI=true",
                        "NO_COLOR=1",
                        "PAGER=cat",
                    ) + buildEnvAssignments(env) + listOf(
                        connection.shell,
                        "-c",
                        "cd -- \"\$1\" && eval \"\$2\"",
                        "rikkahub",
                        workingDir,
                        command,
                    )
                ).joinToString(" ") { it.shellQuote() }
                connection.exec(remoteCommand, timeoutMillis, stdin)
            }
        }
    }

    /** 打开交互式终端: 在工作目录下启动用户的登录 shell */
    fun openShell(columns: Int, rows: Int): SshShell =
        pool.openShell(id, config) { connection, onClose ->
            connection.ensureDir(workspaceDir)
            // 外层交给探测到的 POSIX shell 解析, 再 exec 用户自己的登录 shell ($SHELL 缺失时退回探测结果)
            val remoteCommand = listOf(
                connection.shell,
                "-c",
                "cd -- \"\$1\"; exec \"\${SHELL:-\$0}\" -l",
                connection.shell,
                workspaceDir,
            ).joinToString(" ") { it.shellQuote() }
            connection.openShell(remoteCommand, columns, rows, onClose)
        }

    /** 文件区操作前确保远程工作目录存在, 和本地工作区自动创建目录的行为一致 */
    private fun <T> withSftp(area: WorkspaceStorageArea, retry: Boolean, block: (ChannelSftp) -> T): T =
        pool.use(id, config, retry) { connection ->
            if (area == WorkspaceStorageArea.FILES) connection.ensureDir(workspaceDir)
            connection.sftp(block)
        }

    /** 粗判二进制：含 NUL 字节，或按 UTF-8 解码后大量字符无法还原。 */
    @Suppress("MagicNumber")
    private fun ByteArray.hasBinaryContent(): Boolean {
        if (isEmpty()) return false
        val probe = if (size > 8 * 1024) copyOfRange(0, 8 * 1024) else this
        if (probe.any { it == 0.toByte() }) return true
        val decoded = probe.toString(Charsets.UTF_8)
        val replacement = decoded.count { it == '\uFFFD' }
        return replacement > 0 && replacement * 10 > decoded.length
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
            sftp.collectTree(start, maxDepth)
        }
    }

    /**
     * 逐层（广度优先）收集目录树。深度优先会让某个深层子树吃满 maxListEntries，
     * 把同层的其它条目（尤其顶层文件）整个挤掉。
     */
    private fun ChannelSftp.collectTree(start: String, maxDepth: Int): WorkspaceTreeResult {
        val entries = mutableListOf<WorkspaceTreeEntry>()
        var frontier = listOf(start to 1)
        var truncated = false
        while (frontier.isNotEmpty() && !truncated) {
            val next = mutableListOf<Pair<String, Int>>()
            for ((dir, depth) in frontier) {
                if (depth > maxDepth) {
                    truncated = true
                    break
                }
                truncated = appendLevel(dir, depth, start, entries, next)
                if (truncated) break
            }
            frontier = next
        }
        return WorkspaceTreeResult(entries = entries, truncated = truncated)
    }

    /** 列出一层并追加到 [entries]，可继续下探的目录写进 [next]；返回是否因条目上限截断。 */
    private fun ChannelSftp.appendLevel(
        dir: String,
        depth: Int,
        start: String,
        entries: MutableList<WorkspaceTreeEntry>,
        next: MutableList<Pair<String, Int>>,
    ): Boolean {
        for (child in listLevel(dir, depth, start)) {
            if (entries.size >= workspaceConfig.maxListEntries) return true
            entries += child.entry
            if (child.expandable) next += start.joinChild(child.entry.path) to depth + 1
        }
        return false
    }

    private fun ChannelSftp.listLevel(dir: String, depth: Int, start: String): List<TreeChild> =
        ls(dir.sftpQuote())
            .filter { it.filename != "." && it.filename != ".." }
            .map { lsEntry ->
                val entry = treeEntry(start, dir.joinChild(lsEntry.filename), lsEntry.filename, lsEntry.attrs, depth)
                TreeChild(entry, expandable = entry.isDirectory && !lsEntry.attrs.isLink)
            }
            .sortedWith(
                compareBy<TreeChild> { !it.entry.isDirectory }.thenBy { it.entry.name.lowercase() },
            )

    private fun ChannelSftp.treeEntry(
        start: String,
        absolute: String,
        name: String,
        attrs: SftpATTRS,
        depth: Int,
    ): WorkspaceTreeEntry {
        // 软链跟随一次 stat：显示目标的大小与类型，避免把链接本身的
        // 长度当成文件大小，也避免指向目录的链接被当成文件。
        val target = if (attrs.isLink) statOrNull(absolute) else null
        val isDirectory = attrs.isDir || target?.isDir == true
        val sizeBytes = when {
            attrs.isReg -> attrs.size
            target?.isReg == true -> target.size
            else -> 0L
        }
        return WorkspaceTreeEntry(
            path = absolute.removePrefix(start).trimStart('/'),
            name = name,
            isDirectory = isDirectory,
            sizeBytes = sizeBytes,
            depth = depth,
            isSymlink = attrs.isLink,
            // readlink 经 isUnique 做 glob 展开，路径里的 * ? [ ] 须先 sftpQuote；
            // 老服务端不支持 readlink 时返回空，不阻塞整棵树
            linkTarget = if (attrs.isLink) runCatching { readlink(absolute.sftpQuote()) }.getOrNull() else null,
        )
    }

    private data class TreeChild(val entry: WorkspaceTreeEntry, val expandable: Boolean)

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
        // 自嵌套守卫：本地后端（WorkspaceFileSystem.requireSameOperation）同样拦截。
        // 没有它 overwrite 分支的 rm -rf dst 会先摧毁 src 的子树（如 copy a -> a/b）。
        require(!dst.startsWith(src.trimEnd('/') + "/")) {
            "Refusing to relocate '$source' into its own subtree: $target"
        }
        // shell 的 rm -rf/mv/cp -a 会跟随父目录软链，只做字面规范化的 resolve() 看不到；
        // 先用 realpath 展开源与目标父目录的实际位置，越界即拒绝。只校验父目录：
        // 移动/复制的最终分量本身是软链时，mv/cp -a 只搬链接本身，不跟随。
        withSftp(area, retry = true) { sftp ->
            sftp.requireWithinRoot(area, src.parentPath(), source)
            sftp.requireWithinRoot(area, dst.parentPath(), target)
        }
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
            // 大目录 cp -a 在 30 秒前台超时内跑不完，超时杀掉后会留半截目标；
            // 复制给更长的超时，移动（同文件系统内改名）仍用默认超时。
            timeoutMillis = if (copy) RELOCATE_COPY_TIMEOUT_MS else WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            action = if (copy) "Copy" else "Move",
        )
        return parseSingleEntry(result.stdout, if (copy) "Copy" else "Move").let {
            it.copy(path = relativePath(area, dst), name = dst.fileName())
        }
    }

    /**
     * stat 输出解析失败时，`single()` 的裸报错（"List is empty" /
     * "Collection has more than one element"）看不出是元数据解析失败；
     * 包一层动作名并附原始输出片段。只改报错文案，不改解析逻辑本身。
     */
    private fun parseSingleEntry(output: String, action: String): WorkspaceFileEntry =
        try {
            output.parseShellEntries().single()
        } catch (error: Exception) {
            val snippet = output.replace('\u0000', ' ').trim().take(200)
            throw IllegalStateException(
                "$action failed: could not parse file metadata from shell output " +
                    "(${error.message}); output was: \"$snippet\"",
                error,
            )
        }

    private fun runShell(command: String, timeoutMillis: Long, action: String): WorkspaceCommandResult {
        val result = executeCommand(command, cwd = "", timeoutMillis, stdin = null)
        if (result.timedOut) {
            error(
                "$action timed out after ${timeoutMillis / 1000} seconds; " +
                    "the target may have been left partially copied or moved",
            )
        }
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

        /** 远程大目录复制的前台超时：30 秒默认值对大目录太短，超时后会留半截目标。 */
        private const val RELOCATE_COPY_TIMEOUT_MS = 10 * 60 * 1000L

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

    /**
     * 校验 [path] 经软链解析后的实际位置仍在 [area] 根目录内。
     *
     * [resolve] 只做字面规范化，看不到父目录分量里的软链；lstat 也只看得到最终分量。
     * 这里用 SFTP realpath 把路径（不存在时逐级上溯到存在的祖先再拼回剩余分量）展开成
     * 实际位置，再与根目录的实际位置比对：父目录里任何一条指向根外的软链都会让展开
     * 结果越界，从而被拒绝。根目录本身也取 realpath，避免根目录自身含软链时误判。
     * LINUX 区根即文件系统根，无可越界之处，直接放行。
     */
    private fun ChannelSftp.requireWithinRoot(area: WorkspaceStorageArea, path: String, displayPath: String) {
        val root = areaRoot(area)
        val canonicalRoot = runCatching { realpath(root) }.getOrNull() ?: root
        if (canonicalRoot == "/") return
        val canonical = canonicalize(path)
            ?: throw IOException("Cannot resolve path: $displayPath")
        require(canonical == canonicalRoot || canonical.startsWith("$canonicalRoot/")) {
            "Path escapes workspace root through a symbolic link: $displayPath"
        }
    }

    /** realpath 展开路径；不存在的分量逐级上溯到存在的祖先，展开后再把剩余分量拼回。 */
    private fun ChannelSftp.canonicalize(path: String): String? {
        var current = path
        val suffix = ArrayDeque<String>()
        while (true) {
            val resolved = try {
                realpath(current)
            } catch (e: SftpException) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e
                null
            }
            if (resolved != null) {
                var result = resolved
                while (suffix.isNotEmpty()) result += "/" + suffix.removeFirst()
                return normalizeAbsolute(result)
            }
            val parent = current.parentPath()
            if (parent == current) return null
            suffix.addFirst(current.fileName())
            current = parent
        }
    }

    private fun ChannelSftp.requireFile(
        path: String,
        displayPath: String,
        area: WorkspaceStorageArea? = null,
    ): SftpATTRS {
        // 用 lstat：目标是软链时拒绝读穿；写侧（writeSftpBytes）同口径。
        val linkAttrs = lstatOrNull(path)
            ?: throw IllegalArgumentException("File does not exist: $displayPath")
        require(!linkAttrs.isLink) { "Refusing to read through symbolic link: $displayPath" }
        val attrs = try {
            statOrNull(path)
        } catch (e: SftpException) {
            throw e.wrap(displayPath)
        } ?: throw IllegalArgumentException("File does not exist: $displayPath")
        require(attrs.isReg) { "Path is not a file: $displayPath" }
        // 父目录分量里的软链 lstat 看不到：用 realpath 展开后比对根目录，越界即拒绝。
        if (area != null) requireWithinRoot(area, path, displayPath)
        return attrs
    }

    private fun ChannelSftp.export(
        path: String,
        displayPath: String,
        outputStream: OutputStream,
        area: WorkspaceStorageArea? = null,
    ) {
        requireFile(path, displayPath, area)
        outputStream.use { get(path.sftpQuote(), it) }
    }

    private fun ChannelSftp.writeSftpBytes(
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
        displayPath: String,
        mode: String? = null,
        area: WorkspaceStorageArea? = null,
    ): SftpATTRS {
        // 用 lstat：目标是软链时拒绝写穿，只写链接本身指向的远端文件是越权
        val existing = lstatOrNull(path)
        require(existing == null || overwrite) { "File already exists: $displayPath" }
        require(existing == null || !existing.isDir) { "Path is not a file: $displayPath" }
        require(existing?.isLink != true) { "Refusing to write through symbolic link: $displayPath" }
        // 父目录分量里的软链 lstat 看不到：用 realpath 展开后比对根目录，越界即拒绝。
        // 目标不存在时取父目录展开（逐级上溯到存在的祖先）。
        if (area != null) requireWithinRoot(area, path, displayPath)
        mkdirs(path.parentPath())
        // 先写同目录临时文件、chmod 后再改名到位：目标文件从不以默认权限或半成品形态落地
        val temp = path.parentPath().joinChild(".${path.fileName()}.tmp-${java.util.UUID.randomUUID()}")
        try {
            put(ByteArrayInputStream(bytes), temp.sftpQuote(), ChannelSftp.OVERWRITE)
            if (mode != null) {
                try {
                    chmod(mode.toInt(8), temp.sftpQuote())
                } catch (e: Exception) {
                    // chmod 失败要单独说明，否则会被当成普通的写失败
                    throw IllegalStateException(
                        "Failed to set permissions $mode for $displayPath: ${e.message}",
                        e,
                    )
                }
            }
            rename(temp.sftpQuote(), path.sftpQuote())
        } catch (e: Exception) {
            runCatching { rm(temp.sftpQuote()) }
            throw e
        }
        return requireStat(path, displayPath)
    }

    private fun ChannelSftp.resolveConflict(path: String): String {
        val parent = path.parentPath()
        val name = path.fileName()
        // 点文件（如 .gitignore）的 stem 为空时不能用 "$stem ($n)$ext"，
        // 否则会产出首字符是空格的名字；此时把整个文件名当 stem 用。
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        val ext =
            if (stem == name) {
                ""
            } else {
                name.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
            }
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

    private fun SftpATTRS.toWorkspaceEntry(path: String, name: String, isDirectory: Boolean = isDir, isSymlink: Boolean = isLink) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isReg) size else 0L,
        // SFTP v3 的 mtime 是无符号 32 位秒数
        updatedAt = (mTime.toLong() and 0xFFFFFFFFL) * 1_000L,
        // ls 返回的是 lstat 语义的属性，isLink 即为该条目本身是否软链，无需额外往返
        isSymlink = isSymlink,
    )

    private fun SftpException.wrap(displayPath: String): IOException = when (id) {
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> IOException("Permission denied: $displayPath", this)
        else -> IOException("SFTP error on $displayPath: $message", this)
    }
}

/** 按字面规范化绝对路径: 去掉 . 和多余的 /, 解析 .., 不跟随软链 */
internal fun normalizeAbsolute(path: String): String = normalizeWorkspacePath(path.replace('\\', '/').trim())

/** 不跟随软链的 stat：不存在返回 null，其他错误照常抛出 */
internal fun ChannelSftp.lstatOrNull(path: String): SftpATTRS? =
    try {
        lstat(path.sftpQuote())
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
    }

/** 限流输出流：累计超过 [maxBytes] 即抛错，避免无上限读入撑满内存。 */
private class BoundedOutputStream(
    private val out: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    private var total = 0L

    override fun write(b: Int) {
        total++
        require(total <= maxBytes) {
            "File is too large to read: more than $maxBytes bytes. " +
                "Read it in parts (start_line/end_line), or narrow it down with grep, " +
                "or use shell head/tail to inspect a slice."
        }
        out.write(b)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        total += length
        require(total <= maxBytes) {
            "File is too large to read: more than $maxBytes bytes. " +
                "Read it in parts (start_line/end_line), or narrow it down with grep, " +
                "or use shell head/tail to inspect a slice."
        }
        out.write(buffer, offset, length)
    }

    override fun flush() = out.flush()
}

/** 限流输入流：累计超过 [maxBytes] 即抛错，避免无上限导入撑满远端磁盘 */
private class CountingInputStream(
    private val input: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var total = 0L

    override fun read(): Int {
        val byte = input.read()
        if (byte >= 0) {
            total++
            require(total <= maxBytes) { "File is too large to import: more than $maxBytes bytes" }
        }
        return byte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = input.read(buffer, offset, length)
        if (read > 0) {
            total += read
            require(total <= maxBytes) { "File is too large to import: more than $maxBytes bytes" }
        }
        return read
    }

    override fun close() = input.close()
}

private fun String.joinChild(name: String): String = if (this == "/") "/$name" else "$this/$name"

private fun String.parentPath(): String = substringBeforeLast('/').ifEmpty { "/" }

private fun String.fileName(): String = substringAfterLast('/').ifEmpty { "/" }
