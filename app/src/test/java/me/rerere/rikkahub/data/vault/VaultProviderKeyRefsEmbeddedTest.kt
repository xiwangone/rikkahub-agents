package me.rerere.rikkahub.data.vault

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `$$凭证名` 的**片段**解析契约（HTTP 头这类"前缀 + 引用"形态）。
 *
 * 背景：MCP 的 `Authorization` 头需要 `Bearer <key>`，而 [VaultProviderKeyRefs.resolveValue]
 * 只认整串引用；若只支持整值，用户只能把 `Bearer ` 前缀一起存进凭证库（值不纯净）。
 * [VaultProviderKeyRefs.resolveEmbedded] 补上片段形态，同时保留"未命中保持原样"的降级语义
 * —— 名字写错时能立刻从请求头看出来，而不是默默送出空值。
 */
class VaultProviderKeyRefsEmbeddedTest {

    @Before
    fun setUp() {
        VaultProviderKeyRefs.replaceCacheForTest(
            mapOf(
                "SIMULAR_BOX" to "Bearer cb_test_value",
                "PLAIN_KEY" to "sk-abc123",
                "WITH_FIELD" to "primary",
                "WITH_FIELD.header" to "X-Real-Header",
                "WITH_FIELD.custom.auth_token" to "done",
            )
        )
    }

    @After
    fun tearDown() {
        VaultProviderKeyRefs.replaceCacheForTest(emptyMap())
    }

    @Test
    fun `whole value reference still resolves`() {
        assertEquals("sk-abc123", VaultProviderKeyRefs.resolveEmbedded("\$\$PLAIN_KEY"))
    }

    @Test
    fun `prefix plus reference resolves the fragment only`() {
        // 凭证里已是完整头值（含 Bearer），前缀拼接形态也能用
        assertEquals("Bearer sk-abc123", VaultProviderKeyRefs.resolveEmbedded("Bearer \$\$PLAIN_KEY"))
    }

    @Test
    fun `multiple fragments in one value all resolve`() {
        assertEquals(
            "sk-abc123:primary",
            VaultProviderKeyRefs.resolveEmbedded("\$\$PLAIN_KEY:\$\$WITH_FIELD"),
        )
    }

    @Test
    fun `field reference resolves`() {
        assertEquals(
            "X-Real-Header",
            VaultProviderKeyRefs.resolveEmbedded("\$\$WITH_FIELD.header"),
        )
    }

    @Test
    fun `multi-level field reference resolves`() {
        // $$NAME.custom.key 这类多级自定义元数据字段不能被截断成 $$NAME.custom
        assertEquals(
            "Bearer done",
            VaultProviderKeyRefs.resolveEmbedded("Bearer \$\$WITH_FIELD.custom.auth_token"),
        )
    }

    @Test
    fun `unknown reference is left as is`() {
        assertEquals("Bearer \$\$NO_SUCH_NAME", VaultProviderKeyRefs.resolveEmbedded("Bearer \$\$NO_SUCH_NAME"))
    }

    @Test
    fun `plain text without reference is untouched`() {
        assertEquals("application/json", VaultProviderKeyRefs.resolveEmbedded("application/json"))
        // 单个 $ 不是引用前缀
        assertEquals("price: $100", VaultProviderKeyRefs.resolveEmbedded("price: $100"))
    }
}
