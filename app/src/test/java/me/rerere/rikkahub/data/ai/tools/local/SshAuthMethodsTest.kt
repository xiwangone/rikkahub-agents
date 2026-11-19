package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Pure-JVM tests for the SSH auth methods (none auth, OpenSSH certificates,
 * proxy commands). No Android, no network, no real server: certificates are
 * fixed test fixtures (public material only, no private key is embedded),
 * keys are generated at runtime.
 */
class SshAuthMethodsTest {

    @Test
    fun emptyAuthUsesNoneFirst() {
        assertEquals(
            "none,publickey,keyboard-interactive,password",
            preferredAuthentications(SshAuth()),
        )
        assertTrue(SshAuth().isUsable())
    }

    @Test
    fun credentialAuthNeverOffersNone() {
        val expected = "publickey,keyboard-interactive,password"
        assertEquals(expected, preferredAuthentications(SshAuth(password = "pw")))
        assertEquals(expected, preferredAuthentications(SshAuth(privateKey = "key")))
        assertEquals(expected, preferredAuthentications(SshAuth(certificate = VALID_CERT)))
    }

    @Test
    fun parseValidCertificate() {
        val info = parseSshCertificate(VALID_CERT)
        assertEquals("ssh-ed25519-cert-v01@openssh.com", info.keyType)
        assertTrue(info.isUserCertificate)
        assertEquals(1795019426L, info.validAfterEpochSeconds)
        assertEquals(2109515726L, info.validBeforeEpochSeconds)
    }

    @Test
    fun validateValidCertificate() {
        val info = validateSshCertificate(VALID_CERT)
        assertTrue(info.isUserCertificate)
    }

    @Test
    fun expiredCertificateFailsValidation() {
        val e = assertThrows(JSchException::class.java) {
            validateSshCertificate(EXPIRED_CERT)
        }
        assertTrue(e.message!!.contains("expired"))
    }

    @Test
    fun futureCertificateFailsValidation() {
        val e = assertThrows(JSchException::class.java) {
            validateSshCertificate(FUTURE_CERT)
        }
        assertTrue(e.message!!.contains("not yet valid"))
    }

    @Test
    fun hostCertificateRejected() {
        val e = assertThrows(JSchException::class.java) {
            validateSshCertificate(HOST_CERT)
        }
        assertTrue(e.message!!.contains("host certificate"))
    }

    @Test
    fun garbageCertificateFailsParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSshCertificate("not a certificate")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSshCertificate("ssh-ed25519-cert-v01@openssh.com AAAA")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSshCertificate("")
        }
    }

    @Test
    fun loadIdentityWithValidCertificate() {
        val jsch = JSch()
        installSshIdentity(jsch, SshAuth(privateKey = generatePrivateKeyPem(), certificate = VALID_CERT))
        val identities = jsch.identityRepository.identities
        assertEquals(1, identities.size)
        assertEquals("ssh-ed25519-cert-v01@openssh.com", identities[0].algName)
    }

    @Test
    fun loadIdentityWithExpiredCertificateThrowsBeforeLoading() {
        val jsch = JSch()
        val e = assertThrows(JSchException::class.java) {
            installSshIdentity(jsch, SshAuth(privateKey = generatePrivateKeyPem(), certificate = EXPIRED_CERT))
        }
        assertTrue(e.message!!.contains("expired"))
        assertTrue(jsch.identityRepository.identities.isEmpty())
    }

    @Test
    fun loadIdentityWithCertificateButNoKeyThrows() {
        val jsch = JSch()
        val e = assertThrows(JSchException::class.java) {
            installSshIdentity(jsch, SshAuth(certificate = VALID_CERT))
        }
        assertTrue(e.message!!.contains("requires a private key"))
        assertTrue(jsch.identityRepository.identities.isEmpty())
    }

    @Test
    fun loadIdentityWithGarbageCertificateThrows() {
        val jsch = JSch()
        assertThrows(JSchException::class.java) {
            installSshIdentity(jsch, SshAuth(privateKey = generatePrivateKeyPem(), certificate = "not a certificate"))
        }
        assertTrue(jsch.identityRepository.identities.isEmpty())
    }

    @Test
    fun loadIdentityWithPlainKeyUnchanged() {
        val jsch = JSch()
        installSshIdentity(jsch, SshAuth(privateKey = generatePrivateKeyPem()))
        val identities = jsch.identityRepository.identities
        assertEquals(1, identities.size)
        assertEquals("ssh-rsa", identities[0].algName)
    }

    @Test
    fun loadIdentityWithNothingLoadsNothing() {
        val jsch = JSch()
        installSshIdentity(jsch, SshAuth())
        assertTrue(jsch.identityRepository.identities.isEmpty())
    }

    @Test
    fun splitProxyCommandBasic() {
        assertEquals(
            listOf("cloudflared", "access", "ssh", "--hostname", "relay.example"),
            splitProxyCommand("cloudflared access ssh --hostname %h", "relay.example", 2222),
        )
    }

    @Test
    fun splitProxyCommandSubstitutesPort() {
        assertEquals(
            listOf("ssh", "-W", "relay.example:2222", "bastion"),
            splitProxyCommand("ssh -W %h:%p bastion", "relay.example", 2222),
        )
    }

    @Test
    fun splitProxyCommandRespectsQuotes() {
        assertEquals(
            listOf("ssh", "-o", "ProxyCommand=foo bar", "relay.example"),
            splitProxyCommand("ssh -o \"ProxyCommand=foo bar\" %h", "relay.example", 22),
        )
        assertEquals(
            listOf("ssh", "a b", "relay.example"),
            splitProxyCommand("ssh 'a b' %h", "relay.example", 22),
        )
    }

    @Test
    fun splitProxyCommandRejectsEmptyAndUnbalanced() {
        assertThrows(IllegalArgumentException::class.java) {
            splitProxyCommand("", "relay.example", 22)
        }
        assertThrows(IllegalArgumentException::class.java) {
            splitProxyCommand("ssh 'unbalanced", "relay.example", 22)
        }
    }

    @Test
    fun processProxyRoundTripsBytes() {
        val proxy = ProcessProxyCommand(listOf("cat"))
        proxy.connect(null, "localhost", 22, 0)
        proxy.outputStream.write("hello".toByteArray(Charsets.UTF_8))
        proxy.outputStream.flush()
        val buf = ByteArray(5)
        var read = 0
        while (read < 5) {
            val n = proxy.inputStream.read(buf, read, 5 - read)
            check(n >= 0) { "proxy stream closed early" }
            read += n
        }
        assertEquals("hello", String(buf, Charsets.UTF_8))
        proxy.close()
        assertThrows(IllegalStateException::class.java) {
            proxy.inputStream
        }
    }

    /**
     * Runtime-generated RSA key; never the fixture certificates' real key.
     * (Ed25519 key pairs cannot be written out by this JSch version:
     * KeyPairEdDSA.getPrivateKey throws UnsupportedOperationException, so the
     * generated key is RSA. The certificate tests only need *a* key — the
     * certificate, not the key, decides the loaded identity's algorithm.)
     */
    private fun generatePrivateKeyPem(): String {
        val jsch = JSch()
        val pair = KeyPair.genKeyPair(jsch, KeyPair.RSA)
        val out = ByteArrayOutputStream()
        try {
            pair.writePrivateKey(out)
            return out.toString(Charsets.UTF_8.name())
        } finally {
            pair.dispose()
        }
    }

    companion object {
        // OpenSSH user certificate, valid 2026-11-18T16:30:26Z to 2036-11-05T16:35:26Z.
        // Public material only; the matching private key is not embedded anywhere.
        private const val VALID_CERT =
            "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIBiHkwJ7QJutc8srmUpxYw7h7nKmIV4dKyZXnPBuv2P3AAAAIGDJ/oYJJ9nJCtVVzqQJWrAOYp+PgVq9qFKEDiiWgp1TAAAAAAAAAAoAAAABAAAACXRlc3QtdXNlcgAAAAwAAAAIdGVzdHVzZXIAAAAAav3SogAAAAB9vKfOAAAAAAAAAIIAAAAVcGVybWl0LVgxMS1mb3J3YXJkaW5nAAAAAAAAABdwZXJtaXQtYWdlbnQtZm9yd2FyZGluZwAAAAAAAAAWcGVybWl0LXBvcnQtZm9yd2FyZGluZwAAAAAAAAAKcGVybWl0LXB0eQAAAAAAAAAOcGVybWl0LXVzZXItcmMAAAAAAAAAAAAAADMAAAALc3NoLWVkMjU1MTkAAAAgLpUdeJAoImP11yYNxXKXJqu4nS9//5URzxHdouDDmyMAAABTAAAAC3NzaC1lZDI1NTE5AAAAQAmoL9+WhUgTpWHifoXdMqzcgsyoBQ/0RjZdsuBwffB9oWhE/I99Zesan30KpmyJRDC8QHBtIe7FFk/jmQZEcgQ= test-user"

        // Same key, expired 2026-11-04T16:35:26Z to 2026-11-11T16:35:26Z.
        private const val EXPIRED_CERT =
            "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAICX+jcum1iDnoOhtOdnpOadGHEdjy8WANC5AojEGU8XCAAAAIGDJ/oYJJ9nJCtVVzqQJWrAOYp+PgVq9qFKEDiiWgp1TAAAAAAAAAAsAAAABAAAACXRlc3QtdXNlcgAAAAwAAAAIdGVzdHVzZXIAAAAAautezgAAAABq9JlOAAAAAAAAAIIAAAAVcGVybWl0LVgxMS1mb3J3YXJkaW5nAAAAAAAAABdwZXJtaXQtYWdlbnQtZm9yd2FyZGluZwAAAAAAAAAWcGVybWl0LXBvcnQtZm9yd2FyZGluZwAAAAAAAAAKcGVybWl0LXB0eQAAAAAAAAAOcGVybWl0LXVzZXItcmMAAAAAAAAAAAAAADMAAAALc3NoLWVkMjU1MTkAAAAgLpUdeJAoImP11yYNxXKXJqu4nS9//5URzxHdouDDmyMAAABTAAAAC3NzaC1lZDI1NTE5AAAAQGjSZdO+NHKQtPel01OUlAAAmh1EfuoGZU+5Gah+OTsX7gyUK8c8uZ5v1dDQcG5lmbeKN7eaE6SpUQDO+R+P3gI= test-user"

        // Same key, not yet valid: 2036-11-05T16:35:26Z to 2046-10-24T16:35:26Z.
        private const val FUTURE_CERT =
            "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIDcWuuUPAXcUNYHYtiCuXE+sROGpfM2MJuX/QGrLSkI1AAAAIGDJ/oYJJ9nJCtVVzqQJWrAOYp+PgVq9qFKEDiiWgp1TAAAAAAAAAAwAAAABAAAACXRlc3QtdXNlcgAAAAwAAAAIdGVzdHVzZXIAAAAAfbynzgAAAACQe3vOAAAAAAAAAIIAAAAVcGVybWl0LVgxMS1mb3J3YXJkaW5nAAAAAAAAABdwZXJtaXQtYWdlbnQtZm9yd2FyZGluZwAAAAAAAAAWcGVybWl0LXBvcnQtZm9yd2FyZGluZwAAAAAAAAAKcGVybWl0LXB0eQAAAAAAAAAOcGVybWl0LXVzZXItcmMAAAAAAAAAAAAAADMAAAALc3NoLWVkMjU1MTkAAAAgLpUdeJAoImP11yYNxXKXJqu4nS9//5URzxHdouDDmyMAAABTAAAAC3NzaC1lZDI1NTE5AAAAQOnDLmAolKoiq7yxRdaOWDfuv5rkGkrIV4MF7OyOTCe8i+/1AX9BtfVMNH8FSuG7IbUoKcaD1Ji3Z0YOs7aR7wA= test-user"

        // Host certificate (type 2), valid 2026-11-18T16:30:26Z to 2036-11-05T16:35:26Z.
        private const val HOST_CERT =
            "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAINL3vcToY53o4l09p2WKO5KEnz3I83V+IIxhjJS1xJI4AAAAIGDJ/oYJJ9nJCtVVzqQJWrAOYp+PgVq9qFKEDiiWgp1TAAAAAAAAAA0AAAACAAAACXRlc3QtaG9zdAAAAAwAAAAIdGVzdGhvc3QAAAAAav3SogAAAAB9vKfOAAAAAAAAAAAAAAAAAAAAMwAAAAtzc2gtZWQyNTUxOQAAACAulR14kCgiY/XXJg3Fcpcmq7idL3//lRHPEd2i4MObIwAAAFMAAAALc3NoLWVkMjU1MTkAAABAMhXWtFgB7zYp83KteSu1Qa2ygqZxncsc6ARd465BsvpYGMd7un6A+ZGxsFEGtWBLmM0Af7WZbECF5nh36m1YCA== test-user"
    }
}
