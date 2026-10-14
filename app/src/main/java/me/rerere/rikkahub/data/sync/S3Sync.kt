package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.log.AppLog
import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ImportedDatabaseReconciler
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val appDatabase: AppDatabase,
    private val backupEncryptionManager: BackupEncryptionManager,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        AppLog.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val plainFile = prepareBackupFile(config)
        val file = backupEncryptionManager.maybeEncrypt(plainFile)
        val client = getS3Client(config)
        val key = "rikkahub_backups/${file.name}"

        try {
            client.putObject(
                key = key,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()

            AppLog.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            if (plainFile.exists()) plainFile.delete()
            if (file != plainFile && file.exists()) file.delete()
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/",
            maxKeys = 1000
        ).getOrThrow()

        result.objects
            .filter {
                it.key.startsWith("rikkahub_backups/backup_") &&
                    (it.key.endsWith(".zip") || it.key.endsWith(".enc"))
            }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            AppLog.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            AppLog.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // 若为加密容器则用记住口令解密；缺口令/口令错 → 保留文件并抛 NeedsPassword
            val plainFile =
                try {
                    backupEncryptionManager.maybeDecrypt(backupFile)
                } catch (e: IllegalStateException) {
                    throw BackupNeedsPasswordException(
                        message = e.message ?: "Backup is encrypted",
                        encFile = backupFile,
                        cause = e,
                    )
                } catch (e: IllegalArgumentException) {
                    throw BackupNeedsPasswordException(
                        message = e.message ?: "Backup is encrypted",
                        encFile = backupFile,
                        cause = e,
                    )
                }
            try {
                // Restore from backup file
                restoreFromBackupFile(plainFile, config)
            } finally {
                if (plainFile != backupFile && plainFile.exists()) {
                    plainFile.delete()
                }
            }
            // 恢复成功后才清理下载文件（NeedsPassword 场景保留供输口令后解密）
            if (backupFile.exists()) {
                backupFile.delete()
                AppLog.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        } catch (e: BackupNeedsPasswordException) {
            throw e
        }
    }

    /**
     * 用显式口令恢复 S3 加密备份。[cachedEncFile] 为之前 restoreFromS3 抛
     * [BackupNeedsPasswordException] 时保留的已下载文件；为空则重新下载。
     */
    suspend fun restoreFromS3WithPassword(
        config: S3Config,
        item: S3BackupItem,
        password: String,
        cachedEncFile: File? = null,
    ) = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val client = getS3Client(config)
        val backupFile = cachedEncFile?.takeIf { it.exists() } ?: File(context.cacheDir, item.displayName)

        try {
            if (!backupFile.exists() || cachedEncFile == null) {
                AppLog.i(TAG, "restoreFromS3WithPassword: Downloading ${item.displayName}")
                client.downloadObjectToFile(item.key, backupFile).getOrThrow()
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

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        AppLog.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            // Backup database files
            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
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

            // 文件类逐项打包：勾一个只打一个目录，避免"勾头像却打包 130MB 聊天附件"。
            // 旧聚合项 FILES 已由 withLegacyExpanded 展开成 SKILLS+CHAT_FILES+FONTS_IMAGES，
            // wants() 里的 FILES 分支因此只是语义冗余（与 WebDavSync 保持同一套判断）。
            if (config.wants(S3Config.BackupItem.AVATARS)) {
                addFolderFilesToZip(zipOut, FileFolders.AVATARS)
            }

            if (config.wants(S3Config.BackupItem.CHAT_FILES)) {
                addFolderFilesToZip(zipOut, FileFolders.UPLOAD)
            }

            if (config.wants(S3Config.BackupItem.SKILLS)) {
                addFolderRecursiveToZip(zipOut, FileFolders.SKILLS)
            }

            if (config.wants(S3Config.BackupItem.FONTS_IMAGES)) {
                addFolderFilesToZip(zipOut, FileFolders.FONTS)
                addFolderFilesToZip(zipOut, FileFolders.IMAGES)
            }
        }

        AppLog.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

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
        if (config.items.contains(S3Config.BackupItem.DATABASE)) {
            runCatching { appDatabase.close() }
                .onFailure { AppLog.w(TAG, "restoreFromBackupFile: appDatabase.close() before restore failed", it) }
            // 覆盖前留后路：万一协调/迁移出问题，还能人工把 .pre-restore-backup 换回去
            ImportedDatabaseReconciler.backupBeforeRestore(context)
        }

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    AppLog.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when (zipEntry.name) {
                        "settings.json" -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            AppLog.i(TAG, "restoreFromBackupFile: Restoring settings")
                            try {
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                val settings = json.decodeFromString<Settings>(migratedJson)
                                settingsStore.update(settings)
                                AppLog.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLog.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                throw Exception("Failed to restore settings: ${e.message}", e)
                            }
                        }

                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
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
                                    AppLog.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )
                                    extractEntryAtomically(zipIn, targetFile)
                                    when (zipEntry.name) {
                                        "rikka_hub-wal" -> restoredWal = true
                                        "rikka_hub-shm" -> restoredShm = true
                                    }
                                    when (zipEntry.name) {
                                        "rikka_hub-wal" -> restoredWal = true
                                        "rikka_hub-shm" -> restoredShm = true
                                    }
                                    AppLog.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                    )
                                }
                            }
                        }

                        else -> {
                            // 逐项还原：条目落在哪个目录，就要求本次勾了对应项
                            val folder = zipEntry.name.substringBefore('/', "")
                            when (folder) {
                                FileFolders.AVATARS -> if (config.wants(S3Config.BackupItem.AVATARS)) {
                                    restoreFolderEntry(zipIn, zipEntry, FileFolders.AVATARS)
                                } else skipEntry(zipEntry)

                                FileFolders.UPLOAD -> if (config.wants(S3Config.BackupItem.CHAT_FILES)) {
                                    restoreFolderEntry(zipIn, zipEntry, FileFolders.UPLOAD)
                                } else skipEntry(zipEntry)

                                FileFolders.SKILLS -> if (config.wants(S3Config.BackupItem.SKILLS)) {
                                    restoreSkillEntry(zipIn, zipEntry.name)
                                } else skipEntry(zipEntry)

                                FileFolders.FONTS, FileFolders.IMAGES ->
                                    if (config.wants(S3Config.BackupItem.FONTS_IMAGES)) {
                                        restoreFolderEntry(zipIn, zipEntry, folder)
                                    } else skipEntry(zipEntry)

                                else -> skipEntry(zipEntry)
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        // A backup exported from the base app lacks app-specific tables; reconcile the
        // restored file before Room opens it so the import doesn't crash on first launch.
        if (config.items.contains(S3Config.BackupItem.DATABASE)) {
            // appDatabase was already closed before the zip loop (see top of this function), so
            // the delete + reconcile below run with no live writer attached.
            val dbDir = context.getDatabasePath("rikka_hub").parentFile
            if (dbDir != null) {
                if (!restoredWal) File(dbDir, "rikka_hub-wal").delete()
                if (!restoredShm) File(dbDir, "rikka_hub-shm").delete()
            }
            ImportedDatabaseReconciler.reconcile(context)
        }

        AppLog.i(TAG, "restoreFromBackupFile: Restore completed successfully")
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
            replaceFilePreservingTarget(tmp, targetFile)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun checkpointDatabase() {
        try {
            appDatabase.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            AppLog.i(TAG, "checkpointDatabase: WAL checkpoint(TRUNCATE) done")
        } catch (e: Exception) {
            // Non-fatal: the -wal/-shm files are still copied below, so no committed data
            // is lost — the snapshot just isn't guaranteed torn-free for this run.
            AppLog.w(TAG, "checkpointDatabase: WAL checkpoint failed; copying db+wal+shm as-is", e)
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            AppLog.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
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

    /** 本次备份是否需要 [item] 对应的内容（旧聚合项 FILES 视为"全量"）。 */
    private fun S3Config.wants(item: S3Config.BackupItem): Boolean =
        items.any { it == item || it == S3Config.BackupItem.FILES }

    /** 只打包目录顶层文件（avatars/upload/fonts/images 的旧打包语义）。 */
    private fun addFolderFilesToZip(zipOut: ZipOutputStream, folder: String) {
        val dir = File(context.filesDir, folder)
        if (!dir.exists() || !dir.isDirectory) {
            AppLog.w(TAG, "addFolderFilesToZip: $folder does not exist or is not a directory")
            return
        }
        AppLog.i(TAG, "addFolderFilesToZip: Backing up $folder from ${dir.absolutePath}")
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                addFileToZip(zipOut, file, "$folder/${file.name}")
            }
        }
    }

    /** 递归打包整个目录（skills 等带子结构的目录）。 */
    private fun addFolderRecursiveToZip(zipOut: ZipOutputStream, folder: String) {
        val dir = File(context.filesDir, folder)
        if (!dir.exists() || !dir.isDirectory) {
            AppLog.w(TAG, "addFolderRecursiveToZip: $folder does not exist or is not a directory")
            return
        }
        AppLog.i(TAG, "addFolderRecursiveToZip: Backing up $folder from ${dir.absolutePath}")
        addDirectoryToZip(
            zipOut = zipOut,
            rootDir = dir,
            currentDir = dir,
            entryPrefix = "$folder/",
        )
    }

    /**
     * 恢复一个普通文件夹条目（upload/avatars/fonts/images）：只认顶层文件，
     * 并用 zip-slip 防护拒绝逃逸到目录外的条目（如 "../../databases/..."）。
     */
    private fun restoreFolderEntry(zipIn: ZipInputStream, zipEntry: ZipEntry, folder: String) {
        val relative = zipEntry.name.substringAfter("$folder/")
        if (relative.isEmpty() || relative.contains('/')) {
            AppLog.i(TAG, "restoreFolderEntry: Skipping $folder entry ${zipEntry.name}")
            return
        }
        val folderRoot = File(context.filesDir, folder).apply { mkdirs() }
        val targetFile = SkillPaths.resolveSkillFile(folderRoot, relative)
        if (targetFile == null) {
            AppLog.w(TAG, "restoreFolderEntry: Rejected unsafe $folder entry ${zipEntry.name}")
            return
        }
        targetFile.parentFile?.mkdirs()
        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            AppLog.i(TAG, "restoreFolderEntry: Restored ${zipEntry.name} (${targetFile.length()} bytes)")
        } catch (e: IOException) {
            AppLog.e(TAG, "restoreFolderEntry: Failed to restore ${zipEntry.name}", e)
            throw IOException("Failed to restore ${zipEntry.name}: ${e.message}", e)
        }
    }

    private fun skipEntry(zipEntry: ZipEntry) {
        AppLog.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            AppLog.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
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
            AppLog.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: IOException) {
            AppLog.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw IOException("Failed to restore skill file $entryName: ${e.message}", e)
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        AppLog.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
