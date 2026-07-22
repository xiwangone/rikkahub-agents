package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val background = WorkspaceBackgroundProcesses()

    // 让 startBackground 的启动+注册 与 deleteWorkspace 的 killAll+删除 互斥:
    // 要么启动先完成(随后被 killAll 杀掉), 要么删除先完成(随后 shellRunner.start 因 rootfs
    // 缺失而失败并抛出), 不会出现"进程活着但 workspace 目录已删"的孤儿进程
    private val backgroundLifecycleLock = Any()

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = synchronized(backgroundLifecycleLock) {
        // 先杀掉该 workspace 所有后台进程, 再删目录, 避免进程仍持有已删除目录下的 fd
        killAllBackground(root)
        workspaceDir(root).deleteRecursively()
    }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = resolveCommandWorkingDir(root, cwd)

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
            )
        )
    }

    /**
     * Starts [command] as a long-lived background process for [root]. The process runs
     * in the foreground of its own proot invocation; the caller polls/kills it via
     * [backgroundStatus]/[killBackground]. Throws IllegalStateException if [root] is
     * already at the running-process cap.
     */
    fun startBackground(root: String, command: String, cwd: String = ""): BackgroundStatus =
        synchronized(backgroundLifecycleLock) {
            require(command.isNotBlank()) { "Command is required" }
            val workingDir = resolveCommandWorkingDir(root, cwd)

            val process = shellRunner.start(
                WorkspaceShellContext(
                    root = root,
                    command = command,
                    cwd = cwd,
                    filesDir = filesDir(root),
                    linuxDir = linuxDir(root),
                    tempDir = tempDir(root),
                    workingDir = workingDir,
                    timeoutMillis = 0L,
                )
            )
            background.start(root, process, command, cwd)
        }

    fun backgroundStatus(root: String, id: String): BackgroundStatus? = background.status(root, id)

    fun listBackground(root: String): List<BackgroundStatus> = background.list(root)

    fun killBackground(root: String, id: String): Boolean = background.kill(root, id)

    fun killAllBackground(root: String) = background.killAll(root)

    private fun resolveCommandWorkingDir(root: String, cwd: String): File {
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }
        return workingDir
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}
