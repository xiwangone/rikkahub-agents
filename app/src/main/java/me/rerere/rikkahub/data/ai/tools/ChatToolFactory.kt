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
