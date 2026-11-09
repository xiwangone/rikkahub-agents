package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserAudit
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.HeadlessBrowserSessionPool
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import java.io.File
import java.io.FileOutputStream

/**
 * Pass 2 of the in-app Browser feature: 17 LLM-callable tool factories that drive the
 * BrowserActivity's WebView through [BrowserControllerHandle.withController].
 *
 * Every tool wraps its dispatch in [withTimeoutOrNull] (30 s per the spec's "every tool
 * MUST have a hard timeout" rule); every state-changing write tool also calls
 * [awaitReadyState] post-action so the next read tool sees the new page.
 *
 * Tool registration is gated by [me.rerere.rikkahub.browser.BrowserPreferences] in
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] — toggling a tool off in
 * Settings → Browser unregisters it entirely, so YOLO can't accidentally run a tool the
 * user has explicitly disabled.
 */

// ---- Read tools ---------------------------------------------------------------------------

fun browserOpenTool(context: Context, invocationContext: ToolInvocationContext? = null): Tool = Tool(
    name = BrowserToolDefaults.OPEN,
    description = "Navigate the in-app browser to a URL. Launches the browser if it isn't open. Returns {success, current_url, title}. Resets the per-task 5-minute timer.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The full URL to navigate to (https://...)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // Heuristic exfil-shape check: if the URL's QUERY (not path — CDN asset hashes
        // would false-positive) carries something that looks like an opaque blob, JWT,
        // API key, or credit-card-shaped digit run, attach a warning so the LLM treats
        // the destination with care. Best-effort, not a security boundary.
        val exfilHits = SensitiveContentDetector.scanUrlQuery(url)
        // Scheme allow-list IS a security boundary: the browser WebViews run with
        // allowFileAccess on (skill webview cards open file:// pages there), so a
        // model-driven file:// navigation followed by browser_get_text would read
        // app-private files (datastore, DB, known_hosts) into the conversation —
        // including via prompt injection from a browsed page. file://, content://,
        // javascript:, intent: etc. are therefore rejected at the tool boundary.
        // Scheme-less input is passed through unchanged (pre-existing behaviour).
        val scheme = url?.let { android.net.Uri.parse(it.trim()).scheme?.lowercase() }
        val rawOut = if (url == null) {
            missingArgEnvelope("url", "url is required and must be a non-empty string")
        } else if (scheme != null && scheme !in setOf("http", "https", "about")) {
            ToolErrors.envelopeFor(error = "scheme_not_allowed", message = "browser_open only accepts http(s) and about: URLs; got scheme '$scheme'")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                // Pass 3 mode picker. If the caller is a Telegram / cron / sub-agent
                // conversation, run in headless mode — no on-screen Activity, screenshots
                // streamed to the calling chat after every state-changing tool. Otherwise
                // fall back to the foreground BrowserActivity (the only tool that can
                // launch it).
                val callerConvId = invocationContext?.callerConversationId
                val headless = isHeadlessInvocation(invocationContext)

                if (headless && callerConvId != null) {
                    // Peek BEFORE allocating: getOrCreate + start() spin up a ~30 MB WebView,
                    // which bindHeadless would then immediately reject if a different live
                    // conversation owns the controller slot — wasting the allocation.
                    // canBindHeadless mirrors bindHeadless's reject rule without mutating state;
                    // bindHeadless stays authoritative and re-checks under its lock, so a race
                    // here costs at most a discarded allocation, never a wrong binding.
                    if (!BrowserController.canBindHeadless(callerConvId)) {
                        return@withTimeoutOrNull BrowserController.bindBusyEnvelope()
                    }
                    // Headless path: get-or-create the per-conv WebView session. The
                    // lifecycle of the WebView piggybacks on the calling FGS — when the
                    // FGS dies, the whole pool dies with it. Subsequent tool calls will
                    // see Mode.Idle and return `browser_session_lost`.
                    val session = HeadlessBrowserSessionPool.getOrCreate(context, callerConvId)
                    val webView = withContext(Dispatchers.Main) { session.start(callerConvId) }
                    // Reject if another conversation already holds the (single, global)
                    // controller binding — binding a second concurrently would route this
                    // conv's streamed screenshots into the other chat. Same-conv re-bind is
                    // always accepted, so the common per-task reuse path is unaffected.
                    if (!BrowserController.bindHeadless(callerConvId, webView)) {
                        return@withTimeoutOrNull BrowserController.bindBusyEnvelope()
                    }
                    BrowserController.startTaskWindow()
                    BrowserController.appendAction("Open: $url")
                    BrowserAudit.action(BrowserToolDefaults.OPEN, url, "mode=headless")
                    val result = BrowserControllerHandle.withController {
                        withContext(Dispatchers.Main) { webView.loadUrl(url) }
                        webView.awaitReadyState(8_000L)
                        buildJsonObject {
                            put("success", true)
                            put("current_url", webView.url ?: url)
                            put("title", webView.title.orEmpty())
                        }
                    }
                    // Stream the landing-page screenshot so the user sees we arrived.
                    BrowserController.streamScreenshotIfHeadless("Opened $url")
                    result
                } else {
                    // Foreground path (Pass 1+2 behaviour). browser_open is the ONLY tool
                    // allowed to launch the Activity. Any other tool returning
                    // browser_not_open is the LLM's signal to call this first.
                    val wasAlreadyBound = BrowserController.isBound()
                    if (!wasAlreadyBound) {
                        context.startActivity(
                            me.rerere.rikkahub.browser.BrowserActivity.intent(
                                context,
                                url,
                                conversationId = callerConvId,
                            )
                        )
                        if (!BrowserController.awaitBind(5_000L)) {
                            return@withTimeoutOrNull ToolErrors.envelopeFor(error = "browser_launch_failed", message = "Failed to launch browser", hint = "Activity did not bind within 5s; retry browser_open or check that the app has overlay permission.")
                        }
                    }
                    // (Re)start the 5-minute task window on every browser_open.
                    BrowserController.startTaskWindow()
                    BrowserController.appendAction("Open: $url")
                    BrowserAudit.action(BrowserToolDefaults.OPEN, url, "mode=foreground")
                    BrowserControllerHandle.withController {
                        // When the Activity was just freshly launched, the URL was already
                        // passed as EXTRA_INITIAL_URL and the WebView began loading it
                        // before bind() returned. Skip a redundant second loadUrl — it
                        // would abort the in-flight load and restart from the top.
                        if (wasAlreadyBound) {
                            withContext(Dispatchers.Main) { webView.loadUrl(url) }
                        }
                        webView.awaitReadyState(8_000L)
                        buildJsonObject {
                            put("success", true)
                            put("current_url", webView.url ?: url)
                            put("title", webView.title.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.OPEN)
        }
        val out = if (exfilHits.isEmpty() ||
            rawOut["success"]?.jsonPrimitive?.booleanOrNull != true) rawOut
        else buildJsonObject {
            rawOut.forEach { (k, v) -> put(k, v) }
            put(
                "warning",
                "URL query string carries content shaped like sensitive data " +
                    "(${exfilHits.joinToString { it.name.lowercase() }}). " +
                    "Verify with the user that they intended to send this " +
                    "to ${rawOut["current_url"]?.jsonPrimitive?.contentOrNull ?: url} " +
                    "before relying on the result, and do NOT echo the value back."
            )
        }
        textPart(out)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.CURRENT_URL,
    description = "Return the browser's current URL and page title. {url, title}. browser_not_open if the browser isn't open.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                buildJsonObject {
                    put("url", webView.url.orEmpty())
                    put("title", webView.title.orEmpty())
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CURRENT_URL)
        textPart(out)
    },
)

fun browserScreenshotTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.SCREENSHOT,
    description = "Capture the visible viewport of the browser as a PNG vision attachment. Use browser_get_text first if you only need the page's text — screenshots cost vision tokens. full_page=true is best-effort and currently captures the viewport only (viewport_only:true in the response).$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("full_page", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, attempt to capture the entire scroll height (currently no-op; viewport-only)")
                })
            },
        )
    },
    execute = { input ->
        val fullPage = input.jsonObject["full_page"]?.jsonPrimitive?.booleanOrNull == true
        val parts = mutableListOf<UIMessagePart>()
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val (path, w, h) = withContext(Dispatchers.Main) {
                    val width = webView.width.coerceAtLeast(1)
                    val height = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    webView.draw(canvas)
                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    pruneOldCacheScreenshots(cacheDir)
                    val out = File(cacheDir, "screenshot-${System.currentTimeMillis()}.png")
                    // Recycle unconditionally in a finally block so the bitmap is freed
                    // exactly once regardless of whether compress() succeeds or throws.
                    // The prior pattern called recycle() in onFailure AND then again
                    // unconditionally — a double-recycle causes IllegalStateException.
                    try {
                        FileOutputStream(out).use { os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    Triple(out.absolutePath, width, height)
                }
                BrowserController.appendAction("Screenshot")
                buildJsonObject {
                    put("success", true)
                    put("file_path", path)
                    put("width", w)
                    put("height", h)
                    if (fullPage) put("viewport_only", true)
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCREENSHOT)
        out.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(out.toString()))
        parts
    },
)

fun browserGetTextTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_TEXT,
    description = "Returns the main article content via Readability.js by default, falling back to selector-based extraction if Readability fails. Pass extract_mode:'raw' for the unfiltered text. Pass selector (e.g. 'article', 'main', '.content') for explicit scoping — selectors override Readability. max_chars (default 8000) caps the result. Use this BEFORE screenshot if you only need text content. {text, truncated, extract_mode}.$TELEGRAM_HEADLESS_CUE",
    parameters = { getTextSchema(defaultMax = 8000) },
    execute = { input -> textPart(runGetText(input)) },
)

fun browserGetDomTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_DOM,
    description = "Extract a simplified outerHTML of a CSS selector (default 'body'). Strips <script>/<style>. Truncates at max_chars (default 4000). Use scoped selectors like 'article' / 'main' rather than 'body' for relevance — body usually includes nav and footer chrome that costs tokens without value. {html, truncated}.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorAndMaxCharsSchema(defaultMax = 4000, required = false) },
    execute = { input -> textPart(runReadHelper(input, BrowserToolDefaults.GET_DOM, defaultMax = 4000) { selector, maxChars ->
        """(function(){
            try {
                var el = document.querySelector(${jsString(selector)});
                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                var clone = el.cloneNode(true);
                clone.querySelectorAll('script,style,noscript').forEach(function(n){n.remove();});
                var html = clone.outerHTML || '';
                var truncated = false;
                if (html.length > $maxChars) { html = html.substring(0, $maxChars); truncated = true; }
                return JSON.stringify({html:html, truncated:truncated});
            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
        })()"""
    }) },
)

fun browserGetLinksTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_LINKS,
    description = "List up to 100 anchor links inside a CSS selector (default 'body') as {links:[{href, text}], count}. {error:'selector_not_found'} if the root selector doesn't match.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "Root selector to search inside (default 'body')")
            })
        })
    },
    execute = { input ->
        val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }) ?: "body"
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val js = """(function(){
                    try {
                        var root = document.querySelector(${jsString(selector)});
                        if (!root) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                        var anchors = root.querySelectorAll('a[href]');
                        var out = [];
                        for (var i=0; i<anchors.length && out.length<100; i++) {
                            var a = anchors[i];
                            var href = a.href || '';
                            var text = (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim();
                            if (text.length>200) text = text.substring(0,200);
                            out.push({href:href, text:text});
                        }
                        return JSON.stringify({links:out, count:out.length});
                    } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                })()"""
                parseJsResult(webView.evaluateJavascriptAsync(js))
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_LINKS)
        textPart(out)
    },
)

fun browserBackTool(): Tool = Tool(
    name = BrowserToolDefaults.BACK,
    description = "Navigate the browser one step back in history. {success, current_url}.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.BACK, forward = false)) },
)

fun browserForwardTool(): Tool = Tool(
    name = BrowserToolDefaults.FORWARD,
    description = "Navigate the browser one step forward in history. {success, current_url}.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.FORWARD, forward = true)) },
)

/** Valid values for browser_wait_for's `state` arg. `attached` preserves the original behavior. */
private val WAIT_FOR_STATES = setOf("attached", "detached", "visible", "hidden")

/**
 * Build the JS predicate browser_wait_for polls. Returns a self-invoking expression
 * that evaluates to the JS boolean `true` once the wait condition is satisfied.
 *
 *  - `state` decides what "satisfied" means for the element matching [selector]:
 *      attached  — at least one element matches (default; original behavior)
 *      detached  — no element matches
 *      visible   — a matching element is in the DOM AND rendered (offsetParent or a
 *                  non-zero client rect — covers position:fixed which has null offsetParent)
 *      hidden    — no matching element is visible (none in DOM, or all rendered hidden)
 *  - When [containsText] is non-null, the matched element ALSO has to contain that text
 *    (case-sensitive substring of innerText/textContent). For detached/hidden states the
 *    text constraint is ignored — "not there" can't also "contain text".
 *
 * Pure string builder so it stays unit-testable without a WebView.
 */
internal fun buildWaitForPredicate(selector: String, state: String, containsText: String?): String {
    val sel = jsString(selector)
    val txt = containsText?.let { jsString(it) }
    val textCheck = if (txt != null) {
        "function(el){var t=(el.innerText||el.textContent||'');return t.indexOf($txt)!==-1;}"
    } else {
        "function(){return true;}"
    }
    val visibleCheck = "function(el){" +
        "if(el.offsetParent!==null)return true;" +
        "var r=el.getClientRects();return r&&r.length>0;" +
        "}"
    return when (state) {
        "detached" -> "(function(){try{return document.querySelector($sel)===null;}catch(e){return false;}})()"
        "hidden" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i]))return false;}" +
            "return true;" +
            "}catch(e){return false;}})()"
        "visible" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i])&&hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
        else -> "(function(){try{" + // "attached" (default)
            "var els=document.querySelectorAll($sel);" +
            "var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
    }
}

fun browserWaitForTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR,
    description = "Pause until a CSS selector reaches a target state. Polls every 200 ms up to timeout_ms (default 10_000). state is one of attached (default — element present in DOM), detached (element gone), visible (present AND rendered), hidden (none rendered). Optional contains_text waits until a matching element contains that text. {found, elapsed_ms}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector to wait for")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Max wait in ms (default 10000, capped at the configured per-tool timeout)")
            })
            put("contains_text", buildJsonObject {
                put("type", "string")
                put("description", "Optional — wait until an element matching the selector contains this text (case-sensitive substring). Ignored for state=detached/hidden.")
            })
            put("state", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("attached"); add("detached"); add("visible"); add("hidden") })
                put("description", "Target state to wait for (default 'attached', the original presence-in-DOM behavior)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawState = input.jsonObject["state"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val out = when {
            selector == null ->
                missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
            rawState != null && rawState !in WAIT_FOR_STATES ->
                missingArgEnvelope("state", "state must be one of [attached, detached, visible, hidden]")
            else -> {
                val state = rawState ?: "attached"
                val containsText = input.jsonObject["contains_text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                // Cap the user-supplied timeout at our per-tool budget so the LLM can't ask for a
                // longer wait and starve every other tool call. The withTimeoutOrNull below would
                // catch this anyway, but capping here gives a clean envelope. The cap tracks the
                // user-configured per-tool timeout (Settings → Browser).
                val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .toLong()
                    .coerceIn(200L, toolTimeoutMs)
                withTimeoutOrNull(toolTimeoutMs) {
                    BrowserControllerHandle.withController {
                        val started = System.currentTimeMillis()
                        val deadline = started + timeoutMs
                        val js = buildWaitForPredicate(selector, state, containsText)
                        var found = false
                        while (System.currentTimeMillis() < deadline) {
                            val raw = webView.evaluateJavascriptAsync(js, 1_500L)
                            if (raw == "true") { found = true; break }
                            delay(200)
                        }
                        buildJsonObject {
                            put("found", found)
                            put("elapsed_ms", System.currentTimeMillis() - started)
                            put("state", state)
                            if (containsText != null) put("contains_text", containsText)
                        }
                    }
                } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR)
            }
        }
        textPart(out)
    },
)

