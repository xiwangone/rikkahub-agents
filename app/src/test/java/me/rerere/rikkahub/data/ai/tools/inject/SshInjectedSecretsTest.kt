package me.rerere.rikkahub.data.ai.tools.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 钉住 SSH 侧注入值登记的口径：
 * `ssh_exec` 注入的凭证值要在**后续独立的** `ssh_job_poll` 调用里也能掩掉。
 *
 * 纯 JVM 单测：登记 / 读取 / 按 host 淘汰 / 淘汰后永久掩码规则。
 */
class SshInjectedSecretsTest {

    @Before
    fun setUp() {
        SshInjectedSecrets.clearForTest()
    }

    private fun secret(env: String = "TOKEN", ref: String = "GITHUB_TOKEN", value: String) =
        InjectedSecret(envName = env, ref = ref, value = value)

    @Test
    fun `register then get returns secrets for same host`() {
        SshInjectedSecrets.register("host-a", listOf(secret(value = "super-secret-value")))
        val got = SshInjectedSecrets.get("host-a")
        assertEquals(1, got.size)
        assertEquals("super-secret-value", got[0].value)
    }

    @Test
    fun `get is isolated per host`() {
        SshInjectedSecrets.register("host-a", listOf(secret(value = "value-a-aaaa")))
        SshInjectedSecrets.register("host-b", listOf(secret(value = "value-b-bbbb")))
        assertEquals("value-a-aaaa", SshInjectedSecrets.get("host-a").single().value)
        assertEquals("value-b-bbbb", SshInjectedSecrets.get("host-b").single().value)
        assertTrue(SshInjectedSecrets.get("host-c").isEmpty())
    }

    @Test
    fun `register dedupes same ref and value`() {
        val s = secret(value = "same-value-1234")
        SshInjectedSecrets.register("host-a", listOf(s))
        SshInjectedSecrets.register("host-a", listOf(s))
        assertEquals(1, SshInjectedSecrets.get("host-a").size)
    }

    @Test
    fun `empty secrets or blank host are ignored`() {
        SshInjectedSecrets.register("host-a", emptyList())
        SshInjectedSecrets.register("  ", listOf(secret(value = "value-a-aaaa")))
        assertTrue(SshInjectedSecrets.get("host-a").isEmpty())
    }

    @Test
    fun `per-host overflow moves oldest into retained rules`() {
        // 单 host 上限 16 条：第 17 条起最早的转永久掩码集
        for (i in 0 until 17) {
            SshInjectedSecrets.register("host-a", listOf(secret(value = "value-%04d-xxxx".format(i))))
        }
        val got = SshInjectedSecrets.get("host-a")
        assertEquals(16, got.size)
        // 最早的 value-0000 已淘汰出活动表
        assertTrue(got.none { it.value == "value-0000-xxxx" })
        assertEquals("value-0016-xxxx", got.last().value)
        // 但脱敏能力不丢：永久掩码集里有它，占位符带原引用名
        val rules = SshInjectedSecrets.retainedRules()
        assertTrue(rules.containsKey("value-0000-xxxx"))
        assertEquals(injectedSecretPlaceholder("GITHUB_TOKEN"), rules["value-0000-xxxx"])
        // 掩码端到端可用
        val masked = maskInjectedValues(
            "token is value-0000-xxxx",
            secrets = got,
            legacyRules = rules,
        )
        assertFalse(masked.contains("value-0000-xxxx"))
        assertTrue(masked.contains("[masked:\$\$GITHUB_TOKEN]"))
    }

    @Test
    fun `host eviction beyond max keeps masking via retained rules`() {
        // 32 台主机上限：第 33 台把最早的 host-a 挤出活动表
        for (i in 0 until 33) {
            SshInjectedSecrets.register("host-%02d".format(i), listOf(secret(value = "host-%02d-secret-1".format(i))))
        }
        assertTrue(SshInjectedSecrets.get("host-00").isEmpty())
        assertEquals("host-32-secret-1", SshInjectedSecrets.get("host-32").single().value)
        val rules = SshInjectedSecrets.retainedRules()
        assertTrue(rules.containsKey("host-00-secret-1"))
        // 淘汰后的值仍能被掩
        val masked = maskInjectedValues("out: host-00-secret-1", secrets = emptyList(), legacyRules = rules)
        assertNotEquals("out: host-00-secret-1", masked)
        assertFalse(masked.contains("host-00-secret-1"))
    }

    @Test
    fun `toString never leaks plaintext`() {
        val s = secret(value = "plaintext-secret-1")
        assertFalse(s.toString().contains("plaintext-secret-1"))
    }
}
