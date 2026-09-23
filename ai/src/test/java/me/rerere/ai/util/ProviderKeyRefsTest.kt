package me.rerere.ai.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * provider 密钥「引用展开」的契约测试。
 *
 * 锁死两条最容易被后续改动破坏的性质：
 * 1. **未注入解析器 / 引用未命中 → 原样返回**（零风险回退：配置写错时显式暴露，
 *    而不是静默变成空 key 造成难排查的 401）；
 * 2. **多 key 轮询前每个 token 都过一遍引用展开**（否则 `$$名` 会被当字面 key 发出去）。
 *
 * 注意：`ProviderKeyRefs.resolve` 是全局钩子，测试结束必须还原，避免污染其它用例。
 */
class ProviderKeyRefsTest {

    @After
    fun tearDown() {
        ProviderKeyRefs.resolve = null
        ProviderKeyRefs.onExpanded = null
    }

    // ── expand 的四态 ────────────────────────────────────────────────

    @Test
    fun `non reference token is returned as is`() {
        ProviderKeyRefs.resolve = { name -> "SHOULD_NOT_BE_USED_$name" }
        assertEquals("sk-abc123", ProviderKeyRefs.expand("sk-abc123"))
    }

    @Test
    fun `reference resolves to the real value`() {
        ProviderKeyRefs.resolve = { name -> if (name == "OPENAI_API_KEY") "sk-real" else null }
        assertEquals("sk-real", ProviderKeyRefs.expand("\$\$OPENAI_API_KEY"))
    }

    @Test
    fun `missing reference is kept verbatim`() {
        ProviderKeyRefs.resolve = { null }
        assertEquals("\$\$MISSING", ProviderKeyRefs.expand("\$\$MISSING"))
    }

    @Test
    fun `without a resolver everything stays verbatim`() {
        ProviderKeyRefs.resolve = null
        assertEquals("\$\$OPENAI_API_KEY", ProviderKeyRefs.expand("\$\$OPENAI_API_KEY"))
    }

    // ── 展开通知（上层写审计用）────────────────────────────────────

    @Test
    fun `onExpanded reports the credential name only when a reference resolves`() {
        val seen = mutableListOf<String>()
        ProviderKeyRefs.resolve = { name -> if (name == "OPENAI_API_KEY") "sk-real" else null }
        ProviderKeyRefs.onExpanded = { name -> seen.add(name) }

        assertEquals("sk-real", ProviderKeyRefs.expand("\$\$OPENAI_API_KEY"))
        assertEquals("\$\$MISSING", ProviderKeyRefs.expand("\$\$MISSING"))
        assertEquals("sk-abc123", ProviderKeyRefs.expand("sk-abc123"))
        assertEquals(listOf("OPENAI_API_KEY"), seen)
    }

    @Test
    fun `onExpanded stays silent without a resolver`() {
        val seen = mutableListOf<String>()
        ProviderKeyRefs.resolve = null
        ProviderKeyRefs.onExpanded = { name -> seen.add(name) }
        assertEquals("\$\$OPENAI_API_KEY", ProviderKeyRefs.expand("\$\$OPENAI_API_KEY"))
        assertTrue(seen.isEmpty())
    }

    // ── 轮询（splitKey 经 KeyRoulette.next 间接覆盖）────────────────────

    @Test
    fun `single key is returned unchanged`() {
        assertEquals("sk-only", KeyRoulette.default().next("sk-only", "p1"))
    }

    @Test
    fun `multiple keys split on comma space and newline`() {
        val picked = KeyRoulette.default().next("k1, k2\n k3", "p1")
        assertTrue("unexpected pick: $picked", picked in setOf("k1", "k2", "k3"))
    }

    @Test
    fun `references take part in roulette as real values`() {
        ProviderKeyRefs.resolve = { name ->
            when (name) {
                "A" -> "k1"
                "B" -> "k2"
                else -> null
            }
        }
        val picked = KeyRoulette.default().next("\$\$A,\$\$B", "p1")
        assertTrue("should be a resolved value, got $picked", picked in setOf("k1", "k2"))
    }

    @Test
    fun `duplicate keys collapse and never empty the list`() {
        assertEquals("k1", KeyRoulette.default().next("k1, k1", "p1"))
    }

    @Test
    fun `unresolved reference surfaces verbatim instead of empty`() {
        ProviderKeyRefs.resolve = { null }
        assertEquals("\$\$MISSING", KeyRoulette.default().next("\$\$MISSING", "p1"))
    }
}
