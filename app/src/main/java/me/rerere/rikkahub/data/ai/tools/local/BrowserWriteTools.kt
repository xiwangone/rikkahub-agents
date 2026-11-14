package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.browser.BrowserAudit
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserDiffHelper
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.common.http.optBoolean
import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.ai.tools.ToolErrors

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

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. Returns the diff between the page before and after the action by default ({added, removed, added_chars, removed_chars, truncated} truncated to 4000 chars total). Pass full:true to skip the diff and get post_click_url only — use when the click navigates to an entirely new page. Waits up to 8 s for readyState=complete.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorWithFullSchema("CSS selector to click") },
    execute = { input ->
        val selector = input.jsonObject.optString("selector")?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                el.scrollIntoView({block:'center', inline:'center'});
                                el.click();
                                return JSON.stringify({clicked:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val raw = webView.evaluateJavascriptAsync(js)
                        val res = parseJsResult(raw)
                        if (res.containsKey("error")) return@withDiff res
                        webView.awaitReadyState(8_000L)
                        BrowserController.appendAction("Click: $selector")
                        BrowserAudit.action(BrowserToolDefaults.CLICK, webView.url, "selector=$selector")
                        buildJsonObject {
                            put("success", true)
                            put("post_click_url", webView.url.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        }
        // Pass 3: post-click screenshot stream in headless mode. Skipped on error envelopes
        // so we don't push a stale screenshot when the click never landed.
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Clicked $selector")
        }
        textPart(out)
    },
)

/**
 * JS payload for browser_type. React (and similar frameworks) install their own `value`
 * setter on the DOM node instance to track the "last known value", so a plain
 * `el.value = ...` write is invisible to them: the framework's state never updates and the
 * rendered input silently keeps its old value even though this tool reports success.
 *
 * Fix: fetch the *native* value setter off the element's prototype
 * (`HTMLInputElement.prototype` / `HTMLTextAreaElement.prototype` — whichever
 * `Object.getPrototypeOf(el)` resolves to) and invoke it directly, bypassing the
 * instance-level override, then dispatch real `input`/`change` events. Falls back to plain
 * `el.value = ...` when the prototype has no setter (custom elements, exotic hosts) — the
 * previous behavior for those cases. Pure string builder so it stays unit-testable.
 */
internal fun buildTypeScript(selector: String, text: String, clear: Boolean): String {
    val sel = jsString(selector)
    val txt = jsString(text)
    val clearFlag = if (clear) "true" else "false"
    return """(function(){
        try {
            var el = document.querySelector($sel);
            if (!el) return JSON.stringify({error:'selector_not_found', selector:$sel});
            el.focus();
            function setNativeValue(node, value) {
                var proto = Object.getPrototypeOf(node);
                var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                if (desc && typeof desc.set === 'function') {
                    desc.set.call(node, value);
                    return true;
                }
                return false;
            }
            if ('value' in el) {
                var next = ($clearFlag ? '' : (el.value || '')) + $txt;
                if (!setNativeValue(el, next)) { el.value = next; }
            } else if (el.isContentEditable) {
                if ($clearFlag) el.textContent = '';
                el.textContent = (el.textContent || '') + $txt;
            }
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return JSON.stringify({typed:true});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
}

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable matching a CSS selector. Focuses, optionally clears, sets the value + dispatches an 'input' event so SPA frameworks observe the change. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the input") })
            put("text", buildJsonObject { put("type","string"); put("description","Text to type") })
            put("clear", buildJsonObject { put("type","boolean"); put("description","Clear the field first (default true)") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","text"))
    },
    execute = { input ->
        val selector = input.jsonObject.optString("selector")?.takeIf { it.isNotBlank() }
        val text = input.jsonObject.optString("text")
        val clear = input.jsonObject.optBoolean("clear") ?: true
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            text == null -> missingArgEnvelope("text", "text is required (use empty string to clear)")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        // Routes the write through the element prototype's native value
                        // setter so React-style instance trackers observe it (see
                        // buildTypeScript), then fires both 'input' and 'change'.
                        val js = buildTypeScript(selector, text, clear)
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Typed into $selector")
                        // Length only — the typed text itself may be a credential.
                        BrowserAudit.action(BrowserToolDefaults.TYPE, webView.url, "selector=$selector len=${text.orEmpty().length}")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.TYPE)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Typed into $selector")
        }
        textPart(out)
    },
)

fun browserScrollTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL,
    description = "Scroll the page in a direction (up/down/top/bottom). amount is in pixels (default 600, ignored for top/bottom). {success, scroll_y}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("direction", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("up"); add("down"); add("top"); add("bottom") })
            })
            put("amount", buildJsonObject { put("type","integer"); put("description","Scroll distance in px (default 600)") })
        }, required = listOf("direction"))
    },
    execute = { input ->
        val direction = input.jsonObject.optString("direction")
        val amount = input.jsonObject.optInt("amount") ?: 600
        val out = if (direction == null || direction !in setOf("up", "down", "top", "bottom")) {
            missingArgEnvelope("direction", "direction must be one of [up, down, top, bottom]")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val js = """(function(){
                        try {
                            switch (${jsString(direction)}) {
                                case 'up': window.scrollBy(0, -$amount); break;
                                case 'down': window.scrollBy(0, $amount); break;
                                case 'top': window.scrollTo(0, 0); break;
                                case 'bottom': window.scrollTo(0, document.body.scrollHeight); break;
                            }
                            return JSON.stringify({scroll_y: Math.round(window.scrollY)});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Scroll $direction")
                    BrowserAudit.action(BrowserToolDefaults.SCROLL, webView.url, "direction=$direction")
                    buildJsonObject {
                        put("success", true)
                        put("scroll_y", res.optInt("scroll_y") ?: 0)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SCROLL)
        }
        // Pass 3: browser_scroll is in WRITE_TOOLS and the TELEGRAM_HEADLESS_CUE describes
        // "screenshots stream after each state-changing action." Stream the post-scroll view so
        // the Telegram user can see the new viewport position (scroll is purely viewport movement
        // — the diff helper won't capture it since body.innerText doesn't change).
        if (out["success"]?.toString() == "true") {
            val scrollY = out.optInt("scroll_y")
            BrowserController.streamScreenshotIfHeadless(
                if (scrollY != null) "Scrolled to y=$scrollY" else "Scrolled"
            )
        }
        textPart(out)
    },
)

fun browserSubmitTool(): Tool = Tool(
    name = BrowserToolDefaults.SUBMIT,
    description = "Submit a form. If the selector is a <button type=submit> click it; otherwise locates the enclosing <form> and calls .submit(). Awaits the post-navigation readyState. Returns the diff between the page before and after by default; pass full:true to skip the diff (recommended when submission navigates to a brand-new page). {success, post_submit_url, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorWithFullSchema("CSS selector of a submit button or any element inside the target form") },
    execute = { input ->
        val selector = input.jsonObject.optString("selector")?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                if (el.tagName === 'BUTTON' && (el.type === 'submit' || el.type === '')) {
                                    el.click();
                                    return JSON.stringify({submitted:true, via:'button_click'});
                                }
                                var form = el.closest('form');
                                if (!form) return JSON.stringify({error:'no_enclosing_form'});
                                if (typeof form.requestSubmit === 'function') form.requestSubmit();
                                else form.submit();
                                return JSON.stringify({submitted:true, via:'form_submit'});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        webView.awaitReadyState(8_000L)
                        BrowserController.appendAction("Submit: $selector")
                        BrowserAudit.action(BrowserToolDefaults.SUBMIT, webView.url, "selector=$selector")
                        buildJsonObject {
                            put("success", true)
                            put("post_submit_url", webView.url.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SUBMIT)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Submitted $selector")
        }
        textPart(out)
    },
)

fun browserSelectTool(): Tool = Tool(
    name = BrowserToolDefaults.SELECT,
    description = "Set a <select> element's value. Dispatches 'change' so framework listeners fire. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the <select>") })
            put("value", buildJsonObject { put("type","string"); put("description","The option value to set") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","value"))
    },
    execute = { input ->
        val selector = input.jsonObject.optString("selector")?.takeIf { it.isNotBlank() }
        val value = input.jsonObject.optString("value")
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            value == null -> missingArgEnvelope("value", "value is required")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                if (el.tagName !== 'SELECT') return JSON.stringify({error:'not_a_select'});
                                el.value = ${jsString(value)};
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return JSON.stringify({selected:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Select: $selector=$value")
                        // Option value length only (the value itself can encode user data).
                        BrowserAudit.action(BrowserToolDefaults.SELECT, webView.url, "selector=$selector value_len=${value.orEmpty().length}")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SELECT)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Selected $value in $selector")
        }
        textPart(out)
    },
)

fun browserPressKeyTool(): Tool = Tool(
    name = BrowserToolDefaults.PRESS_KEY,
    description = "Synthesize keydown + keyup events on the active element. Use KeyboardEvent.key values like 'Enter', 'Escape', 'ArrowDown', 'Tab'. Returns the diff between the page before and after by default; pass full:true to skip the diff (recommended when Enter triggers a navigation). {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("key", buildJsonObject {
                put("type","string")
                put("description","KeyboardEvent.key value (e.g. 'Enter', 'Escape', 'ArrowDown')")
            })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("key"))
    },
    execute = { input ->
        // Cap at 32 chars — KeyboardEvent.key values like "ArrowDown" are short. A multi-KB
        // string here suggests model misuse; clamp before splicing into the JS payload.
        val key = input.jsonObject.optString("key")?.takeIf { it.isNotBlank() }?.take(32)
        val full = parseFullArg(input)
        val out = if (key == null) {
            missingArgEnvelope("key", "key is required (e.g. 'Enter', 'Escape')")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.activeElement || document.body;
                                var down = new KeyboardEvent('keydown', {key:${jsString(key)}, bubbles:true, cancelable:true});
                                var up = new KeyboardEvent('keyup', {key:${jsString(key)}, bubbles:true, cancelable:true});
                                el.dispatchEvent(down);
                                el.dispatchEvent(up);
                                return JSON.stringify({pressed:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Press key: $key")
                        BrowserAudit.action(BrowserToolDefaults.PRESS_KEY, webView.url, "key=$key")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.PRESS_KEY)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Pressed $key")
        }
        textPart(out)
    },
)

fun browserEvalJsTool(): Tool = Tool(
    name = BrowserToolDefaults.EVAL_JS,
    description = "Run arbitrary JavaScript in the page and return its last expression. HARDLINE-checked: shell-shaped strings, document.cookie writes, eval/Function constructors, and string-form setTimeout are all blocked at the tool dispatcher BEFORE the JS executes. Always asks for approval; never eligible for 'Always Allow'.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("code", buildJsonObject {
                put("type","string")
                put("description","JavaScript to evaluate. The string returned by the WebView is the value of the last expression, JSON-encoded.")
            })
        }, required = listOf("code"))
    },
    execute = { input ->
        // HARDLINE has already run via GenerationLoop before we get here. The execute
        // body just dispatches the JS and forwards whatever the WebView returns. Any
        // pattern HARDLINE missed is a bug to fix in HardlineCommandGuard, not here.
        val code = input.jsonObject.optString("code")?.takeIf { it.isNotBlank() }
        val out = if (code == null) {
            missingArgEnvelope("code", "code is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val raw = webView.evaluateJavascriptAsync(code, toolTimeoutMs - 1_000L)
                    BrowserController.appendAction("Run JS")
                    // Script length only — the source can embed tokens or page data.
                    BrowserAudit.action(BrowserToolDefaults.EVAL_JS, webView.url, "code_len=${code.orEmpty().length}")
                    // Clamp the raw result before it enters the envelope. evaluateJavascript
                    // returns whatever the page's last expression serialised to — a model that
                    // evals e.g. `document.body.outerHTML` can dump megabytes into the turn.
                    // Reuse the 64 KB cap the read tools (runGetText / runReadHelper) apply.
                    val (clipped, truncated) = clipText(raw ?: "null", EVAL_JS_MAX_RESULT_CHARS)
                    buildJsonObject {
                        put("result", clipped)
                        if (truncated) put("truncated", true)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.EVAL_JS)
        }
        // Pass 3: browser_eval_js is in WRITE_TOOLS — it CAN mutate page state (e.g.
        // click via JS, modify the DOM, submit a form programmatically). Stream a screenshot
        // so the Telegram user sees the page after the script ran, consistent with every
        // other write tool. Only stream on success — error envelopes (timeout, missing_code,
        // browser_not_open) all carry an "error" key so we use that as the gate.
        if (!out.containsKey("error")) {
            BrowserController.streamScreenshotIfHeadless("Ran JS")
        }
        textPart(out)
    },
)

/**
 * Token-cost optimisation pass — composite click+read tool. Cuts the dominant
 * "click → wait → get_text" three-call sequence to one round trip. The trade is
 * a slightly heavier per-call envelope (URL + title + text/diff) which is
 * still cheaper than three separate calls' worth of model thinking-tokens.
 *
 * extract_mode: "diff" (default), "auto", "readability", "raw".
 *   - "diff" → snapshot text before/after, return {diff: ...}.
 *   - "auto"/"readability"/"raw" → snapshot URL+title, click, await readyState,
 *     extract text per get_text rules, return {text, page_title}.
 *
 * max_chars caps either the diff side OR the extracted text. The cap protects
 * against an LLM asking for a megabyte of text on a long-form page.
 */
fun browserClickAndReadTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK_AND_READ,
    description = "One-shot click + read in a single round trip. Click an element, await readyState, then return either the diff (default extract_mode:'diff') or the extracted text (auto/readability/raw — same semantics as browser_get_text). Use this instead of browser_click + browser_get_text when you want to minimise tokens. {success, post_click_url, page_title, [diff], [text]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type","string")
                put("description","CSS selector to click")
            })
            put("extract_mode", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("diff"); add("auto"); add("readability"); add("raw") })
                put("description","diff (default) returns a before/after diff; auto/readability/raw return the extracted page text")
            })
            put("max_chars", buildJsonObject {
                put("type","integer")
                put("description","Caps the returned text length (default 4000)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject.optString("selector")?.takeIf { it.isNotBlank() }
        val mode = input.jsonObject.optString("extract_mode")?.lowercase()
            ?.takeIf { it in setOf("diff", "auto", "readability", "raw") } ?: "diff"
        val maxChars = (input.jsonObject.optInt("max_chars") ?: 4000)
            .coerceIn(100, 64 * 1024)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val before = if (mode == "diff") captureBodyText() else ""
                    val titleBefore = withContext(Dispatchers.Main) { webView.title.orEmpty() }
                    val clickJs = """(function(){
                        try {
                            var el = document.querySelector(${jsString(selector)});
                            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                            el.scrollIntoView({block:'center', inline:'center'});
                            el.click();
                            return JSON.stringify({clicked:true});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val clickRes = parseJsResult(webView.evaluateJavascriptAsync(clickJs))
                    if (clickRes.containsKey("error")) return@withController clickRes
                    webView.awaitReadyState(8_000L)
                    BrowserController.appendAction("Click+read: $selector")
                    BrowserAudit.action(BrowserToolDefaults.CLICK_AND_READ, webView.url, "selector=$selector")
                    val postUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
                    val postTitle = withContext(Dispatchers.Main) { webView.title.orEmpty() }
                    @Suppress("UNUSED_VARIABLE")
                    val unused = titleBefore // documents that we considered showing diff of titles
                    when (mode) {
                        "diff" -> {
                            val after = captureBodyText()
                            buildJsonObject {
                                put("success", true)
                                put("post_click_url", postUrl)
                                put("page_title", postTitle)
                                put("diff", BrowserDiffHelper.computeDiff(before, after))
                            }
                        }
                        else -> {
                            val text = when (mode) {
                                "readability" -> webView.runReadability()
                                "auto" -> webView.runReadability()?.takeIf { it.length >= READABILITY_MIN_CHARS }
                                else -> null
                            }
                            val (resolved, extractMode) = if (!text.isNullOrEmpty()) {
                                text to "readability"
                            } else if (mode == "readability") {
                                // Forced mode + null result → surface the error envelope.
                                return@withController ToolErrors.envelopeFor(error = "readability_failed", message = "readability_failed", extra = ToolErrors.extraOf("success" to false, "post_click_url" to postUrl, "page_title" to postTitle))
                            } else {
                                // Fall back to selector-based body innerText.
                                val rawJs = """(function(){
                                    try {
                                        var t = (document.body.innerText || document.body.textContent || '').replace(/\s+/g,' ').trim();
                                        return JSON.stringify({text:t});
                                    } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                                })()"""
                                val rawRes = parseJsResult(webView.evaluateJavascriptAsync(rawJs))
                                val rawText = rawRes.optString("text").orEmpty()
                                rawText to (if (mode == "auto") "raw_fallback" else "raw")
                            }
                            val (clipped, truncated) = clipText(resolved, maxChars)
                            buildJsonObject {
                                put("success", true)
                                put("post_click_url", postUrl)
                                put("page_title", postTitle)
                                put("text", clipped)
                                put("truncated", truncated)
                                put("extract_mode", extractMode)
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK_AND_READ)
        }
        // Pass 3 parity: stream a screenshot in headless mode on success, same as
        // plain browser_click. The diff envelope still flows to the LLM via textPart.
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Clicked $selector (and read)")
        }
        textPart(out)
    },
)

