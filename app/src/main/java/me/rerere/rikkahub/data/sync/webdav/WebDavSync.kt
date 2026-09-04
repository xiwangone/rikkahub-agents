package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ImportedDatabaseReconciler
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.sync.BackupEncryptionManager
import me.rerere.rikkahub.data.sync.BackupNeedsPasswordException
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val appDatabase: AppDatabase,
    private val backupEncryptionManager: BackupEncryptionManager,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val plainFile = prepareBackupFile(config)
        val file = backupEncryptionManager.maybeEncrypt(plainFile)
        val client = getClient(config)

        try {
            // Ensure the backup directory exists
            client.ensureCollectionExists().getOrThrow()

            // Upload the backup file (加密时为 .zip.enc，明文时为原 .zip)
            client.put(
                path = file.name,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()

            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            // Clean up temp files（明文 zip 与加密产物都清）
            if (plainFile.exists()) plainFile.delete()
            if (file != plainFile && file.exists()) file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter {
                !it.isCollection && it.displayName.startsWith("backup_") &&
                    (it.displayName.endsWith(".zip") || it.displayName.endsWith(".enc"))
            }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val client = getClient(config)
        // item.displayName is server-supplied (WebDavClient already reduces it to a bare
        // basename, but a malicious/misbehaving server is the whole point of not trusting
        // that alone): confirm the resolved path still lands inside cacheDir before using it
        // to build a local file, so a traversal segment can never cause a write elsewhere.
        val backupFile = resolveCacheFile(item.displayName)
            ?: throw Exception("Unsafe backup file name: ${item.displayName}")

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // 若为加密容器则用记住口令解密；缺口令/口令错 → 保留已下载文件并抛 NeedsPassword
            val plainFile =
                try {
                    backupEncryptionManager.maybeDecrypt(backupFile)
                } catch (e: Exception) {
                    if (e is IllegalStateException || e is IllegalArgumentException) {
                        throw BackupNeedsPasswordException(
                            message = e.message ?: "Backup is encrypted",
                            encFile = backupFile,
                        )
                    }
                    throw e
                }
            try {
                // Restore from backup file
                restoreFromBackupFile(plainFile, config)
            } finally {
                // 解密产生的临时明文 zip 清理
                if (plainFile != backupFile && plainFile.exists()) {
                    plainFile.delete()
                }
            }
            // 恢复成功后才清理下载的备份文件（NeedsPassword 场景保留供输口令后解密）
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        } catch (e: BackupNeedsPasswordException) {
            // 保留 backupFile（调用方输口令后用 restoreWithPassword 复用）；本处不再清理
            throw e
        }
    }

    /**
     * Confine [displayName] to a bare basename inside [context]'s cacheDir. Returns null if
     * the resolved canonical path would land outside cacheDir (e.g. a server-supplied
     * displayname smuggling "../" segments) or the name is blank/"."/"..".
     */
    private fun resolveCacheFile(displayName: String): File? {
        val baseName = File(displayName).name
        if (baseName.isBlank() || baseName == "." || baseName == "..") return null

        val canonicalCacheDir = context.cacheDir.canonicalFile
        val candidate = File(canonicalCacheDir, baseName).canonicalFile
        return candidate.takeIf { it.parentFile == canonicalCacheDir }
    }

    /**
     * 用显式口令恢复加密备份。
     * [cachedEncFile] 为之前 restore() 抛 [BackupNeedsPasswordException] 时保留的已下载文件；
     * 为空则重新走完整下载→解密。
     */
    suspend fun restoreWithPassword(
        config: WebDavConfig,
        item: WebDavBackupItem,
        password: String,
        cachedEncFile: File? = null,
    ) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val client = getClient(config)
        val backupFile =
            cachedEncFile?.takeIf { it.exists() }
                ?: (resolveCacheFile(item.displayName)
                    ?: throw Exception("Unsafe backup file name: ${item.displayName}"))

        try {
            if (!backupFile.exists() || cachedEncFile == null) {
                Log.i(TAG, "restoreWithPassword: Downloading ${item.displayName}")
                client.downloadToFile(item.displayName, backupFile).getOrThrow()
            }

            val plainFile = backupEncryptionManager.maybeDecrypt(backupFile, password = password)
            try {
                restoreFromBackupFile(plainFile, config)
            } finally {
                if (plainFile != backupFile && plainFile.exists()) {
                    plainFile.delete()
                }
            }
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            val plainFile = backupEncryptionManager.maybeDecrypt(file)
            try {
                restoreFromBackupFile(plainFile, config)
                Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
            } finally {
                if (plainFile != file && plainFile.exists()) {
                    plainFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            // settings.json —— 仅在勾选 SETTINGS（或兼容旧行为：未显式排除 SETTINGS 的老配置）时打包
            val wantSettings =
                config.items.contains(WebDavConfig.BackupItem.SETTINGS) ||
                    // 旧配置 items 只含 DATABASE/FILES（不含新枚举）时默认仍带 settings，避免升级后丢设置
                    config.items.none { it in NEW_ITEM_KINDS }
            if (wantSettings) {
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )
            }

            // Backup database files
            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                // Flush the WAL into the main db first so the copied rikka_hub.db is a
                // consistent snapshot instead of a torn read against a live WAL.
                checkpointDatabase()

                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            // 头像（独立小目录，与聊天图分离）——勾选 AVATARS 或旧 FILES 时都带（体积小）
            if (config.items.any {
                    it == WebDavConfig.BackupItem.AVATARS || it == WebDavConfig.BackupItem.FILES
                }
            ) {
                addFolderFilesToZip(zipOut, FileFolders.AVATARS, flat = true)
            }

            // 聊天图片/附件 upload/（大头，仅显式勾 CHAT_FILES；FILES 兼容旧聚合也带）
            if (config.items.any {
                    it == WebDavConfig.BackupItem.CHAT_FILES || it == WebDavConfig.BackupItem.FILES
                }
            ) {
                addFolderFilesToZip(zipOut, FileFolders.UPLOAD, flat = true)
            }

            // 技能（递归）
            if (config.items.any {
                    it == WebDavConfig.BackupItem.SKILLS || it == WebDavConfig.BackupItem.FILES
                }
            ) {
                addFolderRecursiveToZip(zipOut, FileFolders.SKILLS)
            }

            // 字体/贴图（顶层）
            if (config.items.any {
                    it == WebDavConfig.BackupItem.FONTS_IMAGES || it == WebDavConfig.BackupItem.FILES
                }
            ) {
                addFolderFilesToZip(zipOut, FileFolders.FONTS, flat = true)
                addFolderFilesToZip(zipOut, FileFolders.IMAGES, flat = true)
            }

            // 工具输出缓存
            if (config.items.contains(WebDavConfig.BackupItem.TOOL_OUTPUTS)) {
                addFolderRecursiveToZip(zipOut, FileFolders.TOOL_OUTPUTS)
            }

            // 工作区文档层：files/workspaces/<root>/files/ 下排除 linux/tmp/.git/大件，递归打包
            if (config.items.contains(WebDavConfig.BackupItem.WORKSPACE_DOCS)) {
                backupWorkspaceDocs(zipOut)
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun backupWorkspaceDocs(zipOut: ZipOutputStream) {
        val workspacesRoot = File(context.filesDir, "workspaces")
        if (!workspacesRoot.exists() || !workspacesRoot.isDirectory) {
            Log.w(TAG, "backupWorkspaceDocs: workspaces root missing: ${workspacesRoot.absolutePath}")
            return
        }
        workspacesRoot.listFiles().orEmpty().forEach { wsDir ->
            if (!wsDir.isDirectory) return@forEach
            val filesLayer = File(wsDir, "files")
            if (!filesLayer.exists() || !filesLayer.isDirectory) return@forEach
            val wsName = wsDir.name
            filesLayer.listFiles().orEmpty().forEach { top ->
                if (!top.exists()) return@forEach
                val name = top.name
                // 排除：系统层/临时/仓库对象库/体积过大目录
                if (name == "tmp" || name == "linux" || name == ".git" || name == "node_modules" ||
                    name == "local-models" || name == "cache" || name == ".gradle" || name == "build"
                ) {
                    Log.i(TAG, "backupWorkspaceDocs: skip workspace top-level $wsName/$name")
                    return@forEach
                }
                if (top.isFile) {
                    addFileToZip(zipOut, top, "workspaces/$wsName/files/$name")
                } else {
                    addDirectoryToZipWithExcludes(
                        zipOut = zipOut,
                        rootDir = top,
                        currentDir = top,
                        entryPrefix = "workspaces/$wsName/files/$name/",
                        excludeNames = WORKSPACE_EXCLUDE_DIRS,
                    )
                }
            }
        }
    }

    private fun addFolderFilesToZip(zipOut: ZipOutputStream, folder: String, flat: Boolean) {
        val dir = File(context.filesDir, folder)
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                addFileToZip(zipOut, file, "$folder/${file.name}")
            } else if (!flat && file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = file,
                    currentDir = file,
                    entryPrefix = "$folder/${file.name}/",
                )
            }
        }
    }

    private fun addFolderRecursiveToZip(zipOut: ZipOutputStream, folder: String) {
        val dir = File(context.filesDir, folder)
        if (!dir.exists() || !dir.isDirectory) return
        addDirectoryToZip(
            zipOut = zipOut,
            rootDir = dir,
            currentDir = dir,
            entryPrefix = "$folder/",
        )
    }

    private fun addDirectoryToZipWithExcludes(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
        excludeNames: Set<String>,
    ) {
        currentDir.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (name in excludeNames) return@forEach
            if (file.isDirectory) {
                addDirectoryToZipWithExcludes(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix + name + "/",
                    excludeNames = excludeNames,
                )
            } else if (file.isFile) {
                addFileToZip(zipOut, file, entryPrefix + name)
            }
        }
    }

    companion object {
        /** 新拆分的枚举项（旧配置不含），用于兼容判断 */
        private val NEW_ITEM_KINDS = setOf(
            WebDavConfig.BackupItem.SETTINGS,
            WebDavConfig.BackupItem.AVATARS,
            WebDavConfig.BackupItem.WORKSPACE_DOCS,
            WebDavConfig.BackupItem.SKILLS,
            WebDavConfig.BackupItem.CHAT_FILES,
            WebDavConfig.BackupItem.FONTS_IMAGES,
            WebDavConfig.BackupItem.TOOL_OUTPUTS,
        )

        /** workspace files 层递归打包时排除的目录名（可重建/巨大/敏感） */
        private val WORKSPACE_EXCLUDE_DIRS = setOf(
            "node_modules", "local-models", "cache", ".gradle", "build",
            ".git", "dist", ".next", ".venv",
        )
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        // Track whether the backup itself shipped a WAL/SHM. If it didn't, any -wal/-shm
        // left on disk belongs to the PRE-restore database and must be removed before Room
        // opens the restored db, or SQLite replays those stale frames over fresh data.
        var restoredWal = false
        var restoredShm = false

        // Release the live Room WAL connection BEFORE the zip loop overwrites rikka_hub.db.
        // The DB is opened in WAL mode, so the close()-time checkpoint folds the OLD connection's
        // cached WAL frames into whatever rikka_hub.db currently is. If we closed AFTER the
        // overwrite, that checkpoint would replay pre-restore frames over the freshly restored
        // bytes and corrupt the import — so the close has to come first. The restore caller
        // restarts the process afterwards, so Room reopens cleanly on the reconciled file.
        // (Best-effort: a concurrent DAO access could lazily reopen Room mid-restore; that race
        // is pre-existing and bounded by the user driving a deliberate, near-idle restore.)
        if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
            runCatching { appDatabase.close() }
                .onFailure { Log.w(TAG, "restoreFromBackupFile: appDatabase.close() before restore failed", it) }
        }

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when {
                        zipEntry.name == "settings.json" -> {
                            // settings 恢复受 SETTINGS 控制；兼容旧包（老备份总是带 settings.json）
                            if (config.items.contains(WebDavConfig.BackupItem.SETTINGS) ||
                                config.items.none { it in NEW_ITEM_KINDS }
                            ) {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                try {
                                    val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                    val settings = json.decodeFromString<Settings>(migratedJson)
                                    settingsStore.update(settings)
                                    Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                    throw Exception("Failed to restore settings: ${e.message}")
                                }
                            } else {
                                Log.i(TAG, "restoreFromBackupFile: Skipping settings.json (SETTINGS not selected)")
                            }
                        }

                        zipEntry.name == "rikka_hub.db" ||
                            zipEntry.name == "rikka_hub-wal" ||
                            zipEntry.name == "rikka_hub-shm" -> {
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                val dbFile = when (zipEntry.name) {
                                    "rikka_hub.db" -> context.getDatabasePath("rikka_hub")
                                    "rikka_hub-wal" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-wal"
                                    )

                                    "rikka_hub-shm" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-shm"
                                    )

                                    else -> null
                                }

                                dbFile?.let { targetFile ->
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )
                                    extractEntryAtomically(zipIn, targetFile)
                                    when (zipEntry.name) {
                                        "rikka_hub-wal" -> restoredWal = true
                                        "rikka_hub-shm" -> restoredShm = true
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                    )
                                }
                            }
                        }

                        // avatars/ —— AVATARS 或旧 FILES
                        zipEntry.name.startsWith("${FileFolders.AVATARS}/") -> {
                            if (config.items.any {
                                    it == WebDavConfig.BackupItem.AVATARS ||
                                        it == WebDavConfig.BackupItem.FILES
                                }
                            ) {
                                restoreFolderEntry(zipIn, zipEntry, FileFolders.AVATARS, allowNested = false)
                            }
                        }

                        // upload/ —— CHAT_FILES 或旧 FILES
                        zipEntry.name.startsWith("${FileFolders.UPLOAD}/") -> {
                            if (config.items.any {
                                    it == WebDavConfig.BackupItem.CHAT_FILES ||
                                        it == WebDavConfig.BackupItem.FILES
                                }
                            ) {
                                restoreFolderEntry(zipIn, zipEntry, FileFolders.UPLOAD, allowNested = false)
                            }
                        }

                        // skills/ —— SKILLS 或旧 FILES
                        zipEntry.name.startsWith("${FileFolders.SKILLS}/") -> {
                            if (config.items.any {
                                    it == WebDavConfig.BackupItem.SKILLS ||
                                        it == WebDavConfig.BackupItem.FILES
                                }
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            }
                        }

                        // fonts/ —— FONTS_IMAGES 或旧 FILES
                        zipEntry.name.startsWith("${FileFolders.FONTS}/") -> {
                            if (config.items.any {
                                    it == WebDavConfig.BackupItem.FONTS_IMAGES ||
                                        it == WebDavConfig.BackupItem.FILES
                                }
                            ) {
                                restoreFolderEntry(zipIn, zipEntry, FileFolders.FONTS, allowNested = false)
                            }
                        }

                        // images/ —— FONTS_IMAGES 或旧 FILES
                        zipEntry.name.startsWith("${FileFolders.IMAGES}/") -> {
                            if (config.items.any {
                                    it == WebDavConfig.BackupItem.FONTS_IMAGES ||
                                        it == WebDavConfig.BackupItem.FILES
                                }
                            ) {
                                restoreFolderEntry(zipIn, zipEntry, FileFolders.IMAGES, allowNested = false)
                            }
                        }

                        // tool_outputs/ —— TOOL_OUTPUTS（递归）
                        zipEntry.name.startsWith("${FileFolders.TOOL_OUTPUTS}/") -> {
                            if (config.items.contains(WebDavConfig.BackupItem.TOOL_OUTPUTS)) {
                                restoreFolderEntry(zipIn, zipEntry, FileFolders.TOOL_OUTPUTS, allowNested = true)
                            }
                        }

                        // workspaces/<root>/files/... —— WORKSPACE_DOCS
                        zipEntry.name.startsWith("workspaces/") -> {
                            if (config.items.contains(WebDavConfig.BackupItem.WORKSPACE_DOCS)) {
                                restoreWorkspaceEntry(zipIn, zipEntry)
                            }
                        }

                        else -> {
                            Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        // A backup exported from upstream RikkaHub lacks the fork-only tables; reconcile the
        // restored file before Room opens it so the import doesn't crash on first launch.
        if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
            // appDatabase was already closed before the zip loop (see top of this function), so
            // the delete + reconcile below run with no live writer attached.
            val dbDir = context.getDatabasePath("rikka_hub").parentFile
            if (dbDir != null) {
                if (!restoredWal) File(dbDir, "rikka_hub-wal").delete()
                if (!restoredShm) File(dbDir, "rikka_hub-shm").delete()
            }
            ImportedDatabaseReconciler.reconcile(context)
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    /**
     * Extract one zip entry to [targetFile] atomically: write to a temp file in the SAME
     * directory, then rename over the target. Used for rikka_hub.db/-wal/-shm, which used to
     * be written straight to their live path: a process kill mid-copy left a torn database
     * file; a rename is atomic on the same filesystem.
     */
    private fun extractEntryAtomically(zipIn: ZipInputStream, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val tmp = File(targetFile.parentFile, "${targetFile.name}.restore-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            if (!tmp.renameTo(targetFile)) {
                // Some filesystems won't rename onto an existing target; delete + retry.
                targetFile.delete()
                if (!tmp.renameTo(targetFile)) {
                    tmp.delete()
                    throw java.io.IOException("Failed to place restored file at ${targetFile.absolutePath}")
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun checkpointDatabase() {
        try {
            appDatabase.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            Log.i(TAG, "checkpointDatabase: WAL checkpoint(TRUNCATE) done")
        } catch (e: Exception) {
            // Non-fatal: the -wal/-shm files are still copied below, so no committed data
            // is lost — the snapshot just isn't guaranteed torn-free for this run.
            Log.w(TAG, "checkpointDatabase: WAL checkpoint failed; copying db+wal+shm as-is", e)
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    /**
     * 恢复一个普通文件夹条目（upload/avatars/fonts/images/tool_outputs）。
     * [allowNested] 为 true 时保留子目录结构，否则仅恢复顶层文件（兼容旧打包只存顶层）。
     */
    private fun restoreFolderEntry(
        zipIn: ZipInputStream,
        zipEntry: ZipEntry,
        folder: String,
        allowNested: Boolean,
    ) {
        val relative = zipEntry.name.substringAfter("$folder/")
        if (relative.isEmpty()) return
        if (!allowNested && relative.contains('/')) {
            Log.i(TAG, "restoreFolderEntry: Skipping nested $folder entry ${zipEntry.name}")
            return
        }
        val folderRoot = File(context.filesDir, folder).apply { mkdirs() }
        // zip-slip 防护：目标必须落在 folderRoot 内
        val targetFile = SkillPaths.resolveSkillFile(folderRoot, relative)
        if (targetFile == null) {
            Log.w(TAG, "restoreFolderEntry: Rejected unsafe $folder entry ${zipEntry.name}")
            return
        }
        targetFile.parentFile?.mkdirs()
        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFolderEntry: Restored ${zipEntry.name} (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFolderEntry: Failed to restore ${zipEntry.name}", e)
            throw Exception("Failed to restore ${zipEntry.name}: ${e.message}")
        }
    }

    /**
     * 恢复工作区文档条目 workspaces/<root>/files/<相对路径>。
     * 目标 = <filesDir>/workspaces/<root>/files/<相对路径>，zip-slip 防护同上。
     */
    private fun restoreWorkspaceEntry(zipIn: ZipInputStream, zipEntry: ZipEntry) {
        // workspaces/<root>/files/<rel>  或 workspaces/<root>/files/<rel>/... 
        val segments = zipEntry.name.split('/')
        if (segments.size < 4 || segments[0] != "workspaces" || segments[2] != "files") {
            Log.w(TAG, "restoreWorkspaceEntry: Unexpected workspace entry ${zipEntry.name}")
            return
        }
        val wsRoot = segments[1]
        if (wsRoot.isBlank() || wsRoot == "." || wsRoot == ".." || wsRoot.contains('\\')) {
            Log.w(TAG, "restoreWorkspaceEntry: Rejected unsafe workspace root in ${zipEntry.name}")
            return
        }
        val relative = segments.drop(3).joinToString("/")
        if (relative.isEmpty()) return
        val wsDir = File(File(context.filesDir, "workspaces"), wsRoot)
        val filesLayer = File(wsDir, "files").apply { mkdirs() }
        val targetFile = SkillPaths.resolveSkillFile(filesLayer, relative)
        if (targetFile == null) {
            Log.w(TAG, "restoreWorkspaceEntry: Rejected unsafe workspace entry ${zipEntry.name}")
            return
        }
        targetFile.parentFile?.mkdirs()
        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreWorkspaceEntry: Restored ${zipEntry.name} (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreWorkspaceEntry: Failed to restore ${zipEntry.name}", e)
            throw Exception("Failed to restore ${zipEntry.name}: ${e.message}")
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
