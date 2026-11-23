@file:Suppress("TooGenericExceptionCaught") // 目录同步的失败模式与平台有关（Android 上 Os 为真实现、JVM 单测里是 stub），异常类型不可枚举

package me.rerere.rikkahub.data.sync

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ

/**
 * Durably sync a directory so a just-completed rename inside it survives a power loss.
 *
 * FileChannel cannot open a directory on Android (EISDIR), so the directory is opened
 * with [Os.open] and fsynced there. Plain O_RDONLY suffices: opening a directory
 * read-only succeeds on Linux, and OsConstants does not expose O_DIRECTORY. A null
 * descriptor (the android.jar stub under returnDefaultValues) also falls through to the
 * FileChannel path, which is the one JVM unit tests exercise. Both paths failing still
 * aborts the restore, preserving the transaction rollback contract.
 */
internal fun syncDirectory(directory: File) {
    val synced = runCatching {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            ?: error("Os.open returned null file descriptor")
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }.isSuccess
    if (synced) return
    try {
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    } catch (e: Exception) {
        throw IllegalStateException("Cannot durably sync restore directory", e)
    }
}
