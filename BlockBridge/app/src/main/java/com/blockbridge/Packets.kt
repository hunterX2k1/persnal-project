package com.blockbridge

/** A UDP datagram read from (or written to) the VPN interface. */
class UdpPacket(
    val v6: Boolean,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object Packets {
    fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte(); b[off + 1] = v.toByte()
    }

    /** Returns the UDP packet inside an IPv4/IPv6 packet, or null for anything else. */
    fun parseUdp(b: ByteArray, len: Int): UdpPacket? {
        if (len < 1) return null
        val udp: Int
        val total: Int
        val src: ByteArray
        val dst: ByteArray
        when ((b[0].toInt() shr 4) and 0xf) {
            4 -> {
                if (len < 28) return null
                val ihl = (b[0].toInt() and 0xf) * 4
                if (ihl < 20 || b[9].toInt() != 17) return null
                if ((u16(b, 6) and 0x3fff) != 0) return null // fragmented
                total = minOf(u16(b, 2), len)
                udp = ihl
                src = b.copyOfRange(12, 16); dst = b.copyOfRange(16, 20)
            }
            6 -> {
                if (len < 48 || b[6].toInt() != 17) return null
                total = minOf(40 + u16(b, 4), len)
                udp = 40
                src = b.copyOfRange(8, 24); dst = b.copyOfRange(24, 40)
            }
            else -> return null
        }
        if (total < udp + 8) return null
        val payloadLen = minOf(u16(b, udp + 4) - 8, total - udp - 8)
        if (payloadLen <= 0) return null
        return UdpPacket(
            v6 = udp == 40, src = src, dst = dst,
            srcPort = u16(b, udp), dstPort = u16(b, udp + 2),
            payload = b.copyOfRange(udp + 8, udp + 8 + payloadLen),
        )
    }

    /** Builds the reply packet for [req], swapping source and destination. */
    fun buildReply(req: UdpPacket, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        if (!req.v6) {
            val out = ByteArray(20 + udpLen)
            out[0] = 0x45
            put16(out, 2, out.size)
            out[6] = 0x40 // don't fragment
            out[8] = 64   // TTL
            out[9] = 17   // UDP
            req.dst.copyInto(out, 12); req.src.copyInto(out, 16)
            put16(out, 10, fold(sum(out, 0, 20)))
            put16(out, 20, req.dstPort); put16(out, 22, req.srcPort); put16(out, 24, udpLen)
            // UDP checksum 0 = "not computed", valid for IPv4
            payload.copyInto(out, 28)
            return out
        }
        val out = ByteArray(40 + udpLen)
        out[0] = 0x60
        put16(out, 4, udpLen)
        out[6] = 17; out[7] = 64
        req.dst.copyInto(out, 8); req.src.copyInto(out, 24)
        put16(out, 40, req.dstPort); put16(out, 42, req.srcPort); put16(out, 44, udpLen)
        payload.copyInto(out, 48)
        // IPv6 requires a UDP checksum over the pseudo-header
        val pseudo = sum(out, 8, 32) + udpLen + 17 + sum(out, 40, udpLen)
        var cs = fold(pseudo)
        if (cs == 0) cs = 0xffff
        put16(out, 46, cs)
        return out
    }

    fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) { s += u16(b, i); i += 2 }
        if (i < end) s += (b[i].toInt() and 0xff) shl 8
        return s
    }

    fun fold(sum: Long): Int {
        var s = sum
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }
}

object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    class Question(val name: String, val type: Int, val end: Int)

    /** Reads the first question of a DNS query. */
    fun parseQuestion(msg: ByteArray): Question? {
        if (msg.size < 12 || Packets.u16(msg, 4) < 1) return null
        if ((msg[2].toInt() and 0x80) != 0) return null // a response, not a query
        val sb = StringBuilder()
        var p = 12
        while (true) {
            if (p >= msg.size) return null
            val l = msg[p].toInt() and 0xff
            p++
            if (l == 0) break
            if ((l and 0xC0) != 0 || p + l > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append((msg[p + i].toInt() and 0xff).toChar())
            p += l
        }
        if (p + 4 > msg.size) return null
        return Question(sb.toString().lowercase(), Packets.u16(msg, p), p + 4)
    }

    /** IP addresses in the answer section of a DNS response (A and AAAA records). */
    fun answerAddresses(msg: ByteArray): List<String> {
        try {
            if (msg.size < 12) return emptyList()
            val qd = Packets.u16(msg, 4)
            val an = Packets.u16(msg, 6)
            var p = 12
            repeat(qd) { p = skipName(msg, p) + 4 }
            val out = ArrayList<String>(an)
            repeat(an) {
                p = skipName(msg, p)
                val type = Packets.u16(msg, p)
                val rdLen = Packets.u16(msg, p + 8)
                val rd = p + 10
                if (rd + rdLen > msg.size) return out
                if ((type == TYPE_A && rdLen == 4) || (type == TYPE_AAAA && rdLen == 16)) {
                    java.net.InetAddress.getByAddress(msg.copyOfRange(rd, rd + rdLen)).hostAddress?.let { out.add(it) }
                }
                p = rd + rdLen
            }
            return out
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            val l = msg[p].toInt() and 0xff
            if (l == 0) return p + 1
            if ((l and 0xC0) == 0xC0) return p + 2
            p += 1 + l
        }
    }

    /**
     * Answer for a blocked domain: 0.0.0.0 / :: for address lookups (so the page fails
     * instantly instead of retrying), and an empty answer for every other record type.
     */
    fun blockedReply(query: ByteArray, q: Question): ByteArray {
        val rdLen = when (q.type) { TYPE_A -> 4; TYPE_AAAA -> 16; else -> 0 }
        val ansLen = if (rdLen > 0) 12 + rdLen else 0
        val out = ByteArray(q.end + ansLen)
        query.copyInto(out, 0, 0, q.end)
        out[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // QR + opcode + RD
        out[3] = 0x80.toByte()                                  // RA, NOERROR
        out[4] = 0; out[5] = 1
        out[6] = 0; out[7] = if (rdLen > 0) 1 else 0
        for (i in 8 until 12) out[i] = 0
        if (rdLen > 0) {
            var p = q.end
            out[p++] = 0xC0.toByte(); out[p++] = 0x0C                       // name -> question
            out[p++] = 0; out[p++] = q.type.toByte()                         // type
            out[p++] = 0; out[p++] = 1                                       // class IN
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60          // TTL 60s
            out[p++] = 0; out[p] = rdLen.toByte()                            // rdata = zeros
        }
        return out
    }
}
