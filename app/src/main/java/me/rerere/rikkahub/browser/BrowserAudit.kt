package me.rerere.rikkahub.browser

import android.os.SystemClock
import android.webkit.WebResourceRequest
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.log.FileLogSink
import me.rerere.rikkahub.utils.LogRedactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browser egress audit — pass 1: record, do not block.
 *
 * Why this exists separately from the generic logging stack: the browser WebView lives
 * inside the app process, so external shell-level guards cannot see anything the page
 * sends. This is the only outbound channel the app owns that no external guard covers,
 * so it needs its own record.
 *
 * Two lines are recorded:
 *  - `action` — a model-initiated page mutation (browser_submit / click / type / …), i.e.
 *    the same tool set that the approval gate already covers in ToolApprovalDefaults.
 *  - `net` — a non-GET request the WebView actually issued, including page-script fetch
 *    and XHR POSTs that never appear in tool arguments. The request body is not
 *    observable via WebResourceRequest, so it is reported as `body=unknown` rather than
 *    guessed.
 *
 * Sinks (none depend on the app-log toggle, which defaults to off):
 *  1. `filesDir/logs/browser-YYYYMMDD.log` via [FileLogSink] (2 MB x 5 rotation, 7-day
 *     retention, re-masked on write) — the durable one.
 *  2. logcat, tag [TAG] — always on, so `adb logcat -s BrowserAudit` works on release.
 *  3. [AppLog] — in-app log page, when the user has that toggle enabled.
 *
 * Privacy: URLs are masked through [LogRedactor.maskUrl] (query secrets become `***`),
 * typed text is recorded as a length only, and eval_js records the script length, never
 * the code.
 */
object BrowserAudit {
    const val TAG = "BrowserAudit"

    /** Same key within this window is recorded once (polls would otherwise flood the log). */
    private const val DEDUP_WINDOW_MS = 2_000L

    /** Upper bound on the dedup table; expired keys are dropped first. */
    private const val MAX_DEDUP_KEYS = 512

    private val lock = Any()
    private val lastEmitAt = HashMap<String, Long>()

    /**
     * Records a page mutation the model asked for. [detail] must be non-secret metadata
     * (selector, direction, key name, or a length) — never user text or script source.
     */
    fun action(
        tool: String,
        url: String?,
        detail: String? = null,
    ) {
        val line =
            buildString {
                append(stamp())
                append(" action tool=").append(tool)
                append(" url=").append(maskedUrl(url))
                if (!detail.isNullOrBlank()) append(" ").append(detail)
            }
        writeLine("action|$tool|$url|$detail", line)
    }

    /**
     * Records a request seen by a `WebViewClient` callback, if it is worth recording.
     *
     * The "is this worth a row" rule lives here rather than in each WebView client so the
     * foreground and headless browsers cannot drift apart — both call this with just their
     * origin tag. Reads are skipped (GET navigations and sub-resources are the norm and
     * would flood the log); everything else is a potential egress.
     */
    fun maybeRecordRequest(
        webRequest: WebResourceRequest?,
        origin: String,
    ) {
        val method = webRequest?.method ?: return
        if (method.equals("GET", ignoreCase = true)) return
        request(
            method = method,
            url = webRequest.url?.toString(),
            mainFrame = webRequest.isForMainFrame,
            origin = origin,
        )
    }

    /**
     * Records a non-GET request the WebView actually sent. [origin] is "foreground" or
     * "headless" so the two browser modes stay distinguishable in the log.
     */
    fun request(
        method: String,
        url: String?,
        mainFrame: Boolean,
        origin: String,
    ) {
        val upper = method.uppercase(Locale.US)
        val line =
            buildString {
                append(stamp())
                append(" net method=").append(upper)
                append(" url=").append(maskedUrl(url))
                append(" mainFrame=").append(mainFrame)
                append(" origin=").append(origin)
                // WebResourceRequest exposes no body — state that instead of implying one.
                append(" body=unknown")
            }
        writeLine("net|$upper|$url|$mainFrame|$origin", line)
    }

    private fun maskedUrl(url: String?): String = if (url.isNullOrBlank()) "-" else LogRedactor.maskUrl(url)

    /** `MM-dd HH:mm:ss.SSS` in the device's locale-independent form (SimpleDateFormat is not thread safe). */
    private fun stamp(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun writeLine(
        dedupKey: String,
        line: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val last = lastEmitAt[dedupKey]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            if (lastEmitAt.size >= MAX_DEDUP_KEYS) {
                val cutoff = now - DEDUP_WINDOW_MS
                lastEmitAt.entries.removeAll { it.value < cutoff }
            }
            lastEmitAt[dedupKey] = now
        }
        // AppLog.i writes logcat unconditionally and the in-app buffer when enabled.
        AppLog.i(TAG, line)
        FileLogSink.append(FileLogSink.KIND_BROWSER, line)
    }
}
