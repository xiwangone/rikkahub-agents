package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.log.AppLog
import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
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
                val snapshot = File.createTempFile("backup-db-", ".sqlite", context.cacheDir)
                try {
                    check(snapshot.delete()) { "Cannot prepare database snapshot destination" }
                    DatabaseBackup.createSnapshot(appDatabase.openHelper.writableDatabase, snapshot)
                    // Preserve the historic archive member name for existing ZIP/Zip4j readers.
                    addFileToZip(zipOut, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                } finally {
                    snapshot.delete()
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
            // Media sessions retain their authoritative relativePath files under 06's directory.
            addFolderRecursiveToZip(zipOut, FileFolders.MEDIA_CREATION)
        }

        AppLog.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        // Do not replace a live database in this process. Database members are validated in a
        // private staging directory and installed by Application before Room starts next launch.
        var databaseStage: File? = null
        fun stage(): File = databaseStage ?: PendingDatabaseRestore.createStage(context).also { databaseStage = it }

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
                                PendingDatabaseRestore.stageSettings(context, stage(), settings)
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
                                PendingDatabaseRestore.extractEntry(stage(), zipEntry.name, zipIn)
                            }
                        }

                        else -> {
                            // 逐项还原：条目落在哪个目录，就要求本次勾了对应项
                            val folder = zipEntry.name.substringBefore('/', "")
                            when (folder) {
                                FileFolders.AVATARS -> if (config.wants(S3Config.BackupItem.AVATARS)) {
                                    if (zipEntry.name.removePrefix("${FileFolders.AVATARS}/").contains('/')) skipEntry(zipEntry) else PendingDatabaseRestore.extractFile(stage(), zipEntry.name, zipIn)
                                } else skipEntry(zipEntry)

                                FileFolders.UPLOAD -> if (config.wants(S3Config.BackupItem.CHAT_FILES)) {
                                    if (zipEntry.name.removePrefix("${FileFolders.UPLOAD}/").contains('/')) skipEntry(zipEntry) else PendingDatabaseRestore.extractFile(stage(), zipEntry.name, zipIn)
                                } else skipEntry(zipEntry)

                                FileFolders.SKILLS -> if (config.wants(S3Config.BackupItem.SKILLS)) {
                                    PendingDatabaseRestore.extractFile(stage(), zipEntry.name, zipIn)
                                } else skipEntry(zipEntry)

                                FileFolders.MEDIA_CREATION -> PendingDatabaseRestore.extractFile(stage(), zipEntry.name, zipIn)

                                FileFolders.FONTS, FileFolders.IMAGES ->
                                    if (config.wants(S3Config.BackupItem.FONTS_IMAGES)) {
                                        if (zipEntry.name.removePrefix("$folder/").contains('/')) skipEntry(zipEntry) else PendingDatabaseRestore.extractFile(stage(), zipEntry.name, zipIn)
                                    } else skipEntry(zipEntry)

                                else -> skipEntry(zipEntry)
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        @Suppress("TooGenericExceptionCaught") // 发布失败要清理 stage 后原样重抛，底层异常类型不可枚举
        databaseStage?.let { stage ->
            try {
                PendingDatabaseRestore.publish(context, stage)
            } catch (failure: Throwable) {
                stage.deleteRecursively()
                throw failure
            }
        }

        AppLog.i(TAG, "restoreFromBackupFile: Restore completed successfully")
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
            if (!RestoreFileTransaction.isSafeTreeEntry(rootDir, file)) return@forEach
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
        if (!RestoreFileTransaction.isSafeTreeEntry(dir, dir)) return
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

/** 未勾选的备份项或嵌套目录条目：不解包，遍历由下一次 nextEntry 推进。 */
@Suppress("UNUSED_PARAMETER")
private fun skipEntry(entry: ZipEntry) = Unit
