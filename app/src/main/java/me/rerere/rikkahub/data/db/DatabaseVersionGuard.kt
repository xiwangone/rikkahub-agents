package me.rerere.rikkahub.data.db

import android.content.Context
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
 *    不删除、不重建、不做 destructive fallback：数据安全优先，是否继续交给上层决定。
 *
 * 版本号直接读 SQLite 文件头（offset 60，大端 4 字节），**不打开数据库** ——
 * 打开会触发 WAL 恢复等副作用，这里要零副作用。
 */
internal object DatabaseVersionGuard {
    private const val TAG = "DatabaseVersionGuard"
    private const val DB_NAME = "rikka_hub"
    private const val SNAPSHOT_DIR = "db-snapshots"
    private const val MAX_SNAPSHOTS = 3
    private const val USER_VERSION_OFFSET = 60L
    private const val SQLITE_HEADER = "SQLite format 3\u0000"

    /** 返回 true 表示已做了快照；false 表示无需或失败（失败只记日志，不阻断启动）。 */
    fun beforeOpen(context: Context, codeVersion: Int): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.isFile) return false
        val installed = readUserVersion(dbFile) ?: return false
        if (installed == codeVersion) return false
        val kind = if (installed < codeVersion) "upgrade" else "downgrade"
        return runCatching {
            val dir = snapshot(context, dbFile, installed, codeVersion, kind)
            Log.w(TAG, "database $kind $installed -> $codeVersion, snapshot at ${dir.name}")
            true
        }.getOrElse {
            Log.w(TAG, "database snapshot failed ($kind $installed -> $codeVersion)", it)
            false
        }
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
    ): File {
        val root = File(context.filesDir, SNAPSHOT_DIR)
        val dir = File(root, "v${installed}-to-v${codeVersion}-$kind-${System.currentTimeMillis()}")
        dir.mkdirs()
        dbFile.copyTo(File(dir, dbFile.name), overwrite = true)
        // WAL / SHM 必须一起带走：只复制主库会丢未 checkpoint 的事务
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(dbFile.parentFile, dbFile.name + suffix)
            if (side.isFile) side.copyTo(File(dir, dbFile.name + suffix), overwrite = true)
        }
        pruneOldSnapshots(root)
        return dir
    }

    private fun pruneOldSnapshots(root: File) {
        val all = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return
        all.drop(MAX_SNAPSHOTS).forEach { old -> runCatching { old.deleteRecursively() } }
    }
}
