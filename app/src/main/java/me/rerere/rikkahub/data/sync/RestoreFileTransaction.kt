@file:Suppress("TooGenericExceptionCaught", "EmptyElseBlock") // 恢复事务：失败即整体回滚，底层异常类型不可枚举；when 分支需显式兜底
package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** File-only transaction core shared by startup restore and filesystem fixture tests. */
internal object RestoreFileTransaction {
    fun install(
        transaction: File,
        targets: List<File>,
        stagedToTargets: List<Pair<File, File>>,
        identifyTarget: (File) -> String,
        resolveTarget: (String) -> File,
        validateInstalledState: () -> Unit,
        failAfterOperations: Int? = null,
    ) {
        check(!transaction.exists()) { "Unrecovered restore transaction" }
        check(transaction.mkdirs()) { "Cannot create restore transaction" }
        val originals = File(transaction, "originals").apply { check(mkdirs()) }
        val names = targets.indices.map { i -> "$i-${targets[i].name}" }
        val present = targets.map { it.exists() }
        durableWrite(File(transaction, "journal"), targets.indices.joinToString("\n") { i ->
            listOf(names[i], present[i].toString(), identifyTarget(targets[i])).joinToString("\t")
        })
        syncDirectory(transaction)
        var operations = 0
        fun operationCompleted() {
            operations++
            if (failAfterOperations == operations) error("Injected restore installation failure")
        }
        try {
            targets.forEachIndexed { i, target ->
                if (present[i]) {
                    move(target, File(originals, names[i]))
                    syncDirectory(originals)
                    operationCompleted()
                }
            }
            stagedToTargets.forEach { (staged, target) ->
                require(targets.any { it == target }) { "Install target was not journaled" }
                require(staged.isFile) { "Missing staged restore file: $staged" }
                move(staged, target)
                syncDirectory(requireNotNull(target.parentFile))
                operationCompleted()
            }
            validateInstalledState()
        } catch (failure: Throwable) {
            try { rollback(transaction, resolveTarget) }
            catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }

    /** Finalize only after the application has opened the restored Room database successfully. */
    fun confirm(transaction: File, pending: File) {
        check(File(transaction, "journal").isFile) { "Restore transaction journal missing" }
        durableWrite(File(transaction, "COMMITTED"), "ok")
        syncDirectory(transaction)
        check(transaction.deleteRecursively()) { "Cannot remove committed restore journal" }
        syncDirectory(requireNotNull(transaction.parentFile))
        if (pending.exists()) check(pending.deleteRecursively()) { "Cannot remove committed staged restore" }
    }

    fun rollback(transaction: File, resolveTarget: (String) -> File) {
        val journal = File(transaction, "journal")
        check(journal.isFile) { "Restore rollback journal missing; refusing startup" }
        val originals = File(transaction, "originals")
        journal.readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            check(parts.size == 3)
            val backup = File(originals, parts[0])
            val hadOriginal = parts[1].toBooleanStrict()
            val target = resolveTarget(parts[2])
            when {
                backup.exists() -> { if (target.exists()) check(target.delete()); move(backup, target) }
                !hadOriginal -> if (target.exists()) check(target.delete())
                else -> Unit
            }
        }
        check(transaction.deleteRecursively())
        syncDirectory(requireNotNull(transaction.parentFile))
    }

    fun resolveUnderRoot(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relative).canonicalFile
        check(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "Restore target escaped directory boundary"
        }
        var cursor: File? = File(canonicalRoot, relative)
        while (cursor != null && cursor.path != canonicalRoot.path) {
            check(!Files.isSymbolicLink(cursor.toPath())) { "Restore target traverses a symbolic link" }
            cursor = cursor.parentFile
        }
        check(!Files.isSymbolicLink(canonicalRoot.toPath())) { "Restore root is a symbolic link" }
        return target
    }

    /** Reject symlinks and canonical escapes while walking an archive source tree. */
    fun isSafeTreeEntry(root: File, entry: File): Boolean {
        val canonicalRoot = root.canonicalFile
        if (Files.isSymbolicLink(root.toPath())) return false
        var cursor: File? = entry
        while (cursor != null && cursor.path != root.path) {
            if (Files.isSymbolicLink(cursor.toPath())) return false
            cursor = cursor.parentFile
        }
        if (cursor == null) return false
        val canonicalEntry = entry.canonicalFile
        return canonicalEntry == canonicalRoot || canonicalEntry.path.startsWith(canonicalRoot.path + File.separator)
    }

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
        try { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
        catch (e: Exception) { throw IllegalStateException("Cannot durably sync restore directory", e) }
    }
}
