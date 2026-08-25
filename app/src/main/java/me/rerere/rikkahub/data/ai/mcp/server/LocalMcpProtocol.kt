package me.rerere.rikkahub.data.ai.mcp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 本地 MCP Server 的 JSON-RPC 2.0 报文模型与 MCP 错误码。
 *
 * 供 Reasonix serve 的 `[[plugins]]`（type = "http", Streamable HTTP）作为一个标准
 * MCP 工具源挂载。报文格式对齐 MCP 2024-11-05 基础协议：单 POST 端点承载
 * `initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `ping`。
 */

@Serializable
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class McpJsonRpcResponse(
    val jsonrpc: String,
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: McpError? = null,
)

@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

internal object McpJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
}