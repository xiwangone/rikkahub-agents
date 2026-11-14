package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 侧 SSH 短时会话缓存单测（纯 JVM，不联网）：
 * 未 connect() 的 JSch Session 靠注入的 isAlive 模拟活/死——真实 isConnected 只在
 * 真连上后才为 true，单测里由调用方注入的 liveness 决定。
 */
class SshSessionCacheTest {

    private val jsch = JSch()

    private fun newSession(): Session = jsch.getSession("user", "example.com", 22)

    private fun key(
        host: String = "example.com",
        port: Int = 22,
        user: String = "user",
        authHash: String = "hash-a",
        jump: String? = null,
    ) = SshSessionKey(host, port, user, authHash, jump)

    private fun connectCounting(connects: AtomicInteger): suspend () -> SshConnection = {
        SshConnection(newSession(), null).also { connects.incrementAndGet() }
    }

    @Test
    fun `second acquire for same key reuses the session`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val first = cache.acquire(key(), connect = connectCounting(connects))
        val second = cache.acquire(key(), connect = connectCounting(connects))
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertSame(first.session, second.session)
        assertEquals(1, connects.get())
        cache.closeAll()
    }

    @Test
    fun `different host gets a different session`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val a = cache.acquire(key(host = "a.example.com"), connect = connectCounting(connects))
        val b = cache.acquire(key(host = "b.example.com"), connect = connectCounting(connects))
        assertFalse(b.fromCache)
        assertNotSame(a.session, b.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `different auth hash gets a different session`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val a = cache.acquire(key(authHash = "hash-a"), connect = connectCounting(connects))
        val b = cache.acquire(key(authHash = "hash-b"), connect = connectCounting(connects))
        assertFalse(b.fromCache)
        assertNotSame(a.session, b.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `different jump route gets a different session`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val direct = cache.acquire(key(), connect = connectCounting(connects))
        val viaJump = cache.acquire(key(jump = "jump.example.com:22:user:hash-j"), connect = connectCounting(connects))
        assertFalse(viaJump.fromCache)
        assertNotSame(direct.session, viaJump.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `expired entry is rebuilt, not reused`() = runBlocking {
        var now = 1_000_000L
        val cache = SshSessionCache(nowMs = { now }, isAlive = { true })
        val connects = AtomicInteger(0)
        val first = cache.acquire(key(), connect = connectCounting(connects))
        now += SSH_SESSION_CACHE_TTL_MS + 1
        val second = cache.acquire(key(), connect = connectCounting(connects))
        assertFalse(second.fromCache)
        assertNotSame(first.session, second.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `entry reused within TTL`() = runBlocking {
        var now = 1_000_000L
        val cache = SshSessionCache(nowMs = { now }, isAlive = { true })
        val connects = AtomicInteger(0)
        val first = cache.acquire(key(), connect = connectCounting(connects))
        now += SSH_SESSION_CACHE_TTL_MS - 1
        val second = cache.acquire(key(), connect = connectCounting(connects))
        assertTrue(second.fromCache)
        assertSame(first.session, second.session)
        assertEquals(1, connects.get())
        cache.closeAll()
    }

    @Test
    fun `failed connect is not cached`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val attempts = AtomicInteger(0)
        try {
            cache.acquire(key()) {
                attempts.incrementAndGet()
                throw IllegalStateException("boom")
            }
            fail("expected connect to throw")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(0, cache.size())
        val ok = cache.acquire(key(), connect = connectCounting(attempts))
        assertFalse(ok.fromCache)
        assertEquals(2, attempts.get())
        assertEquals(1, cache.size())
        cache.closeAll()
    }

    @Test
    fun `disconnected session is not reused`() = runBlocking {
        val alive = mutableSetOf<Session>()
        val cache = SshSessionCache(isAlive = { it in alive })
        val connects = AtomicInteger(0)
        val first = cache.acquire(key()) {
            SshConnection(newSession(), null).also { alive += it.session; connects.incrementAndGet() }
        }
        // 模拟服务端断开：会话对象还在，但已不活。
        alive -= first.session
        val second = cache.acquire(key(), connect = connectCounting(connects))
        assertFalse(second.fromCache)
        assertNotSame(first.session, second.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `network label change forces reconnect`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val first = cache.acquire(key(), networkLabel = "wifi", connect = connectCounting(connects))
        val sameNetwork = cache.acquire(key(), networkLabel = "wifi", connect = connectCounting(connects))
        assertTrue(sameNetwork.fromCache)
        assertSame(first.session, sameNetwork.session)
        val otherNetwork = cache.acquire(key(), networkLabel = "cellular", connect = connectCounting(connects))
        assertFalse(otherNetwork.fromCache)
        assertNotSame(first.session, otherNetwork.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `invalidate only removes the matching session`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val a = cache.acquire(key(host = "a.example.com"), connect = connectCounting(connects))
        val b = cache.acquire(key(host = "b.example.com"), connect = connectCounting(connects))
        // 拿错会话来失效：什么都不该发生。
        cache.invalidate(key(host = "a.example.com"), b.session)
        assertEquals(2, cache.size())
        cache.invalidate(key(host = "a.example.com"), a.session)
        assertEquals(1, cache.size())
        val bAgain = cache.acquire(key(host = "b.example.com"), connect = connectCounting(connects))
        assertTrue(bAgain.fromCache)
        assertSame(b.session, bAgain.session)
        assertEquals(2, connects.get())
        cache.closeAll()
    }

    @Test
    fun `invalidateSession removes the entry, unknown session returns false`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val a = cache.acquire(key(host = "a.example.com"), connect = connectCounting(connects))
        val b = cache.acquire(key(host = "b.example.com"), connect = connectCounting(connects))
        assertTrue(cache.invalidateSession(a.session))
        assertEquals(1, cache.size())
        val bAgain = cache.acquire(key(host = "b.example.com"), connect = connectCounting(connects))
        assertTrue(bAgain.fromCache)
        assertSame(b.session, bAgain.session)
        assertFalse(cache.invalidateSession(newSession()))
        assertEquals(1, cache.size())
        cache.closeAll()
    }

    @Test
    fun `concurrent acquires for same key connect only once`() = runBlocking {
        val cache = SshSessionCache(isAlive = { true })
        val connects = AtomicInteger(0)
        val results = (1..10).map {
            async {
                cache.acquire(key()) {
                    delay(50)
                    SshConnection(newSession(), null).also { connects.incrementAndGet() }
                }
            }
        }.map { it.await() }
        assertEquals(1, connects.get())
        val first = results.first().session
        results.forEach { assertSame(first, it.session) }
        cache.closeAll()
    }

    @Test
    fun `auth fingerprint differs across secrets and is stable for the same secret`() {
        val a = SshAuth(password = "hunter2")
        val b = SshAuth(password = "hunter2")
        val c = SshAuth(password = "hunter3")
        val d = SshAuth(privateKey = "hunter2")
        assertEquals(a.cacheFingerprint(), b.cacheFingerprint())
        assertNotEquals(a.cacheFingerprint(), c.cacheFingerprint())
        assertNotEquals(a.cacheFingerprint(), d.cacheFingerprint())
        assertEquals(64, a.cacheFingerprint().length)
        val withPass1 = SshAuth(privateKey = "key", passphrase = "p1")
        val withPass2 = SshAuth(privateKey = "key", passphrase = "p2")
        assertNotEquals(withPass1.cacheFingerprint(), withPass2.cacheFingerprint())
    }

    @Test
    fun `cache TTL is 90 seconds`() {
        assertEquals(90_000L, SSH_SESSION_CACHE_TTL_MS)
    }
}
