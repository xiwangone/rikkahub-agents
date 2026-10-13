package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private const val TAG = "CompoundUiTools"

private const val CLICK_AND_READ_DEFAULT_MAX_NODES = 500
private const val WAIT_FOR_DEFAULT_TIMEOUT_MS = 5_000L
private const val WAIT_FOR_MIN_TIMEOUT_MS = 1_000L
private const val WAIT_FOR_MAX_TIMEOUT_MS = 30_000L
private const val WAIT_FOR_DEFAULT_INTERVAL_MS = 400L
private const val WAIT_FOR_MIN_INTERVAL_MS = 100L
private const val WAIT_FOR_MAX_INTERVAL_MS = 2_000L

/**
 * click_and_read — the compound action that collapses the classic
 * read_window_tree → click_node → read_window_tree round trip into ONE tool call.
 *
 * Same selector semantics as click_node (by/value/nth, clickable-ancestor resolution,
 * package guard). After the click it waits for the UI to settle (awaitQuiet, same
 * 300ms/2s envelope every action uses), then re-reads the window tree with the standard
 * defaultFilter and returns {success, clicked, nodes, screen_state} — the tree hash
 * before/after gives screen_changed so the model can tell "nothing happened" from
 * "the page moved" without a follow-up read.
 */
fun clickAndReadTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "click_and_read",
    description = """
        Click an accessibility node by selector AND return the post-click window tree in a
        single call. Performs the click, waits for the UI to settle (300ms quiet / 2s cap),
        then returns {success, clicked, nodes, truncated, total_seen, package, window_title,
        screen_state}. Same selector semantics as click_node (by/value/nth; if the matched
        node is not clickable, a clickable ancestor is clicked). Use instead of the
        read_window_tree -> click_node -> read_window_tree round trip.
    """.trimIndent().replace("\n", " "),
    parameters = { clickAndReadParameters() },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull ?: 0
        val maxNodes = (input.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull ?: CLICK_AND_READ_DEFAULT_MAX_NODES)
            .coerceIn(1, MAX_NODES_HARD_CEILING)
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "missing_param", message = "by must be one of [text, content_description, view_id_resource_name] and value is required").toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "invalid_argument", message = "nth must be >= 0", hint = "Check the parameter values and retry with corrected arguments.").toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            clickAndReadOnce(svc, by, value, pkgFilter, nth, maxNodes)
        }
        streamer.streamIfHeadless(invocationContext, "ClickAndRead $by=\"${value.take(30)}\"")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun clickAndReadParameters(): InputSchema.Obj = InputSchema.Obj(
    properties = buildJsonObject {
        put("by", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
        })
        put("value", buildJsonObject { put("type", "string") })
        put("package_name", buildJsonObject {
            put("type", "string")
            put("description", "Optional foreground package guard")
        })
        put("nth", buildJsonObject {
            put("type", "integer")
            put("description", "Zero-based index when multiple nodes match (default 0)")
        })
        put("max_nodes", buildJsonObject {
            put("type", "integer")
            put("description", "Cap on returned nodes (default 500, max 2000)")
        })
    },
    required = listOf("by", "value"),
)

/** The withService body of click_and_read: guard → match → click → settle → read. */
private suspend fun clickAndReadOnce(
    svc: RikkaAccessibilityService,
    by: String,
    value: String,
    pkgFilter: String?,
    nth: Int,
    maxNodes: Int,
): JsonObject {
    val root = svc.rootInActiveWindow
        ?: return ToolErrors.envelopeFor(error = "no_active_window", message = "no_active_window")
    val pkg = root.packageName?.toString().orEmpty()
    if (pkgFilter != null && pkgFilter != pkg) {
        return ToolErrors.envelopeFor(error = "wrong_foreground_app", message = "wrong_foreground_app", extra = ToolErrors.extraOf("current" to pkg))
    }
    val matches = findMatches(root, by, value)
    if (matches.isEmpty()) {
        return ToolErrors.envelopeFor(error = "no_match", message = "no_match")
    }
    if (nth >= matches.size) {
        return ToolErrors.envelopeFor(error = "nth_out_of_range", message = "nth_out_of_range", extra = ToolErrors.extraOf("available" to matches.size))
    }
    val target = matches[nth]
    val clickable = svc.resolveClickable(target)
        ?: return ToolErrors.envelopeFor(error = "no_clickable_ancestor", message = "no_clickable_ancestor")
    val before = withA11yDegradation("click_and_read", "before-hash") { treeHash(svc) }
    val actionStart = SystemClock.uptimeMillis()
    val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    svc.appendLog(
        ActionLogEntry(
            type = "click_and_read",
            paramsSummary = "$by=\"${value.take(40)}\" nth=$nth -> ${if (ok) "ok" else "fail"}",
            success = ok,
            timestampMs = System.currentTimeMillis(),
        )
    )
    val quiet = awaitQuiet(
        quietMs = SETTLE_QUIET_MS,
        timeoutMs = SETTLE_TIMEOUT_MS,
        now = { SystemClock.uptimeMillis() },
        lastEvent = { svc.lastWindowEventUptime },
        floor = actionStart,
    )
    return clickAndReadResult(svc, target, clickable, ok, before, quiet, maxNodes)
}

/** Builds the success payload: post-click tree re-read + screen-state envelope. */
private fun clickAndReadResult(
    svc: RikkaAccessibilityService,
    target: AccessibilityNodeInfo,
    clickable: AccessibilityNodeInfo,
    ok: Boolean,
    before: Long?,
    quiet: Boolean,
    maxNodes: Int,
): JsonObject {
    val after = withA11yDegradation("click_and_read", "after-hash") { treeHash(svc) }
    // Re-fetch the root: the click may have navigated, and the pre-click root can be stale.
    val rootAfter = withA11yDegradation("click_and_read", "post-click-root") { svc.rootInActiveWindow }
    val nodes = mutableListOf<JsonObject>()
    var seen = 0
    var truncated = false
    if (rootAfter != null) {
        val (_, totalSeen, wasTruncated) = svc.traverseTree(
            root = rootAfter,
            filter = ::defaultFilter,
            cap = maxNodes,
            emit = { n, _, idx -> nodes.add(nodeToJson(n, rootAfter.windowId, idx)) },
        )
        seen = totalSeen
        truncated = wasTruncated
    }
    return buildJsonObject {
        put("success", ok)
        put("clicked", clickedJson(clickable, target))
        put("nodes", buildJsonArray { nodes.forEach { add(it) } })
        put("truncated", truncated)
        put("total_seen", seen)
        put("package", rootAfter?.packageName?.toString().orEmpty())
        rootAfter?.window?.title?.toString()?.let { put("window_title", it) } ?: put("window_title", "")
        put("screen_state", screenStateJson(
            svc,
            screenChanged = if (before != null && after != null) before != after else null,
            settleTimedOut = !quiet,
        ))
    }
}

private fun clickedJson(
    clickable: AccessibilityNodeInfo,
    target: AccessibilityNodeInfo,
): JsonObject {
    val rect = Rect()
    clickable.getBoundsInScreen(rect)
    return buildJsonObject {
        put("bounds", buildJsonArray {
            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
        })
        put("text", clickable.text?.toString() ?: "")
        put("resolved_via_ancestor", clickable !== target)
    }
}

/**
 * Degradation guard for the optional a11y observations of click_and_read / wait_for
 * (tree hashes and root re-fetches that must never fail the call). Narrowed to the
 * one failure class the callees can actually raise, checked against their signatures
 * and throw sites:
 *  - IllegalStateException: AccessibilityService.getRootInActiveWindow and every
 *    AccessibilityNodeInfo getter used by treeHash/traverseTree cross into
 *    AccessibilityInteractionClient, which throws IllegalStateException once the a11y
 *    service connection has been closed (mid-call teardown race). Binder death itself
 *    never surfaces here — the client catches RemoteException/DeadObjectException
 *    internally and returns null, which the existing null checks already handle.
 *    There is no second type to add on purpose: a stale-node NPE would mean the
 *    framework contract itself broke, and it propagates exactly like the NPE a plain
 *    click_node / read_window_tree call would already surface to the caller (detekt
 *    also rejects catch(NPE) — handle nullability, don't trap on it).
 * Errors and any other RuntimeException propagate instead of degrading, so genuine
 * bugs are not masked. The caught exception keeps its stack: it is logged via
 * AppLog.w, never swallowed — the degradation to null is observable.
 */
internal fun <R> withA11yDegradation(site: String, what: String, block: () -> R): R? =
    try {
        block()
    } catch (e: IllegalStateException) {
        AppLog.w(TAG, "$what@$site degraded: ${e.message}", e)
        null
    }

/**
 * wait_for — the a11y-chain wait primitive that browser tools already have. awaitQuiet
 * only guarantees "no window events recently"; it does NOT guarantee "the element you
 * want is on screen". This polls findMatches until the selector hits or the timeout
 * expires. found=false on timeout is a NORMAL result (not an error envelope) — the
 * model decides whether to retry, scroll, or give up.
 */
fun waitForTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "wait_for",
    description = """
        Poll until an accessibility node matching the selector appears, or timeout. Use after
        actions that trigger async loading (page navigation, dialogs, network fetches) when
        the target element is not on screen yet. by: text | content_description |
        view_id_resource_name. Returns {found, elapsed_ms, attempts, matches, screen_state};
        found=false means the selector never appeared within timeout_ms — that is a normal
        result, not an error. Pure observation: never clicks, never types.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact value to match")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard; wrong_foreground_app error if it does not match")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max total wait in ms (default 5000, range 1000-30000)")
                })
                put("interval_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Poll interval in ms (default 400, range 100-2000)")
                })
            },
            required = listOf("by", "value")
        )
    },
    execute = { input ->
        val (by, value, pkgFilter) = parseSelector(input)
        val timeoutMs = input.jsonObject["timeout_ms"]?.jsonPrimitive?.longOrNull ?: WAIT_FOR_DEFAULT_TIMEOUT_MS
        val intervalMs = input.jsonObject["interval_ms"]?.jsonPrimitive?.longOrNull ?: WAIT_FOR_DEFAULT_INTERVAL_MS
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "missing_param", message = "by must be one of [text, content_description, view_id_resource_name] and value is required").toString()
                )
            )
        }
        if (timeoutMs !in WAIT_FOR_MIN_TIMEOUT_MS..WAIT_FOR_MAX_TIMEOUT_MS) {
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "invalid_argument", message = "timeout_ms must be between $WAIT_FOR_MIN_TIMEOUT_MS and $WAIT_FOR_MAX_TIMEOUT_MS", hint = "Check the parameter values and retry with corrected arguments.").toString()
                )
            )
        }
        if (intervalMs !in WAIT_FOR_MIN_INTERVAL_MS..WAIT_FOR_MAX_INTERVAL_MS) {
            return@Tool listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "invalid_argument", message = "interval_ms must be between $WAIT_FOR_MIN_INTERVAL_MS and $WAIT_FOR_MAX_INTERVAL_MS", hint = "Check the parameter values and retry with corrected arguments.").toString()
                )
            )
        }
        val svc = RikkaAccessibilityService.instance
            ?: return@Tool listOf(UIMessagePart.Text(AccessibilityServiceHandle.notActiveEnvelope().toString()))
        wakeScreenIfNeeded(svc)
        streamer.streamIfHeadless(invocationContext, "WaitFor $by=\"${value.take(30)}\"")
        waitForNode(svc, by, value, pkgFilter, timeoutMs, intervalMs)
    }
)

/**
 * Poll loop body of wait_for, extracted so execute ends with an expression (a bare
 * while(true) tail does not satisfy the lambda's List<UIMessagePart> return type).
 * A null root mid-wait is transient (screen transitioning / off) — keep polling
 * rather than erroring; the timeout bounds the total wait.
 */
private suspend fun waitForNode(
    svc: RikkaAccessibilityService,
    by: String,
    value: String,
    pkgFilter: String?,
    timeoutMs: Long,
    intervalMs: Long,
): List<UIMessagePart> {
    val start = SystemClock.uptimeMillis()
    var attempts = 0
    while (true) {
        attempts++
        val root = withA11yDegradation("wait_for", "poll-root") { svc.rootInActiveWindow }
        val pkg = root?.packageName?.toString().orEmpty()
        if (pkgFilter != null && root != null && pkgFilter != pkg) {
            svc.appendLog(
                ActionLogEntry(
                    type = "wait_for",
                    paramsSummary = "$by=\"${value.take(40)}\" wrong_pkg current=$pkg",
                    success = false,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            return listOf(
                UIMessagePart.Text(
                    ToolErrors.envelopeFor(error = "wrong_foreground_app", message = "wrong_foreground_app", extra = ToolErrors.extraOf("current" to pkg)).toString()
                )
            )
        }
        val matches = if (root != null) findMatches(root, by, value).take(50) else emptyList()
        if (matches.isNotEmpty()) {
            val elapsed = SystemClock.uptimeMillis() - start
            svc.appendLog(
                ActionLogEntry(
                    type = "wait_for",
                    paramsSummary = "$by=\"${value.take(40)}\" found after ${elapsed}ms ($attempts polls)",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            val windowId = root?.windowId ?: 0
            val payload = buildJsonObject {
                put("found", true)
                put("elapsed_ms", elapsed)
                put("attempts", attempts)
                put("matches", buildJsonArray {
                    matches.forEachIndexed { i, n -> add(nodeToJson(n, windowId, i)) }
                })
                put("screen_state", screenStateJson(svc, screenChanged = null))
            }
            return listOf(UIMessagePart.Text(payload.toString()))
        }
        val elapsed = SystemClock.uptimeMillis() - start
        if (elapsed >= timeoutMs) {
            svc.appendLog(
                ActionLogEntry(
                    type = "wait_for",
                    paramsSummary = "$by=\"${value.take(40)}\" timeout after ${elapsed}ms ($attempts polls)",
                    success = false,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            val payload = buildJsonObject {
                put("found", false)
                put("timed_out", true)
                put("elapsed_ms", elapsed)
                put("attempts", attempts)
                put("screen_state", screenStateJson(svc, screenChanged = null))
            }
            return listOf(UIMessagePart.Text(payload.toString()))
        }
        delay(intervalMs)
    }
}
