package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {

    @Test
    fun `mergeCustomBody with empty list should return original object`() {
        val originalJson = buildJsonObject {
            put("key1", "value1")
            put("key2", 2)
        }

        val result = originalJson.mergeCustomBody(emptyList())

        assertEquals(originalJson, result)
    }

    @Test
    fun `mergeCustomBody with simple keys should merge correctly`() {
        val originalJson = buildJsonObject {
            put("existingKey", "existingValue")
        }

        val customBodies = listOf(
            CustomBody("newKey", JsonPrimitive("newValue")),
            CustomBody("numberKey", JsonPrimitive(42))
        )

        val result = originalJson.mergeCustomBody(customBodies)

        assertEquals("existingValue", result["existingKey"]?.toString()?.trim('"'))
        assertEquals("newValue", result["newKey"]?.toString()?.trim('"'))
        assertEquals("42", result["numberKey"]?.toString())
    }

    @Test
    fun `mergeCustomBody should override existing simple keys`() {
        val originalJson = buildJsonObject {
            put("key1", "oldValue")
        }

        val customBodies = listOf(
            CustomBody("key1", JsonPrimitive("newValue"))
        )

        val result = originalJson.mergeCustomBody(customBodies)

        assertEquals("newValue", result["key1"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should merge nested JsonObjects`() {
        val originalJson = buildJsonObject {
            put("config", buildJsonObject {
                put("setting1", "value1")
                put("setting2", "value2")
            })
            put("simpleKey", "simpleValue")
        }

        val nestedJsonValue = buildJsonObject {
            put("setting2", "updatedValue")
            put("setting3", "newValue")
        }

        val customBodies = listOf(
            CustomBody("config", nestedJsonValue)
        )

        val result = originalJson.mergeCustomBody(customBodies)

        val config = result["config"] as JsonObject
        assertEquals("value1", config["setting1"]?.toString()?.trim('"'))
        assertEquals("updatedValue", config["setting2"]?.toString()?.trim('"'))
        assertEquals("newValue", config["setting3"]?.toString()?.trim('"'))
        assertEquals("simpleValue", result["simpleKey"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should handle deeply nested JsonObjects`() {
        val originalJson = buildJsonObject {
            put("level1", buildJsonObject {
                put("level2", buildJsonObject {
                    put("setting1", "original")
                })
            })
        }

        val nestedValue = buildJsonObject {
            put("level2", buildJsonObject {
                put("setting1", "updated")
                put("setting2", "new")
            })
        }

        val customBodies = listOf(
            CustomBody("level1", nestedValue)
        )

        val result = originalJson.mergeCustomBody(customBodies)

        val level1 = result["level1"] as JsonObject
        val level2 = level1["level2"] as JsonObject

        assertEquals("updated", level2["setting1"]?.toString()?.trim('"'))
        assertEquals("new", level2["setting2"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should ignore empty keys`() {
        val originalJson = buildJsonObject {
            put("key1", "value1")
        }

        val customBodies = listOf(
            CustomBody("", JsonPrimitive("ignored")),
            CustomBody("key2", JsonPrimitive("value2"))
        )

        val result = originalJson.mergeCustomBody(customBodies)

        assertEquals(2, result.size)
        assertEquals("value1", result["key1"]?.toString()?.trim('"'))
        assertEquals("value2", result["key2"]?.toString()?.trim('"'))
    }

    @Test
    fun `redactSecrets should mask secret-named keys regardless of nesting depth`() {
        val body = buildJsonObject {
            put("model", "gpt-5")
            put("api_key", "sk-topsecret")
            put("nested", buildJsonObject {
                put("private_key", "-----BEGIN KEY-----")
                put("prompt", "not a secret")
            })
        }

        val redacted = redactSecrets(body) as JsonObject
        val nested = redacted["nested"] as JsonObject

        assertEquals("gpt-5", redacted["model"]?.toString()?.trim('"'))
        assertEquals("\"***\"", redacted["api_key"].toString())
        assertEquals("\"***\"", nested["private_key"].toString())
        assertEquals("not a secret", nested["prompt"]?.toString()?.trim('"'))
    }

    @Test
    fun `redactSecrets should leave non-secret keys and non-string values untouched`() {
        val body = buildJsonObject {
            put("temperature", 0.7)
            put("stream", true)
            put("token_count", 42)
        }

        val redacted = redactSecrets(body) as JsonObject

        assertEquals(body, redacted)
    }

    @Test
    fun `redactSecrets should mask bare secret keys`() {
        // 旧版只认 `(?:^|_)...$`，裸键 token / privateKey / authorization 全漏
        val body = buildJsonObject {
            put("token", "tok-123")
            put("privateKey", "-----BEGIN KEY-----")
            put("authorization", "Bearer <redacted>")
            put("key", "some-key")
            put("password", "hunter2")
        }

        val redacted = redactSecrets(body) as JsonObject

        for ((k, _) in body) {
            assertEquals("\"***\"", redacted[k].toString())
        }
    }

    @Test
    fun `redactSecrets should mask camelCase suffix keys`() {
        // 驼峰后缀键：旧版要求 ^ 或 _ 开头，accessToken / clientSecret 全漏
        val body = buildJsonObject {
            put("accessToken", "ya29.token")
            put("refreshToken", "1//refresh")
            put("clientSecret", "oauth-secret")
            put("apiKey", "sk-live")
            put("myApiKey", "tavily-key")
            put("x-api-key", "hdr-key")
        }

        val redacted = redactSecrets(body) as JsonObject

        for ((k, _) in body) {
            assertEquals("\"***\"", redacted[k].toString())
        }
    }

    @Test
    fun `redactSecrets should redact pair arrays by name`() {
        // MCP 请求头序列化成 [{"first": ..., "second": ...}]：name 本身不是敏感键，
        // 只按 key 脱敏会把整对原样放过
        val body = buildJsonObject {
            put("headers", buildJsonArray {
                add(buildJsonObject {
                    put("first", "Authorization")
                    put("second", "Bearer <redacted>")
                })
                add(buildJsonObject {
                    put("first", "Content-Type")
                    put("second", "application/json")
                })
                add(buildJsonObject {
                    put("first", "Cookie")
                    put("second", "session=abc")
                })
            })
        }

        val redacted = redactSecrets(body) as JsonObject
        val headers = redacted["headers"] as JsonArray

        assertEquals("\"***\"", (headers[0] as JsonObject)["second"].toString())
        assertEquals("\"Authorization\"", (headers[0] as JsonObject)["first"].toString())
        assertEquals("\"application/json\"", (headers[1] as JsonObject)["second"].toString())
        assertEquals("\"***\"", (headers[2] as JsonObject)["second"].toString())
    }

    @Test
    fun `redactSecrets should redact name-value objects by name`() {
        // Provider 自定义头序列化成 [{"name": ..., "value": ...}]
        val body = buildJsonObject {
            put("customHeaders", buildJsonArray {
                add(buildJsonObject {
                    put("name", "x-api-key")
                    put("value", "hdr-secret")
                })
                add(buildJsonObject {
                    put("name", "Content-Type")
                    put("value", "application/json")
                })
            })
        }

        val redacted = redactSecrets(body) as JsonObject
        val headers = redacted["customHeaders"] as JsonArray

        assertEquals("\"***\"", (headers[0] as JsonObject)["value"].toString())
        assertEquals("\"x-api-key\"", (headers[0] as JsonObject)["name"].toString())
        assertEquals("\"application/json\"", (headers[1] as JsonObject)["value"].toString())
    }

    @Test
    fun `redactSecrets should redact key-value objects when the key names a secret`() {
        // CustomBody 形态 {"key": ..., "value": ...}：key 是字段名时 value 原样保留，
        // key 本身是敏感词时 value 是凭证
        val body = buildJsonObject {
            put("customBody", buildJsonArray {
                add(buildJsonObject {
                    put("key", "apiKey")
                    put("value", JsonPrimitive("sk-secret"))
                })
                add(buildJsonObject {
                    put("key", "model")
                    put("value", JsonPrimitive("gpt-5"))
                })
            })
        }

        val redacted = redactSecrets(body) as JsonObject
        val entries = redacted["customBody"] as JsonArray

        assertEquals("\"***\"", (entries[0] as JsonObject)["value"].toString())
        assertEquals("\"gpt-5\"", (entries[1] as JsonObject)["value"].toString())
    }

    @Test
    fun `redactSecrets should not over-mask lookalike keys`() {
        // "design" / "assign" 以 sign 结尾，但 sign 不是敏感词（词表只收 signature）
        val body = buildJsonObject {
            put("design", "blue")
            put("assign", "task-1")
            put("token_count", 42)
        }

        val redacted = redactSecrets(body) as JsonObject

        assertEquals(body, redacted)
    }

    @Test
    fun `redactSecrets masks keys ending in key even when the word is incidental`() {
        // 后缀匹配的已知代价：monkey 以 key 结尾，会被掩 —— 与 LogRedactor 的
        // URL query 后缀词表同一取舍（宁可误掩，不可漏掩），这里把行为钉死
        val body = buildJsonObject {
            put("monkey", "banana")
        }

        val redacted = redactSecrets(body) as JsonObject

        assertEquals("\"***\"", redacted["monkey"].toString())
    }
}
