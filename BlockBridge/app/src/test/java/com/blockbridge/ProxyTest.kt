package com.blockbridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Drives Socks5Server the way hev-socks5-tunnel does, over real local sockets. */
class ProxyTest {
    private lateinit var proxy: Socks5Server
    private val lo = InetAddress.getByName("127.0.0.1")
    private val closers = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        proxy = Socks5Server { it.hostAddress == "10.111.222.2" }.start()
        val c = UboCompiler.Compiler()
        c.addList(Filter.BY_USER, sequenceOf("||ads.blocked.com^"))
        Filter.setUser(c.build(), emptySet())
        Filter.setLists(Ruleset.EMPTY)
    }

    @After fun tearDown() {
        proxy.close()
        closers.forEach { runCatching { it.close() } }
        Upstream.port = 53
    }

    private fun handshake(user: String = proxy.username, pass: String = proxy.password): Pair<Socket, Int> {
        val s = Socket(lo, proxy.port).also { closers += it }
        s.soTimeout = 5000
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2))
        assertEquals(5, i.read()); assertEquals(2, i.read())
        o.write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() + byteArrayOf(pass.length.toByte()) + pass.toByteArray())
        assertEquals(1, i.read())
        return s to i.read()
    }

    private fun request(s: Socket, cmd: Int, addr: InetAddress, port: Int): Pair<Int, Int> {
        s.getOutputStream().write(byteArrayOf(5, cmd.toByte(), 0, 1) + addr.address + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val i = DataInputStream(s.getInputStream())
        val head = ByteArray(10).also { i.readFully(it) }
        return (head[1].toInt() and 0xff) to Packets.u16(head, 8)
    }

    private fun dnsQuery(name: String, type: Int = Dns.TYPE_A): ByteArray {
        val out = mutableListOf<Byte>(0x55, 0x66, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)
        for (label in name.split('.')) { out += label.length.toByte(); out += label.toByteArray().toList() }
        out += listOf<Byte>(0, 0, type.toByte(), 0, 1)
        return out.toByteArray()
    }

    /** A fake upstream DNS server answering every A query with 9.9.9.9 (with a name pointer). */
    private fun fakeDns(): Int {
        val sock = DatagramSocket(0, lo).also { closers += it }
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            runCatching {
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val q = buf.copyOf(p.length)
                    val resp = q.copyOf() + byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 9, 9, 9, 9)
                    resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte(); resp[7] = 1
                    sock.send(DatagramPacket(resp, resp.size, p.socketAddress))
                }
            }
        }
        return sock.localPort
    }

    @Test fun relaysTcp() {
        val echo = ServerSocket(0, 5, lo).also { closers += it }
        thread(isDaemon = true) {
            echo.accept().use { c -> val b = ByteArray(5); DataInputStream(c.getInputStream()).readFully(b); c.getOutputStream().write(b) }
        }
        val (s, auth) = handshake()
        assertEquals(0, auth)
        assertEquals(0, request(s, 1, lo, echo.localPort).first)
        s.getOutputStream().write("hello".toByteArray())
        val back = ByteArray(5).also { DataInputStream(s.getInputStream()).readFully(it) }
        assertEquals("hello", String(back))
    }

    @Test fun rejectsWrongPassword() {
        assertEquals(1, handshake(pass = "nope").second)
    }

    @Test fun refusesBlockedHost() {
        val ip = InetAddress.getByName("127.0.0.2")
        DnsCache.put(ip.hostAddress!!, "cdn.ads.blocked.com")
        val (s, _) = handshake()
        assertEquals(2, request(s, 1, ip, 443).first)
    }

    @Test fun filtersAndForwardsDnsOverUdp() {
        Upstream.servers = listOf(lo)
        Upstream.port = fakeDns()
        val (ctrl, _) = handshake()
        val (rep, relayPort) = request(ctrl, 3, InetAddress.getByAddress(ByteArray(4)), 0)
        assertEquals(0, rep)
        val u = DatagramSocket().also { closers += it; it.soTimeout = 5000 }
        val google = InetAddress.getByName("8.8.8.8")

        fun ask(name: String): ByteArray {
            val pkt = Socks5Server.udpHeader(google, 53, dnsQuery(name))
            u.send(DatagramPacket(pkt, pkt.size, lo, relayPort))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            u.receive(p)
            val d = Socks5Server.parseUdpHeader(buf, p.length)
            assertNotNull(d)
            assertEquals(google, d!!.addr); assertEquals(53, d.port)
            return d.data
        }

        // blocked by "My filters": 0.0.0.0 answer, never reaches upstream
        val blocked = ask("ads.blocked.com")
        assertEquals(listOf("0.0.0.0"), Dns.answerAddresses(blocked))
        // allowed: forwarded upstream, answer cached for connection matching
        val ok = ask("good.example.com")
        assertEquals(listOf("9.9.9.9"), Dns.answerAddresses(ok))
        assertEquals("good.example.com", DnsCache.host("9.9.9.9"))
    }

    @Test fun relaysPlainUdp() {
        val echo = DatagramSocket(0, lo).also { closers += it }
        thread(isDaemon = true) {
            val b = ByteArray(100); val p = DatagramPacket(b, b.size); echo.receive(p)
            echo.send(DatagramPacket(b, p.length, p.socketAddress))
        }
        val (ctrl, _) = handshake()
        val (_, relayPort) = request(ctrl, 3, InetAddress.getByAddress(ByteArray(4)), 0)
        val u = DatagramSocket().also { closers += it; it.soTimeout = 5000 }
        val pkt = Socks5Server.udpHeader(lo, echo.localPort, "ping".toByteArray())
        u.send(DatagramPacket(pkt, pkt.size, lo, relayPort))
        val buf = ByteArray(100); val p = DatagramPacket(buf, buf.size); u.receive(p)
        val d = Socks5Server.parseUdpHeader(buf, p.length)!!
        assertEquals(echo.localPort, d.port)
        assertArrayEquals("ping".toByteArray(), d.data)
    }

    @Test fun filtersDnsOverTcp() {
        val (s, _) = handshake()
        assertEquals(0, request(s, 1, InetAddress.getByName("10.111.222.2"), 53).first)
        val q = dnsQuery("x.ads.blocked.com")
        s.getOutputStream().write(byteArrayOf(0, q.size.toByte()) + q)
        val i = DataInputStream(s.getInputStream())
        val resp = ByteArray(i.readUnsignedShort()).also { i.readFully(it) }
        assertEquals(listOf("0.0.0.0"), Dns.answerAddresses(resp))
    }

    @Test fun localDnsOtherPortsRefusedFast() {
        val (s, _) = handshake()
        assertEquals(5, request(s, 1, InetAddress.getByName("10.111.222.2"), 853).first)
    }
}
