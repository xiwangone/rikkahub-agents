package me.rerere.workspace

import java.io.File
import java.io.IOException
import me.rerere.common.log.AppLogger

private const val TAG = "AtomicFileReplacement"

/**
 * Replace a file from a fully-written sibling temporary file without deleting the existing target.
 * Prefer the platform's same-filesystem rename (atomic on supported POSIX filesystems).
 * If replacement over an existing target is refused, preserve the target under a recovery name before retrying.
 * If the retry fails, restore the old target; if restoration also fails, leave its backup intact.
 */
@Suppress("TooGenericExceptionCaught")
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

    // On Linux/Android POSIX filesystems, renameTo() replaces existing target atomically.
    if (rename(tempFile, targetFile)) return
    if (!targetFile.exists()) {
        throw IOException("Failed to place file at ${targetFile.absolutePath}")
    }

    val backup = File(parent, "${targetFile.name}.restore-backup-${System.nanoTime()}")
    if (!rename(targetFile, backup)) {
        throw IOException("Could not preserve existing target ${targetFile.absolutePath}; target was not removed")
    }

    try {
        if (!rename(tempFile, targetFile)) {
            throw IOException("Could not install file; original target is preserved at ${backup.absolutePath}")
        }
    } catch (failure: Throwable) {
        if (!targetFile.exists() && backup.exists() && !rename(backup, targetFile)) {
            failure.addSuppressed(IOException("Could not restore original target; recovery copy remains at ${backup.absolutePath}"))
        }
        AppLogger.e(TAG, "replaceFilePreservingTarget failed: ${targetFile.absolutePath}", failure)
        throw failure
    }

    backup.delete()
}
