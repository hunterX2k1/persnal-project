package com.blockbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** Wi-Fi networks that block public DNS: lookups must fall back to the network's own DNS. */
class DnsFallbackTest {
    private val sock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    @After fun tearDown() {
        sock.close()
        Upstream.port = 53
        Upstream.servers = listOf(InetAddress.getByName("1.1.1.1"))
        Upstream.networkChanged(emptyList())
    }

    @Test fun fallsBackToNetworkDnsAndRemembersIt() {
        Filter.setLists(Ruleset.EMPTY); Filter.setUser(Ruleset.EMPTY, emptySet())
        thread(isDaemon = true) { // the "Wi-Fi router" DNS: answers every A query with 10.0.0.9
            val buf = ByteArray(512)
            runCatching {
                while (true) {
                    val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    val q = buf.copyOf(p.length)
                    val r = q + byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, 0, 9)
                    r[2] = 0x81.toByte(); r[3] = 0x80.toByte(); r[7] = 1
                    sock.send(DatagramPacket(r, r.size, p.socketAddress))
                }
            }
        }
        Upstream.port = sock.localPort
        Upstream.servers = listOf(InetAddress.getByName("127.0.0.3")) // "1.1.1.1", blocked here
        Upstream.networkChanged(listOf(InetAddress.getByName("127.0.0.1")))
        val q = byteArrayOf(1, 2, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 4) + "site".toByteArray() + byteArrayOf(3) +
            "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)

        assertEquals(listOf("10.0.0.9"), Dns.answerAddresses(DnsHandler.handle(q)!!))
        assertEquals("127.0.0.1", Upstream.lastOk)
        // next lookup goes straight to the server that worked
        assertEquals("127.0.0.1", Upstream.candidates().first().hostAddress)
        assertEquals(listOf("10.0.0.9"), Dns.answerAddresses(DnsHandler.handle(q)!!))
    }
}
