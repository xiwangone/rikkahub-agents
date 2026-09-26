package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 工作区资源画像（详情页资源面板用）。全部纯文件采集，不依赖 rootfs shell 就绪；
 * 任一项采集失败为 null（面板显示 "-"，不阻断页面）。
 */
data class WorkspaceStats(
    val rootBytes: Long?,
    val packageCount: Int?,
    val kernel: String?,
)

fun collectWorkspaceStats(
    workspaceDir: File,
    linuxDir: File,
    kernel: String? = System.getProperty("os.version"),
): WorkspaceStats = WorkspaceStats(
    rootBytes = runCatching { dirSize(workspaceDir) }.getOrNull(),
    packageCount = countPackages(linuxDir),
    kernel = kernel?.takeIf { it.isNotBlank() },
)

/**
 * 递归求和；显式 NOFOLLOW：java.io.File 的 listFiles 会穿透**目录**符号链接，
 * Debian 布局的 /usr/bin/X11 -> . 自链接会让遍历永不终止（且不报错），
 * 故用 walkFileTree（默认不跟随链接），符号链接本体不计入、访问失败跳过。
 * 目录缺失返回 null。
 */
private fun dirSize(dir: File): Long? {
    if (!dir.isDirectory) return null
    var total = 0L
    Files.walkFileTree(
        dir.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    return total
}

/**
 * 软件包数：Debian/Ubuntu 数 `var/lib/dpkg/status` 的 `^Package: ` 行；
 * Alpine 数 `lib/apk/db/installed` 的 `^P:` 行；都缺失（未知发行版）返回 null。
 */
fun countPackages(linuxDir: File): Int? {
    val dpkg = File(linuxDir, "var/lib/dpkg/status")
    if (dpkg.isFile) {
        return runCatching { dpkg.readLines().count { it.startsWith("Package: ") } }.getOrNull()
    }
    val apkDb = File(linuxDir, "lib/apk/db/installed")
    if (apkDb.isFile) {
        return runCatching { apkDb.readLines().count { it.startsWith("P:") } }.getOrNull()
    }
    return null
}
