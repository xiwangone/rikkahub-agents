package me.rerere.rikkahub.data.sync

import java.io.File

/**
 * 恢复加密备份但缺少可用口令（本机未记住 / 口令错误）时抛出。
 * [encFile] 携带已下载的加密文件：UI 弹框让用户输口令后，
 * 调用方可用该文件直接解密重试，避免再次下载数百 MB。
 */
class BackupNeedsPasswordException(
    message: String,
    val encFile: File?,
) : Exception(message)
