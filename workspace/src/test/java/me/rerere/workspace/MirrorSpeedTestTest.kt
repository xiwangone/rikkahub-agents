package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSpeedTestTest {

    // ---- resolveMirrorProbeUrl ----

    @Test
    fun `non-official preset resolves to its own url`() {
        val preset = WorkspaceMirrorPreset("tuna", "TUNA", "https://mirrors.tuna.tsinghua.edu.cn", "China")
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn", resolveMirrorProbeUrl(preset, null))
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn", resolveMirrorProbeUrl(preset, "Debian GNU/Linux 13"))
    }

    @Test
    fun `apt official maps to ubuntu archive by distro name`() {
        val preset = WorkspaceMirrorPreset("official", "Official", "", "Global")
        assertEquals(
            "http://archive.ubuntu.com/ubuntu/",
            resolveMirrorProbeUrl(preset, "Ubuntu 24.04.4 LTS"),
        )
    }

    @Test
    fun `apt official maps to debian by distro name`() {
        val preset = WorkspaceMirrorPreset("official", "Official", "", "Global")
        assertEquals(
            "https://deb.debian.org/debian/",
            resolveMirrorProbeUrl(preset, "Debian GNU/Linux 13 (trixie)"),
        )
    }

    @Test
    fun `apt official with unknown distro is unresolvable`() {
        val preset = WorkspaceMirrorPreset("official", "Official", "", "Global")
        assertNull(resolveMirrorProbeUrl(preset, null))
        assertNull(resolveMirrorProbeUrl(preset, "Alpine Linux v3.22"))
    }

    @Test
    fun `non-apt official presets have real urls and resolve directly`() {
        WorkspaceMirrorPresets.APK.first { it.id == "official" }.let {
            assertEquals(it.url, resolveMirrorProbeUrl(it, null))
        }
        WorkspaceMirrorPresets.PIP.first { it.id == "official" }.let {
            assertEquals(it.url, resolveMirrorProbeUrl(it, null))
        }
        WorkspaceMirrorPresets.NPM.first { it.id == "official" }.let {
            assertEquals(it.url, resolveMirrorProbeUrl(it, null))
        }
    }

    // ---- measureMirrorSpeeds ----

    @Test
    fun `empty probes return empty results`() {
        assertTrue(measureMirrorSpeeds(emptyList(), probeFn = { 1L }).isEmpty())
    }

    @Test
    fun `results keep input order and collect latencies`() {
        val probes =
            listOf(
                MirrorProbe("a", "http://a/"),
                MirrorProbe("b", "http://b/"),
                MirrorProbe("c", "http://c/"),
            )
        val results =
            measureMirrorSpeeds(probes, probeFn = { url ->
                when (url) {
                    "http://a/" -> 100L
                    "http://b/" -> 50L
                    else -> 200L
                }
            })
        assertEquals(listOf("a", "b", "c"), results.map { it.id })
        assertEquals(listOf(100L, 50L, 200L), results.map { it.latencyMs })
        results.forEach { assertFalse(it.failed) }
    }

    @Test
    fun `probe failure becomes failed result without throwing`() {
        val probes = listOf(MirrorProbe("ok", "http://ok/"), MirrorProbe("bad", "http://bad/"))
        val results =
            measureMirrorSpeeds(probes, probeFn = { url ->
                if (url == "http://bad/") throw java.io.IOException("boom")
                10L
            })
        assertEquals(2, results.size)
        assertEquals(10L, results[0].latencyMs)
        assertFalse(results[0].failed)
        assertNull(results[1].latencyMs)
        assertTrue(results[1].failed)
    }

    @Test
    fun `slow probe is cut off by timeout and marked failed`() {
        val probes = listOf(MirrorProbe("slow", "http://slow/"))
        val start = System.nanoTime()
        val results =
            measureMirrorSpeeds(
                probes,
                timeoutMs = 300,
                probeFn = { _ ->
                    Thread.sleep(5_000)
                    1L
                },
            )
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("should return well before the probe itself finishes: $elapsedMs", elapsedMs < 3_000)
        assertEquals(1, results.size)
        assertNull(results[0].latencyMs)
        assertTrue(results[0].failed)
    }
}
