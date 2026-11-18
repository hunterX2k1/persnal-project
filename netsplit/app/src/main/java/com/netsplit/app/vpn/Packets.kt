package com.netsplit.app.vpn

/**
 * Minimal IPv4 / IPv6 + TCP / UDP / ICMP parsing and building.
 * Everything here is plain JVM code so it can be unit tested off-device.
 */
object Pk {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ICMP6 = 58

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    fun u32(b: ByteArray, o: Int) = (u16(b, o) shl 16) or u16(b, o + 2)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Int) {
        put16(b, o, v ushr 16); put16(b, o + 2, v)
    }

    fun sum(b: ByteArray, off: Int, len: Int, init: Long): Long {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) { s += u16(b, i); i += 2 }
        if (i < end) s += (b[i].toLong() and 0xff) shl 8
        return s
    }

    fun fold(s0: Long): Int {
        var s = s0
        while ((s ushr 16) != 0L) s = (s and 0xffff) + (s ushr 16)
        return (s.inv() and 0xffff).toInt()
    }

    fun l4Checksum(b: ByteArray, src: ByteArray, dst: ByteArray, proto: Int, off: Int, len: Int): Int {
        var s = sum(src, 0, src.size, 0)
        s = sum(dst, 0, dst.size, s)
        s += proto.toLong()
        s += len.toLong()
        s = sum(b, off, len, s)
        return fold(s)
    }

    private var ipId = 1

    /** Writes an IP header at offset 0. Returns the header length. */
    fun writeIpHeader(b: ByteArray, v6: Boolean, src: ByteArray, dst: ByteArray, proto: Int, l4Len: Int): Int {
        return if (!v6) {
            b[0] = 0x45; b[1] = 0
            put16(b, 2, 20 + l4Len)
            put16(b, 4, ipId++ and 0xffff)
            put16(b, 6, 0x4000) // DF
            b[8] = 64; b[9] = proto.toByte()
            put16(b, 10, 0)
            System.arraycopy(src, 0, b, 12, 4)
            System.arraycopy(dst, 0, b, 16, 4)
            put16(b, 10, fold(sum(b, 0, 20, 0)))
            20
        } else {
            b[0] = 0x60; b[1] = 0; b[2] = 0; b[3] = 0
            put16(b, 4, l4Len)
            b[6] = proto.toByte(); b[7] = 64
            System.arraycopy(src, 0, b, 8, 16)
            System.arraycopy(dst, 0, b, 24, 16)
            40
        }
    }

    fun tcp(
        v6: Boolean, src: ByteArray, dst: ByteArray, sport: Int, dport: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        options: ByteArray? = null,
        payload: ByteQueue? = null, pOff: Int = 0, pLen: Int = 0,
    ): ByteArray {
        val ipLen = if (v6) 40 else 20
        val optLen = options?.size ?: 0
        val tcpLen = 20 + optLen
        val b = ByteArray(ipLen + tcpLen + pLen)
        writeIpHeader(b, v6, src, dst, TCP, tcpLen + pLen)
        val o = ipLen
        put16(b, o, sport); put16(b, o + 2, dport)
        put32(b, o + 4, seq); put32(b, o + 8, ack)
        b[o + 12] = ((tcpLen / 4) shl 4).toByte()
        b[o + 13] = flags.toByte()
        put16(b, o + 14, window.coerceIn(0, 0xffff))
        if (options != null) System.arraycopy(options, 0, b, o + 20, optLen)
        if (payload != null && pLen > 0) payload.copyOut(pOff, b, o + tcpLen, pLen)
        put16(b, o + 16, l4Checksum(b, src, dst, TCP, o, tcpLen + pLen))
        return b
    }

    fun udp(
        v6: Boolean, src: ByteArray, dst: ByteArray, sport: Int, dport: Int,
        payload: ByteArray, pOff: Int, pLen: Int,
    ): ByteArray {
        val ipLen = if (v6) 40 else 20
        val l4 = 8 + pLen
        val b = ByteArray(ipLen + l4)
        writeIpHeader(b, v6, src, dst, UDP, l4)
        val o = ipLen
        put16(b, o, sport); put16(b, o + 2, dport); put16(b, o + 4, l4)
        System.arraycopy(payload, pOff, b, o + 8, pLen)
        var cs = l4Checksum(b, src, dst, UDP, o, l4)
        if (cs == 0) cs = 0xffff
        put16(b, o + 6, cs)
        return b
    }

    /** "Port unreachable" reply for a UDP packet sent by the app (makes the app fail fast). */
    fun portUnreachable(ip: IpInfo): ByteArray {
        val d = ip.data
        return if (!ip.v6) {
            val quoted = minOf(ip.l4Off + 8, ip.totalLen)
            val l4 = 8 + quoted
            val b = ByteArray(20 + l4)
            writeIpHeader(b, false, ip.dst, ip.src, ICMP, l4)
            b[20] = 3; b[21] = 3
            System.arraycopy(d, 0, b, 28, quoted)
            put16(b, 22, fold(sum(b, 20, l4, 0)))
            b
        } else {
            val quoted = minOf(ip.totalLen, 1232)
            val l4 = 8 + quoted
            val b = ByteArray(40 + l4)
            writeIpHeader(b, true, ip.dst, ip.src, ICMP6, l4)
            b[40] = 1; b[41] = 4
            System.arraycopy(d, 0, b, 48, quoted)
            put16(b, 42, l4Checksum(b, ip.dst, ip.src, ICMP6, 40, l4))
            b
        }
    }
}

class IpInfo(
    val data: ByteArray,
    val v6: Boolean,
    val src: ByteArray,
    val dst: ByteArray,
    val proto: Int,
    val l4Off: Int,
    val l4Len: Int,
    val totalLen: Int,
) {
    companion object {
        fun parse(d: ByteArray, len: Int = d.size): IpInfo? {
            if (len < 1) return null
            when ((d[0].toInt() ushr 4) and 0xf) {
                4 -> {
                    if (len < 20) return null
                    val ihl = (d[0].toInt() and 0xf) * 4
                    val total = Pk.u16(d, 2)
                    if (ihl < 20 || total < ihl || total > len) return null
                    if ((Pk.u16(d, 6) and 0x3fff) != 0) return null // fragments are not supported
                    return IpInfo(
                        d, false, d.copyOfRange(12, 16), d.copyOfRange(16, 20),
                        Pk.u8(d, 9), ihl, total - ihl, total,
                    )
                }
                6 -> {
                    if (len < 40) return null
                    val payloadLen = Pk.u16(d, 4)
                    val total = 40 + payloadLen
                    if (total > len) return null
                    var next = Pk.u8(d, 6)
                    var off = 40
                    // Skip common extension headers (hop-by-hop, routing, destination options).
                    while (next == 0 || next == 43 || next == 60) {
                        if (off + 8 > total) return null
                        val hdrLen = (Pk.u8(d, off + 1) + 1) * 8
                        next = Pk.u8(d, off)
                        off += hdrLen
                    }
                    if (off > total) return null
                    return IpInfo(
                        d, true, d.copyOfRange(8, 24), d.copyOfRange(24, 40),
                        next, off, total - off, total,
                    )
                }
                else -> return null
            }
        }
    }
}

/** Growable FIFO byte buffer whose content is always contiguous in [buf] from [head]. */
class ByteQueue(initial: Int = 8192) {
    var buf = ByteArray(initial)
        private set
    var head = 0
        private set
    private var tail = 0
    val size get() = tail - head

    fun append(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (tail + len > buf.size) {
            val sz = size
            if (sz + len <= buf.size) {
                System.arraycopy(buf, head, buf, 0, sz)
            } else {
                val nb = ByteArray(maxOf(buf.size * 2, sz + len))
                System.arraycopy(buf, head, nb, 0, sz)
                buf = nb
            }
            head = 0; tail = sz
        }
        System.arraycopy(src, off, buf, tail, len)
        tail += len
    }

    fun drop(n: Int) {
        head += n.coerceAtMost(size)
        if (head == tail) { head = 0; tail = 0 }
    }

    fun copyOut(off: Int, dst: ByteArray, dstOff: Int, n: Int) {
        System.arraycopy(buf, head + off, dst, dstOff, n)
    }
}
