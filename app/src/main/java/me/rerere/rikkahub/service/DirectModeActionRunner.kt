package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.log.AppLog

/**
 * Parses + executes mode='direct' action sequences. Each action is a single
 * `{tool: name, args: { ... }}` object; the array is run in order. Per-action
 * 60-second timeout. HARDLINE checked at every action's args BEFORE invoking.
 *
 * No inter-action templating in v1 — each action is independent. (See spec
 * §"Out of scope for v1" — that's Phase 12 Workflows territory.)
 *
 * Headless authorisation: a direct-mode fire has no conversation, so every
 * action whose tool declares approval ([Tool.needsApproval]) must first pass
 * the shared headless policy ([ToolApprovalDefaults.isHeadlessAutoApproved]:
 * global YOLO or a persistent "Always Allow" grant; NO_ALWAYS_ALLOW tools
 * never pass). An unauthorised action is SKIPPED (not executed, not an
 * error in itself) and recorded by name in [SequenceResult.skippedTools];
 * the run continues with the next action. If every action was skipped the
 * outcome is "failed" so the run is not counted as a success and the user
 * gets a failure notification — a partially-skipped run still reports
 * "success" but names the skipped tools in its record (user decision
 * 2026-11-17: "被拦的动作记进运行记录，全跳过不记成功").
 */
class DirectModeActionRunner(
    private val json: Json,
    private val approvalSnapshot: suspend () -> ToolApprovalDefaults.HeadlessApprovalSnapshot,
) {
    @Serializable
    data class Action(
        val tool: String,
        val args: JsonObject,
    )

    /** Thrown by parse() when the actions JSON is structurally invalid. */
    class ParseError(
        val code: String,
        msg: String,
    ) : Exception(msg)

    /** Outcome of running a single action. */
    sealed class StepResult {
        data class Success(
            val output: List<UIMessagePart>,
        ) : StepResult()

        data class Failed(
            val errorMessage: String,
        ) : StepResult()

        data object TimedOut : StepResult()

        data class HardlineBlocked(
            val reason: String,
        ) : StepResult()

        /** The action's tool is not in the available-tools list at fire time (never
         *  registered, or the assistant disabled it after the job was created). */
        data class UnknownTool(
            val toolName: String,
        ) : StepResult()

        /**
         * The action's tool declares approval but the headless policy did not
         * authorise it (no YOLO, no persistent grant — or the tool is
         * NO_ALWAYS_ALLOW, which can never run unattended). The action is
         * skipped, not executed; the runner continues with the next action.
         */
        data class AuthorizationSkipped(
            val toolName: String,
        ) : StepResult()
    }

    /** Outcome of running the whole sequence. */
    data class SequenceResult(
        val finalOutcome: String, // success|failed|timed_out
        val errorMessage: String?,
        /** Tools skipped for lack of headless authorisation (distinct names, in order). */
        val skippedTools: List<String> = emptyList(),
    )

    /**
     * Execute each action sequentially. Aborts on first non-success result —
     * except authorisation skips, which continue to the next action.
     */
    suspend fun run(
        actions: List<Action>,
        availableTools: List<Tool>,
    ): SequenceResult {
        val snapshot = approvalSnapshot()
        val skipped = mutableListOf<String>()
        var executed = 0
        for ((idx, action) in actions.withIndex()) {
            val result = runOne(idx, action, availableTools, snapshot)
            when (result) {
                is StepResult.Success -> {
                    executed++
                    continue
                }

                is StepResult.AuthorizationSkipped -> {
                    if (result.toolName !in skipped) skipped += result.toolName
                    continue
                }

                is StepResult.Failed -> return SequenceResult(
                    "failed",
                    "action $idx: ${result.errorMessage}",
                    skipped,
                )

                is StepResult.TimedOut -> return SequenceResult(
                    "timed_out",
                    "action $idx: ${action.tool} exceeded 60s",
                    skipped,
                )

                is StepResult.HardlineBlocked -> return SequenceResult(
                    "failed",
                    "action $idx: hardline:${result.reason}",
                    skipped,
                )

                // A direct-mode job validates its tool list at creation time, but the
                // assistant's enabled-tools set can change afterwards. If a tool the job
                // references is no longer in `availableTools` when the job fires, surface
                // it as a NAMED failure ("tool_unavailable: <toolName>") so the failed
                // run-history row tells the user exactly which tool to re-enable — rather
                // than the job appearing to fail for an opaque reason.
                is StepResult.UnknownTool -> return SequenceResult(
                    "failed",
                    "action $idx: tool_unavailable: ${result.toolName}",
                    skipped,
                )
            }
        }
        // All actions skipped for lack of authorisation: not a success — the run
        // must not consume max_runs and the user must get a failure notice.
        // A partially-skipped run still succeeds, but the skipped tools are
        // named in the record so the gap is visible, never silent.
        if (executed == 0 && skipped.isNotEmpty()) {
            return SequenceResult(
                "failed",
                "all ${actions.size} action(s) skipped (authorization_required): " +
                    skipped.joinToString("; ") { ToolApprovalDefaults.headlessSkipReason(it) },
                skipped,
            )
        }
        if (skipped.isNotEmpty()) {
            return SequenceResult(
                "success",
                "skipped ${skipped.size} action(s) (authorization_required): " +
                    skipped.joinToString("; ") { ToolApprovalDefaults.headlessSkipReason(it) } +
                    "; the other actions ran normally",
                skipped,
            )
        }
        return SequenceResult("success", null)
    }

    private suspend fun runOne(
        idx: Int,
        action: Action,
        availableTools: List<Tool>,
        snapshot: ToolApprovalDefaults.HeadlessApprovalSnapshot,
    ): StepResult {
        val hardlineReason = HardlineCommandGuard.checkTool(action.tool, action.args.toString())
        if (hardlineReason != null) {
            AppLog.w(TAG, "direct-mode hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
            return StepResult.HardlineBlocked(hardlineReason)
        }
        val tool =
            availableTools.find { it.name == action.tool }
                ?: return StepResult.UnknownTool(action.tool)
        // Headless authorisation — same central decision as the chat path, minus
        // the chat-only halves (no conversation here): the tool's own declared
        // approval first, then the shared YOLO / persistent-grant policy.
        if (tool.needsApproval(action.args) &&
            !ToolApprovalDefaults.isHeadlessAutoApproved(action.tool, snapshot)
        ) {
            AppLog.w(
                TAG,
                "direct-mode action $idx tool=${action.tool} skipped: " +
                    ToolApprovalDefaults.headlessSkipReason(action.tool),
            )
            return StepResult.AuthorizationSkipped(action.tool)
        }
        return try {
            val out = withTimeoutOrNull(60_000L) { tool.execute(action.args) }
            if (out == null) StepResult.TimedOut else StepResult.Success(out)
        } catch (c: CancellationException) {
            // A cancelled tool must unwind the run as a real cancellation, not be reported as
            // a failed step (same contract as WorkflowActionRunner.run).
            throw c
        } catch (t: Throwable) {
            AppLog.w(TAG, "direct-mode action $idx tool=${action.tool} threw", t)
            StepResult.Failed("${t::class.simpleName}: ${t.message.orEmpty()}".take(500))
        }
    }

    companion object {
        private const val TAG = "DirectModeActionRunner"

        /**
         * Parse a JSON string representing an array of actions. Returns a [Result] wrapping
         * the list of [Action] objects, or a [ParseError] on any structural problem.
         * Called as a static companion so tests don't need a Json instance.
         */
        fun parse(actionsJson: String): Result<List<Action>> {
            val element: JsonElement =
                runCatching {
                    Json.parseToJsonElement(actionsJson)
                }.getOrElse {
                    return Result.failure(ParseError("invalid_json", it.message ?: "JSON parse failed"))
                }
            if (element !is JsonArray) {
                return Result.failure(ParseError("not_an_array", "actions must be a JSON array"))
            }
            if (element.isEmpty()) {
                return Result.failure(ParseError("empty_actions", "actions must be non-empty"))
            }
            val parsed = ArrayList<Action>(element.size)
            for ((idx, el) in element.withIndex()) {
                if (el !is JsonObject) {
                    return Result.failure(
                        ParseError("bad_action_shape", "action $idx is not an object"),
                    )
                }
                val tool =
                    (el["tool"] as? JsonPrimitive)?.contentOrNull
                        ?: return Result.failure(ParseError("missing_tool", "action $idx missing 'tool' field"))
                val args =
                    el["args"] as? JsonObject
                        ?: return Result.failure(ParseError("missing_args", "action $idx missing 'args' object"))
                parsed += Action(tool, args)
            }
            return Result.success(parsed)
        }
    }
}
