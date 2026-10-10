package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.IOException

/**
 * Replace a file from a fully-written sibling temporary file without deleting the existing target.
 * Prefer the platform's same-filesystem rename (atomic on supported filesystems). If replacement
 * over an existing target is refused, preserve the target under a recovery name before retrying.
 * If the retry fails, restore the old target; if restoration also fails, leave its backup intact.
 */
@Suppress("TooGenericExceptionCaught") // Rollback must run for any rename/copy failure so the original target is not lost.
internal fun replaceFilePreservingTarget(
    tempFile: File,
    targetFile: File,
    rename: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
) {
    val parent = targetFile.parentFile ?: throw IOException("Target has no parent directory: $targetFile")
    if (tempFile.parentFile?.canonicalFile != parent.canonicalFile) {
        throw IOException("Temporary file must be a sibling of the target: $tempFile")
    }
    if (!tempFile.isFile) throw IOException("Temporary file is missing: $tempFile")

    // On Android/Linux this normally performs one atomic rename and replaces the existing file.
    if (rename(tempFile, targetFile)) return
    if (!targetFile.exists()) {
        throw IOException("Failed to place restored file at ${targetFile.absolutePath}")
    }

    val backup = File(parent, "${targetFile.name}.restore-backup-${System.nanoTime()}")
    if (!rename(targetFile, backup)) {
        throw IOException("Could not preserve existing target ${targetFile.absolutePath}; target was not removed")
    }

    try {
        if (!rename(tempFile, targetFile)) {
            throw IOException("Could not install restored file; original target is preserved at ${backup.absolutePath}")
        }
    } catch (failure: Throwable) {
        if (!targetFile.exists() && backup.exists() && !rename(backup, targetFile)) {
            failure.addSuppressed(IOException("Could not restore original target; recovery copy remains at ${backup.absolutePath}"))
        }
        throw failure
    }

    // Replacement succeeded; a stale recovery copy is harmless if the filesystem refuses deletion.
    backup.delete()
}
