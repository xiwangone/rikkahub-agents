package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用匹配的边界契约。
 *
 * 这是"安全改名/删除"的判断依据：**误报**会让用户以为有引用而不敢改；
 * **漏报**会让改名切断真实引用。因此前缀相似的场景必须钉死。
 */
class VaultReferenceLocatorTest {

    @Test
    fun `matches exact reference in a header value`() {
        assertTrue(VaultReferenceLocator.mentions("Bearer \$\$GITHUB_TOKEN", "GITHUB_TOKEN"))
    }

    @Test
    fun `matches when reference is the whole value`() {
        assertTrue(VaultReferenceLocator.mentions("\$\$OPENAI_API_KEY", "OPENAI_API_KEY"))
    }

    @Test
    fun `does not match a longer name sharing the prefix`() {
        // `$$GITHUB_TOKEN` 不应被判定为引用了 `GITHUB_TOKEN` 之外的更长名字
        assertFalse(VaultReferenceLocator.mentions("\$\$GITHUB_TOKEN_1", "GITHUB_TOKEN"))
    }

    @Test
    fun `does not match plain text without the prefix`() {
        assertFalse(VaultReferenceLocator.mentions("GITHUB_TOKEN", "GITHUB_TOKEN"))
    }

    @Test
    fun `blank inputs never match`() {
        assertFalse(VaultReferenceLocator.mentions("", "GITHUB_TOKEN"))
        assertFalse(VaultReferenceLocator.mentions("\$\$GITHUB_TOKEN", ""))
    }

    @Test
    fun `matches inside a multi-value list`() {
        assertTrue(VaultReferenceLocator.mentions("\$\$KEY_A,\$\$KEY_B", "KEY_B"))
        assertFalse(VaultReferenceLocator.mentions("\$\$KEY_A,\$\$KEY_B", "KEY_C"))
    }
}
