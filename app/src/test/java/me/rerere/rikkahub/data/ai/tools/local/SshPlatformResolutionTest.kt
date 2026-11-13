package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.datastore.SshPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the SSH platform resolution chain (explicit > banner cache >
 * global default, then command-shape heuristics at the call site).
 *
 * These cover only the pure resolution function and the preference mapping;
 * live behaviour (banner caching, wrapping) needs a real SSH server and is
 * verified on-device.
 */
class SshPlatformResolutionTest {

    @Test
    fun `explicit WINDOWS beats a cached Linux result`() {
        assertEquals(true, RemotePlatform.resolveWindows(SshPlatform.WINDOWS, false, SshPlatform.LINUX))
    }

    @Test
    fun `explicit LINUX beats a cached Windows result`() {
        assertEquals(false, RemotePlatform.resolveWindows(SshPlatform.LINUX, true, SshPlatform.WINDOWS))
    }

    @Test
    fun `explicit AUTO falls through to the cached banner result`() {
        assertEquals(true, RemotePlatform.resolveWindows(SshPlatform.AUTO, true, SshPlatform.LINUX))
        assertEquals(false, RemotePlatform.resolveWindows(SshPlatform.AUTO, false, SshPlatform.WINDOWS))
        assertEquals(true, RemotePlatform.resolveWindows(null, true, SshPlatform.LINUX))
        assertEquals(false, RemotePlatform.resolveWindows(null, false, SshPlatform.WINDOWS))
    }

    @Test
    fun `cached result beats the global default`() {
        assertEquals(true, RemotePlatform.resolveWindows(null, true, SshPlatform.LINUX))
        assertEquals(false, RemotePlatform.resolveWindows(null, false, SshPlatform.WINDOWS))
        assertEquals(true, RemotePlatform.resolveWindows(SshPlatform.AUTO, true, SshPlatform.LINUX))
    }

    @Test
    fun `global default applies when nothing above it resolved`() {
        assertEquals(true, RemotePlatform.resolveWindows(null, null, SshPlatform.WINDOWS))
        assertEquals(false, RemotePlatform.resolveWindows(null, null, SshPlatform.LINUX))
        assertEquals(true, RemotePlatform.resolveWindows(SshPlatform.AUTO, null, SshPlatform.WINDOWS))
    }

    @Test
    fun `global default AUTO falls through to null for heuristic fallback`() {
        assertNull(RemotePlatform.resolveWindows(null, null, SshPlatform.AUTO))
        assertNull(RemotePlatform.resolveWindows(SshPlatform.AUTO, null, SshPlatform.AUTO))
    }

    @Test
    fun `nothing resolved returns null`() {
        assertNull(RemotePlatform.resolveWindows(null, null, null))
        assertNull(RemotePlatform.resolveWindows(SshPlatform.AUTO, null, null))
    }

    @Test
    fun `fromPreference round-trips every platform through its preference name`() {
        for (platform in SshPlatform.values()) {
            assertEquals(platform, SshPlatform.fromPreference(platform.preferenceName))
        }
    }

    @Test
    fun `fromPreference falls back to AUTO for unknown values`() {
        assertEquals(SshPlatform.AUTO, SshPlatform.fromPreference("not-a-platform"))
        assertEquals(SshPlatform.AUTO, SshPlatform.fromPreference(""))
    }

    @Test
    fun `probe cache key includes the login user`() {
        // 同一主机不同用户互不干扰；user 为空时退回旧 host:port 键（平台缓存与旧数据迁移用它）。
        assertEquals("example.com:22:alice", RemotePlatform.cacheKey("example.com", 22, "alice"))
        assertEquals("example.com:22", RemotePlatform.cacheKey("example.com", 22, null))
        assertEquals("example.com:22", RemotePlatform.cacheKey("example.com", 22, "  "))
        assertEquals("example.com:22:alice", RemotePlatform.cacheKey(" Example.COM ", 22, " Alice "))
    }
}
