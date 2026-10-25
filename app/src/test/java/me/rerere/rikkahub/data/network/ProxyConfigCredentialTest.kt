package me.rerere.rikkahub.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigCredentialTest {
    private val syntheticVault = mapOf("SYNTHETIC_PROXY_PASSWORD" to "synthetic-proxy-password")
    private val resolve: (String) -> String? = { raw ->
        if (raw.startsWith("\$\$")) syntheticVault[raw.removePrefix("\$\$")] else raw
    }

    @Test
    fun referenceResolvesWithoutChangingLegacyLiteralPasswords() {
        assertEquals(
            "synthetic-proxy-password",
            resolveProxyPasswordForAuth("\$\$SYNTHETIC_PROXY_PASSWORD", resolve),
        )
        assertEquals("legacy-literal-password", resolveProxyPasswordForAuth("legacy-literal-password", resolve))
    }

    @Test
    fun missingReferenceFailsClosed() {
        assertNull(resolveProxyPasswordForAuth("\$\$MISSING_SYNTHETIC_PROXY_PASSWORD", resolve))
    }
}
