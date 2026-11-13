package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget
import me.rerere.workspace.ssh.SshHostKey

private const val TAG_HOST_KEY = "HostKeyRepository"

/**
 * Host-key checking that implements the accept-new (trust-on-first-use) policy
 * for real.
 *
 * JSch 2.28.7 does NOT understand "accept-new": Session.doCheckHostKey only
 * branches on "ask" / "yes" / "no", so a session configured with
 * StrictHostKeyChecking=accept-new silently accepts a changed key and never
 * records a new one (verified against the 2.28.7 bytecode: the string
 * "accept-new" appears nowhere in Session or KnownHosts). The call sites
 * that set it therefore ran with host-key checking effectively off, even
 * though their comments described the intended accept-new behaviour.
 *
 * This wrapper implements the policy at the [HostKeyRepository] level instead:
 * - first sight of a host ([HostKeyRepository.NOT_INCLUDED]): record the
 *   presented key in the delegate and persist it, then proceed;
 * - a different key for a known host ([HostKeyRepository.CHANGED]): refuse
 *   (return CHANGED; callers pair this with StrictHostKeyChecking=yes so the
 *   session throws instead of connecting).
 *
 * [persist] rewrites the backing known_hosts file from the delegate's
 * in-memory keys. A persist failure is logged, never fatal for the check
 * itself: the in-memory record still protects this process, and the next
 * connect re-trusts. A record failure (unparseable key blob) refuses the
 * connection instead: an unrecordable key cannot be verified later.
 */
internal class AcceptNewHostKeyRepository(
    private val delegate: HostKeyRepository,
    private val persist: () -> Unit,
) : HostKeyRepository {
    override fun check(host: String, key: ByteArray): Int {
        return when (delegate.check(host, key)) {
            HostKeyRepository.OK -> HostKeyRepository.OK
            HostKeyRepository.NOT_INCLUDED -> {
                // Trust on first use: record the presented key before proceeding.
                val recorded = runCatching {
                    delegate.add(HostKey(host, key), null)
                    persist()
                }.onFailure {
                    AppLog.w(TAG_HOST_KEY, "accept-new: could not record host key for $host", it)
                }.isSuccess
                if (recorded) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = delegate.add(hostkey, ui)

    override fun remove(host: String, type: String) = delegate.remove(host, type)

    override fun remove(host: String, type: String, key: ByteArray) = delegate.remove(host, type, key)

    override fun getKnownHostsRepositoryID(): String = delegate.knownHostsRepositoryID

    override fun getHostKey(): Array<HostKey> = delegate.hostKey

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = delegate.getHostKey(host, type)
}

/**
 * Install correct accept-new checking on [session] (call before connect()).
 * The session is set to StrictHostKeyChecking=yes: with the wrapper in place
 * first-use keys are already recorded (check returns OK), so "yes" only ever
 * fires on a genuinely changed key — which must refuse. Caller-supplied
 * extra ssh options applied afterwards may still override the config
 * (explicit user choice, unchanged behaviour).
 */
internal fun installHostKeyVerification(jsch: JSch, session: Session) {
    session.setHostKeyRepository(
        AcceptNewHostKeyRepository(jsch.hostKeyRepository) { persistKnownHosts(jsch) },
    )
    session.setConfig("StrictHostKeyChecking", "yes")
}

/**
 * Rewrite the known_hosts file backing [jsch] from its in-memory repository.
 * The file is resolved from the repository ID (the path setKnownHosts loaded);
 * a JSch with no backing file logs and skips instead of throwing.
 */
internal fun persistKnownHosts(jsch: JSch) {
    val path = jsch.hostKeyRepository.knownHostsRepositoryID
    if (path.isBlank()) {
        AppLog.w(TAG_HOST_KEY, "persistKnownHosts: no backing known_hosts file, key kept in memory only")
        return
    }
    persistKnownHosts(File(path), jsch.hostKeyRepository)
}

/**
 * Atomically rewrite [file] from [repository]'s in-memory keys (known_hosts(5)
 * format). Same serialisation as the forget-host-key path: optional @marker,
 * host, type, base64 key, one line per key. Temp file + atomic replace, so a
 * crash mid-write never leaves a half-written trust store.
 */
internal fun persistKnownHosts(file: File, repository: HostKeyRepository) {
    val parent = file.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
        throw IOException("Could not create known_hosts directory ${parent.absolutePath}")
    }
    val temp = File(parent, "${file.name}.tmp-${System.nanoTime()}")
    try {
        FileOutputStream(temp).bufferedWriter(Charsets.UTF_8).use { writer ->
            for (key in repository.hostKey ?: emptyArray()) {
                writer.appendLine(knownHostsLine(key))
            }
        }
        replaceFilePreservingTarget(temp, file)
    } finally {
        if (temp.exists()) runCatching { temp.delete() }
    }
}

/** One known_hosts(5) line: optional @marker, host, type, base64 key. */
private fun knownHostsLine(key: HostKey): String {
    val marker = key.marker?.takeIf { it.isNotEmpty() }?.let { "@$it " }.orEmpty()
    return "$marker${key.host} ${key.type} ${key.key}"
}

/**
 * One-time migration for the vault's old trust store: copy entries from
 * [vaultFile] (vault_known_hosts) into [repository] (the shared known_hosts)
 * when the shared store has no key for that host+type yet. The shared store
 * wins on conflict: if it already trusts a key for the host, the vault entry
 * is stale and dropped. Returns the number of entries imported; the caller
 * persists when the count is > 0. Hashed host entries (`|1|...`) cannot be
 * matched to a hostname and are skipped.
 *
 * The vault file itself is left on disk (ssh_forget_host_key still cleans it);
 * it is simply no longer read for trust decisions, so a changed key is
 * refused everywhere from one store.
 */
internal fun importLegacyVaultKnownHosts(repository: HostKeyRepository, vaultFile: File): Int {
    if (!vaultFile.exists()) return 0
    var imported = 0
    vaultFile.readLines(Charsets.UTF_8).forEach { line ->
        val tokens = line.trim().split(Regex("\\s+")).dropWhile { it.startsWith("@") }
        val hosts = tokens.getOrNull(0).orEmpty().split(",")
        val type = tokens.getOrNull(1).orEmpty()
        val key = tokens.getOrNull(2).orEmpty()
        if (hosts.isEmpty() || type.isEmpty() || key.isEmpty()) return@forEach
        val keyBytes = runCatching { Base64.getDecoder().decode(key) }.getOrNull() ?: return@forEach
        for (host in hosts) {
            if (host.isBlank() || host.startsWith("|")) continue
            if (!repository.getHostKey(host, type).isNullOrEmpty()) continue
            runCatching {
                val parsed = HostKey(host, keyBytes)
                if (parsed.type != type) return@runCatching
                repository.add(parsed, null)
                imported++
            }.onFailure {
                AppLog.w(TAG_HOST_KEY, "legacy vault known_hosts: skipped unparseable entry for $host", it)
            }
        }
    }
    return imported
}

/**
 * 包装文件持久化的 known_hosts 仓库：严格校验（未知 / 变更的密钥一律拒绝），
 * 同时记下对方出示的密钥，供确认弹窗展示指纹；信任后由调用方写入 known_hosts 再重连。
 * 终端页（交互式）专用：非交互路径用 [AcceptNewHostKeyRepository]（首次自动信任）。
 */
internal class VerifyingHostKeyRepository(
    private val delegate: HostKeyRepository,
) : HostKeyRepository {
    @Volatile
    var unknownKey: SshHostKey? = null
        private set

    /** first 为已信任的旧密钥，second 为对方新出示的密钥 */
    @Volatile
    var changedKey: Pair<SshHostKey, SshHostKey>? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val result = delegate.check(host, key)
        when (result) {
            HostKeyRepository.OK -> {
                unknownKey = null
                changedKey = null
            }
            HostKeyRepository.NOT_INCLUDED -> {
                unknownKey = key.toSshHostKey(host)
                changedKey = null
            }
            HostKeyRepository.CHANGED -> {
                val actual = key.toSshHostKey(host)
                val expected = delegate.getHostKey(host, actual.type)
                    ?.firstOrNull()
                    ?.let { SshHostKey(it.type, it.key) }
                if (expected == null) {
                    unknownKey = actual
                    changedKey = null
                } else {
                    unknownKey = null
                    changedKey = expected to actual
                }
            }
        }
        return result
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = delegate.add(hostkey, ui)

    override fun remove(host: String, type: String) = delegate.remove(host, type)

    override fun remove(host: String, type: String, key: ByteArray) = delegate.remove(host, type, key)

    override fun getKnownHostsRepositoryID(): String = delegate.knownHostsRepositoryID

    override fun getHostKey(): Array<HostKey> = delegate.hostKey

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = delegate.getHostKey(host, type)
}

/** 从密钥 blob 解析算法名并拼成可算指纹的 [SshHostKey] */
private fun ByteArray.toSshHostKey(host: String): SshHostKey {
    val type = runCatching { HostKey(host, this).type }.getOrDefault("unknown")
    return SshHostKey(type = type, key = Base64.getEncoder().encodeToString(this))
}
