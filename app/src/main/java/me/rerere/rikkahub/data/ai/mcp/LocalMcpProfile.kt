package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/**
 * 本地 MCP Server 的可配置 profile（第 2 步：默认空、用户自填、可保存多个）。
 *
 * 每个 profile 是一份独立的本地 MCP 配置：端口 + 暴露给 Backend 的设备工具子集。
 * 与「远程 MCP」(McpServerConfig/mcpServers) 区分；本地 MCP 由 [LocalMcpServerManager]
 * 在 127.0.0.1:<port> 启动，供 Backend serve 访问手机设备能力。
 */
@Serializable
data class LocalMcpProfile(
    val id: String,
    val name: String,
    val port: Int = 8788,
    /** 暴露的工具子集；默认空——用户自填。为空时启动后暴露 0 个工具。 */
    val allowedTools: List<LocalToolOption> = emptyList(),
)
