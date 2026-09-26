package me.rerere.workspace

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Lightweight mirror speed test for the picker dialog.
 *
 * Design notes:
 * - This module has no coroutine dependency: a fixed thread pool is used internally, and the
 *   UI layer wraps [measureMirrorSpeeds] in `withContext(Dispatchers.IO)`.
 * - Any HTTP status counts as "reachable" (403/404 still proves connectivity); latency covers
 *   connect + response headers (TTFB-like). The body is never read.
 * - The per-URL probe is injectable so JVM unit tests cover ordering/aggregation/timeout
 *   without touching the real network.
 */

/** Default per-probe timeout in milliseconds. */
const val MIRROR_SPEED_TEST_TIMEOUT_MS = 5_000L

/** One probe target resolved from a [WorkspaceMirrorPreset]. */
data class MirrorProbe(
    val id: String,
    val url: String,
)

/** Result for one preset; [latencyMs] is null when unreachable or timed out. */
data class MirrorSpeedResult(
    val id: String,
    val latencyMs: Long?,
    val failed: Boolean = false,
)

/**
 * Resolve the URL to probe for a preset.
 *
 * The APT "official" preset has a blank base URL (sources are assembled per distro when
 * applying mirrors), so probing needs a real host mapped from the rootfs distro name.
 * [distroName] is the os-release PRETTY_NAME (e.g. "Ubuntu 24.04.4 LTS").
 * Returns null when there is nothing meaningful to probe (APT official + unknown distro).
 */
fun resolveMirrorProbeUrl(
    preset: WorkspaceMirrorPreset,
    distroName: String?,
): String? {
    val url = preset.url.trim()
    if (url.isNotEmpty()) return url
    if (preset.id != "official") return null
    val name = distroName.orEmpty()
    return when {
        name.contains("ubuntu", ignoreCase = true) -> "http://archive.ubuntu.com/ubuntu/"
        name.contains("debian", ignoreCase = true) -> "https://deb.debian.org/debian/"
        else -> null
    }
}

/**
 * Measure all probes concurrently and return results in the same order as [probes].
 * Failures never propagate - each becomes a [MirrorSpeedResult] with [MirrorSpeedResult.failed].
 *
 * [probeFn] returns the measured latency in ms, or null when the target is unreachable.
 */
fun measureMirrorSpeeds(
    probes: List<MirrorProbe>,
    timeoutMs: Long = MIRROR_SPEED_TEST_TIMEOUT_MS,
    probeFn: (String) -> Long? = ::probeUrlLatency,
): List<MirrorSpeedResult> {
    if (probes.isEmpty()) return emptyList()
    val pool: ExecutorService = Executors.newFixedThreadPool(probes.size.coerceAtMost(MAX_CONCURRENCY))
    return try {
        val futures =
            probes.map { probe ->
                probe to
                    pool.submit(
                        Callable {
                            runCatching { probeFn(probe.url) }.getOrNull()
                        },
                    )
            }
        futures.map { (probe, future) ->
            try {
                val latency = future.get(timeoutMs + FUTURE_GRACE_MS, TimeUnit.MILLISECONDS)
                if (latency != null) {
                    MirrorSpeedResult(probe.id, latency)
                } else {
                    MirrorSpeedResult(probe.id, null, failed = true)
                }
            } catch (_: TimeoutException) {
                future.cancel(true)
                MirrorSpeedResult(probe.id, null, failed = true)
            } catch (_: Exception) {
                MirrorSpeedResult(probe.id, null, failed = true)
            }
        }
    } finally {
        pool.shutdownNow()
    }
}

/**
 * Real probe: time to first response (status line) via GET. The body is not read.
 * Any response (even 403/404) counts as reachable; returns null on IO failure.
 */
private fun probeUrlLatency(url: String): Long? {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = MIRROR_SPEED_TEST_TIMEOUT_MS.toInt()
        connection.readTimeout = MIRROR_SPEED_TEST_TIMEOUT_MS.toInt()
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = "GET"
        val start = System.nanoTime()
        connection.responseCode
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    } catch (_: IOException) {
        return null
    } finally {
        connection.disconnect()
    }
}

private const val FUTURE_GRACE_MS = 1_500L
private const val MAX_CONCURRENCY = 8
private const val USER_AGENT = "RikkaHub-MirrorSpeedTest/1.0"
