package app.chumo.epd42

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DNS fallback:
 * 1) try the system resolver first;
 * 2) if it fails (common when the app is in background / Doze), query public DNS
 *    servers directly over UDP so the keep-alive loop can still reach the API.
 */
class FallbackDns(
    private val servers: List<String> = listOf("223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1"),
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        // 1) system resolver (bounded: it can block for a long time on a broken network)
        try {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<List<InetAddress>> { Dns.SYSTEM.lookup(hostname) }
                val result = future.get(4, TimeUnit.SECONDS)
                if (result.isNotEmpty()) return result
            } finally {
                executor.shutdownNow()
            }
        } catch (_: Exception) {
        }
        // 2) raw UDP DNS
        for (server in servers) {
            try {
                val addresses = query(hostname, server)
                if (addresses.isNotEmpty()) return addresses
            } catch (_: Exception) {
            }
        }
        throw UnknownHostException("Unable to resolve host \"$hostname\" (system + fallback DNS failed)")
    }

    private fun query(hostname: String, server: String): List<InetAddress> {
        val id = Random().nextInt(0xFFFF)
        val query = buildQuery(hostname, id)
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val addr = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, addr, 53))
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return parseResponse(buf, packet.length, id)
        }
    }

    private fun buildQuery(hostname: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        u16(id)
        u16(0x0100)          // recursion desired
        u16(1)               // QDCOUNT
        u16(0)               // ANCOUNT
        u16(0)               // NSCOUNT
        u16(0)               // ARCOUNT
        for (part in hostname.trimEnd('.').split('.')) {
            out.write(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(1)               // QTYPE A
        u16(1)               // QCLASS IN
        return out.toByteArray()
    }

    private fun parseResponse(buf: ByteArray, length: Int, id: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        fun u8(i: Int) = buf[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != id) return emptyList()
        val rcode = u8(3) and 0x0F
        if (rcode != 0) return emptyList()
        val qd = u16(4)
        val an = u16(6)
        var pos = 12
        // skip question section
        repeat(qd) {
            while (pos < length) {
                val len = u8(pos)
                pos += 1
                if (len == 0) break
                if ((len and 0xC0) != 0) { pos += 1; return@repeat }
                pos += len
            }
            pos += 4
        }
        val result = mutableListOf<InetAddress>()
        repeat(an) {
            if (pos + 10 > length) return@repeat
            if ((u8(pos) and 0xC0) != 0) pos += 2 else {
                while (pos < length && u8(pos) != 0) pos += 1 + u8(pos)
                pos += 1
            }
            if (pos + 10 > length) return@repeat
            val type = u16(pos); pos += 2
            val cls = u16(pos); pos += 2
            pos += 4 // ttl
            val rdlen = u16(pos); pos += 2
            if (pos + rdlen > length) return@repeat
            if (type == 1 && cls == 1 && rdlen == 4) {
                result += InetAddress.getByAddress(buf.copyOfRange(pos, pos + 4))
            }
            pos += rdlen
            if (result.isNotEmpty()) return result
        }
        return result
    }
}
