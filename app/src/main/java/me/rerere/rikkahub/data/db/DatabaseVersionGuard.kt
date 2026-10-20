package me.rerere.rikkahub.data.db

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 数据库结构版本守卫：在 Room 打开库之前跑。
 *
 * 做两件事：
 * 1. **升级前快照** —— 磁盘版本 < 代码版本时，把 `db` + `-wal` + `-shm` 原样复制一份，
 *    让"回滚到旧版本 App"有可恢复的原始数据（回滚 = 用快照恢复，而不是让 Room 倒着迁移）。
 * 2. **降级检测** —— 磁盘版本 > 代码版本（用户装回了旧版），照样快照并记录。
 *    不删除、不重建、不做 destructive fallback：数据安全优先。
 *
 * 版本号直接读 SQLite 文件头（offset 60，大端 4 字节），**不打开数据库** ——
 * 打开会触发 WAL 恢复等副作用，这里要零副作用。
 *
 * 快照只写**外部可见目录** `/sdcard/Android/data/<pkg>/files/db-snapshots/`：
 * 内部私有目录在没有 root 时取不出来，App 起不来时等于白存。外部目录无需权限，
 * 电脑或文件管理器就能拷走。只保留最近一次，空间不足时跳过（不把存储写满）。
 */
internal object DatabaseVersionGuard {
    private const val TAG = "DatabaseVersionGuard"
    private const val DB_NAME = "rikka_hub"
    private const val SNAPSHOT_DIR = "db-snapshots"
    private const val USER_VERSION_OFFSET = 60L
    private const val SQLITE_HEADER = "SQLite format 3\u0000"

    /** 返回 true 表示已做了快照；false 表示无需或失败（失败只记日志，不阻断启动）。 */
    fun beforeOpen(context: Context, codeVersion: Int): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.isFile) return false
        val installed = readUserVersion(dbFile) ?: return false
        if (installed == codeVersion) return false
        val kind = if (installed < codeVersion) "upgrade" else "downgrade"
        val dir =
            runCatching { snapshot(context, dbFile, installed, codeVersion, kind) }
                .getOrElse {
                    Log.w(TAG, "database snapshot failed ($kind $installed -> $codeVersion)", it)
                    null
                } ?: return false
        Log.w(TAG, "database $kind $installed -> $codeVersion, snapshot at ${dir.absolutePath}")
        return true
    }

    /** 读 SQLite 文件头的 user_version；不是 SQLite 文件或读不到时返回 null。 */
    fun readUserVersion(dbFile: File): Int? = runCatching {
        RandomAccessFile(dbFile, "r").use { file ->
            val header = ByteArray(16)
            if (file.read(header) != header.size) return null
            if (String(header, Charsets.US_ASCII) != SQLITE_HEADER) return null
            file.seek(USER_VERSION_OFFSET)
            val raw = ByteArray(4)
            if (file.read(raw) != raw.size) return null
            // 大端
            ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
        }
    }.getOrNull()

    private fun snapshot(
        context: Context,
        dbFile: File,
        installed: Int,
        codeVersion: Int,
        kind: String,
    ): File? {
        val root = context.getExternalFilesDir(SNAPSHOT_DIR) ?: return null
        // WAL / SHM 必须一起带走：只复制主库会丢未 checkpoint 的事务
        val sidecars =
            listOf("-wal", "-shm")
                .map { File(dbFile.parentFile, dbFile.name + it) }
                .filter { it.isFile }
        val totalBytes = dbFile.length() + sidecars.sumOf { it.length() }
        val available = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (available < (totalBytes * 1.2).toLong()) {
            Log.w(TAG, "skip snapshot: need ${totalBytes / 1024 / 1024}MB, available ${available / 1024 / 1024}MB")
            return null
        }
        val dir = File(root, "v${installed}-to-v${codeVersion}-$kind")
        dir.deleteRecursively() // 只保留最近一次
        dir.mkdirs()
        dbFile.copyTo(File(dir, dbFile.name), overwrite = true)
        sidecars.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        return dir
    }
}
