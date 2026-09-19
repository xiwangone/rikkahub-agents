package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

internal fun encodeRun(run: SubAgentRun): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    if (run.modelId != null) put("model_id", run.modelId)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("max_trips", run.maxTrips)
    put("started_at_ms", run.startedAtMs)
    if (run.finishedAtMs != null) put("finished_at_ms", run.finishedAtMs)
    if (run.result != null && !run.noResult) {
        put("result", run.result)
    } else if (run.noResult) {
        put("result_suppressed", true)
    }
    if (run.error != null) put("error", run.error)
    put("tokens_in", run.tokensIn)
    put("tokens_cached", run.tokensCached)
    put("tokens_out", run.tokensOut)
    // 真实成本在"未命中"部分：命中率高时输入虽大但便宜。
    if (run.tokensIn > 0) {
        put("cache_hit_pct", (run.tokensCached * 1000L / run.tokensIn) / 10.0)
    }
    put("trip_count", run.tripCount)
    if (run.workspaceId != null) put("workspace_id", run.workspaceId)
    run.toolScope?.takeIf { it.isNotEmpty() }?.let { scope ->
        put("tool_scope", buildJsonArray { scope.forEach { add(it) } })
    }
}

/**
 * Phase 11 — sub-agent dispatch + observation tools. The four register only when the
 * assistant has the `Sub-agents` Local Tools toggle on, AND the calling conversation is
 * NOT itself headless (the engine refuses recursive dispatch — these tools are not
 * useful inside a sub-agent run).
 */

fun subagentDispatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
    // #36: named sub-agent profiles, passed in fresh at tool-construction time (the
    // caller reads them from current settings) so the description below - and whether `agent`
    // is offered as a parameter at all - always reflects what's configured right now.
    profiles: List<SubAgentProfile> = emptyList(),
): Tool {
    val enabledProfiles = SubAgentProfileResolver.enabledProfiles(profiles)
    val description = buildString {
        append(
            """
                Dispatch a focused sub-agent — a clean-context LLM run that returns a concise
                summary. Use when the task is independent (research, lookup, multi-step work)
                and would otherwise pollute your context with intermediate output, OR when the
                user explicitly asks for parallel work.

                Pass a clear, self-contained task — the sub-agent doesn't see your conversation,
                so restate any context it needs. Pass a short label so the user can recognise
                the running sub-agent. For long-running work, set run_in_background=true and
                poll with subagent_get; otherwise foreground (default) blocks until terminal.

                Concurrency caps: each assistant has its own (default 3, configurable 1-8) and
                there's a global cap of 30 across all assistants. Over-cap dispatches fail with
                a clear error — back off and retry, or wait for a slot.

                Approval-required: every dispatch needs explicit confirmation. Eligible for
                Always Allow if the user trusts the assistant to delegate freely.
            """.trimIndent()
        )
        if (enabledProfiles.isNotEmpty()) {
            appendLine()
            appendLine()
            append("Named sub-agent profiles (pass the name as `agent`):\n")
            append(enabledProfiles.joinToString("\n") { "- ${it.name}: ${it.description}" })
        }
    }
    return Tool(
        name = "subagent_dispatch",
        description = description,
        parameters = { dispatchParameters(enabledProfiles) },
        needsApproval = { true },
        execute = { args -> runSubAgentDispatch(engine, callerContext, args) },
    )
}

/** 派发工具的参数 schema（独立函数：缩短工具定义整体长度）。 */
private fun dispatchParameters(enabledProfiles: List<SubAgentProfile>): InputSchema.Obj =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject { put("type", "string") })
                put("label", buildJsonObject { put("type", "string") })
                if (enabledProfiles.isNotEmpty()) {
                    put("agent", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Name of a configured sub-agent profile (case-insensitive), " +
                                "supplying that profile's model and system prompt. Unknown " +
                                "names fail the dispatch and the error lists the valid " +
                                "names. model_id, if also given, wins over the profile's " +
                                "model.",
                        )
                    })
                }
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Model for this sub-agent: a model uuid, a provider model id, or a " +
                            "display name (case-insensitive exact match). Ambiguous or unknown " +
                            "values fail the dispatch and the error lists the valid options. " +
                            "Takes precedence over agent's model. Omit to inherit the agent " +
                            "profile's model (if agent is set) or the parent assistant's model.",
                    )
                })
                put("system_prompt", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Extra instruction for this run only, prepended to the task. A named " +
                            "agent's own system prompt is used instead when agent is given.",
                    )
                })
                put(
                    "tools",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put(
                            "description",
                            "OPTIONAL allow-list of tool names for this sub-agent (narrowing " +
                                "only - a name the parent cannot use is dropped). Pass e.g. " +
                                "[\"workspace_read_file\",\"workspace_shell\",\"web_fetch\"] to " +
                                "keep a read-only researcher cheap and safe. Omit to inherit " +
                                "the parent's full tool set. Unknown names are ignored.",
                        )
                    },
                )
                put("workspace", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "OPTIONAL workspace uuid to bind this sub-agent's workspace_* tools " +
                            "to. Use it to run the sub-agent in an isolated workspace so its " +
                            "writes cannot touch the parent's files. Omit to use the agent " +
                            "profile's workspace, or the parent assistant's workspace if " +
                            "neither is set.",
                    )
                })
                put("run_in_background", buildJsonObject { put("type", "boolean") })
                put("no_result", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "When true the sub-agent's final output is not returned to you " +
                            "(only status, ids and counters). Use for fire-and-forget work " +
                            "whose result you do not need, to keep your context small.",
                    )
                })
                put("timeout_seconds", buildJsonObject { put("type", "integer") })
                put("max_trips", buildJsonObject { put("type", "integer") })
            },
            required = listOf("task"),
        )

/**
 * 派发工具的执行体。独立成函数：工具定义（名称/描述/参数）保持可读，且避免单方法过长
 * 触发静态检查门限。返回给模型的信封与 engine.dispatch 的结果一一对应。
 */
private suspend fun runSubAgentDispatch(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext,
    args: kotlinx.serialization.json.JsonObject,
): List<UIMessagePart> {
    // Hard recursion guard —— 调用方本身是 headless run（cron / 工作流 / 子代理 / 外部自动化）
    // 时拒绝派发；引擎自身的守卫依赖已登记的会话 id，cron/workflow 直连路径没有会话，故在此兜底。
    if (callerContext.isHeadless) {
        return errEnv(
            "no_recursion",
            "sub-agent dispatch is not allowed from inside a headless run (cron / workflow / sub-agent / external automation). Run the work inline instead.",
        )
    }
    val params = args.jsonObject
    val task = params["task"]?.jsonPrimitive?.contentOrNull
        ?: return errEnv("invalid_task", "task is required")
    val request = SubAgentRequest(
        task = task,
        modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
        agentName = params["agent"]?.jsonPrimitive?.contentOrNull,
        systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull,
        tools = params["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull },
        runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false,
        noResult = params["no_result"]?.jsonPrimitive?.booleanOrNull ?: false,
        timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
            ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull
            ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
        label = params["label"]?.jsonPrimitive?.contentOrNull,
        workspaceId = params["workspace"]?.jsonPrimitive?.contentOrNull,
    )
    // 调用上下文（callerAssistantId / callerConversationId）由工具构造时注入；
    // 空值是无上下文的哨兵，引擎按「不在 headless run 中」处理（旧注册路径无上下文）。
    val parentAssistantId = callerContext.callerAssistantId.orEmpty()
    val parentChatId: String? = callerContext.callerConversationId
    return when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
        is SubAgentEngine.DispatchResult.Reject -> errEnv(res.error, res.detail)
        is SubAgentEngine.DispatchResult.Ok ->
            listOf(UIMessagePart.Text(encodeRun(res.run).toString()))
    }
}

fun subagentListTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_list",
    description = """
        List sub-agent runs visible to this assistant. Set active_only=true to omit
        terminal runs. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("active_only", buildJsonObject { put("type", "boolean") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val activeOnly = args.jsonObject["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val list = registry.list(activeOnly)
        val arr = buildJsonArray {
            list.forEach { addJsonObject {
                put("id", it.id)
                put("label", it.label)
                put("status", it.status.name)
                if (it.modelId != null) put("model_id", it.modelId)
                put("started_at_ms", it.startedAtMs)
                put("trip_count", it.tripCount)
            } }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("runs", arr)
        }.toString()))
    },
)

fun subagentGetTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val run = registry.get(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

fun subagentCancelTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel",
    description = """
        Cancel a running sub-agent by id. Marks the run CANCELLED; safe to call on
        already-terminal runs (returns ok=false). Read-only from the user's perspective
        — no approval required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val cancelled = registry.requestCancel(id)
        if (cancelled) {
            registry.update(id) { it.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", cancelled)
            put("id", id)
        }.toString()))
    },
)
