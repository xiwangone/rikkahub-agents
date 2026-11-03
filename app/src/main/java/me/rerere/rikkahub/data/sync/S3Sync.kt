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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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

// 7 个构造依赖 = 既有 6 个 + 新增的逻辑备份段（2026-11 外部交付）；
// 拆成参数对象只会引入一层不必要的间接 —— 所有字段都是本类直接使用的稳定依赖。
@Suppress("TooManyFunctions", "LongParameterList")
class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val appDatabase: AppDatabase,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val logicalBackup: LogicalBackup,
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

    suspend fun backupToS3(config: S3Config): BackupStats = withContext(Dispatchers.IO) {
        val config = config.withLegacyExpanded()
        // 网络备份按迁移包逻辑：含明文密钥（settings apiKey / 逻辑域凭据）→ 必须整包口令加密
        requireBackupEncryption()
        val prepared = prepareBackupFile(config)
        val plainFile = prepared.file
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
            // 统计随返回值交给 UI：有解不开的凭证必须让用户知道
            prepared.toStats()
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

    suspend fun prepareBackupFile(config: S3Config): PreparedBackup = withContext(Dispatchers.IO) {
        cleanupStaleBackupTemps(context.cacheDir)
        // settings 的兼容判断要基于**用户原始勾选**：withLegacyExpanded 会把旧 FILES 展开成
        // 新枚举项，展开后再判断就再也识别不出“旧式配置”了。
        val wantSettings =
            config.items.contains(S3Config.BackupItem.SETTINGS) ||
                config.items.none { it in NEW_ITEM_KINDS }
        val config = config.withLegacyExpanded()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        val itemNames = config.items.map { it.name }
        // settings.json 含 provider apiKey；逻辑域是明文 JSON —— 任一被选中都必须整包加密
        if (LogicalDomains.anySelected(itemNames) || config.items.contains(S3Config.BackupItem.SETTINGS)) {
            requireBackupEncryption()
        }

        var logical = LogicalExport()

        // Create zip file and backup data
        ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile), 64 * 1024)).use { zipOut ->
            // 只有勾了 SETTINGS（或旧式配置）才带 settings.json，否则「只勾提供商/凭证」
            // 会顺带备份并恢复整份设置，粒度形同虚设。
            if (wantSettings) {
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )
            }

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
            if (config.items.contains(S3Config.BackupItem.TOOL_OUTPUTS)) {
                addFolderRecursiveToZip(zipOut, FileFolders.TOOL_OUTPUTS)
            }

            // Media sessions retain their authoritative relativePath files under 06's directory.
            addFolderRecursiveToZip(zipOut, FileFolders.MEDIA_CREATION)

            if (config.items.contains(S3Config.BackupItem.WORKSPACE_DOCS)) {
                backupWorkspaceDocs(zipOut)
            }

            // 按域逻辑备份：明文 JSON 写进 migration/ 段（整包随后由口令加密）
            val effectiveLogicalItems =
                if (backupEncryptionManager.isEnabled && backupEncryptionManager.rememberedPassword != null) {
                    LogicalDomains.expandImpliedForEncryptedBackup(itemNames)
                } else {
                    itemNames
                }
            logical = logicalBackup.writeTo(zipOut, effectiveLogicalItems)
        }

        AppLog.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        PreparedBackup(
            file = backupFile,
            credentialCount = logical.credentialCount,
            undecryptableCredentials = logical.undecryptableCredentials,
            providersIncluded = logical.providersIncluded,
        )
    }


    private fun backupWorkspaceDocs(zipOut: ZipOutputStream) {
        val workspacesRoot = File(context.filesDir, "workspaces")
        if (!workspacesRoot.isDirectory) return
        val excludeDirs = setOf("node_modules", "local-models", "cache", ".gradle", "build", ".git", "dist", ".next", ".venv")
        workspacesRoot.listFiles().orEmpty().forEach { wsDir ->
            val filesLayer = File(wsDir, "files")
            if (!wsDir.isDirectory || !filesLayer.isDirectory) return@forEach
            val wsName = wsDir.name
            filesLayer.listFiles().orEmpty().forEach { top ->
                val name = top.name
                if (name in excludeDirs || name == "tmp" || name == "linux") return@forEach
                if (top.isFile) {
                    addFileToZip(zipOut, top, "workspaces/$wsName/files/$name")
                } else if (top.isDirectory && RestoreFileTransaction.isSafeTreeEntry(filesLayer, top)) {
                    addDirectoryToZip(zipOut, top, top, "workspaces/$wsName/files/$name/")
                }
            }
        }
    }

    private fun requireBackupEncryption() {
        check(backupEncryptionManager.isEnabled && backupEncryptionManager.rememberedPassword != null) {
            "备份包包含明文密钥（提供商密钥/凭证库等）。请先在「备份与恢复 → 加密设置」开启加密并设置口令。"
        }
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        // Do not replace a live database in this process. Database members are validated in a
        // private staging directory and installed by Application before Room starts next launch.
        var databaseStage: File? = null
        fun stage(): File = databaseStage ?: PendingDatabaseRestore.createStage(context).also { databaseStage = it }
        // 逻辑段（明文，整包口令加密）只暂存，等新库安装、Room 打开后写回。
        val logicalEntries = mutableMapOf<String, String>()

        @Suppress("TooGenericExceptionCaught")
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(backupFile), 64 * 1024)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        AppLog.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                        when (zipEntry.name) {
                            "settings.json" -> {
                                if (config.items.contains(S3Config.BackupItem.SETTINGS) ||
                                    config.items.none { it in NEW_ITEM_KINDS }
                                ) {
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
                                } else {
                                    AppLog.i(TAG, "restoreFromBackupFile: Skipping settings.json (SETTINGS not selected)")
                                }
                            }

                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                                PendingDatabaseRestore.extractEntry(stage(), zipEntry.name, zipIn)
                            }
                        }

                        // migration/ 段（按域逻辑备份）→ 暂存，启动后写回
                        else -> {
                            if (zipEntry.name.trimStart('/').startsWith(MigrationPackage.DIR + "/")) {
                                val normalizedName = zipEntry.name.trimStart('/')
                                val selectedNames = config.items.map { it.name }.toSet()
                                if (LogicalDomains.shouldRestoreEntry(normalizedName, selectedNames)) {
                                    logicalEntries[normalizedName] =
                                        zipIn.readBytes().toString(Charsets.UTF_8)
                                } else {
                                    skipEntry(zipEntry)
                                }
                            } else {
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

                                    FileFolders.TOOL_OUTPUTS -> if (config.items.contains(S3Config.BackupItem.TOOL_OUTPUTS)) {
                                        PendingDatabaseRestore.extractFile(stage(), zipEntry.name, zipIn)
                                    } else skipEntry(zipEntry)

                                    "workspaces" -> if (config.items.contains(S3Config.BackupItem.WORKSPACE_DOCS)) {
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
                    }

                    zipIn.closeEntry()
                }
            }
        }

            databaseStage?.let { stage ->
                PendingDatabaseRestore.publish(context, stage)
            }
        } catch (failure: Throwable) {
            databaseStage?.let { PendingDatabaseRestore.discardStage(it) }
            throw failure
        }

        // 逻辑段暂存，待新库安装且 Room 打开后写回
        if (logicalEntries.isNotEmpty()) {
            PendingLogicalApply.stage(context, logicalEntries)
        }

        AppLog.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut, bufferSize = 64 * 1024)
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

    companion object {
        /** 新拆分的枚举项（旧配置不含），用于 settings.json 的兼容判断。 */
        private val NEW_ITEM_KINDS = setOf(
            S3Config.BackupItem.SETTINGS,
            S3Config.BackupItem.AVATARS,
            S3Config.BackupItem.WORKSPACE_DOCS,
            S3Config.BackupItem.SKILLS,
            S3Config.BackupItem.CHAT_FILES,
            S3Config.BackupItem.FONTS_IMAGES,
            S3Config.BackupItem.TOOL_OUTPUTS,
            S3Config.BackupItem.PROVIDERS,
            S3Config.BackupItem.VAULT_CREDENTIALS,
            S3Config.BackupItem.SSH_HOSTS,
            S3Config.BackupItem.SCHEDULED_JOBS,
        )
    }

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
