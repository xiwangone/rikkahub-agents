package me.rerere.rikkahub.ui.pages.mediacreation

import java.io.File

/** 预留一个不覆盖已有文件的相册目标名；同一毫秒内的后续保存会在扩展名前追加序号。 */
internal fun createUniqueMediaTarget(directory: File, requestedName: String): File {
    check(directory.exists() || directory.mkdirs()) { "Unable to create media directory" }
    val safeName = File(requestedName).name
    val extensionStart = safeName.lastIndexOf('.').takeIf { it > 0 } ?: safeName.length
    val stem = safeName.substring(0, extensionStart)
    val extension = safeName.substring(extensionStart)
    var suffix = 0
    while (true) {
        val candidateName = if (suffix == 0) safeName else "${stem}_$suffix$extension"
        val candidate = File(directory, candidateName)
        if (candidate.createNewFile()) return candidate
        suffix++
    }
}
