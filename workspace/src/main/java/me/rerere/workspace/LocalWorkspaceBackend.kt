package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import me.rerere.common.log.AppLogger

/** 本机上的工作区: 文件落在 [WorkspaceManager] 管理的目录下, 命令在 PRoot Rootfs 内执行 */
class LocalWorkspaceBackend(
    private val manager: WorkspaceManager,
    private val root: String,
    private val shellCompatibilityMode: Boolean = false,
) : WorkspaceBackend {
    override val layout: WorkspaceLayout
        get() = manager.layout

    override fun shell(): String = WorkspaceManager.rootfsShell(manager.linuxDir(root))

    override fun listFiles(area: WorkspaceStorageArea, path: String, limit: Int?): List<WorkspaceFileEntry> {
        manager.ensureWorkspace(root)
        return manager.listFiles(root, path, area, limit)
    }

    override fun listFilesDetailed(area: WorkspaceStorageArea, path: String, limit: Int?): WorkspaceListResult {
        manager.ensureWorkspace(root)
        return manager.listFilesDetailed(root, path, area, limit)
    }

    override fun readText(path: String): String {
        manager.ensureWorkspace(root)
        return manager.readText(root, path)
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        manager.ensureWorkspace(root)
        return manager.writeText(root, path, text, overwrite)
    }

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        manager.ensureWorkspace(root)
        return manager.importFile(root, destinationPath, area, fileName, inputStream)
    }

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long =
        manager.fileSize(root, path, area)

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) =
        manager.exportFile(root, path, area, outputStream)

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        manager.deleteFile(root, path, recursive, area)

    override fun moveFile(area: WorkspaceStorageArea, source: String, target: String, overwrite: Boolean) =
        manager.moveFile(root, source, target, overwrite, area)

    override fun copyFile(area: WorkspaceStorageArea, source: String, target: String, overwrite: Boolean) =
        manager.copyFile(root, source, target, overwrite, area)

    override fun absoluteFileSize(path: String): Long {
        manager.ensureWorkspace(root)
        return manager.rootfsFileSize(root, path)
    }

    override fun exportAbsoluteFile(path: String, outputStream: OutputStream) {
        manager.ensureWorkspace(root)
        manager.exportRootfsFile(root, path, outputStream)
    }

    // 在 Rootfs 内用 shell 写入: Rootfs 里的绝对路径软链只有 PRoot 内才能正确解析
    override fun writeAbsoluteText(
        path: String,
        text: String,
        overwrite: Boolean,
        mode: String?,
    ): WorkspaceFileEntry {
        require(mode == null || mode.matches(OCTAL_MODE)) { "mode must be 3-4 octal digits, got: $mode" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= manager.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes (limit ${manager.maxWriteBytes} bytes). " +
                "Split it into smaller writes, or write it via shell in chunks."
        }
        val pathArg = path.shellQuote()
        // 先写同目录临时文件、chmod 后再改名到位：目标文件从不以默认权限或半成品形态落地
        val tmpPath = "$path.tmp-${java.util.UUID.randomUUID()}"
        val tmpArg = tmpPath.shellQuote()
        val result = runCommand(
            action = "Write file",
            command = """
                if [ -e $pathArg ] && [ ${if (overwrite) 0 else 1} = 1 ]; then
                  printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
                  exit 1
                fi
                if [ -e $pathArg ] && [ ! -f $pathArg ]; then
                  printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
                  exit 1
                fi
                parent=${'$'}(dirname -- $pathArg) || exit 1
                mkdir -p -- "${'$'}parent" || exit 1
                cat > $tmpArg || exit 1
                ${mode?.let { "chmod $it -- $tmpArg || { rm -f -- $tmpArg; exit 1; }" }.orEmpty()}
                mv -f -- $tmpArg $pathArg || { rm -f -- $tmpArg; exit 1; }
                ${statEntryCommand(path)}
            """.trimIndent(),
            stdin = bytes,
        )
        return parseSingleEntry(result.stdout, "Write file")
    }

    override fun createAbsoluteFolder(path: String): WorkspaceFileEntry {
        val pathArg = path.shellQuote()
        val result = runCommand(
            action = "Create folder",
            command = """
                if [ -e $pathArg ] && [ ! -d $pathArg ]; then
                  printf '%s\n' ${"Path already exists and is not a directory: $path".shellQuote()} >&2
                  exit 1
                fi
                mkdir -p -- $pathArg || exit 1
                ${statEntryCommand(path)}
            """.trimIndent(),
            stdin = null,
        )
        return parseSingleEntry(result.stdout, "Create folder")
    }

    override fun localFile(area: WorkspaceStorageArea, path: String): File {
        manager.ensureWorkspace(root)
        return manager.resolveFile(root, path, area)
    }

    override fun localAbsoluteFile(path: String): File = manager.resolveRootfsFile(root, path)

    override fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): WorkspaceCommandResult {
        manager.ensureWorkspace(root)
        return try {
            manager.executeCommand(
                root = root,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                env = env,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        } catch (caught: CancellationException) {
            throw caught
        } catch (ignored: Exception) {
            // 命令文本可能含 env 值，不记；只记 cwd 与超时，失败详情见异常。
            AppLogger.e(TAG, "executeCommand failed: cwd=$cwd timeoutMs=$timeoutMillis", ignored)
            throw ignored
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
            AppLogger.e(TAG, "parseSingleEntry failed: $action", error)
            throw IllegalStateException(
                "$action failed: could not parse file metadata from shell output " +
                    "(${error.message}); output was: \"$snippet\"",
                error,
            )
        }

    private fun runCommand(action: String, command: String, stdin: ByteArray?): WorkspaceCommandResult {
        val result = executeCommand(command, cwd = "", WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS, stdin)
        if (result.timedOut) {
            error("$action timed out")
        }
        if (result.exitCode != 0) {
            val message = result.stderr.ifBlank { result.stdout }.trim()
            error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
        }
        if (result.truncated) {
            error("$action output is too large")
        }
        return result
    }

    private companion object {
        private const val TAG = "LocalWorkspaceBackend"
        /** 权限串会拼进 shell 命令, 先限定成 3-4 位八进制 */
        val OCTAL_MODE = Regex("^[0-7]{3,4}$")
    }
}
