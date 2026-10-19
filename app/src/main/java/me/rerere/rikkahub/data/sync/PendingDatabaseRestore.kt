@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions") // 恢复编排集中一处：步骤顺序与清理口径强相关，拆散会丢顺序
package me.rerere.rikkahub.data.sync

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** One durable staging transaction for a DB, portable settings, and selected app-private files. */
internal object PendingDatabaseRestore {
    private const val ROOT = "pending-db-restore"
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private val databaseNames = setOf("rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm")
    private val allowedFileRoots = setOf("avatars", "upload", "skills", "fonts", "images", "tool_outputs", "workspaces", "workspace", "media_creation")

    fun createStage(context: Context): File {
        val root = root(context)
        check(!File(root, "pending").exists()) { "Another restore is pending" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create restore staging root" }
        return File(root, "incoming-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    fun extractEntry(stage: File, entryName: String, input: InputStream) {
        require(entryName in databaseNames) { "Unsupported database archive member: $entryName" }
        extractTo(File(stage, entryName), input, stage)
    }

    /** Stage an archive file under the corresponding filesDir path; reject traversal and unknown roots. */
    fun extractFile(stage: File, archivePath: String, input: InputStream) {
        if (archivePath.endsWith('/')) return // ZIP directory marker; never a payload file.
        val relative = safeArchivePath(archivePath)
        val filesRoot = File(stage, "payload/files")
        val target = RestoreFileTransaction.resolveUnderRoot(filesRoot, relative)
        extractTo(target, input, stage)
    }

    /** Settings are decoded from portable JSON, then written through SettingsStore's shared encoder. */
    suspend fun stageSettings(context: Context, stage: File, settings: me.rerere.rikkahub.data.datastore.Settings) {
        val file = File(stage, "payload/datastore/settings.preferences_pb")
        me.rerere.rikkahub.data.datastore.SettingsStore.writeColdSettings(context, settings, file)
    }

    private fun extractTo(out: File, input: InputStream, stage: File) {
        require(!out.exists()) { "Duplicate restore member: ${out.name}" }
        val totalBefore = stage.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        out.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        var total = totalBefore
        FileOutputStream(out).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_TOTAL_BYTES) { "Restore archive exceeds uncompressed size limit" }
                stream.write(buffer, 0, count)
            }
            stream.fd.sync()
        }
    }

    fun publish(context: Context, stage: File) {
        val database = File(stage, DatabaseBackup.ARCHIVE_DATABASE)
        if (database.exists()) DatabaseBackup.validate(database)
        val payloadRoot = File(stage, "payload")
        val payload = payloadRoot.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(payloadRoot).invariantSeparatorsPath
        }.sorted().toList()
        require(database.exists() || payload.isNotEmpty()) { "Restore archive has no selected payload" }
        // Manifest is immutable after publication and contains only normalized, staged paths.
        durableWrite(File(stage, "manifest"), buildString {
            append("v1\n")
            append("database=").append(database.exists()).append('\n')
            payload.forEach { append("file=").append(it).append('\n') }
        })
        syncDirectory(stage)
        val root = root(context)
        val pending = File(root, "pending")
        check(!pending.exists()) { "Another restore is pending" }
        move(stage, pending)
        syncDirectory(root)
    }

    /** Must be invoked synchronously in Application.attachBaseContext, before providers/Koin/Room. */
    fun applyAtStartup(context: Context): Boolean {
        val root = root(context)
        if (!root.exists()) return false
        check(root.isDirectory) { "Restore root is not a directory" }
        RandomAccessFile(File(root, "install.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                refuseIfOtherAppProcess(context)
                recoverInterruptedInstall(context, root)
                val pending = File(root, "pending")
                if (!pending.exists()) return false
                val manifest = readManifest(pending)
                if (manifest.database) DatabaseBackup.validate(File(pending, DatabaseBackup.ARCHIVE_DATABASE))
                manifest.files.filter { it.startsWith("datastore/") }.forEach {
                    check(it == "datastore/settings.preferences_pb" && File(pending, "payload/$it").isFile) {
                        "Unsupported staged settings target"
                    }
                }
                installWithRollback(context, root, pending, manifest)
                return true
            }
        }
    }

    /** Commit only after Application has opened the restored Room database successfully. */
    fun confirmStartup(context: Context) {
        val root = root(context)
        RandomAccessFile(File(root, "install.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                refuseIfOtherAppProcess(context)
                RestoreFileTransaction.confirm(File(root, "installing"), File(root, "pending"))
                syncDirectory(root)
            }
        }
    }

    private data class Manifest(val database: Boolean, val files: List<String>)

    private fun readManifest(pending: File): Manifest {
        val manifest = File(pending, "manifest")
        check(manifest.isFile) { "Pending restore has no manifest; refusing startup" }
        val lines = manifest.readLines()
        check(lines.firstOrNull() == "v1") { "Unsupported restore manifest" }
        val databaseLines = lines.filter { it.startsWith("database=") }
        check(databaseLines.size == 1 && databaseLines.single() in setOf("database=true", "database=false")) {
            "Invalid database manifest entry"
        }
        check(lines.drop(2).all { it.startsWith("file=") }) { "Unknown restore manifest record" }
        val database = databaseLines.single() == "database=true"
        val files = lines.filter { it.startsWith("file=") }.map { line ->
            val path = line.removePrefix("file=")
            if (path.startsWith("datastore/")) {
                require(path == "datastore/settings.preferences_pb")
            } else require(path.startsWith("files/") && safeArchivePath(path.removePrefix("files/")) == path.removePrefix("files/"))
            check(File(pending, "payload/$path").isFile) { "Missing staged restore payload: $path" }
            path
        }
        check(files.distinct().size == files.size) { "Duplicate restore manifest path" }
        check(database || files.isNotEmpty()) { "Empty restore manifest" }
        return Manifest(database, files)
    }

    private fun installWithRollback(context: Context, root: File, pending: File, manifest: Manifest) {
        val transaction = File(root, "installing")
        val db = context.getDatabasePath("rikka_hub")
        val dbDir = requireNotNull(db.parentFile)
        check(dbDir.isDirectory || dbDir.mkdirs())
        val targets = mutableListOf<File>()
        if (manifest.database) targets += listOf(db, File(dbDir, "rikka_hub-wal"), File(dbDir, "rikka_hub-shm"), File(dbDir, "rikka_hub-journal"))
        manifest.files.forEach { relative ->
            targets += if (relative.startsWith("datastore/")) appFilesTarget(context, relative)
                else appFilesTarget(context, relative.removePrefix("files/"))
        }
        val staged = mutableListOf<Pair<File, File>>()
        if (manifest.database) {
            listOf(db, File(dbDir, "rikka_hub-wal"), File(dbDir, "rikka_hub-shm")).forEach { target ->
                val source = File(pending, target.name)
                if (source.isFile) staged += source to target
            }
        }
        manifest.files.forEach { relative ->
            val target = if (relative.startsWith("datastore/")) appFilesTarget(context, relative)
                else appFilesTarget(context, relative.removePrefix("files/"))
            staged += File(pending, "payload/$relative") to target
        }
        try {
            RestoreFileTransaction.install(
                transaction = transaction,
                targets = targets,
                stagedToTargets = staged,
                identifyTarget = { targetRelative(context, it) },
                resolveTarget = { resolveTarget(context, it) },
                validateInstalledState = { if (manifest.database) DatabaseBackup.validate(db) },
            )
        } catch (failure: Throwable) {
            if (pending.exists() && !pending.deleteRecursively()) {
                failure.addSuppressed(IllegalStateException("Cannot discard failed staged restore"))
            }
            throw failure
        }
    }

    private fun appFilesTarget(context: Context, relative: String): File {
        return RestoreFileTransaction.resolveUnderRoot(context.filesDir, relative)
    }

    private fun targetRelative(context: Context, file: File): String =
        runCatching { file.canonicalFile.relativeTo(context.filesDir.canonicalFile).invariantSeparatorsPath }
            .getOrElse { "db/${file.name}" }

    private fun recoverInterruptedInstall(context: Context, root: File) {
        val transaction = File(root, "installing")
        if (!transaction.exists()) return
        if (File(transaction, "COMMITTED").isFile) {
            check(transaction.deleteRecursively())
            File(root, "pending").deleteRecursively()
        } else rollback(context, transaction)
    }

    private fun rollback(context: Context, transaction: File) {
        RestoreFileTransaction.rollback(transaction) { resolveTarget(context, it) }
    }

    private fun resolveTarget(context: Context, path: String): File {
        if (path.startsWith("db/")) {
            return RestoreFileTransaction.resolveUnderRoot(
                requireNotNull(context.getDatabasePath("rikka_hub").parentFile),
                path.removePrefix("db/"),
            )
        }
        return appFilesTarget(context, path)
    }

    internal fun safeArchivePath(path: String): String {
        require(path.isNotBlank() && '\\' !in path && !path.startsWith('/') && '\u0000' !in path)
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe archive path: $path" }
        require(segments.first() in allowedFileRoots) { "Unsupported attachment path: $path" }
        return segments.joinToString("/")
    }

    private fun refuseIfOtherAppProcess(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefix = context.packageName
        check(manager.runningAppProcesses.orEmpty().none { it.pid != Process.myPid() &&
            (it.processName == prefix || it.processName.startsWith("$prefix:")) }) {
            "Another app process is active; refusing restore"
        }
    }

    private fun root(context: Context) = File(context.noBackupFilesDir, ROOT)

    private fun durableWrite(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        move(temp, file)
    }

    private fun move(source: File, target: File) {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        try { Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { error("Filesystem does not support atomic restore rename") }
    }

    private fun syncDirectory(directory: File) {
        try { RandomAccessFile(directory, "r").use { it.fd.sync() } }
        catch (e: Exception) { throw IllegalStateException("Cannot durably sync restore directory", e) }
    }
}
