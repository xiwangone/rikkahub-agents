package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.Session
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 侧 SSH 短时会话缓存 TTL：90 秒（任务取 60–120 秒区间中点）。
 *
 * 取 90 秒的理由：
 * - 远低于服务端常见的空闲断连阈值与 NAT 闲置映射回收时间，会话在 TTL 内几乎总
 *   是活的；会话本身还配了 30 秒 serverAliveInterval 保活，TTL 是信任边界而非
 *   存活边界，过期就重建，不赌连接还活着。
 * - 足够覆盖模型连续多条命令的突发间隔（读上条结果、组织下条命令通常在数十秒内），
 *   同一轮对话里对同一主机的第 2 条起命令不再付完整密钥交换握手。
 * - TTL 是空闲超时（每次命中滑动续期），不是绝对寿命：连续使用且间隔 < 90 秒的
 *   会话可以一直留着，停用 90 秒后下次 acquire 必然重建。
 */
internal const val SSH_SESSION_CACHE_TTL_MS: Long = 90_000L

/**
 * 短时会话缓存的键：(host, port, user, authHash, jump)。
 *
 * [authHash] 是凭证材料的 SHA-256 摘要（见 [SshAuth.cacheFingerprint]），只存摘要
 * 不存明文；密码/私钥改了就换键，旧条目随 TTL 自然过期，不会被新凭证误用。
 * [jump] 是跳板机的 "host:port:user:authHash"，直连为 null。
 */
internal data class SshSessionKey(
    val host: String,
    val port: Int,
    val user: String,
    val authHash: String,
    val jump: String?,
)

/** [SshSessionCache.acquire] 的一次租用结果。 */
internal data class CachedSshSession(
    val session: Session,
    val tunnel: JumpTunnelProxy?,
    val fromCache: Boolean,
)

/**
 * 建连成功后共享给调用方的连接（会话 + 可空的跳板隧道）。
 * 原先 SshTool.kt 里私有的 SshConnectionResources，B-2 起由缓存与建连入口共用。
 */
internal data class SshConnection(
    val session: Session,
    val tunnel: JumpTunnelProxy?,
)

/**
 * 凭证材料的缓存指纹：私钥/密码分别加 `key:`/`pw:` 前缀再取 SHA-256 hex，含
 * passphrase。只存摘要不存明文；同一份凭证每次算出的指纹相同，不同凭证必然不同键。
 */
internal fun SshAuth.cacheFingerprint(): String {
    val material = when {
        !privateKey.isNullOrBlank() -> "key:$privateKey|pass:${passphrase.orEmpty()}"
        !password.isNullOrBlank() -> "pw:$password"
        else -> "none:"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** 把跳板描述折成缓存键里的一段；直连为 null。 */
internal fun JumpSpec.cacheKeyPart(): String = "$host:$port:$user:${auth.cacheFingerprint()}"

/**
 * AI 侧 SSH 短时会话缓存（ssh_exec_saved / ssh_upload / ssh_download /
 * vault_ssh_exec 共用；workspace 侧的 SshConnectionPool 不动）。
 *
 * 同一 (host, port, user, authHash, jump) 在 [SSH_SESSION_CACHE_TTL_MS] 空闲时间
 * 内复用同一个已连通的 JSch Session，不再每调用一次就 newJSch + 完整密钥交换握手；
 * 空闲超时、会话已断、建连网络与当前探测网络不一致时一律重建。
 *
 * 并发与失效口径：
 * - 底层是 [ConcurrentHashMap]，acquire 由单个 [Mutex] 串行化；acquire 的握手
 *   也在锁内做——同一键并发调用只握手一次，代价是不同主机的并发调用最多多等
 *   一次握手时间（AI 调用多为串行，可接受，换简单可靠）。
 * - release/invalidate/invalidateSession 是无锁的（ConcurrentHashMap 原子操作
 *   + volatile 时间戳），故可在 runInterruptible 的非 suspend 块里直接调；
 *   只有 acquire 是 suspend 的。无锁失效与持锁 acquire 之间存在竞态：失效可能
 *   发生在 acquire 刚返回之后，调用方拿到已断开的会话——本次命令失败、finally
 *   失效、模型重试即重连，有界、可自愈（见下）。
 * - 失败的 connect() 不进缓存（只在 connect() 返回后才存条目），调用方自己的
 *   finally 已清理半成品资源。
 * - 没有后台线程、没有引用计数：过期条目只在 acquire 时懒清理（吸取 R4 常驻线程
 *   的教训）。同一会话被两个并发调用共享时，一方强制断开会让另一方本次失败，
 *   模型重试即重连——有界、可自愈；不为此加引用计数（漏一次 release 就会永久泄漏
 *   会话，比偶发一次重试的代价大得多）。
 * - networkLabel（ProbeOutcome.winningLabel，如 wifi/cellular/default）是
 *   acquire 的独立校验：条目建连时的 label 与当前探测 label 不同即视为 miss——
 *   网络切换后旧 TCP 大概率已静默断连，isConnected 发现不了。vault 无探测，传
 *   null 跳过校验。
 */
internal class SshSessionCache(
    private val ttlMs: Long = SSH_SESSION_CACHE_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val isAlive: (Session) -> Boolean = { it.isConnected },
) {
    private data class Entry(
        val session: Session,
        val tunnel: JumpTunnelProxy?,
        val networkLabel: String?,
        @Volatile var lastUsedMs: Long,
    )

    private val entries = ConcurrentHashMap<SshSessionKey, Entry>()
    private val lock = Mutex()

    /**
     * 取一个可用会话：命中（未过期、活着、网络 label 一致）则返回它并把
     * [CachedSshSession.fromCache] 置 true；否则摘除并断开旧条目，再调 [connect]
     * 新建并存入。connect() 抛错时不存条目、不动旧条目以外的任何东西。
     */
    suspend fun acquire(
        key: SshSessionKey,
        networkLabel: String? = null,
        connect: suspend () -> SshConnection,
    ): CachedSshSession {
        // 握手在锁内做：同一键的并发 acquire 只会有一个真正握手，其余等锁后命中。
        return lock.withLock {
            evictExpiredLocked()
            val now = nowMs()
            val existing = entries[key]
            if (existing != null && existing.networkLabel == networkLabel && isAlive(existing.session)) {
                existing.lastUsedMs = now
                return@withLock CachedSshSession(existing.session, existing.tunnel, fromCache = true)
            }
            if (existing != null) {
                entries.remove(key, existing)
                disconnectEntry(existing)
            }
            val fresh = connect()
            entries[key] = Entry(fresh.session, fresh.tunnel, networkLabel, now)
            CachedSshSession(fresh.session, fresh.tunnel, fromCache = false)
        }
    }

    /**
     * 正常用完归还：只刷新 lastUsed（滑动 TTL），不断开。
     * 无锁：lastUsedMs 是 volatile 写；条目已被替换/失效时写的是已脱离表的旧对象，
     * 无害。
     */
    fun release(key: SshSessionKey) {
        entries[key]?.let { it.lastUsedMs = nowMs() }
    }

    /**
     * 失效指定条目：只有 [session] 与条目里的会话是同一对象（`===`）时才摘除并断开，
     * 防止并发替换后误删新会话；不匹配时什么都不做。
     * 无锁：`remove(key, entry)` 只在值仍是该条目时才删。
     */
    fun invalidate(key: SshSessionKey, session: Session) {
        val entry = entries[key] ?: return
        if (entry.session === session && entries.remove(key, entry)) {
            disconnectEntry(entry)
        }
    }

    /**
     * 按会话身份全表失效：供 runCancellableSshOp 的超时/取消 finally 用（调用方
     * 手里只有 session 没有 key）。命中则摘除并断开；未命中也断开传入的会话——
     * 这是强制关闭路径，socket 必须关，调用方可无条件用它替代原地 disconnect()。
     */
    fun invalidateSession(session: Session): Boolean {
        var removed = false
        for ((key, entry) in entries.entries) {
            if (entry.session === session && entries.remove(key, entry)) {
                removed = true
                disconnectEntry(entry)
            }
        }
        if (!removed) runCatching { session.disconnect() }
        return removed
    }

    /** 删掉所有已过 TTL 的条目并断开它们；只在 acquire 时懒执行，无后台线程。 */
    private fun evictExpiredLocked() {
        val now = nowMs()
        for ((key, entry) in entries.entries) {
            if (now - entry.lastUsedMs >= ttlMs && entries.remove(key, entry)) {
                disconnectEntry(entry)
            }
        }
    }

    /** 断开一个条目的会话与跳板隧道；任一步抛错不影响另一步。 */
    private fun disconnectEntry(entry: Entry) {
        runCatching { entry.session.disconnect() }
        runCatching { entry.tunnel?.close() }
    }

    /** 测试/调试用：当前条目数（含未及懒清理的过期条目）。 */
    fun size(): Int = entries.size

    /** 测试用：清空并断开全部条目。 */
    fun closeAll() {
        val all = entries.values.toList()
        entries.clear()
        all.forEach { disconnectEntry(it) }
    }
}

/** 进程内共享的短时会话缓存。 */
internal val sharedSshSessionCache = SshSessionCache()
