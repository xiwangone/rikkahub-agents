package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserDiffHelper
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.common.http.optBoolean
import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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


internal const val MAX_SCREENSHOT_HEIGHT_PX = 8192
internal const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"

/**
 * Hard cap on the string browser_eval_js puts in its result envelope. Matches the 64 KB
 * ceiling the read tools (runGetText / runReadHelper) clamp page text/HTML to, so an eval of
 * something like `document.body.outerHTML` can't bloat the turn with megabytes of payload.
 */
internal const val EVAL_JS_MAX_RESULT_CHARS = 64 * 1024

/**
 * Per-tool timeout budget every browser tool wraps its dispatch in. User-configurable via
 * Settings → Browser (GitHub issue #4) — resolved fresh on each tool call from
 * [BrowserController.perToolTimeoutMs], which [me.rerere.rikkahub.browser.BrowserPreferences]
 * keeps in sync with the persisted value.
 */
internal val toolTimeoutMs: Long get() = BrowserController.perToolTimeoutMs

/**
 * Pass 3: appended to every browser tool's description so the LLM knows that in headless
 * (Telegram / cron / sub-agent) mode, the act of taking a state-changing action will
 * automatically push a screenshot+caption to the calling chat. Without this cue, the
 * model would otherwise call `browser_screenshot` after every action — doubling vision
 * tokens and round-trips for no gain.
 */
internal const val TELEGRAM_HEADLESS_CUE =
    " In Telegram / headless mode, screenshots stream to the calling chat after each state-changing action — call browser_done when you're confident the task is complete."

/**
 * Decide whether the current invocation should run in headless mode. Resolution order:
 *  1. [ToolInvocationContext.callerConversationId] is the canonical source. Cron jobs,
 *     sub-agents, Telegram bot, external automation all set it.
 *  2. We try to parse it as a [Uuid]. The [HeadlessConversations] registry keys on Uuid
 *     (cron / sub-agent paths use the conversation's primary key).
 *  3. If parsing fails (some external automation paths use string ids), we still treat
 *     the [ToolInvocationContext.isHeadless] flag as authoritative — that flag is set by
 *     every flow that wants headless behaviour.
 *
 * Modified: default to headless so the user stays on the chat page while the AI browses
 * in the background. Tool results (get_text, screenshot) stream into the chat log.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun isHeadlessInvocation(ctx: ToolInvocationContext?): Boolean {
    if (ctx == null) return true // headless default
    if (ctx.isHeadless) return true
    val convId = ctx.callerConversationId ?: return false
    val asUuid = runCatching { Uuid.parse(convId) }.getOrNull() ?: return false
    return HeadlessConversations.isHeadless(asUuid)
}

// ---- Common envelope helpers --------------------------------------------------------------

internal fun timeoutEnvelope(toolName: String): JsonObject = ToolErrors.envelopeFor(error = "tool_timeout", message = "tool_timeout", hint = "The browser tool exceeded its $toolTimeoutMs-ms budget. Retry, or simplify the selector.", extra = ToolErrors.extraOf("tool" to toolName))

internal fun missingArgEnvelope(name: String, detail: String): JsonObject = ToolErrors.envelopeFor(error = "invalid_argument", message = "missing_$name", hint = "Check the parameter values and retry with corrected arguments.", extra = ToolErrors.extraOf("detail" to detail))

internal fun textPart(obj: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(obj.toString()))

/**
 * JSON-encode a Kotlin string so it can be embedded inside an evaluateJavascript payload
 * as a JS string literal — handles backslashes, quotes, control characters, and Unicode
 * escapes uniformly. Doing this by hand is the canonical XSS-via-LLM-tool footgun; we
 * route through kotlinx.serialization's JsonPrimitive which formats per the JSON spec
 * (also valid JS string syntax).
 */
internal fun jsString(s: String): String = JsonPrimitive(s).toString()

// ---- Loop control --------------------------------------------------------------------------

fun browserDoneTool(invocationContext: ToolInvocationContext? = null): Tool = Tool(
    name = BrowserToolDefaults.DONE,
    description = "Signal that the AI has finished its current browser task. Clears the per-task 5-minute timer so the next browser_open starts fresh. The browser session ITSELF stays alive — subsequent turns can navigate, click, scroll without re-opening; only `/new` (Telegram) or the in-app reset closes it. result_url is optional; pass the page URL the user should look at if any. {success}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("summary", buildJsonObject {
                put("type","string")
                put("description","One-sentence summary of what was accomplished")
            })
            put("result_url", buildJsonObject {
                put("type","string")
                put("description","Optional URL the user should look at")
            })
        }, required = listOf("summary"))
    },
    execute = { input ->
        val summary = input.jsonObject.optString("summary")?.takeIf { it.isNotBlank() }
        val out = if (summary == null) {
            missingArgEnvelope("summary", "summary is required")
        } else {
            BrowserController.appendAction("Done: $summary")
            BrowserController.clearTaskWindow()
            // Pass 3 design originally released the headless WebView here. Live-test feedback
            // users want the session to persist across LLM turns so a follow-up
            // "click the next link" doesn't have to re-open from scratch — that broke the
            // page state, cookies-in-flight, and the read screenshots stayed white because
            // we paid the page-load tax twice. browser_done now ONLY clears the per-task
            // 5-minute timer; the session stays alive until `/new` (TelegramBotService.handleResetCommand)
            // or the calling FGS dies. Foreground mode behaves identically — Activity keeps
            // running as before.
            buildJsonObject { put("success", true) }
        }
        textPart(out)
    },
)

// ---- Internal helpers ---------------------------------------------------------------------

/**
 * selector + optional full flag — used by the state-changing tools (click / submit)
 * that gain the diff-after-action toggle. selector remains required; `full` defaults
 * to false (i.e. return diff). The naming matches the spec verbatim.
 */
internal fun selectorWithFullSchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
        put("full", buildJsonObject {
            put("type", "boolean")
            put("description", "If true, return the action envelope without the page-text diff (default false)")
        })
    },
    required = listOf("selector"),
)

internal fun selectorAndMaxCharsSchema(defaultMax: Int, required: Boolean): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector (default 'body')")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
    },
    required = if (required) listOf("selector") else null,
)

/**
 * Schema for browser_get_text. Adds the [extract_mode] enum (auto / readability /
 * raw) to the standard selector + max_chars surface. Defaults are documented inline
 * so the LLM doesn't need to hunt through the spec to know the fallback behaviour.
 */
internal fun getTextSchema(defaultMax: Int): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "Optional CSS selector — when set, overrides Readability and reads the selector's innerText directly")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
        put("extract_mode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("auto"); add("readability"); add("raw") })
            put("description", "auto (default) tries Readability then falls back; readability forces it; raw uses selector-based innerText")
        })
    },
)


/**
 * Snapshot the page's `document.body.innerText` for the diff-after-action path.
 * Whitespace is collapsed so that incidental layout reflows (e.g. an extra newline
 * inserted by a CSS animation that just landed) don't show up as "added" lines in
 * the diff. Returns the empty string on any JS failure — the diff helper will then
 * treat an empty before-snapshot as "everything is new", which is the conservative
 * call when we can't tell what was there.
 */
internal suspend fun BrowserControllerHandle.WithControllerScope.captureBodyText(): String {
    val raw = webView.evaluateJavascriptAsync(
        "(function(){try{return JSON.stringify(document.body.innerText||'');}catch(e){return JSON.stringify('');}})()",
        4_000L,
    ) ?: return ""
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        (Json.parseToJsonElement(inner) as? JsonPrimitive)?.contentOrNull.orEmpty()
    }.getOrElse { "" }
}

/**
 * Token-cost optimisation pass — wrap a state-changing tool's action with a
 * before/after text snapshot so the LLM gets a diff envelope instead of a full
 * page re-read. Returns:
 *  - The action's own envelope unchanged when [full] is true (legacy path).
 *  - The action's envelope merged with `{ "diff": {...} }` when [full] is false.
 *  - An error envelope if the action returned one — diff is skipped on error so we
 *    don't push a stale snapshot when nothing changed because the action failed.
 *
 * The action is responsible for awaiting readyState if it triggers navigation; we
 * snapshot AFTER the action returns to ensure we read the post-action page.
 */
internal suspend fun BrowserControllerHandle.WithControllerScope.withDiff(
    full: Boolean,
    action: suspend BrowserControllerHandle.WithControllerScope.() -> JsonObject,
): JsonObject {
    if (full) return action()
    val before = captureBodyText()
    val result = action()
    if (result.containsKey("error")) return result
    val after = captureBodyText()
    return buildJsonObject {
        result.forEach { (k, v) -> put(k, v) }
        put("diff", BrowserDiffHelper.computeDiff(before, after))
    }
}

/**
 * Read the optional `full` arg uniformly across the state-changing tools. Default
 * false → diff path; true → preserve legacy envelope (post_*_url only). The arg
 * name matches the spec verbatim so the LLM has one concept to learn.
 */
internal fun parseFullArg(input: kotlinx.serialization.json.JsonElement): Boolean =
    input.jsonObject.optBoolean("full") == true

/**
 * Parse the raw string evaluateJavascript returned. Our JS helpers always return a
 * JSON.stringify(...) result, so the raw value is itself a JSON-encoded string (i.e.
 * a literal "..." with internal escapes). Unwrap once to get the real JSON object.
 *
 * Falls back to {error:'js_no_result'} on null and {error:'js_parse_failed'} on a
 * value that can't be parsed — both surface to the LLM cleanly without throwing.
 */
internal fun parseJsResult(raw: String?): JsonObject {
    if (raw == null) return ToolErrors.envelopeFor(error = "js_no_result", message = "js_no_result")
    return runCatching {
        // evaluateJavascript wraps a JS string return value in JSON-quoted form, so
        // raw is "\"{...}\"" — parse the outer quoted string into a Kotlin string,
        // then parse that string as JSON.
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonObject
    }.getOrElse { ToolErrors.envelopeFor(error = "js_parse_failed", message = "js_parse_failed", extra = ToolErrors.extraOf("raw" to raw)) }
}

/**
 * Shared body for browser_get_text / browser_get_dom: same selector+max_chars schema,
 * different JS body. The [jsBuilder] returns the JS payload; the helper handles the
 * round-trip + envelope + timeout.
 */
internal suspend fun runReadHelper(
    input: kotlinx.serialization.json.JsonElement,
    toolName: String,
    defaultMax: Int,
    jsBuilder: (selector: String, maxChars: Int) -> String,
): JsonObject {
    val selector = (input.jsonObject.optString("selector")
        ?.takeIf { it.isNotBlank() }) ?: "body"
    // Clamp max_chars at 100 (anything smaller is unhelpful) and 64 KB (so a runaway
    // model can't tell us to grab a megabyte of HTML).
    val maxChars = (input.jsonObject.optInt("max_chars") ?: defaultMax)
        .coerceIn(100, 64 * 1024)
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars)))
        }
    } ?: timeoutEnvelope(toolName)
}

/**
 * Token-cost optimisation pass — browser_get_text body. Resolves [extract_mode] +
 * [selector] precedence:
 *  - Explicit `selector` arg → skip Readability and use selector-based innerText
 *    (the user knows what they want; we trust the model).
 *  - `extract_mode = "raw"` → selector-based innerText against `body` (current
 *    pre-pass behaviour).
 *  - `extract_mode = "readability"` → force Readability; surface
 *    `{error:"readability_failed"}` if it returns null.
 *  - `extract_mode = "auto"` (default) → try Readability; fall back to
 *    selector-based innerText if it returns null OR less than 200 chars (a
 *    too-short article is often a junk extraction — better to fall back).
 */
internal const val READABILITY_MIN_CHARS = 200

internal suspend fun runGetText(input: kotlinx.serialization.json.JsonElement): JsonObject {
    val explicitSelector = input.jsonObject.optString("selector")
        ?.takeIf { it.isNotBlank() }
    val maxChars = (input.jsonObject.optInt("max_chars") ?: 8000)
        .coerceIn(100, 64 * 1024)
    val mode = input.jsonObject.optString("extract_mode")?.lowercase()
        ?.takeIf { it in setOf("auto", "readability", "raw") } ?: "auto"

    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            // Selector arg trumps everything — the model is being explicit, honour it.
            if (explicitSelector != null) {
                return@withController runRawText(explicitSelector, maxChars, mode = "raw_selector")
            }
            when (mode) {
                "raw" -> runRawText("body", maxChars, mode = "raw")
                "readability" -> {
                    val text = webView.runReadability()
                    if (text.isNullOrEmpty()) {
                        ToolErrors.envelopeFor(error = "readability_failed", message = "readability_failed", hint = "Try extract_mode:'auto' or pass a specific selector")
                    } else {
                        buildJsonObject {
                            val (clipped, truncated) = clipText(text, maxChars)
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    }
                }
                else -> {
                    // auto: Readability first, then selector fallback
                    val text = webView.runReadability()
                    if (!text.isNullOrEmpty() && text.length >= READABILITY_MIN_CHARS) {
                        val (clipped, truncated) = clipText(text, maxChars)
                        buildJsonObject {
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    } else {
                        runRawText("body", maxChars, mode = "raw_fallback")
                    }
                }
            }
        }
    } ?: timeoutEnvelope(BrowserToolDefaults.GET_TEXT)
}

internal suspend fun BrowserControllerHandle.WithControllerScope.runRawText(
    selector: String,
    maxChars: Int,
    mode: String,
): JsonObject {
    val js = """(function(){
        try {
            var el = document.querySelector(${jsString(selector)});
            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
            var t = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
            var truncated = false;
            if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
            return JSON.stringify({text:t, truncated:truncated});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
    return if (res.containsKey("error")) res else buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("extract_mode", mode)
    }
}

internal fun clipText(text: String, maxChars: Int): Pair<String, Boolean> =
    if (text.length <= maxChars) text to false
    else text.substring(0, maxChars) to true

/**
 * How [runHistoryNav] should carry out a back/forward request, decided from state read off
 * the live WebView. Extracted as a pure function so the decision (issue #59: Chromium's
 * history-manipulation intervention marks gesture-less navigations skippable, making
 * `canGoBack()`/`canGoForward()` false even though a real entry exists) unit-tests without
 * Robolectric.
 */
internal enum class HistoryNavMethod { NATIVE, OFFSET, NONE }

/**
 * NATIVE when the native canGoBack()/canGoForward() check already agrees. Otherwise OFFSET
 * when `copyBackForwardList()`'s index proves a real entry exists in that direction (back:
 * not already at the oldest entry; forward: not already at the newest). NONE when there's
 * genuinely nowhere to go.
 */
internal fun resolveHistoryNav(
    forward: Boolean,
    canNative: Boolean,
    currentIndex: Int,
    size: Int,
): HistoryNavMethod = when {
    canNative -> HistoryNavMethod.NATIVE
    forward && currentIndex < size - 1 -> HistoryNavMethod.OFFSET
    !forward && currentIndex > 0 -> HistoryNavMethod.OFFSET
    else -> HistoryNavMethod.NONE
}

internal suspend fun runHistoryNav(toolName: String, forward: Boolean): JsonObject {
    val out = withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            // Returns (navigated, usedHistoryLoadFallback).
            val (ok, usedFallback) = withContext(Dispatchers.Main) {
                val list = webView.copyBackForwardList()
                val canNative = if (forward) webView.canGoForward() else webView.canGoBack()
                when (resolveHistoryNav(forward, canNative, list.currentIndex, list.size)) {
                    HistoryNavMethod.NATIVE -> {
                        if (forward) webView.goForward() else webView.goBack()
                        true to false
                    }
                    HistoryNavMethod.OFFSET -> {
                        // goBackOrForward(offset) honors the skippable flag on some WebView
                        // builds too and silently no-ops, so load the target history item's
                        // URL directly — a fresh load can't be suppressed, at the cost of
                        // appending a new entry rather than moving the index.
                        val target = list.getItemAtIndex(list.currentIndex + (if (forward) 1 else -1))
                        me.rerere.rikkahub.data.log.AppLog.i(
                            "BrowserNav",
                            "runHistoryNav: native nav refused, loading history item directly: ${target.url.take(120)}",
                        )
                        webView.loadUrl(target.url)
                        true to true
                    }
                    HistoryNavMethod.NONE -> false to false
                }
            }
            if (ok) webView.awaitReadyState(8_000L)
            BrowserController.appendAction(if (forward) "Forward" else "Back")
            buildJsonObject {
                put("success", ok)
                put("current_url", webView.url.orEmpty())
                if (ok && usedFallback) put("method", "history_load_fallback")
            }
        }
    } ?: timeoutEnvelope(toolName)
    // Pass 3: stream the post-nav screenshot to the calling chat in headless mode. Only
    // fires when the controller is actually in Mode.Headless; foreground-mode is a no-op.
    // Don't stream when we already returned an error envelope.
    if (out["success"]?.toString() == "true") {
        BrowserController.streamScreenshotIfHeadless(if (forward) "Forward" else "Back")
    }
    return out
}

/**
 * Factory dispatch for a given browser tool name. Used by
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] which iterates the per-tool
 * preference map and only constructs the ones currently enabled.
 *
 * Pass 3: [invocationContext] flows through to the two tool factories that consult it:
 *  - [browserOpenTool]: picks foreground-Activity vs headless-WebView mode by reading
 *    [HeadlessConversations.isHeadless] on the caller's conversation id.
 *  - [browserDoneTool]: in headless mode, also releases the per-conv WebView session.
 *
 * Other tool factories don't need the context — the auto-stream side of headless mode is
 * handled inside [BrowserController.streamScreenshotIfHeadless] which reads the live mode
 * directly without per-tool plumbing.
 */
fun createBrowserTool(
    toolName: String,
    context: Context,
    invocationContext: ToolInvocationContext? = null,
): Tool? = when (toolName) {
    BrowserToolDefaults.OPEN -> browserOpenTool(context, invocationContext)
    BrowserToolDefaults.CURRENT_URL -> browserCurrentUrlTool()
    BrowserToolDefaults.SCREENSHOT -> browserScreenshotTool(context)
    BrowserToolDefaults.GET_TEXT -> browserGetTextTool()
    BrowserToolDefaults.GET_DOM -> browserGetDomTool()
    BrowserToolDefaults.GET_LINKS -> browserGetLinksTool()
    BrowserToolDefaults.BACK -> browserBackTool()
    BrowserToolDefaults.FORWARD -> browserForwardTool()
    BrowserToolDefaults.WAIT_FOR -> browserWaitForTool()
    BrowserToolDefaults.CLICK -> browserClickTool()
    BrowserToolDefaults.TYPE -> browserTypeTool()
    BrowserToolDefaults.SCROLL -> browserScrollTool()
    BrowserToolDefaults.SUBMIT -> browserSubmitTool()
    BrowserToolDefaults.SELECT -> browserSelectTool()
    BrowserToolDefaults.PRESS_KEY -> browserPressKeyTool()
    BrowserToolDefaults.EVAL_JS -> browserEvalJsTool()
    BrowserToolDefaults.CLICK_AND_READ -> browserClickAndReadTool()
    BrowserToolDefaults.DONE -> browserDoneTool(invocationContext)
    else -> null
}
