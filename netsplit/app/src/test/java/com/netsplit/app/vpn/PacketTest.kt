package com.netsplit.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PacketTest {
    private val app6 = InetAddress.getByName("fd00:215::2").address
    private val srv6 = InetAddress.getByName("2404:6800:4009:80b::200e").address
    private val app4 = InetAddress.getByName("10.215.0.2").address
    private val srv4 = InetAddress.getByName("142.250.183.14").address

    /** Verifies an L4 checksum the way a receiver does: sum over pseudo header + segment must fold to 0. */
    private fun l4Valid(ip: IpInfo) = Pk.l4Checksum(ip.data, ip.src, ip.dst, ip.proto, ip.l4Off, ip.l4Len) == 0

    @Test
    fun tcpV6RoundTrip() {
        val q = ByteQueue().apply { append("hello".toByteArray(), 0, 5) }
        val b = Pk.tcp(true, srv6, app6, 443, 40000, 1000, 2000, Pk.ACK or Pk.PSH, 5000, null, q, 0, 5)
        val ip = IpInfo.parse(b)
        assertNotNull(ip); ip!!
        assertTrue(ip.v6)
        assertEquals(Pk.TCP, ip.proto)
        assertEquals(40, ip.l4Off)
        assertEquals(25, ip.l4Len)
        assertEquals(443, Pk.u16(b, 40))
        assertEquals(1000, Pk.u32(b, 44))
        assertEquals("hello", String(b, 60, 5))
        assertTrue(l4Valid(ip))
    }

    @Test
    fun tcpV4HeaderChecksum() {
        val b = Pk.tcp(false, srv4, app4, 443, 40000, -5, 7, Pk.SYN or Pk.ACK, 65535, byteArrayOf(2, 4, 5, 0xb4.toByte()))
        val ip = IpInfo.parse(b)!!
        assertEquals(0, Pk.fold(Pk.sum(b, 0, 20, 0)))
        assertTrue(l4Valid(ip))
        assertEquals(-5, Pk.u32(b, 24))
    }

    @Test
    fun udpBothVersions() {
        val payload = ByteArray(301) { it.toByte() }
        for (v6 in listOf(false, true)) {
            val b = if (v6) Pk.udp(true, srv6, app6, 53, 5555, payload, 0, payload.size)
            else Pk.udp(false, srv4, app4, 53, 5555, payload, 0, payload.size)
            val ip = IpInfo.parse(b)!!
            assertEquals(Pk.UDP, ip.proto)
            assertEquals(309, ip.l4Len)
            assertTrue(l4Valid(ip))
        }
    }

    @Test
    fun icmpUnreachableV6() {
        val udp = Pk.udp(true, app6, srv6, 5555, 443, ByteArray(40), 0, 40)
        val icmp = Pk.portUnreachable(IpInfo.parse(udp)!!)
        val ip = IpInfo.parse(icmp)!!
        assertEquals(Pk.ICMP6, ip.proto)
        assertEquals(1, Pk.u8(icmp, 40))
        assertEquals(4, Pk.u8(icmp, 41))
        assertTrue(ip.src.contentEquals(srv6))
        assertTrue(l4Valid(ip))
    }

    @Test
    fun tokenBucketRate() {
        val tb = TokenBucket(200_000)
        var sent = 0L
        val start = System.nanoTime()
        while (System.nanoTime() - start < 1_000_000_000L) {
            val a = tb.available()
            if (a > 0) { tb.consume(a); sent += a }
            Thread.sleep(2)
        }
        assertTrue("sent $sent", sent in 150_000..240_000)
    }
}
