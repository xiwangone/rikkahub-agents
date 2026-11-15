package me.rerere.rikkahub.data.ai.tools.inject

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * env 注入两道护栏的纯逻辑回归（无 Android / Koin 依赖）：
 * 旧写法兼容护栏（字面值 = 凭证名）与注入值输出脱敏。
 */
class EnvInjectionSafetyTest {

    @After
    fun tearDown() = BackgroundInjectedSecrets.clearForTest()

    private val names = setOf("GITHUB_TOKEN", "MY_S3", "DEBUG")

    // ---------- 兼容护栏 ----------

    @Test
    fun `literal equal to an existing credential name is flagged`() {
        val hits = findLegacyCredentialNameLiterals(mapOf("GH" to "GITHUB_TOKEN"), names)
        assertEquals(listOf("GH" to "GITHUB_TOKEN"), hits)
    }

    @Test
    fun `literal of the form NAME dot field is flagged when NAME exists`() {
        val hits = findLegacyCredentialNameLiterals(mapOf("AK" to "MY_S3.access_key_id"), names)
        assertEquals(listOf("AK" to "MY_S3.access_key_id"), hits)
    }

    @Test
    fun `surrounding whitespace does not hide the legacy pattern`() {
        val hits = findLegacyCredentialNameLiterals(mapOf("GH" to "  GITHUB_TOKEN "), names)
        assertEquals(listOf("GH" to "GITHUB_TOKEN"), hits)
    }

    @Test
    fun `ordinary literals pass`() {
        val literals = mapOf(
            "NODE_ENV" to "production",
            "LOWER" to "github_token", // 大小写敏感：凭证名全大写
            "HOST" to "api.example.com",
            "UNKNOWN" to "NOT_A_CRED",
            "TRAILING_DOT" to "MY_S3.", // 点后为空不算字段引用
            "OTHER_DOT" to "OTHER.field", // 名字不存在
            "EMPTY" to "",
        )
        assertTrue(findLegacyCredentialNameLiterals(literals, names).isEmpty())
    }

    @Test
    fun `no credential names means no hits`() {
        assertTrue(findLegacyCredentialNameLiterals(mapOf("GH" to "GITHUB_TOKEN"), emptySet()).isEmpty())
    }

    @Test
    fun `error message tells the model how to rewrite`() {
        val msg = legacyCredentialNameError(listOf("GH" to "GITHUB_TOKEN"))
        assertTrue(msg.contains("GH=\"GITHUB_TOKEN\""))
        assertTrue(msg.contains("\"GH\": \"\$\$GITHUB_TOKEN\""))
        assertTrue(msg.contains("LITERALLY"))
    }

    // ---------- 输出脱敏 ----------

    @Test
    fun `injected primary value is replaced with a named placeholder`() {
        val s = listOf(InjectedSecret("GH", "GITHUB_TOKEN", "ghp_abcdefSECRET123"))
        val out = maskInjectedValues("token=ghp_abcdefSECRET123\nok", s)
        assertEquals("token=[masked:\$\$GITHUB_TOKEN]\nok", out)
    }

    @Test
    fun `trimmed form of the value is also masked`() {
        val s = listOf(InjectedSecret("PW", "DB_PASS", "  hunter22\n"))
        assertEquals("pw=[masked:\$\$DB_PASS]", maskInjectedValues("pw=hunter22", s))
    }

    @Test
    fun `too short primary values are left alone and counted`() {
        val s = listOf(InjectedSecret("PIN", "PIN", "123"))
        assertEquals("pin is 123", maskInjectedValues("pin is 123", s))
        assertEquals(1, countUnmaskableInjected(s))
    }

    @Test
    fun `four char primary value is masked`() {
        val s = listOf(InjectedSecret("PIN", "PIN", "9f3k"))
        assertEquals("[masked:\$\$PIN]", maskInjectedValues("9f3k", s))
        assertEquals(0, countUnmaskableInjected(s))
    }

    @Test
    fun `field values use the higher threshold`() {
        val shortField = InjectedSecret("REGION", "MY_S3.region", "eu-west")   // 7 < 8
        val longField = InjectedSecret("AK", "MY_S3.access_key_id", "AKIAEXAMPLE0001")
        val out = maskInjectedValues("region=eu-west ak=AKIAEXAMPLE0001", listOf(shortField, longField))
        assertEquals("region=eu-west ak=[masked:\$\$MY_S3.access_key_id]", out)
        assertEquals(1, countUnmaskableInjected(listOf(shortField, longField)))
    }

    @Test
    fun `longer values are replaced before their substrings`() {
        val s = listOf(
            InjectedSecret("A", "SHORT", "abcd"),
            InjectedSecret("B", "LONG", "abcd-efgh-ijkl"),
        )
        assertEquals("[masked:\$\$LONG] [masked:\$\$SHORT]", maskInjectedValues("abcd-efgh-ijkl abcd", s))
    }

    @Test
    fun `no secrets leaves text untouched`() {
        assertEquals("hello", maskInjectedValues("hello", emptyList()))
    }

    @Test
    fun `toString never prints the plaintext`() {
        val s = InjectedSecret("GH", "GITHUB_TOKEN", "ghp_abcdefSECRET123")
        assertFalse(s.toString().contains("ghp_"))
    }

    // ---------- 后台任务登记 ----------

    @Test
    fun `background registry is keyed by workspace and task and cleared on remove`() {
        val s = listOf(InjectedSecret("GH", "GITHUB_TOKEN", "ghp_abcdefSECRET123"))
        BackgroundInjectedSecrets.register("ws1", "t1", s)
        assertEquals(s, BackgroundInjectedSecrets.get("ws1", "t1"))
        assertTrue(BackgroundInjectedSecrets.get("ws2", "t1").isEmpty())
        BackgroundInjectedSecrets.remove("ws1", "t1")
        assertTrue(BackgroundInjectedSecrets.get("ws1", "t1").isEmpty())
    }

    @Test
    fun `background registry is bounded`() {
        val s = listOf(InjectedSecret("GH", "GITHUB_TOKEN", "ghp_abcdefSECRET123"))
        repeat(200) { BackgroundInjectedSecrets.register("ws", "t$it", s) }
        assertTrue(BackgroundInjectedSecrets.get("ws", "t0").isEmpty())
        assertEquals(s, BackgroundInjectedSecrets.get("ws", "t199"))
    }
}
