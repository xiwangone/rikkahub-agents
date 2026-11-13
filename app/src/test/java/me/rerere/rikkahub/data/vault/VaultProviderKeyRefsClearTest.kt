package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VaultProviderKeyRefs.clear] 的契约：清空凭证库后，内存里的 `$$引用` 缓存必须同步失效，
 * 否则已删凭证的明文会继续被解析出来，直到重启或下次保存/导入。
 */
class VaultProviderKeyRefsClearTest {

    @Test
    fun `clear drops all refs including field keys`() {
        VaultProviderKeyRefs.replaceCacheForTest(
            mapOf(
                "GITHUB_TOKEN" to "sk-secret",
                "GITHUB_TOKEN.api_key" to "field-value",
                "OTHER" to "other-value",
            )
        )
        assertTrue(VaultProviderKeyRefs.canResolve("GITHUB_TOKEN"))

        VaultProviderKeyRefs.clear()

        assertFalse(VaultProviderKeyRefs.canResolve("GITHUB_TOKEN"))
        assertFalse(VaultProviderKeyRefs.canResolve("GITHUB_TOKEN.api_key"))
        assertFalse(VaultProviderKeyRefs.canResolve("OTHER"))
        assertEquals("\$\$GITHUB_TOKEN", VaultProviderKeyRefs.resolveValue("\$\$GITHUB_TOKEN"))
        assertNull(VaultProviderKeyRefs.resolveOrNull("\$\$GITHUB_TOKEN"))
    }

    @Test
    fun `clear bumps revision so ref UI re-evaluates`() {
        VaultProviderKeyRefs.replaceCacheForTest(mapOf("GITHUB_TOKEN" to "sk-secret"))
        val before = VaultProviderKeyRefs.revision.value
        VaultProviderKeyRefs.clear()
        assertEquals(before + 1, VaultProviderKeyRefs.revision.value)
    }
}
