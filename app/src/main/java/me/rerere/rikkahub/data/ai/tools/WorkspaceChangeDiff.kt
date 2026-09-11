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
    /** 最多遍历的目录条目数（含子目录）。超出后仍产出「新增/修改」，但不判定删除。 */
    const val MAX_ENTRIES = 6_000

    /**
     * 遍历深度上限（相对扫描根）。
     *
     * 工作区可能挂着完整代码仓库（实测某工作区 2 万+ 文件），无限制遍历既慢又会触发条目上限，
     * 反而完全不产出 diff。命令改动的文件绝大多数在浅层，故默认限制深度；
     * 命令可传 cwd 进一步收窄扫描根。
     */
    const val MAX_DEPTH = 5

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
     *
     * 截断时（扫描未覆盖全量）**只报「新增 / 修改」，不报「删除」**——
     * 漏扫会被误判成删除，从而谎报「文件被删了」；而新增/修改是扫到即真实的。
     */
    fun compare(before: WorkspaceSnapshot, after: WorkspaceSnapshot): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        if (!before.truncated && !after.truncated) {
            before.files.forEach { (path, old) ->
                val new = after.files[path]
                when {
                    new == null -> changes += FileChange(path, FileChangeKind.DELETED)
                    new != old -> changes += FileChange(path, FileChangeKind.MODIFIED)
                }
            }
        } else {
            // 截断：仅报能确证的修改（两侧都扫到且指纹不同），跳过删除
            before.files.forEach { (path, old) ->
                val new = after.files[path]
                if (new != null && new != old) changes += FileChange(path, FileChangeKind.MODIFIED)
            }
        }
        after.files.forEach { (path, _) ->
            if (path !in before.files) changes += FileChange(path, FileChangeKind.ADDED)
        }
        return changes.sortedBy { it.path }
    }

    /** 敏感路径：文件名/路径含凭证线索时不生成 diff（脱敏之外的第二道闸） */
    fun isSensitivePath(path: String): Boolean {
        val p = path.lowercase()
        return SENSITIVE_PATH_HINTS.any { p.contains(it) }
    }

    private val SENSITIVE_PATH_HINTS =
        listOf(
            "vault-env", "load-creds", "credential", "credentials",
            ".env", "id_rsa", "id_ed25519", ".pem", ".key",
            "token", "secret", "password",
        )

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
            // 敏感路径不生成 diff：凭证/密钥文件的内容不得进入消息与记录
            if (isSensitivePath(change.path)) {
                sb.appendLine("${kindLabel(change.kind)} ${change.path}（内容已隐藏）")
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
        // 统一脱敏：避免任何疑似凭证/密钥片段随 diff 进入消息与聊天记录
        return me.rerere.rikkahub.data.vault.SecretMasker.mask(sb.toString().trimEnd())
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
        rootPath: String = "",
        maxDepth: Int = WorkspaceChangePolicy.MAX_DEPTH,
    ): WorkspaceSnapshot {
        val files = mutableMapOf<String, FileFingerprint>()
        val contents = mutableMapOf<String, String>()
        var cachedBytes = 0L
        var count = 0
        var truncated = false
        // 队列元素带深度，超过 maxDepth 的目录不再展开
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += rootPath to 0
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val entries = runCatching { listFiles(dir) }.getOrDefault(emptyList())
            for (entry in entries) {
                if (++count > WorkspaceChangePolicy.MAX_ENTRIES) {
                    truncated = true
                    break
                }
                if (entry.isDirectory) {
                    val descend = entry.name !in WorkspaceChangePolicy.SKIPPED_DIRS && depth < maxDepth
                    if (descend) queue += entry.path to (depth + 1)
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
