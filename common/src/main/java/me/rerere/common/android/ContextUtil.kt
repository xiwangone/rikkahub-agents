package me.rerere.common.android

import android.content.Context
import java.io.File
import java.io.IOException

val Context.appTempFolder: File
    get() {
        val dir = File(cacheDir, "temp")
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.exists()) {
                throw IOException("Failed to create directory: $dir")
            }
        }
        return dir
    }

fun Context.getCacheDirectory(namespace: String): File {
    val dir = File(cacheDir, "disk_cache/$namespace")
    if (!dir.exists()) {
        if (!dir.mkdirs() && !dir.exists()) {
            throw IOException("Failed to create directory: $dir")
        }
    }
    return dir
}
