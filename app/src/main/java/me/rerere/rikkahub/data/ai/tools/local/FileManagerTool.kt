package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import me.rerere.rikkahub.BuildConfig
import java.util.Base64 as JBase64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// ---------- content:// support (Phase 25) ----------

/**
 * Description suffix appended to every file-manager tool's [Tool.description] so the LLM
 * knows the tools accept content:// URIs from any SAF-granted DocumentsProvider.
 */
internal const val CONTENT_URI_DESC =
    " Path supports file:// and content:// (USB / SD / Downloads / cloud / shared media via SAF grant or share intent)."

/** Render a DocumentFile entry into the same JSON shape as a [File] entry, plus is_content_uri. */
internal fun documentEntryJson(doc: DocumentFile) = buildJsonObject {
    put("path", doc.uri.toString())
    put("name", doc.name ?: "")
    put("size_bytes", if (doc.isDirectory) 0L else doc.length())
    put("modified_at_ms", doc.lastModified())
    put("is_directory", doc.isDirectory)
    doc.type?.let { put("mime", it) }
    put("is_content_uri", true)
}

/**
 * Resolve the app [Context] for content:// routing. The file-manager tool factories take
 * no Context (so the pure-JVM file:// unit tests need no Android context); content:// ops
 * resolve it lazily from Koin at execute time, after the DI graph has settled.
 */
internal fun fmContext(): Context =
    org.koin.java.KoinJavaComponent.getKoin().get(Context::class)

// ---------- Error helpers ----------

internal fun fmErrEnvelope(code: String, detail: String): String =
    buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()

internal fun fmTextPart(s: String) = listOf(UIMessagePart.Text(s))

// ---------- Shared read helpers (file:// and content:// paths) ----------

/**
 * Read up to [maxBytes] from [stream], looping until the budget or EOF. A single read()
 * returns whatever is currently buffered — for pipe-backed content:// providers (Drive,
 * Dropbox, share intents) that is often one 8-64 KB chunk, which silently truncates the
 * result while reporting it as complete.
 */
internal fun readUpTo(stream: java.io.InputStream, maxBytes: Int): ByteArray {
    val buf = ByteArray(maxBytes)
    var off = 0
    while (off < maxBytes) {
        val n = stream.read(buf, off, maxBytes - off)
        if (n < 0) break
        off += n
    }
    return if (off <= 0) ByteArray(0) else buf.copyOf(off)
}

/**
 * Control-byte count for the binary sniff. Bytes are masked to unsigned first: Kotlin
 * Byte is signed, so an unmasked `b < 0x09` counts every byte >= 0x80 as non-printable
 * and misclassifies all UTF-8 multibyte text (CJK, Cyrillic, accented Latin) as binary.
 */
internal fun countNonPrintable(sample: List<Byte>): Int = sample.count { b ->
    val ub = b.toInt() and 0xFF
    ub < 0x09 || (ub in 0x0E..0x1F && ub != 0x1B)
}

// ---------- PathSafetyGuard ----------

/**
 * Lightweight path-safety guard for file manager tools.
 *
 * We deliberately do NOT reuse HardlineCommandGuard (which is string-matching
 * on shell commands) — this guard is path-canonical and type-safe.
 */
object PathSafetyGuard {

    /** Prefixes that are permanently blocked — system-owned, never user data. */
    private val SYSTEM_PREFIXES = listOf(
        "/system",
        "/system_ext",
        "/vendor",
        "/proc",
        "/dev",
        "/sys",
        "/apex",
    )

    /** Our own app sandbox, allowed even inside /data/data. Derived from the real
     *  applicationId (not hardcoded) so it stays correct across forks/renames and
     *  upstream merges; both canonical-path forms are covered. */
    private val OWN_APP_PREFIXES = listOf(
        "/data/data/${BuildConfig.APPLICATION_ID}",
        "/data/user/0/${BuildConfig.APPLICATION_ID}",
    )

    data class Violation(val code: String, val detail: String)

    /**
     * Check [raw] for safety. Returns null on success, a [Violation] otherwise.
     *
     * Callers must convert to a structured error envelope via [fmErrEnvelope].
     */
    fun check(raw: String?): Violation? {
        if (raw.isNullOrEmpty()) {
            return Violation("path_blocked", "Path must not be empty.")
        }
        if (raw.contains('\u0000')) {
            return Violation("path_blocked", "Path must not contain null bytes.")
        }

        val canonical = try {
            File(raw).canonicalPath
        } catch (_: IOException) {
            return Violation("path_blocked", "Path could not be resolved.")
        }

        // System-owned prefixes
        for (prefix in SYSTEM_PREFIXES) {
            if (canonical == prefix || canonical.startsWith("$prefix/")) {
                return Violation(
                    "path_blocked",
                    "Paths under $prefix are read-only system storage and cannot be accessed by this tool."
                )
            }
        }

        // /data/data/<other-package> — allow only our own app sandbox
        if (canonical.startsWith("/data/data/")) {
            val isOwn = OWN_APP_PREFIXES.any { own ->
                canonical == own || canonical.startsWith("$own/")
            }
            if (!isOwn) {
                return Violation(
                    "path_blocked",
                    "Paths inside other apps' private sandboxes (/data/data/<other>) cannot be accessed."
                )
            }
        }

        // Path traversal: if the canonical form starts with a system prefix that the
        // raw path did NOT explicitly name, the caller tried to escape via ".."
        // (already caught by the prefix checks above since we work on canonical form).
        // Additionally, catch traversal that might land in a blocked prefix when the
        // raw path contained ".." that resolved to something else unexpected.
        val rawNorm = raw.replace('\\', '/')
        if (rawNorm.contains("/../") || rawNorm.endsWith("/..") ||
            rawNorm == ".." || rawNorm.startsWith("../")) {
            return Violation("path_blocked", "Path traversal sequences ('..') are not allowed.")
        }

        return null
    }
}

// ---------- Scoped-storage gate ----------

/**
 * Read-side tools (list_files / find_files / read_file) need MANAGE_EXTERNAL_STORAGE
 * to see files the app didn't create itself when the path is under shared storage
 * (/storage/emulated/...). Without it, File.listFiles() silently strips foreign
 * files even though stat() and own-creation reads still work.
 *
 * Returns null when access is OK; otherwise an envelope string the caller can
 * return verbatim. Paths outside shared storage (app-private, /sdcard/Android/...
 * which is also app-scoped) bypass the gate so they keep working without the
 * special permission.
 */
internal fun allFilesAccessGuard(rawPath: String): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    // Path-shape check first so JVM unit tests on /tmp paths never call
    // Environment.isExternalStorageManager() (which is a stub-jar method that
    // throws on standard `:app:testDebugUnitTest`).
    val canonical = try { File(rawPath).canonicalPath } catch (_: IOException) { rawPath }
    val isSharedStorage = canonical.startsWith("/storage/emulated/") ||
        canonical.startsWith("/sdcard/") ||
        canonical.startsWith("/storage/self/")
    if (!isSharedStorage) return null
    val granted = try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }
    if (granted) return null
    return buildJsonObject {
        put("error", "permission_denied")
        put(
            "detail",
            "All files access is required to enumerate or read files in shared storage. " +
                "Open Settings → Apps → RikkaHub → Permissions → All files access, or have " +
                "the user re-toggle the Files tool on the assistant Local Tools page to be " +
                "prompted."
        )
        put("settings_action", "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
    }.toString()
}

// ---------- MIME helper ----------

private fun mimeFromExtension(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { return null }
    return try {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    } catch (_: Exception) {
        null
    }
}

// ---------- Glob matching ----------

/**
 * Minimal glob → regex conversion supporting '*', '**', and '?'.
 * '**' matches any path segment chain (including '/').
 * '*' matches any character except '/'.
 * '?' matches any single character except '/'.
 */
internal fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        when {
            pattern[i] == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                sb.append(".*")
                i += 2
                if (i < pattern.length && pattern[i] == '/') i++ // skip trailing slash after **
            }
            pattern[i] == '*' -> {
                sb.append("[^/]*")
                i++
            }
            pattern[i] == '?' -> {
                sb.append("[^/]")
                i++
            }
            else -> {
                sb.append(Regex.escape(pattern[i].toString()))
                i++
            }
        }
    }
    sb.append("$")
    return Regex(sb.toString())
}

// ---------- File entry builder ----------

private fun fileEntryJson(f: File) = buildJsonObject {
    put("path", f.absolutePath)
    put("name", f.name)
    put("size_bytes", if (f.isDirectory) 0L else f.length())
    put("modified_at_ms", f.lastModified())
    put("is_directory", f.isDirectory)
    mimeFromExtension(f.name)?.let { put("mime", it) }
}

// ============================================================
//  TOOL FACTORIES
// ============================================================

// ---------- list_files ----------

fun listFilesTool(): Tool = Tool(
    name = "list_files",
    description = """
        List files and directories at the given path. Optionally filter by glob pattern
        (e.g. *.mp3, **/*.pdf). recursive defaults false. limit caps results (default 50,
        max 500). Returns {files: [{path, name, size_bytes, modified_at_ms, is_directory,
        mime?}], truncated}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("pattern", buildJsonObject { put("type", "string") })
                put("recursive", buildJsonObject { put("type", "boolean") })
                put("limit", buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", 500)
                })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawArg = obj["path"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawArg)) {
            return@Tool listFilesContent(fmContext(), rawArg!!, obj)
        }
        val rawPath = rawArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawPath)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        allFilesAccessGuard(rawPath!!)?.let { return@Tool fmTextPart(it) }
        val dir = File(rawPath)
        if (!dir.exists()) return@Tool fmTextPart(fmErrEnvelope("not_found", "Path does not exist: $rawPath"))
        if (!dir.isDirectory) return@Tool fmTextPart(fmErrEnvelope("not_a_directory", "Path is not a directory: $rawPath"))

        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull
        val patternRegex = pattern?.let { globToRegex(it) }
        val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)

        val collected = mutableListOf<File>()
        var truncated = false

        fun collect(d: File) {
            val entries = try { d.listFiles() ?: emptyArray() } catch (_: SecurityException) { emptyArray() }
            for (f in entries) {
                if (collected.size >= limit) { truncated = true; return }
                val matches = patternRegex == null || patternRegex.matches(f.name)
                if (matches) collected.add(f)
                if (recursive && f.isDirectory) collect(f)
            }
        }
        collect(dir)

        fmTextPart(buildJsonObject {
            put("files", buildJsonArray { collected.forEach { add(fileEntryJson(it)) } })
            put("truncated", truncated)
        }.toString())
    },
)

// ---------- read_file ----------

private const val DEFAULT_READ_BYTES = 65536
private const val MAX_READ_BYTES = 1048576

fun readFileTool(): Tool = Tool(
    name = "read_file",
    description = """
        Read the content of a file. max_bytes defaults to 65536, max 1048576 (1 MB).
        encoding defaults UTF-8 with BOM detection. For binary files returns base64 with
        "binary": true. Returns {content, truncated, bytes_read, encoding} or
        {content_base64, binary: true, bytes_read, truncated}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("max_bytes", buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", MAX_READ_BYTES)
                })
                put("encoding", buildJsonObject { put("type", "string") })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawArg = obj["path"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawArg)) {
            return@Tool readFileContent(fmContext(), rawArg!!, obj)
        }
        val rawPath = rawArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawPath)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        allFilesAccessGuard(rawPath!!)?.let { return@Tool fmTextPart(it) }
        val file = File(rawPath)
        if (!file.exists()) return@Tool fmTextPart(fmErrEnvelope("not_found", "File not found: $rawPath"))
        if (file.isDirectory) return@Tool fmTextPart(fmErrEnvelope("is_directory", "Path is a directory, not a file."))

        val maxBytes = (obj["max_bytes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_READ_BYTES)
            .coerceIn(1, MAX_READ_BYTES)
        val encodingHint = obj["encoding"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

        val bytes = try {
            file.inputStream().use { readUpTo(it, maxBytes) }
        } catch (e: SecurityException) {
            return@Tool fmTextPart(fmErrEnvelope("permission_denied", e.message ?: "Permission denied"))
        } catch (e: IOException) {
            return@Tool fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error"))
        }

        val truncated = file.length() > maxBytes

        // BOM detection + binary sniff
        val isBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val charset = when {
            encodingHint != null -> runCatching { charset(encodingHint) }.getOrNull()
            isBom -> Charsets.UTF_8
            else -> Charsets.UTF_8
        } ?: Charsets.UTF_8

        // Heuristic binary check: if >15% of the first 512 bytes are non-printable, treat as binary
        val sample = bytes.take(minOf(512, bytes.size))
        val nonPrintable = countNonPrintable(sample)
        val isBinary = sample.isNotEmpty() && (nonPrintable.toDouble() / sample.size) > 0.15

        if (isBinary) {
            fmTextPart(buildJsonObject {
                put("content_base64", JBase64.getEncoder().encodeToString(bytes))
                put("binary", true)
                put("bytes_read", bytes.size)
                put("truncated", truncated)
            }.toString())
        } else {
            val startOffset = if (isBom) 3 else 0
            val text = String(bytes, startOffset, bytes.size - startOffset, charset)
            fmTextPart(buildJsonObject {
                put("content", text)
                put("truncated", truncated)
                put("bytes_read", bytes.size)
                put("encoding", charset.name())
            }.toString())
        }
    },
)

// ---------- write_binary_file ----------

fun writeBinaryFileTool(): Tool = Tool(
    name = "write_binary_file",
    description = """
        Write a binary blob (base64-encoded) to a file path. overwrite defaults false
        (returns error if file already exists). Returns {success, path, bytes_written}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("base64_content", buildJsonObject { put("type", "string") })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("path", "base64_content"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawArg = obj["path"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawArg)) {
            return@Tool writeBinaryFileContent(fmContext(), rawArg!!, obj)
        }
        val rawPath = rawArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawPath)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        val file = File(rawPath!!)
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        if (file.exists() && !overwrite) {
            return@Tool fmTextPart(fmErrEnvelope("file_exists", "File already exists. Pass overwrite=true to replace it."))
        }
        val b64 = obj["base64_content"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool fmTextPart(fmErrEnvelope("missing_content", "base64_content is required"))
        val bytes = try {
            JBase64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return@Tool fmTextPart(fmErrEnvelope("bad_base64", "base64_content is not valid base64"))
        }
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (e: SecurityException) {
            return@Tool fmTextPart(fmErrEnvelope("permission_denied", e.message ?: "Permission denied"))
        } catch (e: IOException) {
            return@Tool fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error writing file"))
        }
        fmTextPart(buildJsonObject {
            put("success", true)
            put("path", file.absolutePath)
            put("bytes_written", bytes.size)
        }.toString())
    },
)

// ---------- delete_file ----------

fun deleteFileTool(): Tool = Tool(
    name = "delete_file",
    description = """
        Delete a file or directory. For non-empty directories, recursive must be true.
        Returns {success, path, deleted_count}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("recursive", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawArg = obj["path"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawArg)) {
            return@Tool deleteFileContent(fmContext(), rawArg!!, obj)
        }
        val rawPath = rawArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawPath)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        val file = File(rawPath!!)
        if (!file.exists()) return@Tool fmTextPart(fmErrEnvelope("not_found", "Path does not exist: $rawPath"))

        val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        if (file.isDirectory && !file.listFiles().isNullOrEmpty() && !recursive) {
            return@Tool fmTextPart(fmErrEnvelope("not_empty", "Directory is not empty. Pass recursive=true to delete it and its contents."))
        }

        var count = 0
        fun deleteRecursive(f: File) {
            if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
            if (f.delete()) count++
        }
        try {
            deleteRecursive(file)
        } catch (e: SecurityException) {
            return@Tool fmTextPart(fmErrEnvelope("permission_denied", e.message ?: "Permission denied"))
        }
        // If the top-level path still exists, the delete failed (or only partially removed
        // a directory's contents) — don't report success.
        if (file.exists()) {
            return@Tool fmTextPart(buildJsonObject {
                put("error", if (count > 0) "partial_delete" else "delete_failed")
                put("detail", "Path could not be fully deleted: $rawPath")
                put("path", rawPath)
                put("deleted_count", count)
            }.toString())
        }
        fmTextPart(buildJsonObject {
            put("success", true)
            put("path", rawPath)
            put("deleted_count", count)
        }.toString())
    },
)

// ---------- move_file ----------

fun moveFileTool(): Tool = Tool(
    name = "move_file",
    description = """
        Move or rename a file or directory. overwrite defaults false.
        Returns {success, from, to}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("src", buildJsonObject { put("type", "string") })
                put("dst", buildJsonObject { put("type", "string") })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("src", "dst"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawSrcArg = obj["src"]?.jsonPrimitive?.contentOrNull
        val rawDstArg = obj["dst"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawSrcArg) || ContentUriSafetyGuard.isContentUri(rawDstArg)) {
            return@Tool moveOrCopyContent(fmContext(), rawSrcArg, rawDstArg, obj, deleteSrc = true)
        }
        val rawSrc = rawSrcArg?.let(AgentWorkspace::expand)
        val rawDst = rawDstArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawSrc)?.let { v -> return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail)) }
        PathSafetyGuard.check(rawDst)?.let { v -> return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail)) }
        val src = File(rawSrc!!)
        val dst = File(rawDst!!)
        if (!src.exists()) return@Tool fmTextPart(fmErrEnvelope("not_found", "Source does not exist: $rawSrc"))
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        if (dst.exists() && !overwrite) {
            return@Tool fmTextPart(fmErrEnvelope("destination_exists", "Destination already exists. Pass overwrite=true to replace it."))
        }
        try {
            dst.parentFile?.mkdirs()
            if (!dst.exists()) {
                // No destination to protect: rename directly, copy+delete across filesystems.
                if (!src.renameTo(dst)) {
                    src.copyRecursively(dst, overwrite = false)
                    src.deleteRecursively()
                }
            } else {
                // Overwriting: never delete the old dst until the new content is in place,
                // so a failed move can't lose the destination. Rename src to a temp sibling
                // and swap; the old dst is deleted only AFTER the new one lands.
                val backup = File(dst.parentFile, "${dst.name}.rkmv-old-${System.nanoTime()}")
                if (dst.renameTo(backup)) {
                    if (src.renameTo(dst)) {
                        backup.deleteRecursively()
                    } else {
                        // Same-filesystem rename of src failed; fall back to a verified copy
                        // into a temp, then swap. Restore the backup if anything fails.
                        val tmp = File(dst.parentFile, "${dst.name}.rkmv-new-${System.nanoTime()}")
                        try {
                            src.copyRecursively(tmp, overwrite = false)
                            if (!tmp.renameTo(dst)) {
                                tmp.deleteRecursively()
                                backup.renameTo(dst)
                                return@Tool fmTextPart(fmErrEnvelope("io_error", "Failed to place moved file at destination."))
                            }
                            src.deleteRecursively()
                            backup.deleteRecursively()
                        } catch (e: IOException) {
                            tmp.deleteRecursively()
                            backup.renameTo(dst)
                            return@Tool fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error"))
                        }
                    }
                } else {
                    // Couldn't move the old dst aside (e.g. cross-fs): copy src to a temp
                    // beside dst first. Only AFTER the copy lands do we move the old dst to a
                    // backup, swap the temp into place, and drop the backup. The old dst is
                    // never deleted before the replacement bytes exist, so a failed swap
                    // leaves the original destination intact (restored from the backup).
                    val tmp = File(dst.parentFile, "${dst.name}.rkmv-new-${System.nanoTime()}")
                    val backup = File(dst.parentFile, "${dst.name}.rkmv-old-${System.nanoTime()}")
                    try {
                        src.copyRecursively(tmp, overwrite = false)
                        if (!dst.renameTo(backup)) {
                            tmp.deleteRecursively()
                            return@Tool fmTextPart(fmErrEnvelope("io_error", "Failed to place moved file at destination."))
                        }
                        if (!tmp.renameTo(dst)) {
                            tmp.deleteRecursively()
                            backup.renameTo(dst)
                            return@Tool fmTextPart(fmErrEnvelope("io_error", "Failed to place moved file at destination."))
                        }
                        backup.deleteRecursively()
                        src.deleteRecursively()
                    } catch (e: IOException) {
                        tmp.deleteRecursively()
                        if (!dst.exists()) backup.renameTo(dst) else backup.deleteRecursively()
                        return@Tool fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error"))
                    }
                }
            }
        } catch (e: SecurityException) {
            return@Tool fmTextPart(fmErrEnvelope("permission_denied", e.message ?: "Permission denied"))
        } catch (e: IOException) {
            return@Tool fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error"))
        }
        fmTextPart(buildJsonObject {
            put("success", true)
            put("from", src.absolutePath)
            put("to", dst.absolutePath)
        }.toString())
    },
)

// ---------- copy_file ----------

fun copyFileTool(): Tool = Tool(
    name = "copy_file",
    description = """
        Copy a file or directory to a new location. overwrite defaults false.
        Returns {success, from, to, bytes_copied}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("src", buildJsonObject { put("type", "string") })
                put("dst", buildJsonObject { put("type", "string") })
                put("overwrite", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("src", "dst"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawSrcArg = obj["src"]?.jsonPrimitive?.contentOrNull
        val rawDstArg = obj["dst"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawSrcArg) || ContentUriSafetyGuard.isContentUri(rawDstArg)) {
            return@Tool moveOrCopyContent(fmContext(), rawSrcArg, rawDstArg, obj, deleteSrc = false)
        }
        val rawSrc = rawSrcArg?.let(AgentWorkspace::expand)
        val rawDst = rawDstArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawSrc)?.let { v -> return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail)) }
        PathSafetyGuard.check(rawDst)?.let { v -> return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail)) }
        val src = File(rawSrc!!)
        val dst = File(rawDst!!)
        if (!src.exists()) return@Tool fmTextPart(fmErrEnvelope("not_found", "Source does not exist: $rawSrc"))
        val overwrite = obj["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        if (dst.exists() && !overwrite) {
            return@Tool fmTextPart(fmErrEnvelope("destination_exists", "Destination already exists. Pass overwrite=true to replace it."))
        }
        var bytesCopied = 0L
        try {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) {
                src.walkTopDown().forEach { f ->
                    val rel = f.relativeTo(src)
                    val target = File(dst, rel.path)
                    if (f.isDirectory) target.mkdirs()
                    else {
                        f.copyTo(target, overwrite = overwrite)
                        bytesCopied += f.length()
                    }
                }
            } else {
                src.copyTo(dst, overwrite = overwrite)
                bytesCopied = src.length()
            }
        } catch (e: SecurityException) {
            return@Tool fmTextPart(fmErrEnvelope("permission_denied", e.message ?: "Permission denied"))
        } catch (e: IOException) {
            return@Tool fmTextPart(fmErrEnvelope("io_error", e.message ?: "IO error"))
        }
        fmTextPart(buildJsonObject {
            put("success", true)
            put("from", src.absolutePath)
            put("to", dst.absolutePath)
            put("bytes_copied", bytesCopied)
        }.toString())
    },
)

// ---------- create_directory ----------

fun createDirectoryTool(): Tool = Tool(
    name = "create_directory",
    description = """
        Create a directory (and any intermediate directories, like mkdir -p).
        Returns {success, path, created} where created=false if it already existed.
        For a content:// parent tree, pass the parent tree URI as path and the new
        directory name as name.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "New directory name to create under a content:// parent tree")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val rawArg = input.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawArg)) {
            return@Tool createDirectoryContent(fmContext(), rawArg!!, input.jsonObject)
        }
        val rawPath = rawArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawPath)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        val dir = File(rawPath!!)
        val alreadyExisted = dir.exists()
        try {
            val ok = dir.mkdirs()
            // mkdirs() returns false both when the dir already existed and when creation
            // failed; only the latter (path still isn't a directory) is an error.
            if (!ok && !dir.isDirectory) {
                return@Tool fmTextPart(fmErrEnvelope("mkdir_failed", "Failed to create directory: $rawPath"))
            }
        } catch (e: SecurityException) {
            return@Tool fmTextPart(fmErrEnvelope("permission_denied", e.message ?: "Permission denied"))
        }
        fmTextPart(buildJsonObject {
            put("success", true)
            put("path", dir.absolutePath)
            put("created", !alreadyExisted)
        }.toString())
    },
)

// ---------- file_info ----------

fun fileInfoTool(): Tool = Tool(
    name = "file_info",
    description = """
        Stat a single path. include_hash=true computes SHA-256 (slow for large files).
        Returns {path, exists, size_bytes?, modified_at_ms?, is_directory?, mime?, sha256?}.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("include_hash", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawArg = obj["path"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawArg)) {
            return@Tool fileInfoContent(fmContext(), rawArg!!, obj)
        }
        val rawPath = rawArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawPath)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        val file = File(rawPath!!)
        if (!file.exists()) {
            return@Tool fmTextPart(buildJsonObject {
                put("path", rawPath)
                put("exists", false)
            }.toString())
        }
        val includeHash = obj["include_hash"]?.jsonPrimitive?.booleanOrNull ?: false
        fmTextPart(buildJsonObject {
            put("path", file.absolutePath)
            put("exists", true)
            put("size_bytes", file.length())
            put("modified_at_ms", file.lastModified())
            put("is_directory", file.isDirectory)
            mimeFromExtension(file.name)?.let { put("mime", it) }
            if (includeHash && !file.isDirectory) {
                val sha256 = try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { s ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (s.read(buf).also { n = it } >= 0) digest.update(buf, 0, n)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                } catch (_: Exception) { null }
                sha256?.let { put("sha256", it) }
            }
        }.toString())
    },
)

// ---------- find_files ----------

private const val FIND_VISIT_CAP = 10_000

fun findFilesTool(): Tool = Tool(
    name = "find_files",
    description = """
        Search for files by name substring or glob under a root directory.
        query is matched against the filename (not the full path). recursive defaults true.
        limit caps results (default 50, max 500). Visits at most 10,000 entries to avoid OOM.
        Returns same shape as list_files.
    """.trimIndent().replace("\n", " ") + CONTENT_URI_DESC,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("root", buildJsonObject { put("type", "string") })
                put("query", buildJsonObject { put("type", "string") })
                put("recursive", buildJsonObject { put("type", "boolean") })
                put("limit", buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", 500)
                })
            },
            required = listOf("root", "query"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val rawRootArg = obj["root"]?.jsonPrimitive?.contentOrNull
        if (ContentUriSafetyGuard.isContentUri(rawRootArg)) {
            return@Tool findFilesContent(fmContext(), rawRootArg!!, obj)
        }
        val rawRoot = rawRootArg?.let(AgentWorkspace::expand)
        PathSafetyGuard.check(rawRoot)?.let { v ->
            return@Tool fmTextPart(fmErrEnvelope(v.code, v.detail))
        }
        allFilesAccessGuard(rawRoot!!)?.let { return@Tool fmTextPart(it) }
        val rootDir = File(rawRoot)
        if (!rootDir.exists()) return@Tool fmTextPart(fmErrEnvelope("not_found", "Root does not exist: $rawRoot"))
        if (!rootDir.isDirectory) return@Tool fmTextPart(fmErrEnvelope("not_a_directory", "Root is not a directory."))

        val query = obj["query"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool fmTextPart(fmErrEnvelope("missing_query", "query is required"))
        val recursive = obj["recursive"]?.jsonPrimitive?.booleanOrNull ?: true
        val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)

        // Determine match mode: if query contains glob chars, use glob; otherwise substring
        val isGlob = query.contains('*') || query.contains('?')
        val patternRegex = if (isGlob) globToRegex(query) else null

        val collected = mutableListOf<File>()
        var visited = 0
        var truncated = false

        fun walk(d: File) {
            val entries = try { d.listFiles() ?: emptyArray() } catch (_: SecurityException) { emptyArray() }
            for (f in entries) {
                if (visited >= FIND_VISIT_CAP || collected.size >= limit) {
                    truncated = true
                    return
                }
                visited++
                val matches = if (patternRegex != null) patternRegex.matches(f.name)
                              else f.name.contains(query, ignoreCase = true)
                if (matches) collected.add(f)
                if (recursive && f.isDirectory) walk(f)
            }
        }
        walk(rootDir)

        fmTextPart(buildJsonObject {
            put("files", buildJsonArray { collected.forEach { add(fileEntryJson(it)) } })
            put("truncated", truncated)
        }.toString())
    },
)
