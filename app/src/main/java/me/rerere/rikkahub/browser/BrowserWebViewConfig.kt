package me.rerere.rikkahub.browser

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File
import java.io.ByteArrayInputStream
import android.webkit.WebResourceResponse
import android.net.Uri
import me.rerere.rikkahub.data.log.AppLog

/**
 * Single source of truth for WebView settings shared by the foreground browser
 * ([BrowserView]) and the headless browser ([HeadlessBrowserSession]).
 *
 * Why this exists. The foreground BrowserView accumulated four white-page render
 * fixes between commits `1ac54c4b`, `3ac3b4b4`, and `a1db859c`:
 *  - `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE` (HTTPS pages with HTTP
 *    analytics / fonts render blank under the default NEVER_ALLOW)
 *  - `setLayerType(LAYER_TYPE_HARDWARE, null)` (Compose `AndroidView` interop loses
 *    the hardware layer inside a `Box` and the page renders all-white)
 *  - `mediaPlaybackRequiresUserGesture = false` (sites whose player JS errors out
 *    before layout settle render blank)
 *  - `userAgentString.replace("; wv)", ")")` (Hugo / Cloudflare / bot-sniff CMSes
 *    serve stripped-down content to a `wv`-marked embedded WebView)
 *
 * Those fixes lived in `BrowserView.WebViewHost` only. The headless WebView created
 * by `HeadlessBrowserSession.start` had NONE of them, so a Telegram-bot-driven
 * browse on the same site that the user just verified loads in foreground would
 * silently render an all-white PNG and stream it back to the user's chat.
 *
 * Pulling the configuration into one shared function means future fixes for either
 * mode automatically benefit the other.
 */
internal fun configureWebViewForRikka(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // Removed in API 35 but still compile-time present and load-bearing for some
        // sites that store IndexedDB shadow data via the old WebSQL fallback.
        @Suppress("DEPRECATION")
        databaseEnabled = true
        // Phase 20D needs this — skill webview cards produce file:// URLs into the
        // app's private data dir. Cross-origin protection still applies via the
        // file:// unique-origin rule (http(s) pages can't fetch file:// content).
        allowFileAccess = true
        // Required for skill webview assets: when a skill's viewer page (e.g.
        // virtual-piano's ui.html) is opened from a file:// URL it needs to load
        // sibling asset files (audio, images, sub-pages) also via file://. Without
        // this flag the WebView blocks those requests silently (no error, just empty
        // <audio> elements). This only enables file:// → file:// sub-resource loads;
        // http(s) pages still cannot reach app-private file:// paths.
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        allowContentAccess = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = false
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        userAgentString = userAgentString.replace("; wv)", ")")
    }
    // Hardware layer hint. For the foreground Activity's WebView this fixes a Compose
    // AndroidView interop quirk that produces all-white pages. For headless capture via
    // `webView.draw(canvas)` onto a software bitmap the framework falls back to the
    // software path automatically — calling this is harmless either way and keeps the
    // two code paths identical.
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
}

/**
 * file:// 资源白名单：只放行技能目录（`filesDir/skills`）内的本地文件。
 *
 * 背景：为支持技能卡片（`file://` 页面）加载同目录素材，WebView 开了
 * `allowFileAccessFromFileURLs` —— 但那是**全局开关**，无法按目录限定。于是一个被打开的
 * 技能页（含其中的第三方脚本）可以读取任意 `file://` 路径，包括 App 私有目录（凭证密文、
 * 配置文件）。这里在**资源请求层**补一道按目录的闸：非技能目录的 file:// 请求拦空。
 *
 * 非 file:// 请求恒返回 true（交给系统默认处理，不影响 http(s) 浏览）。
 */
internal fun isLocalFileRequestAllowed(url: Uri?, skillsDir: File): Boolean {
    if (url == null) return true
    if (!url.scheme.equals("file", ignoreCase = true)) return true
    val path = url.path ?: return false
    return runCatching {
        val target = File(path).canonicalFile
        val root = skillsDir.canonicalFile
        target.path == root.path || target.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)
}

/**
 * 被白名单拒绝的 file:// 请求的统一响应。
 *
 * **不静默**：① 落一条 WARN 日志（含被拒 URL，可从 App 日志定位）
 * ② 返回可见的 HTML 说明页 —— 此前是 403 空体，表现是技能卡片白屏且毫无原因。
 * URL 进 HTML 前做转义，避免把页面可控内容当标记渲染。
 */
internal fun localFileForbiddenResponse(url: Uri? = null): WebResourceResponse {
    val target = url?.toString().orEmpty()
    AppLog.w("Browser", "file:// request blocked by local-file whitelist: " + target)
    val safe = target.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val html =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>403 file:// blocked</title></head>" +
            "<body style=\"font:14px/1.5 sans-serif;padding:16px\">" +
            "<h3>file:// request blocked by local-file whitelist</h3>" +
            "<p>Only local files under the skills directory (filesDir/skills) are allowed.</p>" +
            "<p>Blocked URL: <code>" + safe + "</code></p></body></html>"
    return WebResourceResponse(
        "text/html",
        "utf-8",
        403,
        "Forbidden",
        emptyMap(),
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )
}
