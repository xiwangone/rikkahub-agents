package me.rerere.rikkahub.data.network

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executor

/**
 * Built-in browser (WebView) proxy, following the Minis approach: the browser
 * scope points the app-wide WebView proxy at the local proxy override
 * ([ProxyController], `PROXY_OVERRIDE` feature).
 *
 * Notes:
 * - The override is app-wide (all WebViews in this process), not per page.
 *   It is (re)applied when a browser page opens and when the browser route
 *   changes; a route change does not reach an already-open page until it is
 *   reopened.
 * - Only the proxy host/port (plus credentials, when configured) are put in
 *   the rule; loopback is always bypassed. A proxy that answers 407 without
 *   usable URL credentials will fail in the WebView — the WebView page has no
 *   auth handler for external proxies (the API-traffic authenticators do not
 *   apply here). This is a known limitation, documented in the delivery notes.
 * - SOCKS5 uses the `socks5://` rule scheme (Chromium's SOCKS5 scheme).
 *   Whether a given WebView build honours it depends on the WebView
 *   implementation; unsupported builds are reported by [isSupported].
 */
object WebViewProxy {
    private val mainExecutor: Executor =
        Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * Apply [route] for the built-in browser. Returns false when the WebView
     * implementation does not support proxy override (the caller should show
     * a note instead of silently pretending it worked). Clearing the override
     * (non-PROXY route, or PROXY with no proxy configured) also returns true.
     */
    fun apply(
        route: NetworkRoute,
        proxyUrl: String,
        proxyUsername: String,
        proxyPassword: String?,
    ): Boolean {
        if (!isSupported()) return false
        val controller = ProxyController.getInstance()
        if (route != NetworkRoute.PROXY) {
            controller.clearProxyOverride(mainExecutor) {}
            return true
        }
        val proxy = proxyUrl.toProxyOrNull() ?: run {
            // PROXY route but no proxy configured (missing-proxy rule):
            // clear any override so the browser goes direct.
            controller.clearProxyOverride(mainExecutor) {}
            return true
        }
        val address = proxy.address() as? InetSocketAddress ?: return false
        val credentials = if (proxyUsername.isNotBlank() && !proxyPassword.isNullOrEmpty()) {
            "${proxyUsername}:${proxyPassword}@"
        } else {
            ""
        }
        val scheme = if (proxy.type() == Proxy.Type.SOCKS) "socks5" else "http"
        val rule = "$scheme://$credentials${address.hostString}:${address.port}"
        val config = buildWebViewProxyConfig(rule)
        controller.setProxyOverride(config, mainExecutor) {}
        return true
    }
}

/**
 * Proxy override for [rule], bypassing loopback. Chromium bypass syntax
 * requires brackets around IPv6 literals, so the loopback address is
 * written `[::1]`, not `::1`.
 */
internal fun buildWebViewProxyConfig(rule: String): ProxyConfig =
    ProxyConfig.Builder()
        .addProxyRule(rule)
        .addBypassRule("localhost")
        .addBypassRule("127.0.0.1")
        .addBypassRule("[::1]")
        .build()
