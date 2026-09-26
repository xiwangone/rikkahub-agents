package me.rerere.rikkahub.ui.pages.setting.doctor

import android.content.Context
import android.net.ConnectivityManager
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One per-server DNS probe outcome. */
data class DnsProbeResult(
    val server: String,
    val ok: Boolean,
    val rttMs: Long,
    val answerIp: String? = null,
)

/**
 * Minimal UDP DNS client used by the doctor page: sends one A query per server,
 * measures round-trip time and reads back the first A record. A server that
 * does not answer UDP 53 within the timeout counts as unreachable.
 */
object DnsProbe {

    /** DNS servers of the active network — the system resolver's actual upstream. */
    fun activeServers(context: Context): List<String> {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        return cm.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
    }

    /** Probe all servers concurrently (IO dispatcher). */
    suspend fun probeAll(
        servers: List<String>,
        domain: String,
        timeoutMs: Int = 2_000,
    ): List<DnsProbeResult> =
        coroutineScope {
            servers.distinct().map { server ->
                async(Dispatchers.IO) { probe(server, domain, timeoutMs) }
            }.awaitAll()
        }

    fun probe(
        server: String,
        domain: String,
        timeoutMs: Int = 2_000,
    ): DnsProbeResult {
        val id = Random.nextInt(1, 0xFFFF)
        val address = runCatching { InetAddress.getByName(server) }.getOrNull()
            ?: return DnsProbeResult(server, ok = false, rttMs = 0)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(address, 53)
                val query = buildQuery(id, domain)
                val start = System.nanoTime()
                socket.send(DatagramPacket(query, query.size))
                val buf = ByteArray(1_500)
                val packet = DatagramPacket(buf, buf.size)
                var answerIp: String? = null
                while (answerIp == null) {
                    socket.receive(packet)
                    answerIp = parseAnswer(id, buf, packet.length)
                }
                val rtt = (System.nanoTime() - start) / 1_000_000
                DnsProbeResult(server, ok = true, rttMs = rtt, answerIp = answerIp)
            }
        } catch (_: Exception) {
            DnsProbeResult(server, ok = false, rttMs = 0)
        }
    }

    /** Build a single-question A/IN query packet. */
    private fun buildQuery(
        id: Int,
        domain: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // type A, class IN
        return out.toByteArray()
    }

    /** Read back the first A record; null when the packet is not a valid matching answer. */
    private fun parseAnswer(
        id: Int,
        buf: ByteArray,
        length: Int,
    ): String? {
        if (length < 12) return null
        val txid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (txid != id) return null
        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (flags and 0x8000 == 0) return null // not a response
        if (flags and 0x000F != 0) return null // error rcode
        var i = 12
        while (i < length && buf[i].toInt() != 0) i += (buf[i].toInt() and 0xFF) + 1
        i += 5 // null label + qtype + qclass
        while (i + 12 <= length) {
            if (buf[i].toInt() and 0xC0 == 0xC0) {
                i += 2
            } else {
                while (i < length && buf[i].toInt() != 0) i += (buf[i].toInt() and 0xFF) + 1
                i += 1
            }
            if (i + 10 > length) return null
            val type = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            val rdlength = ((buf[i + 8].toInt() and 0xFF) shl 8) or (buf[i + 9].toInt() and 0xFF)
            i += 10
            if (type == 1 && rdlength == 4 && i + 4 <= length) {
                return (buf[i].toInt() and 0xFF).toString() + "." +
                    (buf[i + 1].toInt() and 0xFF) + "." +
                    (buf[i + 2].toInt() and 0xFF) + "." +
                    (buf[i + 3].toInt() and 0xFF)
            }
            i += rdlength
        }
        return null
    }
}
