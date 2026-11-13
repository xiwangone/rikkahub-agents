package me.rerere.rikkahub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.redactSecrets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-2：SSE `settings` 快照走的正是这条路 —— 整个 Settings 编码成 JSON 后过
 * `redactSecrets` 再下发。这里用与真实快照同形的 JSON 断言：裸键 / 驼峰后缀键 /
 * MCP 请求头 pair 数组（`{first, second}`）/ provider 自定义头（`{name, value}`）
 * / OAuth 状态里的 token 全部被脱敏，普通字段原样保留。
 */
class SseSettingsRedactionTest {

    @Test
    fun `settings snapshot redacts every credential shape`() {
        val snapshot = buildJsonObject {
            put("webServerAccessPassword", "hunter2")
            put("webBridgePassword", "bridge-pass")
            put("assistantId", "00000000-0000-0000-0000-000000000000")
            put("providers", buildJsonArray {
                add(buildJsonObject {
                    put("apiKey", "sk-live-123")
                    put("customHeaders", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "X-Api-Key")
                            put("value", "hdr-secret")
                        })
                        add(buildJsonObject {
                            put("name", "Content-Type")
                            put("value", "application/json")
                        })
                    })
                })
            })
            put("mcpServers", buildJsonArray {
                add(buildJsonObject {
                    put("commonOptions", buildJsonObject {
                        put("headers", buildJsonArray {
                            add(buildJsonObject {
                                put("first", "Authorization")
                                put("second", "Bearer <redacted>")
                            })
                            add(buildJsonObject {
                                put("first", "X-Custom")
                                put("second", "plain-value")
                            })
                        })
                        put("oauth", buildJsonObject {
                            put("clientSecret", "oauth-secret")
                            put("accessToken", "ya29.token")
                            put("refreshToken", "1//refresh")
                        })
                    })
                })
            })
        }

        val redacted = redactSecrets(snapshot) as JsonObject

        assertEquals("\"***\"", redacted["webServerAccessPassword"].toString())
        assertEquals("\"***\"", redacted["webBridgePassword"].toString())
        assertEquals(
            "\"00000000-0000-0000-0000-000000000000\"",
            redacted["assistantId"].toString()
        )

        val providers = redacted["providers"] as JsonArray
        val provider = providers[0] as JsonObject
        assertEquals("\"***\"", provider["apiKey"].toString())

        val customHeaders = provider["customHeaders"] as JsonArray
        assertEquals("\"***\"", (customHeaders[0] as JsonObject)["value"].toString())
        assertEquals("\"X-Api-Key\"", (customHeaders[0] as JsonObject)["name"].toString())
        assertEquals(
            "\"application/json\"",
            (customHeaders[1] as JsonObject)["value"].toString()
        )

        val mcpServers = redacted["mcpServers"] as JsonArray
        val commonOptions = (mcpServers[0] as JsonObject)["commonOptions"] as JsonObject
        val headers = commonOptions["headers"] as JsonArray
        assertEquals("\"***\"", (headers[0] as JsonObject)["second"].toString())
        assertEquals("\"Authorization\"", (headers[0] as JsonObject)["first"].toString())
        assertEquals("\"plain-value\"", (headers[1] as JsonObject)["second"].toString())

        val oauth = commonOptions["oauth"] as JsonObject
        assertEquals("\"***\"", oauth["clientSecret"].toString())
        assertEquals("\"***\"", oauth["accessToken"].toString())
        assertEquals("\"***\"", oauth["refreshToken"].toString())
    }
}
