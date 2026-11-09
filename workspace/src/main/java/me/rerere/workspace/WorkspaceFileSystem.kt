package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.name

/** [WorkspaceFileSystem.listDetailed] 的返回: 条目 + 是否因上限截断 + 截断前总数。 */
data class WorkspaceListResult(
    val entries: List<WorkspaceFileEntry>,
    val truncated: Boolean,
    val totalCount: Int,
)

/** [WorkspaceFileSystem.grepDetailed] 的返回: 匹配 + 是否因上限截断 + 跳过的大文件数。 */
data class WorkspaceSearchResult(
    val matches: List<WorkspaceSearchMatch>,
    val truncated: Boolean,
    val skippedLargeFiles: Int,
)

/** [WorkspaceFileSystem.globDetailed] 的返回: 条目 + 空结果时给调用方的提示, 有匹配时为空。 */
data class WorkspaceGlobResult(
    val entries: List<WorkspaceFileEntry>,
    val hint: String?,
)

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(
        root: File,
        path: String = "",
        limit: Int = config.maxListEntries,
    ): List<WorkspaceFileEntry> = listDetailed(root, path, limit).entries

    /**
     * 与 [list] 相同, 但额外返回是否因 [limit] 截断以及截断前的总数,
     * 调用方据此判断目录是否还有未列出的条目。
     */
    fun listDetailed(
        root: File,
        path: String = "",
        limit: Int = config.maxListEntries,
    ): WorkspaceListResult {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        val all =
            dir
                .listFiles()
                .orEmpty()
                .filter { !it.name.startsWith(".l2s.") }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        return WorkspaceListResult(
            entries = all.take(limit).map { it.toEntry(root) },
            truncated = all.size > limit,
            totalCount = all.size,
        )
    }

    fun readText(
        root: File,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        // 有界读取：不先信 length()，读到上限+1 字节即报错，避免 TOCTOU 下文件变大撑满内存
        val out = java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var read = input.read(buffer)
            while (read >= 0) {
                total += read
                require(total <= config.maxReadBytes) {
                    "File is too large to read: more than ${config.maxReadBytes} bytes. " +
                        "Read it in parts (start_line/end_line), or narrow it down with grep, " +
                        "or use shell head/tail to inspect a slice."
                }
                out.write(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return out.toString(charset.name())
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes (limit ${config.maxWriteBytes} bytes). " +
                "Split it into smaller writes, or write it via shell in chunks."
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        val parent = file.parentFile ?: root
        parent.mkdirs()
        val temp = File(parent, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            temp.outputStream().use { out ->
                out.write(bytes)
                out.flush()
            }
            replaceFilePreservingTarget(temp, file)
        } finally {
            runCatching { temp.delete() }
        }
        return file.toEntry(root)
    }

    fun importBytes(
        root: File,
        path: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: root
        parent.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        val temp = File(parent, ".${target.name}.import-tmp-${System.nanoTime()}")
        runCatching {
            copyToTargetWithLimit(inputStream, temp, config.maxImportBytes)
            replaceFilePreservingTarget(temp, target)
        }.onFailure {
            runCatching { temp.delete() }
            runCatching { target.delete() }
        }.getOrThrow()
        return target.toEntry(root)
    }

    private fun copyToTargetWithLimit(input: InputStream, target: File, maxBytes: Long) {
        input.use { src ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var read = src.read(buffer)
                while (read >= 0) {
                    total += read
                    require(total <= maxBytes) {
                        "File is too large to import: more than $maxBytes bytes"
                    }
                    out.write(buffer, 0, read)
                    read = src.read(buffer)
                }
            }
        }
    }

    private fun resolveConflict(file: File): File {
        // 点文件（如 .gitignore）的 stem 为空时不能用 "$stem ($n)$ext"，
        // 否则会产出首字符是空格的名字；此时把整个文件名当 stem 用。
        val stem = file.nameWithoutExtension.ifBlank { file.name }
        val ext = if (stem == file.name) "" else file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do {
            candidate = File(file.parentFile, "$stem ($n)$ext")
            n++
        } while (candidate.exists())
        return candidate
    }

    fun delete(
        root: File,
        path: String,
        recursive: Boolean = false,
    ): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(
        root: File,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        requireSameOperation(sourceFile, targetFile, source, target, "move")
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            // 先把原目标改名到同目录备份，改名成功后再删备份：改名失败时原目标仍可恢复，不丢数据
            val backupFile = File(targetFile.parentFile, ".${targetFile.name}.bak-${java.util.UUID.randomUUID()}")
            require(targetFile.renameTo(backupFile)) { "Failed to replace target: $target" }
            try {
                targetFile.parentFile?.mkdirs()
                require(sourceFile.renameTo(targetFile)) {
                    "Failed to move $source to $target"
                }
            } catch (e: IllegalArgumentException) {
                runCatching { backupFile.renameTo(targetFile) }
                throw e
            }
            runCatching {
                if (backupFile.isDirectory) backupFile.deleteRecursively() else backupFile.delete()
            }
            return targetFile.toEntry(root)
        }
        targetFile.parentFile?.mkdirs()
        if (!sourceFile.renameTo(targetFile)) {
            // renameTo 在跨设备（不同挂载点）时失败，原因不写进返回值；
            // 回落到复制+删除，并把失败原因带进报错里。
            val reason =
                when {
                    !sourceFile.exists() -> "source no longer exists"
                    targetFile.exists() -> "target already exists"
                    targetFile.parentFile?.canWrite() == false -> "target directory is not writable"
                    else -> "rename failed (for example, source and target are on different filesystems)"
                }
            val budget = longArrayOf(0L)
            try {
                copyInto(sourceFile, targetFile, budget, mutableSetOf())
            } catch (error: Exception) {
                runCatching {
                    if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
                }
                throw IllegalStateException(
                    "Failed to move $source to $target: $reason; copy fallback also failed: ${error.message}",
                    error,
                )
            }
            if (!sourceFile.deleteRecursively()) {
                throw IllegalStateException(
                    "Moved $source to $target by copying, but failed to delete the source: $reason",
                )
            }
        }
        return targetFile.toEntry(root)
    }

    /**
     * 复制一个条目到 [target]（文件或整棵目录树）。
     *
     * 与 [move] 的区别：源保留；目录递归；累计字节受 [WorkspaceConfig.maxCopyBytes] 约束
     * （口径同 [importBytes] 的限流写法，但上限按「整单」而非「单文件」计，
     * 故用一个跨文件累计的计数器）。
     */
    fun copy(
        root: File,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to copy workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        requireSameOperation(sourceFile, targetFile, source, target, "copy")
        var backupFile: File? = null
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            // 先把原目标改名到同目录备份，复制成功后再删备份：复制失败时原目标仍可恢复，不丢数据
            backupFile = File(targetFile.parentFile, ".${targetFile.name}.bak-${java.util.UUID.randomUUID()}")
            require(targetFile.renameTo(backupFile)) { "Failed to replace target: $target" }
        }
        val budget = longArrayOf(0L)
        val visitedDirs = mutableSetOf<java.nio.file.Path>()
        runCatching { copyInto(sourceFile, targetFile, budget, visitedDirs) }
            .onFailure { error ->
                runCatching {
                    if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
                }
                backupFile?.let { backup ->
                    runCatching { backup.renameTo(targetFile) }
                }
                throw error
            }
            .onSuccess {
                backupFile?.let { backup ->
                    runCatching {
                        if (backup.isDirectory) backup.deleteRecursively() else backup.delete()
                    }
                }
            }
        return targetFile.toEntry(root)
    }

    private fun copyInto(
        source: File,
        target: File,
        copiedBytes: LongArray,
        visitedDirs: MutableSet<java.nio.file.Path>,
    ) {
        // 软链不跟随：链接本身不复制，树外目标与循环链接都进不来
        require(!Files.isSymbolicLink(source.toPath())) {
            "Refusing to copy symbolic link: ${source.name}"
        }
        if (source.isDirectory) {
            val canonicalDir = source.canonicalFile.toPath()
            require(visitedDirs.add(canonicalDir)) {
                "Refusing to copy directory cycle: ${source.name}"
            }
            require(target.mkdirs() || target.isDirectory) { "Failed to create directory: ${target.name}" }
            source.listFiles()
                .orEmpty()
                .sortedBy { it.name.lowercase() }
                .forEach { copyInto(it, File(target, it.name), copiedBytes, visitedDirs) }
            // 子项写完后再同步目录自身的权限与 mtime（建子项会改目录 mtime）
            syncModeAndTime(source, target)
            return
        }
        require(source.isFile) { "Unsupported entry type: ${source.name}" }
        source.inputStream().use { src ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = src.read(buffer)
                while (read >= 0) {
                    copiedBytes[0] = copiedBytes[0] + read
                    require(copiedBytes[0] <= config.maxCopyBytes) {
                        "Copy is too large: more than ${config.maxCopyBytes} bytes in total " +
                            "(limit ${config.maxCopyBytes} bytes). Copy in smaller batches or exclude large files."
                    }
                    out.write(buffer, 0, read)
                    read = src.read(buffer)
                }
            }
        }
        syncModeAndTime(source, target)
    }

    /** 复制后同步权限与 mtime，否则拷过去的脚本会丢可执行位、时间戳也会变。 */
    private fun syncModeAndTime(source: File, target: File) {
        target.setReadable(source.canRead(), false)
        target.setWritable(source.canWrite(), true)
        target.setExecutable(source.canExecute(), false)
        runCatching { target.setLastModified(source.lastModified()) }
    }

    /**
     * move/copy 共同的自嵌套守卫：原地操作会「先删目标再改名」从而丢数据，
     * 把目录移/复制进自己的子树则在 POSIX 下必然失败（这里换成明确报错）。
     */
    private fun requireSameOperation(
        sourceFile: File,
        targetFile: File,
        source: String,
        target: String,
        action: String,
    ) {
        require(sourceFile != targetFile) { "Refusing to $action onto itself: $source -> $target" }
        require(!targetFile.path.startsWith(sourceFile.path + File.separator)) {
            "Refusing to $action '$source' into its own subtree: $target"
        }
    }

    /**
     * 递归列目录为一棵树（目录优先、同级按名排序）。条目数或深度超限时置 truncated。
     */
    fun tree(root: File, path: String = "", maxDepth: Int = 10): WorkspaceTreeResult {
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        require(start.isDirectory) { "Path is not a directory: $path" }

        val entries = mutableListOf<WorkspaceTreeEntry>()
        var truncated = false

        fun walkDir(dir: File, depth: Int) {
            if (depth > maxDepth) {
                truncated = true
                return
            }
            val children = dir.listFiles()
                .orEmpty()
                .filter { !it.name.startsWith(".l2s.") }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            for (child in children) {
                if (entries.size >= config.maxListEntries) {
                    truncated = true
                    return
                }
                // 软链不跟随：不展开链接目录、不把链接文件当普通文件列出
                if (Files.isSymbolicLink(child.toPath())) continue
                entries += WorkspaceTreeEntry(
                    path = child.relativePath(start),
                    name = child.name,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    depth = depth,
                )
                if (child.isDirectory) walkDir(child, depth + 1)
                if (truncated) return
            }
        }
        walkDir(start, 1)

        return WorkspaceTreeResult(entries = entries, truncated = truncated)
    }

    fun glob(
        root: File,
        pattern: String,
        path: String = "",
        limit: Int = config.maxListEntries,
    ): List<WorkspaceFileEntry> = globDetailed(root, pattern, path, limit).entries

    /**
     * 与 [glob] 相同, 但非法 pattern 给出友好报错, 空结果时提示用递归 pattern。
     * `*.kt` 只匹配顶层，子目录里的文件要用递归 pattern。
     */
    fun globDetailed(
        root: File,
        pattern: String,
        path: String = "",
        limit: Int = config.maxListEntries,
    ): WorkspaceGlobResult {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = globMatcher(pattern)
        val entries =
            walk(start) { paths ->
                paths
                    .filter { !Files.isSymbolicLink(it) }
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { !it.toFile().name.startsWith(".l2s.") }
                    .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                    .take(limit)
                    .map { it.toFile().toEntry(root) }
                    .toList()
            }
        val hint =
            if (entries.isEmpty() && "**/" !in pattern) {
                "No files matched \"$pattern\". Patterns like \"*.kt\" only match the top level; " +
                    "use a recursive pattern such as \"**/*.kt\" to match files in subdirectories."
            } else {
                null
            }
        return WorkspaceGlobResult(entries = entries, hint = hint)
    }

    /** 非法 glob 语法直接抛 JDK 原文，看不懂；包一层说明 pattern 本身哪里有问题。 */
    private fun globMatcher(pattern: String): PathMatcher =
        try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (error: IllegalArgumentException) {
            // getPathMatcher 对非法语法抛 PatternSyntaxException（IllegalArgumentException 的子类）
            throw IllegalArgumentException(
                "Invalid glob pattern \"$pattern\": ${error.message}. " +
                    "Use \"*\" for names in one directory and \"**/*.kt\" to match recursively.",
                error,
            )
        }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> = grepDetailed(root, query, path, regex, ignoreCase, includeGlob).matches

    /**
     * 与 [grep] 相同, 但额外返回是否因 [WorkspaceConfig.maxSearchResults] 截断，
     * 以及因超 [WorkspaceConfig.maxReadBytes] 而跳过的大文件数。
     */
    fun grepDetailed(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): WorkspaceSearchResult {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher =
            includeGlob
                ?.takeIf { it.isNotBlank() }
                ?.let { globMatcher(it) }

        val results = mutableListOf<WorkspaceSearchMatch>()
        var truncated = false
        var skippedLargeFiles = 0
        walk(start) { paths ->
            paths
                .filter { !Files.isSymbolicLink(it) }
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { path ->
                    if (truncated) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) {
                        skippedLargeFiles++
                        return@forEach
                    }
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (matcher.containsMatchIn(line)) {
                                if (results.size >= config.maxSearchResults) {
                                    // 已满后又发现匹配，才算真的截断
                                    truncated = true
                                    return@useLines
                                }
                                results +=
                                    WorkspaceSearchMatch(
                                        path = file.relativePath(root),
                                        line = index + 1,
                                        text = line,
                                    )
                            }
                        }
                    }
                }
        }
        return WorkspaceSearchResult(
            matches = results,
            truncated = truncated,
            skippedLargeFiles = skippedLargeFiles,
        )
    }

    private fun <T> walk(
        start: File,
        block: (Sequence<Path>) -> T,
    ): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(
        root: File,
        path: String,
    ): File {
        // 读操作也不应静默吞掉建目录失败：建不出目录时明确报错
        if (!root.mkdirs() && !root.isDirectory) {
            error("Failed to create workspace root directory: ${root.path}")
        }
        // 不 trim 路径本身：首尾带空格的文件名（如 " notes.md"）是合法名字，
        // trim 会让它永远访问不到。空串/纯空白仍表示工作区根目录。
        val normalized =
            path
                .replace('\\', '/')
                .trimStart('/')
                .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(
        root: File,
        path: String,
    ): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry =
        WorkspaceFileEntry(
            path = relativePath(root),
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isFile) length() else 0L,
            updatedAt = lastModified(),
        )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path = FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String = joinToString("/") { it.name }
}