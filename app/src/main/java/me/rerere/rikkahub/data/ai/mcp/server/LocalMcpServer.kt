package me.rerere.rikkahub.data.ai.mcp.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.utils.isRemoteHostAllowed
import java.security.MessageDigest

/**
 * 本地 MCP Server（Streamable HTTP，单 POST 端点）。
 *
 * 监听 127.0.0.1，作为 Backend serve `[[plugins]]` type="http" 的工具源：
 * `initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `ping`。
 * 工具执行走 [LocalToolRegistry] + [LocalApprovalBridge]（审批在 App 进程内）。
 */
class LocalMcpServer(
    private val port: Int,
    private val registry: LocalToolRegistry,
    private val approvalBridge: LocalApprovalBridge,
    /** 监听地址：127.0.0.1（默认，仅本机）或 0.0.0.0（对外） */
    private val host: String = HOST,
    /** 访问令牌提供者：非空则要求 Authorization: Bearer <token>；每次请求解析，支持凭证轮换 */
    private val authTokenProvider: (suspend () -> String?)? = null,
    /** 允许访问的来源网段白名单（CIDR，逗号分隔；空 = 不限制） */
    private val allowedNetworks: String = "",
) {

    private companion object {
        const val HOST = "127.0.0.1"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "rikkahub-agents"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }

    @Volatile
    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    fun start() {
        if (engine != null) return
        val server = embeddedServer(CIO, port = port, host = host, module = { mcpModule() })
        server.start(wait = false)
        engine = server
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun Application.mcpModule() {
        routing {
            post("/") {
                // 0) 网段白名单：allowedNetworks 为空 = 不限制
                val remoteHost = call.request.local.remoteHost
                if (!isRemoteHostAllowed(remoteHost, allowedNetworks)) {
                    call.respondText(
                        "network not allowed: $remoteHost",
                        ContentType.Text.Plain,
                        HttpStatusCode.Forbidden,
                    )
                    return@post
                }
                // 1) 访问令牌：authTokenRef 非空时强制校验（每次请求解析，支持凭证轮换）
                val expectedToken = authTokenProvider?.invoke()
                if (!expectedToken.isNullOrBlank()) {
                    val provided =
                        call.request.headers["Authorization"]?.let {
                            if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it.trim()
                        }
                    if (provided.isNullOrBlank() ||
                        !MessageDigest.isEqual(provided.toByteArray(), expectedToken.toByteArray())
                    ) {
                        call.respondText(
                            "invalid or missing access token",
                            ContentType.Text.Plain,
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                }
                val raw = call.receiveText()
                val (responseBody, sessionId) = dispatch(raw)
                if (responseBody == null) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    if (sessionId != null) {
                        call.response.headers.append(SESSION_HEADER, sessionId)
                    }
                    call.respondText(responseBody, ContentType.Application.Json)
                }
            }
        }
    }

    private suspend fun dispatch(raw: String): Pair<String?, String?> {
        val request = runCatching {
            McpJson.json.decodeFromString<McpJsonRpcRequest>(raw)
        }.getOrNull()
        if (request == null) {
            return respond(
                id = null,
                error = McpError(McpErrorCodes.PARSE_ERROR, "Parse error: invalid JSON-RPC payload"),
            ) to null
        }
        val id = request.id
        return when (request.method) {
            "initialize" -> {
                val result = buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", false) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", SERVER_NAME)
                        put("version", "1.0.0")
                    })
                }
                respond(id, result) to "mcp-${System.currentTimeMillis()}"
            }
            "notifications/initialized" -> null to null
            "ping" -> respond(id, buildJsonObject {}) to null
            "tools/list" -> {
                val result = buildJsonObject {
                    put("tools", JsonArray(registry.all().map { it.toMcpToolDefinition() }))
                }
                respond(id, result) to null
            }
            "tools/call" -> {
                val params = request.params?.jsonObject
                val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
                val arguments = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())
                val tool = toolName?.let { registry.get(it) }
                if (tool == null) {
                    respond(
                        id,
                        error = McpError(McpErrorCodes.METHOD_NOT_FOUND, "Unknown tool: $toolName"),
                    ) to null
                } else {
                    respond(id, executeCall(tool, arguments, id?.toString() ?: "mcp-call")) to null
                }
            }
            else -> {
                respond(
                    id,
                    error = McpError(McpErrorCodes.METHOD_NOT_FOUND, "Method not found: ${request.method}"),
                ) to null
            }
        }
    }

    private suspend fun executeCall(tool: Tool, arguments: JsonObject, requestId: String): JsonObject {
        val approved = approvalBridge.requireApproval(tool, arguments, requestId)
        if (!approved) {
            return errorContent("tool rejected by user (approval not granted)")
        }
        return try {
            val parts = tool.execute(arguments)
            buildJsonObject {
                put("content", JsonArray(parts.map { it.toMcpContent() }))
            }
        } catch (e: Exception) {
            errorContent("tool execution failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun errorContent(text: String): JsonObject = buildJsonObject {
        put("isError", true)
        put("content", JsonArray(listOf(buildJsonObject {
            put("type", "text")
            put("text", text)
        })))
    }

    private fun respond(id: JsonElement?, result: JsonElement? = null, error: McpError? = null): String =
        McpJson.json.encodeToString(
            McpJsonRpcResponse(jsonrpc = "2.0", id = id, result = result, error = error)
        )
}