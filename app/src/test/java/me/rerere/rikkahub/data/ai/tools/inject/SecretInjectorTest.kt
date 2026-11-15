package me.rerere.rikkahub.data.ai.tools.inject

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 统一注入服务（方案 D）的纯逻辑回归：凭证库以假解析器替身，覆盖四个通道共用的
 * 解析 / 护栏 / 解析失败 / 脱敏语义。
 */
class SecretInjectorTest {

    private val vault = mapOf(
        "GITHUB_TOKEN" to "ghp_abcdefSECRET123",
        "MY_S3.access_key_id" to "AKIAEXAMPLE0001",
    )
    private var resolveCalls = 0
    private var namesCalls = 0

    private fun injector(available: Boolean = true) = SecretInjector(
        credentialNames = { namesCalls++; setOf("GITHUB_TOKEN", "MY_S3") },
        resolverFactory = {
            if (!available) {
                null
            } else {
                SecretRefResolver { ref ->
                    resolveCalls++
                    vault[ref]?.let { RefOutcome.Granted(it) } ?: RefOutcome.Denied("Credential $ref not found")
                }
            }
        },
    )

    private fun env(vararg pairs: Pair<String, Any?>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonArray(emptyList())
            }
        },
    )

    private fun ok(r: InjectionResult): InjectedEnv = when (r) {
        is InjectionResult.Ok -> r.injected
        is InjectionResult.Failed -> { fail("expected Ok, got ${r.message}"); error("unreachable") }
    }

    private fun failed(r: InjectionResult): InjectionResult.Failed = when (r) {
        is InjectionResult.Failed -> r
        is InjectionResult.Ok -> { fail("expected Failed, got $r"); error("unreachable") }
    }

    private val ref = "\$\$"

    @Test
    fun `missing or empty env yields empty result without touching the vault`() = runBlocking {
        assertTrue(ok(injector().prepare(null)).isEmpty())
        assertTrue(ok(injector().prepare(JsonObject(emptyMap()))).isEmpty())
        assertEquals(0, namesCalls)
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `literals pass and numbers become text`() = runBlocking {
        val r = ok(injector().prepare(env("NODE_ENV" to "production", "COUNT" to 3, "ON" to true)))
        assertEquals(mapOf("NODE_ENV" to "production", "COUNT" to "3", "ON" to "true"), r.env)
        assertTrue(r.secrets.isEmpty())
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `invalid key is rejected before anything else`() = runBlocking {
        val f = failed(injector().prepare(env("A-B" to "1", "GH" to "${ref}GITHUB_TOKEN")))
        assertEquals(InjectionFailureKind.INVALID_ARGUMENT, f.kind)
        assertTrue(f.message.contains("invalid variable name"))
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `null and structured values are rejected`() = runBlocking {
        assertTrue(failed(injector().prepare(env("T" to null))).message.contains("must be a string"))
        assertTrue(failed(injector().prepare(env("T" to listOf<Int>()))).message.contains("must be a string"))
    }

    @Test
    fun `empty reference is rejected`() = runBlocking {
        val f = failed(injector().prepare(env("T" to "  $ref  ")))
        assertTrue(f.message.contains("empty vault reference"))
    }

    @Test
    fun `legacy credential-name literal is refused`() = runBlocking {
        val f = failed(injector().prepare(env("GH" to "GITHUB_TOKEN")))
        assertEquals(InjectionFailureKind.LEGACY_NAME, f.kind)
        assertTrue(f.message.startsWith("env injection refused"))
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `legacy guard is skipped when names are unavailable`() = runBlocking {
        val inj = SecretInjector(credentialNames = { emptySet() }, resolverFactory = { null })
        assertEquals(mapOf("GH" to "GITHUB_TOKEN"), ok(inj.prepare(env("GH" to "GITHUB_TOKEN"))).env)
    }

    @Test
    fun `reference without vault fails closed`() = runBlocking {
        val f = failed(injector(available = false).prepare(env("NODE_ENV" to "x", "GH" to "${ref}GITHUB_TOKEN")))
        assertEquals(InjectionFailureKind.VAULT_UNAVAILABLE, f.kind)
        assertTrue(f.message.contains("credential vault unavailable"))
    }

    @Test
    fun `denied reference fails the whole call with no partial env`() = runBlocking {
        val f = failed(injector().prepare(env("GH" to "${ref}GITHUB_TOKEN", "X" to "${ref}MISSING")))
        assertEquals(InjectionFailureKind.REF_DENIED, f.kind)
        assertTrue(f.message.contains("X -> Credential MISSING not found"))
    }

    @Test
    fun `granted references are injected in caller order and recorded for masking`() = runBlocking {
        val r = ok(
            injector().prepare(
                env("A" to "lit", "GH" to " ${ref}GITHUB_TOKEN ", "AK" to "${ref}MY_S3.access_key_id", "Z" to "z"),
            ),
        )
        assertEquals(listOf("A", "GH", "AK", "Z"), r.env.keys.toList())
        assertEquals("ghp_abcdefSECRET123", r.env["GH"])
        assertEquals("AKIAEXAMPLE0001", r.env["AK"])
        assertEquals(listOf("GITHUB_TOKEN", "MY_S3.access_key_id"), r.secrets.map { it.ref })
    }

    @Test
    fun `masking covers text and every string in nested json but not keys or literals`() = runBlocking {
        val r = ok(injector().prepare(env("GH" to "${ref}GITHUB_TOKEN", "MODE" to "production")))
        assertEquals("t=[masked:${ref}GITHUB_TOKEN] m=production", r.mask("t=ghp_abcdefSECRET123 m=production"))
        val payload = JsonObject(
            mapOf(
                "stdout" to JsonPrimitive("echo ghp_abcdefSECRET123"),
                "exit_code" to JsonPrimitive(0),
                "extra" to JsonObject(
                    mapOf("partial_stderr" to JsonArray(listOf(JsonPrimitive("x ghp_abcdefSECRET123 y")))),
                ),
                "ghp_abcdefSECRET123" to JsonPrimitive("key stays"),
            ),
        )
        val masked = r.maskJsonObject(payload)
        assertEquals(JsonPrimitive("echo [masked:${ref}GITHUB_TOKEN]"), masked["stdout"])
        assertEquals(JsonPrimitive(0), masked["exit_code"])
        assertTrue(masked.toString().contains("x [masked:${ref}GITHUB_TOKEN] y"))
        assertTrue(masked.containsKey("ghp_abcdefSECRET123")) // 键名不动（键名不来自命令输出）
    }

    @Test
    fun `masking json survives characters that json escaping would break`() = runBlocking {
        val inj = SecretInjector({ emptySet() }, { SecretRefResolver { RefOutcome.Granted("pa\"ss\\word\n9") } })
        val r = ok(inj.prepare(env("PW" to "${ref}DB_PASS")))
        val masked = r.maskJsonObject(JsonObject(mapOf("stdout" to JsonPrimitive("pw=pa\"ss\\word\n9!"))))
        assertEquals(JsonPrimitive("pw=[masked:${ref}DB_PASS]!"), masked["stdout"])
    }

    @Test
    fun `mask note is added only when some injected value is too short`() = runBlocking {
        val shortInj = SecretInjector({ emptySet() }, { SecretRefResolver { RefOutcome.Granted("123") } })
        val r = ok(shortInj.prepare(env("PIN" to "${ref}PIN")))
        val noted = r.maskJsonObjectWithNote(JsonObject(mapOf("stdout" to JsonPrimitive("pin 123"))), "mask_note")
        assertEquals(JsonPrimitive("pin 123"), noted["stdout"])
        assertTrue(noted.containsKey("mask_note"))

        val longR = ok(injector().prepare(env("GH" to "${ref}GITHUB_TOKEN")))
        val clean = longR.maskJsonObjectWithNote(JsonObject(mapOf("stdout" to JsonPrimitive("x"))), "mask_note")
        assertFalse(clean.containsKey("mask_note"))
    }

    @Test
    fun `toString of results never contains plaintext`() = runBlocking {
        val r = ok(injector().prepare(env("GH" to "${ref}GITHUB_TOKEN")))
        assertFalse(r.toString().contains("ghp_"))
        assertFalse(RefOutcome.Granted("ghp_abcdefSECRET123").toString().contains("ghp_"))
    }
}
