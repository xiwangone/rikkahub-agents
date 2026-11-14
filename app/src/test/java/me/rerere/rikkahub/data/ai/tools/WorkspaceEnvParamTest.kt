package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `resolveInjectedEnv` 的语义回归：与 ssh 工具（`readEnvParam`）保持一致 ——
 * `$$` 前缀按凭证名解析，其余值按字面值注入。
 *
 * 这里只覆盖不需要凭证库的分支（字面量 / 非法键 / 空引用 / 无 Koin 时的失败路径）：
 * 真实解密与授权门属于 CredentialResolver 的职责，另有其测试。
 */
class WorkspaceEnvParamTest {

    private fun env(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun params(env: kotlinx.serialization.json.JsonObject) = buildJsonObject { put("env", env) }

    @Test
    fun `literal values pass through without touching the vault`() = runBlocking {
        val (map, error) = resolveInjectedEnv(
            params(env("NODE_ENV" to "production", "GREETING" to "hello world")),
        )
        assertNull(error)
        assertEquals(mapOf("NODE_ENV" to "production", "GREETING" to "hello world"), map)
    }

    @Test
    fun `empty env object yields empty map and no error`() = runBlocking {
        val (map, error) = resolveInjectedEnv(buildJsonObject { })
        assertNull(error)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `invalid variable name is rejected instead of silently dropped`() = runBlocking {
        val (map, error) = resolveInjectedEnv(params(env("A-B" to "1")))
        assertTrue(map.isEmpty())
        assertNotNull(error)
        assertTrue(error!!.contains("invalid variable name"))
        assertTrue(error.contains("A-B"))
    }

    @Test
    fun `numeric primitive is injected as its text form`() = runBlocking {
        // 环境变量本质是字符串：与 ssh 侧（readEnvParam 用 contentOrNull）一致，数字/布尔按文本注入
        val p = buildJsonObject {
            put(
                "env",
                buildJsonObject { put("COUNT", JsonPrimitive(3)) },
            )
        }
        val (map, error) = resolveInjectedEnv(p)
        assertNull(error)
        assertEquals(mapOf("COUNT" to "3"), map)
    }

    @Test
    fun `null value is rejected`() = runBlocking {
        val p = buildJsonObject {
            put(
                "env",
                buildJsonObject { put("TOKEN", JsonNull) },
            )
        }
        val (_, error) = resolveInjectedEnv(p)
        assertNotNull(error)
        assertTrue(error!!.contains("must be a string"))
    }

    @Test
    fun `empty vault reference is rejected`() = runBlocking {
        val (_, error) = resolveInjectedEnv(params(env("TOKEN" to "\$\$")))
        assertNotNull(error)
        assertTrue(error!!.contains("empty vault reference"))
    }

    @Test
    fun `vault reference without koin fails loudly rather than falling back to the literal`() = runBlocking {
        // 没有 Koin 容器 → 凭证库不可用；必须是报错，不能把 "$$TOKEN" 当字面值写进环境
        val (map, error) = resolveInjectedEnv(params(env("TOKEN" to "\$\$SOME_CRED")))
        assertTrue(map.isEmpty())
        assertNotNull(error)
        assertTrue(error!!.contains("credential vault unavailable"))
    }

    @Test
    fun `mixed literal and reference still fails loudly when vault is unavailable`() = runBlocking {
        val (map, error) = resolveInjectedEnv(
            params(env("NODE_ENV" to "production", "TOKEN" to "\$\$SOME_CRED")),
        )
        assertTrue(map.isEmpty())
        assertNotNull(error)
    }
}
