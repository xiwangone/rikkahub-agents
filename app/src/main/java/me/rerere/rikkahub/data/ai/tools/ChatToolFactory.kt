package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

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
    private val context: android.content.Context,
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
    ): List<Tool> = buildList {
        // 记忆分层：只注入 core 常驻；conditional 由模型按需检索。
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(
                buildMemoryTools(
                    json = json,
                    onCreation = { content, tier ->
                        memoryRepository.addMemory(memoryAssistantId, content, tier)
                    },
                    onUpdate = { id, content, tier ->
                        memoryRepository.updateContent(id, content, tier)
                    },
                    onDelete = { id ->
                        memoryRepository.deleteMemory(id)
                    },
                    onSearch = { keyword ->
                        memoryRepository.searchConditionalMemories(keyword)
                    },
                    onListAll = {
                        memoryRepository.getMemoriesOfAssistant(memoryAssistantId)
                    },
                )
            )
        }

        if (assistant.enableWebSearch) {
            addAll(createSearchTools(settings))
        }

        addAll(localTools.getTools(assistant.localTools, invocationCtx))

        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))

        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                )
            )
        }

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
            // 用服务器 id 前 8 位十六进制做命名空间，避免两个服务器同名工具相互覆盖；
            // 保留 `mcp__` 前缀（HardlineCommandGuard 与 ToolApprovalDefaults 均按此前缀分支）。
            val serverSlug = serverId.toString().take(8).replace("-", "")
            val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
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
        // 注入视图：低频工具精简描述；元工具基于**未精简**的完整列表，保证 get_tool_schema 能取回原文。
        val injected = full.map { slimDescriptionForInjection(it) } + buildToolDiscoveryTools(full)
        // 登记本次注入集合：让 tool_usage_stats 能直接识别"已启用但从未调用"的工具。
        ToolUsageTracker.recordInjected(context, injected.map { it.name })
        trackUsage(injected)
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
 * 低频工具只保留一行用途，压缩常驻提示体积（渐进式披露的 S2）。
 *
 * 只裁剪 description，**完整保留参数表**——工具仍可被正常调用；需要完整说明时由检索类工具按需提供。
 * 高频工具（[SurfaceTier.HOT] / [SurfaceTier.WARM]）不作改动。
 */
private fun slimDescriptionForInjection(tool: Tool): Tool =
    when (ToolSurfacePolicy.tierOf(tool.name)) {
        SurfaceTier.COLD -> tool.copy(description = tool.description.toSingleLine())
        else -> tool
    }

/** 取描述的首句（英文句点或换行分隔），并限制长度。 */
private fun String.toSingleLine(maxChars: Int = 120): String {
    val normalized = replace("\n", " ").trim()
    val head = normalized.substringBefore(". ").substringBefore("。")
    return if (head.length <= maxChars) head else head.take(maxChars).trimEnd() + "…"
}
