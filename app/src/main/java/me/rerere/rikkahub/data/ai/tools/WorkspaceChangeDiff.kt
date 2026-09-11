package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.utils.generateUnifiedDiff

/**
 * 工作区文件变更检测：用于让 `workspace_shell` 里跑的脚本所改动的文件也能像
 * `workspace_write_file` / `workspace_edit_file` 一样在对话里显示红绿 diff。
 *
 * 做法：执行命令前后各取一次快照（路径 → 大小/修改时间），对比出新增、修改、删除的文件，
 * 再对文本文件生成 unified diff。
 *
 * 取舍（shell 是高频工具，必须控制开销）：
 * - 只扫工作区文件区，跳过依赖/缓存类目录；
 * - 遍历条目数与单文件大小都有上限，超限则不产出 diff（退回原行为，不影响命令本身）；
 * - 二进制文件只记名称、不生成 diff。
 */
object WorkspaceChangePolicy {
    /** 最多遍历的目录条目数（含子目录），超过即放弃检测。 */
    const val MAX_ENTRIES = 3_000

    /** 单个文件超过此大小不生成 diff（避免读入超大文件）。 */
    const val MAX_DIFF_FILE_BYTES = 256L * 1024

    /** 一次命令最多展示的变更文件数。 */
    const val MAX_CHANGED_FILES = 15

    /** 执行前内容缓存的总字节上限（超出即不再缓存，仅能做文件名级变更提示）。 */
    const val MAX_CACHED_BYTES = 4L * 1024 * 1024

    /** 跳过这些目录（依赖 / 缓存 / 版本库元数据）。 */
    val SKIPPED_DIRS = setOf(
        ".git", "node_modules", "__pycache__", ".gradle", ".cache",
        "build", "dist", ".venv", "venv", ".idea", ".pytest_cache",
    )

    /** 文本扩展名白名单（其余按二进制处理，仅列名）。 */
    val TEXT_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "yml", "yaml",
        "md", "txt", "sh", "bash", "zsh", "html", "htm", "css", "scss", "sql", "toml",
        "ini", "cfg", "conf", "properties", "gradle", "c", "h", "cpp", "hpp", "rs", "go",
        "rb", "php", "swift", "m", "mm", "lua", "pl", "r", "csv", "tsv", "log", "env",
    )
}

/** 一个文件的快照指纹。 */
data class FileFingerprint(
    val sizeBytes: Long,
    val updatedAt: Long,
)

/** 快照：工作区文件区内的路径 → 指纹。 */
data class WorkspaceSnapshot(
    val files: Map<String, FileFingerprint>,
    /**
     * 可 diff 的文本文件内容缓存。
     *
     * 必须在**执行命令之前**留下旧内容，否则「修改」「删除」的文件在执行后再也读不到原文，
     * 无法生成 diff。只缓存文本扩展名且不超过单文件上限的内容，并有总量上限。
     */
    val contents: Map<String, String> = emptyMap(),
    /** 遍历是否因超过上限而截断；截断时不做变更判定，避免误报。 */
    val truncated: Boolean = false,
)

/** 变更类型。 */
enum class FileChangeKind { ADDED, MODIFIED, DELETED }

/** 单个文件的变更。 */
data class FileChange(
    val path: String,
    val kind: FileChangeKind,
    val diff: String? = null,
)

object WorkspaceChangeDiff {
    /**
     * 对比两份快照，产出变更列表（按路径稳定排序）。
     * 任一快照被截断时返回空列表——宁可不出 diff，也不要因漏扫而误报「删除」。
     */
    fun compare(before: WorkspaceSnapshot, after: WorkspaceSnapshot): List<FileChange> {
        if (before.truncated || after.truncated) return emptyList()
        val changes = mutableListOf<FileChange>()
        before.files.forEach { (path, old) ->
            val new = after.files[path]
            when {
                new == null -> changes += FileChange(path, FileChangeKind.DELETED)
                new != old -> changes += FileChange(path, FileChangeKind.MODIFIED)
            }
        }
        after.files.forEach { (path, _) ->
            if (path !in before.files) changes += FileChange(path, FileChangeKind.ADDED)
        }
        return changes.sortedBy { it.path }
    }

    fun isTextFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in WorkspaceChangePolicy.TEXT_EXTENSIONS
    }

    /**
     * 把变更列表渲染成一个多文件 unified diff（unified diff 本身支持多段 `---/+++`），
     * 已有的 DiffView 可直接渲染。
     *
     * @param before 执行前快照（提供「修改 / 删除」所需的旧内容）
     * @param after  执行后快照（提供「新增 / 修改」所需的新内容）
     */
    fun buildDiff(
        changes: List<FileChange>,
        before: WorkspaceSnapshot,
        after: WorkspaceSnapshot,
    ): String {
        val sb = StringBuilder()
        var emitted = 0
        for (change in changes) {
            if (emitted >= WorkspaceChangePolicy.MAX_CHANGED_FILES) {
                sb.appendLine("… 其余 ${changes.size - emitted} 个文件略")
                break
            }
            if (!isTextFile(change.path)) {
                sb.appendLine("${kindLabel(change.kind)} ${change.path}（二进制，未生成 diff）")
                emitted++
                continue
            }
            when (change.kind) {
                FileChangeKind.ADDED -> {
                    val newText = after.contents[change.path] ?: continue
                    sb.appendLine(generateUnifiedDiff("", newText, change.path))
                }
                FileChangeKind.DELETED -> {
                    val oldText = before.contents[change.path] ?: continue
                    sb.appendLine(generateUnifiedDiff(oldText, "", change.path))
                }
                FileChangeKind.MODIFIED -> {
                    val oldText = before.contents[change.path] ?: continue
                    val newText = after.contents[change.path] ?: continue
                    sb.appendLine(generateUnifiedDiff(oldText, newText, change.path))
                }
            }
            emitted++
        }
        return sb.toString().trimEnd()
    }

    /**
     * 采集快照：递归列出工作区文件区，并对可 diff 的文本文件缓存内容。
     *
     * @param listFiles 列出某相对目录下的条目
     * @param readText  读取某相对路径的文本（读取失败返回 null）
     */
    suspend fun takeSnapshot(
        listFiles: suspend (path: String) -> List<me.rerere.workspace.WorkspaceFileEntry>,
        readText: suspend (path: String) -> String?,
    ): WorkspaceSnapshot {
        val files = mutableMapOf<String, FileFingerprint>()
        val contents = mutableMapOf<String, String>()
        var cachedBytes = 0L
        var count = 0
        var truncated = false
        val queue = ArrayDeque<String>()
        queue += ""
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val entries = runCatching { listFiles(dir) }.getOrDefault(emptyList())
            for (entry in entries) {
                if (++count > WorkspaceChangePolicy.MAX_ENTRIES) {
                    truncated = true
                    break
                }
                if (entry.isDirectory) {
                    if (entry.name !in WorkspaceChangePolicy.SKIPPED_DIRS) queue += entry.path
                    continue
                }
                files[entry.path] = FileFingerprint(entry.sizeBytes, entry.updatedAt)
                if (isTextFile(entry.path) &&
                    entry.sizeBytes <= WorkspaceChangePolicy.MAX_DIFF_FILE_BYTES &&
                    cachedBytes < WorkspaceChangePolicy.MAX_CACHED_BYTES
                ) {
                    runCatching { readText(entry.path) }.getOrNull()?.let { text ->
                        contents[entry.path] = text
                        cachedBytes += text.length
                    }
                }
            }
            if (truncated) break
        }
        return WorkspaceSnapshot(files = files, contents = contents, truncated = truncated)
    }

    fun kindLabel(kind: FileChangeKind): String = when (kind) {
        FileChangeKind.ADDED -> "新增"
        FileChangeKind.MODIFIED -> "修改"
        FileChangeKind.DELETED -> "删除"
    }
}
