package me.rerere.rikkahub.data.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证名规范化的纯函数契约（纯 JVM，不碰 ICU：中文用注入的假音译）。
 *
 * 钉住三条：
 * - 中文先转拼音再折大写蛇形（七牛云 → QI_NIU_YUN），而不是旧的"非法字符一刀切"
 *   （中文整段被换成下划线、最后只剩 CRED，所有纯中文名撞同一个名字）；
 * - 退化名（CRED / CRED_UNNAMED / CRED_2）只来自"没有任何可用字符"的输入；
 * - 重名加 `_2`/`_3` 后缀，不超 64 字符。
 *
 * 真实 ICU 的行为（音节间是否空格、声调剥离）由 [CredentialNameNormalizerIcuTest] 钉。
 */
class CredentialNameNormalizerTest {

    /** 假音译：按音节拼（空格只留在音节之间，与真实 Han-Latin 的输出形态一致）。 */
    private val fakeTransliterate: (String) -> String = { raw ->
        buildString {
            var pendingSpace = false
            for (c in raw) {
                val syllable = when (c) {
                    '七' -> "qi"
                    '牛' -> "niu"
                    '云' -> "yun"
                    '商' -> "shang"
                    '汤' -> "tang"
                    '爱' -> "ai"
                    '思' -> "si"
                    else -> null
                }
                if (syllable != null) {
                    if (pendingSpace) append(" ")
                    append(syllable)
                    pendingSpace = true
                } else {
                    append(c)
                    pendingSpace = false
                }
            }
        }
    }

    @Test
    fun `ascii name is uppercased as before`() {
        assertEquals("DEEPSEEK_API_KEY", CredentialNameNormalizer.normalizeName("deepseek_api_key"))
    }

    @Test
    fun `chinese name becomes pinyin`() {
        assertEquals("QI_NIU_YUN", CredentialNameNormalizer.normalizeName("七牛云", fakeTransliterate))
    }

    @Test
    fun `mixed chinese and latin keeps both`() {
        assertEquals("QI_NIU_YUNAPI", CredentialNameNormalizer.normalizeName("七牛云API", fakeTransliterate))
    }

    @Test
    fun `empty input falls back to unnamed`() {
        assertEquals("CRED_UNNAMED", CredentialNameNormalizer.normalizeName(""))
        assertEquals("CRED_UNNAMED", CredentialNameNormalizer.normalizeName("   "))
    }

    @Test
    fun `symbols only fall back to unnamed`() {
        assertEquals("CRED_UNNAMED", CredentialNameNormalizer.normalizeName("!!!"))
    }

    @Test
    fun `leading digits get a prefix`() {
        assertEquals("CRED_123ABC", CredentialNameNormalizer.normalizeName("123abc"))
    }

    @Test
    fun `long names truncate to 64 chars`() {
        val name = CredentialNameNormalizer.normalizeName("a".repeat(100))
        assertEquals("A".repeat(64), name)
    }

    @Test
    fun `fold keeps spaces as underscores for live typing`() {
        assertEquals("QI_NIU_YUN", CredentialNameNormalizer.foldNameChars("七牛云", fakeTransliterate))
        assertEquals("ABC_DEF", CredentialNameNormalizer.foldNameChars("abc def"))
    }

    @Test
    fun `degenerate names are only the empty-input fallbacks`() {
        assertTrue(CredentialNameNormalizer.isDegenerateName("CRED"))
        assertTrue(CredentialNameNormalizer.isDegenerateName("CRED_UNNAMED"))
        assertTrue(CredentialNameNormalizer.isDegenerateName("CRED_2"))
        assertFalse(CredentialNameNormalizer.isDegenerateName("QI_NIU_YUN"))
        assertFalse(CredentialNameNormalizer.isDegenerateName("CRED_ABC"))
        assertFalse(CredentialNameNormalizer.isDegenerateName("MY_CRED"))
    }

    @Test
    fun `unique name appends numeric suffix`() = runBlocking {
        val taken = mutableSetOf("QI_NIU_YUN")
        assertEquals("QI_NIU_YUN_2", CredentialNameNormalizer.uniqueName("QI_NIU_YUN") { it in taken })
        taken += "QI_NIU_YUN_2"
        assertEquals("QI_NIU_YUN_3", CredentialNameNormalizer.uniqueName("QI_NIU_YUN") { it in taken })
    }

    @Test
    fun `unique name keeps a free base and respects the 64 char cap`() = runBlocking {
        assertEquals("FREE_NAME", CredentialNameNormalizer.uniqueName("FREE_NAME") { false })
        val longBase = "A".repeat(64)
        val suffixed = CredentialNameNormalizer.uniqueName(longBase) { it == longBase }
        assertEquals(64, suffixed.length)
        assertTrue(suffixed.endsWith("_2"))
    }
}
