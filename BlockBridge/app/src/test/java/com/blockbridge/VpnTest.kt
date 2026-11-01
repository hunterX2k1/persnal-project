package com.blockbridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * BlockBridge relay -> "VPN" upstream -> destination. The upstream here is a second
 * Socks5Server standing in for bbwg (bbwg itself is tested end-to-end in wg/main_test.go).
 */
class VpnTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val closers = mutableListOf<AutoCloseable>()
    @Volatile private var upstream: SocksUpstream? = null

    @After fun tearDown() = closers.forEach { runCatching { it.close() } }

    private fun relayWithUpstream(): Socks5Server {
        Filter.setLists(Ruleset.EMPTY)
        Filter.setUser(Ruleset.EMPTY, emptySet())
        val vpn = Socks5Server({ false }).start().also { closers += it }
        upstream = SocksUpstream(vpn.port, vpn.username, vpn.password)
        return Socks5Server({ false }, { upstream }).start().also { closers += it; it.requireUpstream = true }
    }

    private fun open(relay: Socks5Server): Pair<Socket, DataInputStream> {
        val s = Socket(lo, relay.port).also { closers += it; it.soTimeout = 5000 }
        val i = DataInputStream(s.getInputStream())
        val o = s.getOutputStream()
        o.write(byteArrayOf(5, 1, 2)); i.readFully(ByteArray(2))
        val u = relay.username; val p = relay.password
        o.write(byteArrayOf(1, u.length.toByte()) + u.toByteArray() + byteArrayOf(p.length.toByte()) + p.toByteArray())
        i.readFully(ByteArray(2))
        return s to i
    }

    private fun request(s: Socket, i: DataInputStream, cmd: Int, addr: InetAddress, port: Int): Pair<Int, Int> {
        s.getOutputStream().write(byteArrayOf(5, cmd.toByte(), 0, 1) + addr.address + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val h = ByteArray(10).also { i.readFully(it) }
        return (h[1].toInt() and 0xff) to Packets.u16(h, 8)
    }

    @Test fun tcpGoesThroughVpn() {
        val echo = ServerSocket(0, 5, lo).also { closers += it }
        thread(isDaemon = true) { echo.accept().use { c -> val b = ByteArray(3); DataInputStream(c.getInputStream()).readFully(b); c.getOutputStream().write(b) } }
        val relay = relayWithUpstream()
        val (s, i) = open(relay)
        assertEquals(0, request(s, i, 1, lo, echo.localPort).first)
        s.getOutputStream().write("vpn".toByteArray())
        assertEquals("vpn", String(ByteArray(3).also { i.readFully(it) }))
    }

    @Test fun udpGoesThroughVpn() {
        val echo = DatagramSocket(0, lo).also { closers += it }
        thread(isDaemon = true) {
            val b = ByteArray(64); val p = DatagramPacket(b, b.size); echo.receive(p)
            echo.send(DatagramPacket(b, p.length, p.socketAddress))
        }
        val relay = relayWithUpstream()
        val (s, i) = open(relay)
        val (rep, port) = request(s, i, 3, InetAddress.getByAddress(ByteArray(4)), 0)
        assertEquals(0, rep)
        val u = DatagramSocket().also { closers += it; it.soTimeout = 5000 }
        val pkt = Socks5Server.udpHeader(lo, echo.localPort, "hi".toByteArray())
        u.send(DatagramPacket(pkt, pkt.size, lo, port))
        val buf = ByteArray(64); val p = DatagramPacket(buf, buf.size); u.receive(p)
        val d = Socks5Server.parseUdpHeader(buf, p.length)!!
        assertArrayEquals("hi".toByteArray(), d.data)
        assertEquals(echo.localPort, d.port)
    }

    @Test fun killSwitchBlocksWhenVpnDown() {
        val relay = relayWithUpstream()
        upstream = null // VPN selected but not connected
        val (s, i) = open(relay)
        assertEquals(2, request(s, i, 1, lo, 9).first)
        val (s2, i2) = open(relay)
        assertEquals(2, request(s2, i2, 3, InetAddress.getByAddress(ByteArray(4)), 0).first)

        DnsHandler.requireVia = true
        try {
            val q = byteArrayOf(1, 2, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
            assertNull(DnsHandler.handle(q)) // no lookup leaks outside the VPN
        } finally {
            DnsHandler.requireVia = false
        }
    }

    @Test fun configHelpers() {
        val conf = """
            [Interface]
            PrivateKey = abc=
            Address = 10.2.0.2/32
            DNS = 10.2.0.1, 2a07:b944::2:1, search.lan

            [Peer]
            PublicKey = xyz=
            AllowedIPs = 0.0.0.0/0
            Endpoint = localhost:51820
        """.trimIndent()
        assertNull(VpnProfile.validate(conf))
        assertNotNull(VpnProfile.validate("[Interface]\nPrivateKey = x"))
        assertEquals(listOf("10.2.0.1", "2a07:b944:0:0:0:0:2:1"), VpnClient.dnsOf(conf).map { it.hostAddress })
        val resolved = VpnClient.resolveEndpoints(conf)
        assertTrue(resolved, resolved.contains("Endpoint = 127.0.0.1:51820"))
        assertTrue(VpnClient.resolveEndpoints("Endpoint = 1.2.3.4:51820").contains("1.2.3.4:51820"))
        assertEquals("NL-FREE-12", VpnProfile.nameFrom("wg-NL-FREE-12.conf"))
    }
}
