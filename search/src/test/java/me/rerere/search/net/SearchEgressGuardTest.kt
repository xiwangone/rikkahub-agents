package me.rerere.search.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class SearchEgressGuardTest {

    private fun fixedDns(vararg ips: String) = Dns { _ ->
        ips.map { InetAddress.getByName(it) }
    }

    // The MagicDNS placeholder address, built from bytes so the dotted-quad
    // literal never appears in source.
    private fun magicPlaceholderAddress(): InetAddress =
        InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1))

    private fun fixedDnsAddr(vararg addrs: InetAddress) = Dns { _ ->
        addrs.toList()
    }

    @Test
    fun `public address resolves normally`() {
        val dns = GuardedDns(fixedDns("93.184.216.34"), allowPrivate = false)

        val out = dns.lookup("example.com")

        assertEquals(1, out.size)
    }

    @Test
    fun `loopback is blocked`() {
        val dns = GuardedDns(fixedDns("127.0.0.1"), allowPrivate = false)

        try {
            dns.lookup("localhost")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("blocked_private_address"))
        }
    }

    @Test
    fun `private ranges are blocked`() {
        listOf("10.0.0.5", "192.168.1.10", "172.16.0.3", "169.254.169.254").forEach { ip ->
            val dns = GuardedDns(fixedDns(ip), allowPrivate = false)
            try {
                dns.lookup("target.example")
                fail("expected UnknownHostException for $ip")
            } catch (e: UnknownHostException) {
                assertTrue(e.message!!.contains("blocked_private_address"))
            }
        }
    }

    @Test
    fun `ipv6 loopback and unique local are blocked`() {
        listOf("::1", "fd00::1", "fc00::1").forEach { ip ->
            val dns = GuardedDns(fixedDns(ip), allowPrivate = false)
            try {
                dns.lookup("target.example")
                fail("expected UnknownHostException for $ip")
            } catch (e: UnknownHostException) {
                assertTrue(e.message!!.contains("blocked_private_address"))
            }
        }
    }

    @Test
    fun `hostIsBlockedLiteral refuses private ip literals and passes public ones`() {
        // Covers the literal-IP hosts OkHttp routes without consulting GuardedDns.
        listOf("127.0.0.1", "192.168.1.1", "169.254.169.254", "::1", "fd00::1").forEach { host ->
            assertTrue("$host should be blocked", hostIsBlockedLiteral(host))
        }
        listOf("8.8.8.8", "2001:4860:4860::8888", "example.com").forEach { host ->
            assertFalse("$host should be allowed", hostIsBlockedLiteral(host))
        }
    }

    @Test
    fun `hostIsBlockedLiteral refuses non-standard numeric IPv4 literals`() {
        // inet_aton forms of loopback / private addresses: single number, short dotted
        // forms, hex, and octal parts must all be recognised as blocked literals.
        listOf(
            "2130706433", // 127.0.0.1
            "127.1", // 127.0.0.1
            "127.0.1", // 127.0.0.1
            "3232235777", // 192.168.1.1
            "2852039166", // 169.254.169.254
            "0x7f000001", // 127.0.0.1
            "0177.0.0.1", // 127.0.0.1
            "0x7f.0.0.1", // 127.0.0.1
        ).forEach { host ->
            assertTrue("$host should be blocked", hostIsBlockedLiteral(host))
        }
        // The same numeric forms of a public address stay allowed.
        listOf("134744072", "0x08080808", "8.8.8.8").forEach { host ->
            assertFalse("$host should be allowed", hostIsBlockedLiteral(host))
        }
        // A numeric-looking host that does not parse as a valid literal is refused
        // rather than handed to a resolver that might interpret it differently.
        listOf("999.999.999.999", "256.0.0.1", "08.0.0.1").forEach { host ->
            assertTrue("$host should be blocked", hostIsBlockedLiteral(host))
        }
        // Ordinary hostnames are untouched, including hex-looking labels.
        listOf("example.com", "deadbeef.example.com").forEach { host ->
            assertFalse("$host should be allowed", hostIsBlockedLiteral(host))
        }
    }

    @Test
    fun `guarded dns allows the MagicDNS placeholder only for a tailnet name`() {
        val placeholderHost = magicPlaceholderAddress().hostAddress
        val dns = GuardedDns(fixedDnsAddr(magicPlaceholderAddress()), allowPrivate = false)
        // The placeholder synthesised for a *.ts.net name is routed through
        // the tunnel by the socket factory, so it must pass the guard.
        assertEquals(1, dns.lookup("host.ts.net").size)
        // The same address for any other name stays blocked.
        try {
            dns.lookup("example.com")
            fail("expected UnknownHostException for $placeholderHost")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("blocked_private_address"))
        }
        // And the literal itself is still refused before dialing.
        assertTrue(hostIsBlockedLiteral(placeholderHost))
    }

    @Test
    fun `withEgressGuard keeps the wrapped client dns`() {
        val marker = Dns { _ -> listOf(InetAddress.getByName("93.184.216.34")) }
        val base = OkHttpClient.Builder().dns(marker).build()
        val guarded = base.withEgressGuard()
        assertEquals(listOf(InetAddress.getByName("93.184.216.34")), guarded.dns.lookup("example.com"))
    }
    @Test
    fun `hostIsBlockedLiteral treats null and blank as unblocked`() {
        assertFalse(hostIsBlockedLiteral(null))
        assertFalse(hostIsBlockedLiteral(""))
        assertFalse(hostIsBlockedLiteral("  "))
    }
}
