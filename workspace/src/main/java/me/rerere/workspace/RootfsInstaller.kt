package me.rerere.workspace

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import me.rerere.common.log.AppLogger

/** tar 时间戳合理上限（9999-12-31，秒）；超出视为非法，避免 *1000 溢出或负数 */
private const val MAX_TAR_MOD_TIME_SECONDS = 253_402_300_799L

/** 解包时跳过的条目汇总（名字各取前 20 条，其余只计数量）。 */
internal data class SkippedTarEntries(
    val unknownTypeCount: Int,
    val unknownTypeNames: List<String>,
    val missingHardLinkCount: Int,
    val missingHardLinkNames: List<String>,
) {
    val totalCount: Int get() = unknownTypeCount + missingHardLinkCount
}

/** [RootfsInstaller.extractTar] 的返回：解出的条目数与跳过的条目汇总。 */
internal data class ExtractTarResult(
    val entriesExtracted: Int,
    val skipped: SkippedTarEntries,
) {
    /** 安装结束时向进度流汇报的一句话汇总；无跳过时为空。 */
    fun skippedSummary(): String? {
        if (skipped.totalCount == 0) return null
        val parts = mutableListOf<String>()
        if (skipped.unknownTypeCount > 0) {
            parts += "unknown entry type: " + formatSkippedNames(
                skipped.unknownTypeNames,
                skipped.unknownTypeCount,
            )
        }
        if (skipped.missingHardLinkCount > 0) {
            parts += "hard link with missing source: " + formatSkippedNames(
                skipped.missingHardLinkNames,
                skipped.missingHardLinkCount,
            )
        }
        return "Skipped ${skipped.totalCount} entries during extraction (${parts.joinToString("; ")})"
    }

    private fun formatSkippedNames(names: List<String>, total: Int): String {
        val shown = names.joinToString(", ")
        return if (total > names.size) "$shown and ${total - names.size} more" else shown
    }
}

/** 安装流程结束，但 rootfs 未提供 Terminal 所需的可用 shell。 */
class RootfsShellNotFoundException : IllegalStateException(
    "No usable shell was found in the installed rootfs; " +
        "reinstall the rootfs or try a different rootfs image",
)

// tar 解包（约 14 个）与安装编排（约 10 个）共享中断检查、路径归一化、权限写入等私有 helper，
// 抽出独立类需把一批 private 提升为 internal；当前阶段保持单类，拆分留待专项重构。
@Suppress("TooManyFunctions")
class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    fun install(
        root: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) = executeUrlRootfsOperation(root, url, overlayOnly = false, onProgress = onProgress)

    /** 从本地归档文件安装 rootfs（离线/内网分发场景；与 URL 安装共用解压与补丁流程）。 */
    fun installFromFile(
        root: String,
        archive: File,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) = executeFileRootfsOperation(root, archive, overlayOnly = false, onProgress = onProgress)

    /**
     * 无损覆盖更新 rootfs：将新归档解压至暂存目录后覆盖合并进现有 [manager.linuxDir]，
     * 保留用户目录（`/root`、`/home`、`/opt`、`/usr/local`）中的新增文件及已配置的镜像源/证书。
     */
    fun update(
        root: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) = executeUrlRootfsOperation(root, url, overlayOnly = true, onProgress = onProgress)

    /** 从本地归档文件无损覆盖更新 rootfs。 */
    fun updateFromFile(
        root: String,
        archive: File,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) = executeFileRootfsOperation(root, archive, overlayOnly = true, onProgress = onProgress)

    private fun executeUrlRootfsOperation(
        root: String,
        url: String,
        overlayOnly: Boolean,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        require(url.isNotBlank()) { "Rootfs download url is required" }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromUrl(url)
        val archive = File(manager.tempDir(root), "rootfs.${format.extension}")
        try {
            download(url, archive, onProgress)
            applyArchiveToRootfs(root, archive, format, overlayOnly, onProgress)
        } finally {
            archive.delete()
        }
    }

    private fun executeFileRootfsOperation(
        root: String,
        archive: File,
        overlayOnly: Boolean,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        require(archive.isFile) { "Rootfs archive not found: ${archive.absolutePath}" }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromFileName(archive.name)
        applyArchiveToRootfs(root, archive, format, overlayOnly, onProgress)
    }

    private fun applyArchiveToRootfs(
        root: String,
        archive: File,
        format: ArchiveFormat,
        overlayOnly: Boolean,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val stagingDir = File(manager.tempDir(root), "rootfs-staging")
        val linuxDir = manager.linuxDir(root)
        val shouldOverlay = overlayOnly && linuxDir.isDirectory && manager.hasRootfs(root)
        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val extractResult = try {
                extractTar(archive, stagingDir, format, onProgress)
            } catch (caught: CancellationException) {
                throw caught
            } catch (ignored: Exception) {
                AppLogger.e(TAG, "extractTar failed: root=$root archive=${archive.name}", ignored)
                throw ignored
            }
            var totalEntries = extractResult.entriesExtracted
            if (shouldOverlay) {
                totalEntries += overlayDirectory(stagingDir, linuxDir, stagingDir, totalEntries, onProgress)
            } else {
                linuxDir.deleteRecursively()
                require(stagingDir.renameTo(linuxDir)) {
                    "Failed to move rootfs into workspace"
                }
            }
            // 补丁阶段也发进度事件，否则 UI 会在上一步的末尾长时间卡住
            onProgress(
                RootfsInstallProgress(
                    stage = RootfsInstallStage.EXTRACTING,
                    entriesExtracted = totalEntries,
                    currentEntry = "Applying rootfs patches",
                ),
            )
            patcher.patch(linuxDir)
            extractResult.skippedSummary()?.let { summary ->
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = totalEntries,
                        currentEntry = summary,
                    ),
                )
            }
            ensureTerminalShellIsReady(root)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** 覆盖目录并回报进度；返回实际覆盖的条目数。 */
    private fun overlayDirectory(
        sourceDir: File,
        targetDir: File,
        stagingRoot: File,
        extractedEntries: Int,
        onProgress: (RootfsInstallProgress) -> Unit,
    ): Int {
        val overlaidCount = intArrayOf(0)
        overlayDirectory(sourceDir, targetDir, stagingRoot, extractedEntries, overlaidCount, onProgress)
        return overlaidCount[0]
    }

    private fun overlayDirectory(
        sourceDir: File,
        targetDir: File,
        stagingRoot: File,
        extractedEntries: Int,
        overlaidCount: IntArray,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        targetDir.mkdirs()
        val children = sourceDir.listFiles().orEmpty()
        for (child in children) {
            checkInterrupted()
            val relPath = child.relativeTo(stagingRoot).invariantSeparatorsPath
            val dest = File(targetDir, child.name)
            val srcPath = child.toPath()
            val destPath = dest.toPath()
            when {
                Files.isSymbolicLink(srcPath) -> {
                    if (isPreservedConfig(relPath) && dest.exists()) continue
                    if (Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) {
                        dest.deleteRecursively()
                    }
                    val linkTarget = Files.readSymbolicLink(srcPath)
                    dest.parentFile?.mkdirs()
                    Files.createSymbolicLink(destPath, linkTarget)
                    reportOverlayProgress(relPath, extractedEntries, overlaidCount, onProgress)
                }

                child.isDirectory -> {
                    // 已保留的配置目录（如 sources.list.d）整个跳过，不展开覆盖
                    if (isPreservedConfig(relPath) && dest.isDirectory) continue
                    if (Files.isSymbolicLink(destPath) || (dest.exists() && !dest.isDirectory)) {
                        dest.delete()
                    }
                    overlayDirectory(child, dest, stagingRoot, extractedEntries, overlaidCount, onProgress)
                }

                else -> {
                    if (isPreservedConfig(relPath) && dest.isFile && dest.length() > 0L) {
                        continue
                    }
                    if (Files.isSymbolicLink(destPath) || dest.isDirectory) {
                        dest.deleteRecursively()
                    }
                    dest.parentFile?.mkdirs()
                    Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING)
                    dest.setReadable(child.canRead(), false)
                    dest.setWritable(child.canWrite(), true)
                    dest.setExecutable(child.canExecute(), false)
                    runCatching { dest.setLastModified(child.lastModified()) }
                    reportOverlayProgress(relPath, extractedEntries, overlaidCount, onProgress)
                }
            }
        }
    }

    /** 保留路径按“本身或其子路径”匹配：sources.list.d 这类目录下的碎片配置都要保留。 */
    private fun isPreservedConfig(relPath: String): Boolean =
        PRESERVED_CONFIG_PATHS.any { preserved ->
            relPath == preserved || relPath.startsWith("$preserved/")
        }

    /** 覆盖阶段也发进度事件，否则 UI 会在“解压 100%”处长时间卡住，看不出是在覆盖还是卡死了。 */
    private fun reportOverlayProgress(
        relPath: String,
        extractedEntries: Int,
        overlaidCount: IntArray,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        overlaidCount[0]++
        onProgress(
            RootfsInstallProgress(
                stage = RootfsInstallStage.EXTRACTING,
                entriesExtracted = extractedEntries + overlaidCount[0],
                currentEntry = relPath,
            ),
        )
    }

    private fun ensureTerminalShellIsReady(root: String) {
        if (!manager.hasRootfs(root)) {
            throw RootfsShellNotFoundException()
        }
    }

    /**
     * 下载 rootfs：失败重试（最多 [DOWNLOAD_MAX_ATTEMPTS] 次），重试时用 Range 从已下载的
     * 半截文件续传；下载结束按服务端给的总字节数校验，半截包不再拖到解压才报 tar EOF。
     * 4xx（408/429 除外）重试也不会好，直接失败；中断不重试。
     */
    private fun download(
        url: String,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        var downloaded = if (target.exists()) target.length() else 0L
        var totalBytes: Long? = null
        var lastError: IOException? = null
        repeat(DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                val result = downloadOnce(url, target, downloaded, onProgress)
                downloaded = result.bytesRead
                totalBytes = result.totalBytes
                if (totalBytes != null && downloaded != totalBytes) {
                    throw IOException(
                        "Rootfs download incomplete: received $downloaded of $totalBytes bytes",
                    )
                }
                return
            } catch (error: InterruptedException) {
                throw error
            } catch (error: IOException) {
                lastError = error
                downloaded = if (target.exists()) target.length() else 0L
                if (attempt == DOWNLOAD_MAX_ATTEMPTS - 1) {
                    // 最后一次也失败：记一条 e。错误摘要去掉 URL 部分
                    // （HttpURLConnection 的报错含完整 URL，镜像地址可能带 token）。
                    AppLogger.e(
                        TAG,
                        "download failed after $DOWNLOAD_MAX_ATTEMPTS attempts " +
                            "(downloaded=$downloaded bytes): ${downloadErrorSummary(error)}",
                    )
                    throw error
                }
                // 还有重试机会：记一条 w，否则「下载其实是第 N 次才成功」
                // 在时间线里看不出来。
                AppLogger.w(
                    TAG,
                    "download attempt ${attempt + 1}/$DOWNLOAD_MAX_ATTEMPTS failed " +
                        "(downloaded=$downloaded bytes): ${downloadErrorSummary(error)}",
                )
            }
        }
        throw lastError ?: IOException("Rootfs download failed: $url")
    }

    /**
     * 下载失败日志用的错误摘要：HttpURLConnection 的报错可能含完整 URL
     * （如 `Server returned HTTP response code: 403 for URL: ...`），
     * 镜像地址可能带 token，先截掉 URL 部分再记。
     */
    private fun downloadErrorSummary(error: IOException): String =
        error.message?.substringBefore(" for URL:")?.take(200)
            ?: error.javaClass.simpleName

    private data class DownloadResult(
        val bytesRead: Long,
        val totalBytes: Long?,
    )

    private fun downloadOnce(
        url: String,
        target: File,
        resumeFrom: Long,
        onProgress: (RootfsInstallProgress) -> Unit,
    ): DownloadResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                if (code in 400..499 && code != 408 && code != 429) {
                    throw IllegalStateException("Rootfs download failed: HTTP $code")
                }
                throw IOException("Rootfs download failed: HTTP $code")
            }
            // 206 表示服务端接受了 Range 续传，继续往半截文件后追加；
            // 200 表示服务端忽略了 Range，从头重下（截断已有半截文件）。
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val startOffset = if (resumed) resumeFrom else 0L
            val remaining = connection.contentLengthLong.takeIf { it > 0 }
            val totalBytes = remaining?.let { startOffset + it }
            var bytesRead = startOffset
            var lastReportBytes = startOffset
            connection.inputStream.use { input ->
                java.io.FileOutputStream(target, resumed).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }
            }
            return DownloadResult(bytesRead = bytesRead, totalBytes = totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ): ExtractTarResult {
        var entries = 0
        var skippedUnknownTypeCount = 0
        val skippedUnknownTypeNames = mutableListOf<String>()
        var skippedMissingHardLinkCount = 0
        val skippedMissingHardLinkNames = mutableListOf<String>()
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header =
                    rawHeader.copy(
                        name = pendingName ?: rawHeader.name,
                        linkName = pendingLinkName ?: rawHeader.linkName,
                    )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val normalizedName = normalizeTarPathOrNull(header.name)
                if (normalizedName == null) {
                    // 根目录/空路径条目（部分发行版如 Alpine 的 tar 会带 "./"）：跳过而非报错
                    input.skipFully(header.size)
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val target = targetDir.safeResolveNormalized(normalizedName)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> {
                        target.mkdirs()
                    }

                    TarEntryType.SYMLINK -> {
                        createSymlink(targetDir, target, header.linkName)
                    }

                    TarEntryType.HARDLINK -> {
                        if (!createHardLink(targetDir, target, header.linkName)) {
                            skippedMissingHardLinkCount++
                            if (skippedMissingHardLinkNames.size < MAX_SKIPPED_NAMES) {
                                skippedMissingHardLinkNames += header.name
                            }
                        }
                    }

                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }

                    // LONG_NAME/LONG_LINK/PAX 已在上方 continue, 这里只有 OTHER 可达;
                    // 数据区统一由下方的非 FILE skip 跳过, 这里再 skip 会双重跳过导致后续 header 错位
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    -> {
                        Unit
                    }

                    // 未知类型条目：静默跳过会让装完缺文件找不到原因，记下来最后汇总汇报
                    TarEntryType.OTHER -> {
                        skippedUnknownTypeCount++
                        if (skippedUnknownTypeNames.size < MAX_SKIPPED_NAMES) {
                            skippedUnknownTypeNames += header.name
                        }
                    }

                    // GNU sparse（磁盘镜像，如 Debian cloud 的 disk.raw）不是 rootfs 结构，
                    // 其数据布局由扩展头描述，按普通条目继续解析会导致后续 header 错位
                    TarEntryType.SPARSE -> {
                        throw IllegalArgumentException(
                            "This archive is a disk image (disk.raw), not a rootfs; " +
                                "use a rootfs archive instead (for example, an Ubuntu base or Alpine minirootfs tarball)",
                        )
                    }
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                val modTimeSeconds = header.modTime
                if (
                    header.type != TarEntryType.SYMLINK &&
                    modTimeSeconds in 1..MAX_TAR_MOD_TIME_SECONDS
                ) {
                    // 异常时间戳（base-256 负值 / 越界）直接忽略；设置失败也不应中断解压
                    runCatching { target.setLastModified(modTimeSeconds * 1000) }
                }
                entries++
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    ),
                )
            }
        }
        return ExtractTarResult(
            entriesExtracted = entries,
            skipped = SkippedTarEntries(
                unknownTypeCount = skippedUnknownTypeCount,
                unknownTypeNames = skippedUnknownTypeNames,
                missingHardLinkCount = skippedMissingHardLinkCount,
                missingHardLinkNames = skippedMissingHardLinkNames,
            ),
        )
    }

    private fun createSymlink(
        root: File,
        target: File,
        linkName: String,
    ) {
        if (linkName.isBlank()) return
        val linkTarget =
            if (File(linkName).isAbsolute) {
                File(linkName)
            } else {
                val resolved = File(target.parentFile ?: root, linkName).canonicalFile
                val rootFile = root.canonicalFile
                require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                    "Symlink escapes rootfs: ${target.name}"
                }
                (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
            }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    /** 建硬链接：源缺失或名字为空时跳过该条目（返回 false，供调用方汇总），而不是中断安装。 */
    private fun createHardLink(
        root: File,
        target: File,
        linkName: String,
    ): Boolean {
        if (linkName.isBlank()) return false
        // linkName 可能是 "./" 这类归一化后为空的路径 → 跳过该链接条目而不是中断安装
        val source = root.safeResolve(linkName) ?: return false
        if (!source.exists()) return false
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrThrow()
        return true
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName =
            listOf(prefix, name)
                .filter { it.isNotBlank() }
                .joinToString("/")
        return TarHeader(
            // 允许为空：根目录条目（如 "./"）与空 PAX 名交由外层循环跳过
            name = normalizeTarPathOrNull(fullName) ?: "",
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type =
                when (header[156].toInt().toChar()) {
                    '0', '\u0000' -> TarEntryType.FILE
                    '5' -> TarEntryType.DIRECTORY
                    '2' -> TarEntryType.SYMLINK
                    '1' -> TarEntryType.HARDLINK
                    'L' -> TarEntryType.LONG_NAME
                    'K' -> TarEntryType.LONG_LINK
                    'x' -> TarEntryType.PAX
                    'S' -> TarEntryType.SPARSE
                    else -> TarEntryType.OTHER
                },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    // 协程取消时调用方通过 runInterruptible 将取消转成线程中断, 这里在阻塞循环中检测并尽早退出,
    // 避免离开页面后仍继续下载/解压并向已清空的 StateFlow 推送进度
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(
        output: java.io.OutputStream,
        bytes: Long,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File? {
        val normalized = normalizeTarPathOrNull(path) ?: return null
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $path"
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    /** 归一化 tar 条目路径；根目录/空路径条目（如 "./"）返回 null 表示可跳过。 */
    private fun normalizeTarPathOrNull(path: String): String? {
        val normalized =
            path
                .replace('\\', '/')
                .trim()
                .trimStart('/')
                .removePrefix("./")
        if (normalized.isBlank()) return null
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun File.safeResolveNormalized(normalized: String): File {
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $normalized"
        }
        return target
    }

    private fun ByteArray.string(
        offset: Int,
        length: Int,
    ): String {
        val end =
            (offset until offset + length)
                .firstOrNull { this[it] == 0.toByte() }
                ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(
        offset: Int,
        length: Int,
    ): Long {
        if (length <= 0) return 0L
        // GNU tar 对超出八进制范围的数值使用 base-256（首字节最高位为 1）；
        // 某些发行版（如 Debian cloud 镜像）的条目会带上该编码，直接按八进制解析会抛
        // NumberFormatException("For input string ... under radix 8")。
        if ((this[offset].toInt() and 0x80) != 0) {
            // GNU base-256：最高位置 1 表示二进制编码，其余位为大端二补码有符号数
            // （首字节 0x80 表示负数）。此前的无符号实现会把负值读成巨大值，
            // 下游 *1000 溢出为负，setLastModified 因此抛 "Negative time"。
            var value = if ((this[offset].toInt() and 0x40) != 0) -1L else 0L
            for (i in offset until offset + length) {
                value = (value shl 8) or (this[i].toLong() and 0xFF)
            }
            return value
        }
        val raw =
            copyOfRange(offset, offset + length)
                .toString(Charsets.US_ASCII)
                .trim { it == ' ' || it == '\u0000' || it == '\n' || it == '\r' }
        return if (raw.isEmpty()) 0L else (raw.toLongOrNull(8) ?: 0L)
    }

    private fun Long.paddingSize(): Long =
        (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
            if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
        }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        SPARSE,
        OTHER,
    }

    enum class ArchiveFormat(
        val extension: String,
    ) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        }, ;

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return fromFileName(path)
            }

            /** 按文件名推断压缩包格式（本地导入用）。未知后缀直接报错，不再默认当作 tar.gz。 */
            fun fromFileName(name: String): ArchiveFormat =
                when {
                    name.endsWith(".tar.gz", ignoreCase = true) ||
                        name.endsWith(".tgz", ignoreCase = true) -> TAR_GZ

                    name.endsWith(".tar.xz", ignoreCase = true) ||
                        name.endsWith(".txz", ignoreCase = true) -> TAR_XZ

                    else -> throw IllegalArgumentException(
                        "Unsupported archive format: $name (expected a .tar.gz or .tar.xz archive)",
                    )
                }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAG = "RootfsInstaller"
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_MAX_ATTEMPTS = 3
        private const val MAX_SKIPPED_NAMES = 20

        /** 覆盖更新时保留已有用户配置的相对路径（避免抹掉镜像源、DNS 与已写入的 CA bundle）。 */
        private val PRESERVED_CONFIG_PATHS =
            setOf(
                "etc/resolv.conf",
                "etc/hosts",
                "etc/hostname",
                "etc/apk/repositories",
                "etc/apk/repositories.d",
                "etc/apt/sources.list",
                "etc/apt/sources.list.d",
                "etc/pip.conf",
                "etc/ssl/certs/ca-certificates.crt",
                "root/.npmrc",
                "root/.bashrc",
                "root/.profile",
            )
    }
}
