package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the model-facing SSH host mapping and the session-cache
 * fingerprint. Only light seams are covered here:
 *
 * - [sshHostEntityFromSaveInput] is what save_ssh_host.execute calls, so a
 *   proxy_command key in the model input being ignored here IS the model
 *   path being refused (the tool's parameter schema has no proxy_command
 *   either — verified statically, see below).
 * - [SshAuth.cacheFingerprint] pins backwards compatibility.
 *
 * Not covered locally (they need the full app closure — DataStore, Koin,
 * MCP — which the local kotlinc-direct harness cannot compile; they run in
 * the Gradle unit test task instead):
 * - save_ssh_host.execute() itself (SshHostRepository.upsert logs via AppLog),
 * - the save_ssh_host parameter schema at runtime (no proxy_command in the
 *   schema; verified by grep on SshHostsTool.kt),
 * - list_ssh_hosts output (has_certificate / has_proxy_command).
 */
class SshHostsModelTest {

    @Test
    fun saveEntityIgnoresProxyCommand() {
        val entity = sshHostEntityFromSaveInput(
            buildJsonObject {
                put("name", "relay")
                put("host", "relay.example")
                put("port", 2222)
                put("user", "u")
                put("certificate", VALID_CERT_LINE)
                put("proxy_command", "cloudflared access ssh --hostname %h")
            },
            nowMs = 123L,
        )
        assertEquals("relay", entity.name)
        assertEquals("relay.example", entity.host)
        assertEquals(2222, entity.port)
        assertEquals(VALID_CERT_LINE, entity.certificate)
        assertNull(entity.proxyCommand)
        assertEquals(123L, entity.createdAtMs)
    }

    @Test
    fun saveEntityAcceptsNoneAuth() {
        val entity = sshHostEntityFromSaveInput(
            buildJsonObject {
                put("name", "relay")
                put("host", "relay.example")
                put("user", "u")
            },
            nowMs = 1L,
        )
        assertNull(entity.password)
        assertNull(entity.privateKey)
        assertNull(entity.vaultCredentialRef)
        assertNull(entity.certificate)
        assertNull(entity.proxyCommand)
    }

    @Test
    fun saveEntityVaultCertificateWinsOverLiteral() {
        val entity = sshHostEntityFromSaveInput(
            buildJsonObject {
                put("name", "relay")
                put("host", "relay.example")
                put("user", "u")
                put("certificate", VALID_CERT_LINE)
                put("vault_certificate", "MY_CERT")
            },
            nowMs = 1L,
        )
        assertNull(entity.certificate)
        assertEquals("MY_CERT", entity.vaultCertificateRef)
    }

    @Test
    fun cacheFingerprintBackwardsCompatible() {
        // Exact pins: SHA-256("none:"), SHA-256("key:k|pass:"), SHA-256("pw:p").
        assertEquals(
            "c8db7394b512b6c8274efc42dd03ff59b9fcb2cbaed8a135c9d8b71c0690a967",
            SshAuth().cacheFingerprint(),
        )
        assertEquals(
            "b064175665dea9b770ff39fb051e5f575e23229c34b2509d0a01466447cd5989",
            SshAuth(privateKey = "k").cacheFingerprint(),
        )
        assertEquals(
            "1565a71aaeeff03a13eb7e1394ed77e145f427b516597264187c4f1d24f6aa22",
            SshAuth(password = "p").cacheFingerprint(),
        )
    }

    @Test
    fun cacheFingerprintChangesWithNewFields() {
        val base = SshAuth(privateKey = "k").cacheFingerprint()
        val withCert = SshAuth(privateKey = "k", certificate = "cert").cacheFingerprint()
        val withProxy = SshAuth(privateKey = "k", proxyCommand = "proxy").cacheFingerprint()
        assertTrue(withCert != base)
        assertTrue(withProxy != base)
        assertTrue(withCert != withProxy)
        // Empty new fields keep the old fingerprint.
        assertEquals(base, SshAuth(privateKey = "k", certificate = "", proxyCommand = "").cacheFingerprint())
    }

    companion object {
        // Any non-blank certificate text; parsing is covered by SshAuthMethodsTest.
        private const val VALID_CERT_LINE = "ssh-ed25519-cert-v01@openssh.com AAAA test-user"
    }
}
