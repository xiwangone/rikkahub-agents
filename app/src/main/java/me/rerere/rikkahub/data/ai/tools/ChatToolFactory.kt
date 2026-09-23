package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.costguards.runToolSurfaceReport
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

/** L4 观测工具名；装配出口据此把报告重绑到裁剪后的实际注入集。 */
private const val TOOL_SURFACE_REPORT_TOOL_NAME = "tool_surface_report"

/** MCP 服务器名不符合 `^[a-zA-Z0-9_-]+$` 时抛出，由调用方转成用户可见错误。 */
class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/**
 * 单次生成所需的完整工具面装配点。
 *
 * 装配顺序即对外暴露顺序（顺序参与请求前缀字节，改动会影响前缀缓存命中）：
 * memory → search → local → workspace → skill → mcp
 */
class ChatToolFactory(
    private val context: android.app.Application,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        invocationCtx: ToolInvocationContext,
        workspaceCwd: String? = null,
        // 会话级作用域覆盖（目前由 SubAgentEngine 写入；null = 跟随助手设置）：
        //  - workspaceIdOverride：workspace_* 工具绑定到哪个工作区
        //  - toolScopeOverride：工具白名单（只减不增）
        workspaceIdOverride: String? = null,
        toolScopeOverride: List<String>? = null,
    ): List<Tool> = buildList {
        // 记忆分层：只注入 core 常驻；conditional 由模型按需检索。
        addAll(memoryToolsIfEnabled(assistant))

        if (assistant.enableWebSearch) {
            addAll(createSearchTools(settings))
        }

        addAll(localTools.getTools(assistant.localTools, invocationCtx))

        addAll(workspaceAndSkillTools(assistant, workspaceIdOverride, workspaceCwd))

        val mcpTools = mcpManager.getAllAvailableTools()
        // 服务器名不是纯英文+数字（允许 _ 和 -）时无法组成合法的 mcp__<name>__tool 名，
        // 报错而非下发模型无法寻址的工具。
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name ->
                name.isEmpty() ||
                    !name.all {
                        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
                    }
            }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            // 统一走 McpManager.buildMcpToolName：名字必须与 mcp_list_tools 显示给模型的一致，
            // 否则出现「列出来的名字调不动」。该函数同时负责归一化与长度上限。
            val mcpToolName = buildMcpToolName(serverId, serverName, tool.name)
            add(
                Tool(
                    name = mcpToolName,
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    // MCP 工具语义不透明，一律默认需要审批；用户可对单个工具授予 Always。
                    needsApproval = {
                        ToolApprovalDefaults.requiresApproval(mcpToolName) || tool.needsApproval
                    },
                    execute = {
                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                    },
                )
            )
        }
    }.let { full ->
        // 固定按工具名排序：工具顺序参与请求前缀，排序后集合与顺序稳定，避免
        // MCP/技能装配顺序抖动击穿缓存。
        val stableFull = full.sortedBy { it.name }
        // 注入视图：冷档固定保留工具名，但未解锁时只发空 schema；工具集合/顺序不变。
        // get_tool_schema 成功后按会话记忆，下一次请求恢复完整 schema。
        // 检索元工具与其余工具**一起参与排序**：此前它们是追加在列表末尾的，使注入集
        // 并非全局有序（tool_surface_report 的 order_is_sorted 恒为 false，观测口径失真，
        // 容易被误读成"顺序抖动"）。顺序本来稳定，这里只是让它同时自洽、可观测。
        // 工具范围（助手级白名单）：留空 = 不限制；非空 = 只注入名单内工具（保命工具始终保留）。
        // 工具范围：会话级覆盖优先（子代理），否则助手级白名单；留空 = 不限制；非空 = 只注入名单内工具（保命工具始终保留）。
        val effectiveToolScope = toolScopeOverride ?: assistant.onlyTools
        val listFiltered = applyToolScopeFilter(stableFull, effectiveToolScope)
        // 白名单里拼错/过期的名字不会报错, 只会静默失效; 统一算出未匹配项,
        // 记一条日志并透出给 tool_surface_report, 便于事后自查。
        val onlyToolsUnmatched =
            if (effectiveToolScope.isEmpty()) {
                emptyList()
            } else {
                val knownNames = stableFull.mapTo(HashSet()) { it.name }
                effectiveToolScope.filterNot { it in knownNames }
            }
        if (onlyToolsUnmatched.isNotEmpty()) {
            AppLog.w(TAG, "tool scopes has ${onlyToolsUnmatched.size} unmatched name(s): $onlyToolsUnmatched")
        }

        val injected =
            (
                listFiltered.map {
                    surfaceView(
                        it,
                        invocationCtx.callerConversationId,
                        trimEnabled = settings.displaySetting.toolSurfaceTrimming && ToolSurfacePolicy.TRIM_ENABLED,
                        extraCold = assistant.extraColdTools.toSet(),
                    )
                } +
                    buildToolDiscoveryTools(
                        listFiltered,
                        invocationCtx.callerConversationId,
                        extraCold = assistant.extraColdTools.toSet(),
                    )
            ).sortedBy { it.name }
        // 工具重绑到与模型看到的一致的那份列表上，否则「省了多少」永远是 0。
        val measured =
            injected.map { tool ->
                if (tool.name != TOOL_SURFACE_REPORT_TOOL_NAME) {
                    tool
                } else {
                    tool.copy(
                        execute = { args -> runToolSurfaceReport(args, injected, onlyToolsUnmatched) },
                    )
                }
            }
        // 登记本次注入集合：让 tool_usage_stats 能直接识别"已启用但从未调用"的工具。
        ToolUsageTracker.recordInjected(context, measured.map { it.name })
        trackUsage(measured)
    }

    /**
     * 统一埋点：记录工具名/次数/失败/耗时（**不含参数**）。
     *
     * 放在装配出口而不是各工具内部，是为了覆盖所有来源（本地工具 / 工作区 / MCP / 技能），
     * 否则统计只覆盖 LocalTools 一族，分档判断会失真。
     */
    private fun trackUsage(tools: List<Tool>): List<Tool> =
        tools.map { tool ->
            tool.copy(
                execute = { args ->
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    var failed = false
                    try {
                        tool.execute(args)
                    } catch (error: Throwable) {
                        failed = true
                        throw error
                    } finally {
                        ToolUsageTracker.record(
                            context = context,
                            name = tool.name,
                            durationMs = android.os.SystemClock.elapsedRealtime() - startedAt,
                            failed = failed,
                        )
                    }
                },
            )
        }

    /** 工作区 shell 未就绪时不下发工作区工具（避免模型调用必然失败的工具）。 */
    /** 记忆工具：按助手开关注入（独立函数仅为压低工具装配主函数的复杂度）。 */
    private suspend fun memoryToolsIfEnabled(assistant: Assistant): List<Tool> {
        if (!assistant.enableMemory) return emptyList()
        val memoryAssistantId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        return buildMemoryTools(
            json = json,
            onCreation = { content, tier -> memoryRepository.addMemory(memoryAssistantId, content, tier) },
            onUpdate = { id, content, tier -> memoryRepository.updateContent(id, content, tier) },
            onDelete = { id -> memoryRepository.deleteMemory(id) },
            onSearch = { keyword -> memoryRepository.searchConditionalMemories(keyword) },
            onListAll = { memoryRepository.getMemoriesOfAssistant(memoryAssistantId) },
        )
    }

    /**
     * 工作区工具（会话级覆盖优先于助手级）+ 技能工具。
     * 抽成独立函数：既让主装配函数保持可读，也避免复杂度门限（detekt）。
     */
    private suspend fun workspaceAndSkillTools(
        assistant: Assistant,
        workspaceIdOverride: String?,
        workspaceCwd: String?,
    ): List<Tool> = buildList {
        addAll(
            createWorkspaceToolsIfReady(
                workspaceIdOverride ?: assistant.workspaceId?.toString(),
                workspaceCwd,
            )
        )
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                )
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            AppLog.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}",
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}


/**
 * 冷档工具保留固定名称，但首次只带一行说明和空 schema；get_tool_schema 成功后，
 * 会话内后续请求恢复完整 schema。工具集合和顺序不变，避免前缀缓存抖动。
 */
private fun surfaceView(
    tool: Tool,
    conversationId: String?,
    trimEnabled: Boolean,
    extraCold: Set<String>,
): Tool {
    // 助手级黑名单（「这些工具只发简要说明」）**独立于**全局裁剪开关：名单非空即生效，
    // 与助手级白名单（onlyTools）语义对齐——填了就生效、清空保存即恢复。
    if (tool.name in extraCold) return toColdView(tool, conversationId)
    // 全局开关：设置页的「精简工具说明」与编译期总开关取与关系，任一关闭即完全不做裁剪。
    if (!trimEnabled) return tool
    val tier = ToolSurfacePolicy.tierOf(tool.name, extraCold)
    if (tier == SurfaceTier.HOT) return tool

    if (tier == SurfaceTier.WARM) {
        // WARM 档：描述收敛为首段以压低常驻体积，**参数表完整保留** ——
        // 与 COLD 的「空 schema + 拦截」不同，模型据此仍可直接调用，无需 get_tool_schema 往返。
        // 只对「明显偏长」的描述动手：短描述原样保留，避免白白丢信息。
        if (tool.description.length <= WARM_DESCRIPTION_KEEP_CHARS) return tool
        val compact = tool.description.toCompactDescription()
        return if (compact.isBlank() || compact == tool.description) tool else tool.copy(description = compact)
    }

    return toColdView(tool, conversationId)
}

/**
 * 冷档注入视图：只发一行用途 + 空 schema，调用被拦截直到 get_tool_schema 解锁。
 * 抽为独立函数，使「工具名单（deny）」在不开启全局裁剪时也能复用同一形态。
 */
private fun toColdView(tool: Tool, conversationId: String?): Tool {
    if (ToolSurfaceSession.isLoaded(conversationId, tool.name)) return tool
    return tool.copy(
        description = tool.description.toSingleLine() +
            " Before calling, use get_tool_schema with this exact tool name, then retry with the returned parameters.",
        // 动态读取会话状态：get_tool_schema 在同一生成回合执行后，下一次请求无需重建 Tool 列表。
        parameters = {
            if (ToolSurfaceSession.isLoaded(conversationId, tool.name)) {
                tool.parameters()
            } else {
                InputSchema.Obj(properties = buildJsonObject {}, required = emptyList())
            }
        },
        execute = { args ->
            if (!ToolSurfaceSession.isLoaded(conversationId, tool.name)) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "tool_schema_not_loaded")
                            put("tool", tool.name)
                            put("hint", "Call get_tool_schema with this exact tool name, then retry the tool.")
                        }.toString(),
                    ),
                )
            } else {
                tool.execute(args)
            }
        },
    )
}

/**
 * 工具范围过滤（助手级白名单）：非空时只保留名单内工具 + 保命工具。
 *
 * 抽为独立函数既降低 `createTools` 的圈复杂度，也让「白名单过滤」可被单测覆盖。
 */
private fun applyToolScopeFilter(tools: List<Tool>, only: List<String>): List<Tool> {
    if (only.isEmpty()) return tools
    val allow = only.toSet()
    return tools.filter { it.name in allow || it.name in ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES }
}

/** 描述长度阈值：超过它才做 WARM 档收敛（短的保持原样，不丢信息）。 */
private const val WARM_DESCRIPTION_KEEP_CHARS = 200

/** 取描述的首句（英文句点或换行分隔），并限制长度。 */
private fun String.toSingleLine(maxChars: Int = 120): String {
    val normalized = replace("\n", " ").trim()
    val head = normalized.substringBefore(". ").substringBefore("。")
    return if (head.length <= maxChars) head else head.take(maxChars).trimEnd() + "…"
}

/**
 * WARM 档描述收敛：保留**首段**（首个空行之前）并限制长度。
 *
 * 相比 [toSingleLine] 只取首句，这里保留整段，确保「用途 + 关键用法」不丢；
 * 且 WARM 档的参数表（schema）**完整保留**，模型据此仍可直接调用、无需额外往返。
 */
private fun String.toCompactDescription(maxChars: Int = 400): String {
    val normalized = substringBefore("\n\n").replace("\n", " ").trim()
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars).trimEnd() + "…"
}
