package me.rerere.rikkahub.data.vault

import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
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

    // ---------- 字段引用（`$$名字.字段`）：反查要认得，否则改名/删除会漏报 ----------

    @Test
    fun `matches a credential referenced by field`() {
        // 改名前的反查若漏掉 `$$MY_S3.access_key_id`，改完名这处就静默失效了
        assertTrue(VaultReferenceLocator.mentions("\$\$MY_S3.access_key_id", "MY_S3"))
        assertTrue(VaultReferenceLocator.mentions("Bearer \$\$MY_S3.custom.平台", "MY_S3"))
    }

    @Test
    fun `does not attribute a field reference to a longer name sharing the prefix`() {
        assertFalse(VaultReferenceLocator.mentions("\$\$MY_S3.access_key_id", "MY_S3_ACCESS"))
        assertFalse(VaultReferenceLocator.mentions("\$\$MY_S3.access_key_id", "OTHER_KEY"))
    }

    @Test
    fun `still matches when the whole dotted string is itself a stored name`() {
        // 历史带点名字（整名优先容错）：反查同样认得这个整串，两处口径一致
        assertTrue(VaultReferenceLocator.mentions("\$\$MY_S3.access_key_id", "MY_S3.access_key_id"))
    }

    @Test
    fun `new provider credential fields participate in vault reference lookup`() {
        val ref = "\$\$SYNTHETIC_KEY"
        assertTrue(VaultReferenceLocator.mentions(
            VaultReferenceLocator.mediaSecrets(MediaGenerationProviderSetting.OpenAI(apiKey = ref)),
            "SYNTHETIC_KEY",
        ))
        assertTrue(VaultReferenceLocator.mentions(
            VaultReferenceLocator.searchSecrets(SearchServiceOptions.TavilyOptions(apiKey = ref)),
            "SYNTHETIC_KEY",
        ))
        assertTrue(VaultReferenceLocator.mentions(
            VaultReferenceLocator.searchSecrets(SearchServiceOptions.SearXNGOptions(password = ref)),
            "SYNTHETIC_KEY",
        ))
        assertTrue(VaultReferenceLocator.mentions(
            VaultReferenceLocator.asrSecrets(ASRProviderSetting.Step(apiKey = ref)),
            "SYNTHETIC_KEY",
        ))
        assertTrue(VaultReferenceLocator.mentions(
            VaultReferenceLocator.ttsSecrets(TTSProviderSetting.OpenAI(apiKey = ref)),
            "SYNTHETIC_KEY",
        ))
    }
}
