package me.rerere.common.cache

import java.io.File
import java.io.IOException

internal fun ensureParentDir(file: File) {
    val parent = file.parentFile
    if (parent != null && !parent.exists()) {
        if (!parent.mkdirs() && !parent.exists()) {
            throw IOException("Failed to create directory: $parent")
        }
    }
}

internal fun atomicWrite(
    file: File,
    content: String,
) {
    ensureParentDir(file)
    // A unique temp name: two stores writing the same file must not share one temp
    // file and interleave their contents.
    val tmp = File(file.parentFile, "${file.name}.tmp-${java.util.UUID.randomUUID()}")
    try {
        tmp.writeText(content)
        // Flush the temp file to disk before renaming, so a crash cannot leave a
        // half-written file at the target path.
        java.io.FileOutputStream(tmp, true).use { it.channel.force(true) }
        if (!tmp.renameTo(file)) {
            // Never delete the original first: on failure the old file stays intact.
            throw IOException("Failed to replace $file with temp file")
        }
        // Flush the parent directory so the rename itself is durable.
        runCatching {
            java.io.FileInputStream(file.parentFile).use { it.channel.force(true) }
        }
    } catch (e: IOException) {
        runCatching { tmp.delete() }
        throw e
    }
}
