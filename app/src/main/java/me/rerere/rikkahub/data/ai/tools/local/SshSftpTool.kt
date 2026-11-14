package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.data.log.AppLog

private const val TAG_SFTP = "SshSftpTool"

private fun openSftp(session: Session): ChannelSftp {
    val channel = session.openChannel("sftp") as ChannelSftp
    channel.connect()
    return channel
}

/**
 * Open a connected SSH session to a saved host with the same probe → bind → cancel logic
 * SSH exec uses. Stashes the live Session in [sessionRef] so the outer cancellable wrapper
 * can disconnect it on coroutine cancellation. Returns the [block]'s envelope, or a
 * connect-error envelope if the handshake failed before [block] could run.
 */
private suspend fun withSavedHostSession(
    context: Context,
    repo: SshHostRepository,
    vaultRepository: CredentialVaultRepository,
    name: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    block: (Session) -> JsonObject,
): JsonObject {
    val h = repo.getByName(name)
        ?: return ToolErrors.envelopeFor(error = "invalid_argument", message = "no saved host: $name", hint = "Check the parameter values and retry with corrected arguments.")
    val auth = when (val r = resolveHostAuthDetailed(h, vaultRepository, context)) {
        is HostAuthResolution.Ready -> r.auth
        is HostAuthResolution.Unusable -> return ToolErrors.envelopeFor(
            error = ToolErrors.classifyMessage(r.reason),
            message = r.reason,
        )
    }
    // Same probe-then-bind path the exec tool uses. Without this, SFTP traffic is left to
    // Android's default-network selection, which on adaptive-routing devices routes the
    // app's outgoing connections away from WiFi LAN even though WiFi is up — meaning every
    // recent fix that gave SSH exec the WiFi-binding behaviour was bypassed for SFTP.
    val outcome = probeReachability(context, h.host, h.port)
    if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) {
        return unreachableEnvelope(h.host, h.port, outcome)
    }
    // Stage 2: 建连。短时会话复用（B-2）：同一主机连续传文件不再每调用一次就
    // newJSch + 完整握手；握手失败不进缓存（acquire 只在 connect() 返回后才存条目）。
    val cacheKey = SshSessionKey(h.host, h.port, h.user, auth.cacheFingerprint(), jump = null)
    val leased = try {
        sharedSshSessionCache.acquire(cacheKey, outcome.winningLabel) {
            runInterruptible(Dispatchers.IO) {
                val jsch = newJSch(context)
                val session = openSshSession(jsch, h.host, h.port, h.user, auth, timeoutMs, network = outcome.winningNetwork)
                SshConnection(session, null)
            }
        }
    } catch (e: Throwable) {
        AppLog.w(TAG_SFTP, "ssh handshake failed", e)
        return wrapConnectError(h.host, e)
    }
    val session = leased.session
    sessionRef.set(session)
    // Stage 3: SFTP 操作（阻塞 IO）。正常完成归还会话；块抛错/取消或会话已断则失效，
    // 下次调用重建。
    var blockFailed = false
    return try {
        runInterruptible(Dispatchers.IO) { block(session) }
    } catch (e: Throwable) {
        blockFailed = true
        throw e
    } finally {
        sessionRef.set(null)
        if (blockFailed || !session.isConnected) sharedSshSessionCache.invalidate(cacheKey, session) else sharedSshSessionCache.release(cacheKey)
    }
}

/** Upload a local file to a saved-host's remote path via SFTP. */
fun sshUploadTool(
    context: Context,
    repo: SshHostRepository,
    vaultRepository: CredentialVaultRepository,
): Tool = Tool(
    name = "ssh_upload",
    description = """
        Upload a local file from the device to a remote path on a saved SSH host using SFTP.
        Both paths are absolute file paths. The remote directory must already exist.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Absolute path on the device") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote path (full filename)") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
                put("hash", buildJsonObject { put("type", "string"); put("description", "Checksum algorithms for the local file, comma-separated: md5, sha1, sha256, sha512 (default sha256; 'all' for every one). Returned under `hashes`.") })
            },
            required = listOf("name", "local_path", "remote_path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p.optString("name") ?: error("name is required")
        val rawLocalPath = p.optString("local_path") ?: error("local_path is required")
        val localPath = AgentWorkspace.expand(rawLocalPath)
        PathSafetyGuard.check(localPath)?.let { v ->
            return@Tool listOf(UIMessagePart.Text(ToolErrors.envelopeFor(error = v.code, message = v.detail).toString()))
        }
        val remotePath = p.optString("remote_path") ?: error("remote_path is required")
        val timeoutSec = (p.optInt("timeout_seconds") ?: 60).coerceIn(1, 600)
        val localFile = File(localPath)
        if (!localFile.exists() || !localFile.isFile) {
            return@Tool listOf(UIMessagePart.Text(
                ToolErrors.envelopeFor(error = "not_found", message = "local file not found: $localPath", hint = "Verify the name or id and retry.").toString()
            ))
        }
        val payload = runCancellableSshOp(
            timeoutSec * 1000L,
            // 超时/取消时缓存条目随会话一起失效（B-2）。
            onForceDisconnect = { sharedSshSessionCache.invalidateSession(it) },
        ) { sessionRef ->
            withSavedHostSession(context, repo, vaultRepository, name, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                try {
                    localFile.inputStream().use { input -> sftp.put(input, remotePath) }
                    buildJsonObject {
                        put("success", true)
                        put("remote_path", remotePath)
                        put("bytes", localFile.length())
                        // 上传内容的指纹：便于与对端 `sha256sum` / `md5sum` 对比，确认落盘字节一致
                        appendHashes(this, localFile, p.optString("hash"))
                    }
                } catch (e: Throwable) {
                    // Mirror the download path: a failed put leaves a partial remote file
                    // that looks like a completed upload. Remove it; if removal itself
                    // fails, say so in the envelope instead of silently leaving it behind.
                    var cleanupFailed: String? = null
                    val partialExists = runCatching { sftp.stat(remotePath) }.getOrNull() != null
                    if (partialExists) {
                        runCatching { sftp.rm(remotePath) }
                            .onFailure { cleanupFailed = it.message ?: it.javaClass.simpleName }
                    }
                    val message = buildString {
                        append("sftp put failed: ${e.message ?: "unknown"}")
                        if (cleanupFailed != null) {
                            append("; partial remote file could not be removed: $cleanupFailed")
                        }
                    }
                    val extra = if (partialExists) {
                        ToolErrors.extraOf("partial_remote_file_removed" to (cleanupFailed == null))
                    } else {
                        emptyMap()
                    }
                    ToolErrors.envelopeFor(error = "tool_failed", message = message, extra = extra)
                } finally {
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** Download a remote file from a saved host to a local path via SFTP. */
fun sshDownloadTool(
    context: Context,
    repo: SshHostRepository,
    vaultRepository: CredentialVaultRepository,
): Tool = Tool(
    name = "ssh_download",
    description = """
        Download a remote file from a saved SSH host to a local path on the device using SFTP.
        Both paths are absolute file paths. The local directory must already exist (or be the
        app's cache/files dir).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote file path") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Absolute local path on the device") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
                put("hash", buildJsonObject { put("type", "string"); put("description", "Checksum algorithms for the local file, comma-separated: md5, sha1, sha256, sha512 (default sha256; 'all' for every one). Returned under `hashes`.") })
            },
            required = listOf("name", "remote_path", "local_path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p.optString("name") ?: error("name is required")
        val remotePath = p.optString("remote_path") ?: error("remote_path is required")
        val rawLocalPath = p.optString("local_path") ?: error("local_path is required")
        val localPath = AgentWorkspace.expand(rawLocalPath)
        PathSafetyGuard.check(localPath)?.let { v ->
            return@Tool listOf(UIMessagePart.Text(ToolErrors.envelopeFor(error = v.code, message = v.detail).toString()))
        }
        val timeoutSec = (p.optInt("timeout_seconds") ?: 60).coerceIn(1, 600)
        val localFile = File(localPath)
        // Ensure the local parent directory exists.
        try { localFile.parentFile?.mkdirs() } catch (_: Throwable) {}
        val payload = runCancellableSshOp(
            timeoutSec * 1000L,
            // 超时/取消时缓存条目随会话一起失效（B-2）。
            onForceDisconnect = { sharedSshSessionCache.invalidateSession(it) },
        ) { sessionRef ->
            withSavedHostSession(context, repo, vaultRepository, name, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                var ok = false
                try {
                    localFile.outputStream().use { output -> sftp.get(remotePath, output) }
                    ok = true
                    buildJsonObject {
                        put("success", true)
                        put("local_path", localFile.absolutePath)
                        put("bytes", localFile.length())
                        // 就地给出摘要：交付/校验不必再跑一次 file_info + 对端 Get-FileHash
                        appendHashes(this, localFile, p.optString("hash"))
                    }
                } catch (e: Throwable) {
                    // 失败已随 error 信封返回给调用方；再记一条 w，否则
                    // 「这次 sftp 下载失败」只留在工具返回里，时间线里查不到。
                    AppLog.w(TAG_SFTP, "sftp get failed: $remotePath -> ${localFile.absolutePath}", e)
                    ToolErrors.envelopeFor(error = "tool_failed", message = "sftp get failed: ${e.message ?: "unknown"}")
                } finally {
                    // Delete partial files left by a failed get. Otherwise the user is left
                    // with a half-written file that LOOKS like a successful download —
                    // misleading and (for binaries) dangerous to execute.
                    if (!ok) try { if (localFile.exists()) localFile.delete() } catch (_: Throwable) {}
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 支持的校验算法：对外名 → JVM MessageDigest 名。 */
private val SUPPORTED_HASH_ALGORITHMS = linkedMapOf(
    "md5" to "MD5",
    "sha1" to "SHA-1",
    "sha256" to "SHA-256",
    "sha512" to "SHA-512",
)

/**
 * 解析 `hash` 参数：逗号 / 中文逗号 / 分号 / 空白分隔，或 `all`；**缺省为 sha256**。
 * 返回（要算的算法, 不认识的名字）——不认识的名字回给调用方，而不是静默忽略。
 */
private fun resolveHashAlgorithms(spec: String?): Pair<List<Pair<String, String>>, List<String>> {
    val raw = spec?.trim()?.lowercase().orEmpty()
    val names =
        when {
            raw == "all" -> SUPPORTED_HASH_ALGORITHMS.keys.toList()
            // 缺省只算 sha256（与工具描述一致）；要多种就显式写 `md5,sha256`，或写 `all`
            raw.isEmpty() -> listOf("sha256")
            else -> raw.split(',', '，', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        }
    val wanted = names.distinct()
    val known = wanted.filter { it in SUPPORTED_HASH_ALGORITHMS }.map { it to SUPPORTED_HASH_ALGORITHMS.getValue(it) }
    return known to wanted.filter { it !in SUPPORTED_HASH_ALGORITHMS }
}

/**
 * **一次读盘**同时算出多个摘要（多算法共用一遍 IO），返回 算法名 → 小写十六进制。
 * 文件读不了时返回空表——校验值是附加信息，不该让整次传输变成失败。
 */
private fun hashesOf(file: File, algorithms: List<Pair<String, String>>): Map<String, String> {
    if (algorithms.isEmpty()) return emptyMap()
    return try {
        val digests = algorithms.map { (_, jdkName) -> java.security.MessageDigest.getInstance(jdkName) }
        feedDigests(file, digests)
        algorithms.mapIndexed { index, (name, _) ->
            name to digests[index].digest().joinToString("") { "%02x".format(it) }
        }.toMap()
    } catch (_: Throwable) {
        emptyMap()
    }
}

/** 把文件内容喂给全部 digest（单次 IO、多算法共用）；供 [hashesOf] 使用，单独成函数以避免嵌套过深。 */
private fun feedDigests(file: File, digests: List<java.security.MessageDigest>) {
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digests.forEach { it.update(buffer, 0, read) }
        }
    }
}

/** 把请求的校验摘要写进返回体：`hashes`（算法名 → 摘要）+ 不认识的算法名。 */
private fun appendHashes(
    builder: kotlinx.serialization.json.JsonObjectBuilder,
    file: File,
    spec: String?,
) {
    val (algorithms, unsupported) = resolveHashAlgorithms(spec)
    val digests = hashesOf(file, algorithms)
    if (digests.isNotEmpty()) {
        builder.put("hashes", buildJsonObject { digests.forEach { (name, value) -> put(name, value) } })
    }
    if (unsupported.isNotEmpty()) builder.put("hashes_unsupported", unsupported.joinToString(","))
}
