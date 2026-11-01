package com.blockbridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The selector-based relay, connection tracking, routing and the encrypted-DNS guard. */
class RelayTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val closers = mutableListOf<AutoCloseable>()
    private lateinit var relay: Socks5Server

    @Before fun setUp() {
        Filter.setLists(Ruleset.EMPTY)
        Filter.setUser(Ruleset.EMPTY, emptySet())
        relay = Socks5Server({ false }).start().also { closers += it }
    }

    @After fun tearDown() = closers.forEach { runCatching { it.close() } }

    private fun connect(port: Int, user: String = relay.username, target: InetAddress = lo): Pair<Socket, Int> {
        val s = Socket(lo, relay.port).also { closers += it; it.soTimeout = 15_000 }
        val i = DataInputStream(s.getInputStream())
        val o = s.getOutputStream()
        o.write(byteArrayOf(5, 1, 2)); i.readFully(ByteArray(2))
        val p = relay.password
        o.write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() + byteArrayOf(p.length.toByte()) + p.toByteArray())
        i.readFully(ByteArray(2))
        o.write(byteArrayOf(5, 1, 0, 1) + target.address + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val h = ByteArray(10).also { i.readFully(it) }
        return s to (h[1].toInt() and 0xff)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Test fun bulkBothDirectionsAtOnce() {
        val size = 4 * 1024 * 1024
        val toServer = ByteArray(size).also { Random(1).nextBytes(it) }
        val toClient = ByteArray(size).also { Random(2).nextBytes(it) }
        val srv = ServerSocket(0, 5, lo).also { closers += it }
        var serverGot: ByteArray? = null
        val t = thread {
            srv.accept().use { c ->
                val w = thread { c.getOutputStream().write(toClient); c.shutdownOutput() }
                serverGot = c.getInputStream().readBytes()
                w.join()
            }
        }
        val (s, rep) = connect(srv.localPort)
        assertEquals(0, rep)
        val w = thread { s.getOutputStream().write(toServer); s.shutdownOutput() }
        val clientGot = s.getInputStream().readBytes()
        w.join(); t.join(20_000)
        assertArrayEquals(sha(toClient), sha(clientGot))
        assertArrayEquals(sha(toServer), sha(serverGot!!))
    }

    @Test fun halfCloseRequestThenResponse() {
        val srv = ServerSocket(0, 5, lo).also { closers += it }
        thread {
            srv.accept().use { c ->
                val req = c.getInputStream().readBytes() // until the client's FIN
                c.getOutputStream().write("got ${req.size} bytes".toByteArray())
            }
        }
        val (s, _) = connect(srv.localPort)
        s.getOutputStream().write(ByteArray(300_000))
        s.shutdownOutput()
        assertEquals("got 300000 bytes", String(s.getInputStream().readBytes()))
    }

    @Test fun manyConcurrentConnections() {
        val srv = ServerSocket(0, 512, lo).also { closers += it }
        val echo = Executors.newCachedThreadPool()
        thread(isDaemon = true) {
            while (true) {
                val c = runCatching { srv.accept() }.getOrNull() ?: break
                echo.execute { c.use { val b = ByteArray(64); DataInputStream(it.getInputStream()).readFully(b); it.getOutputStream().write(b) } }
            }
        }
        val clients = Executors.newFixedThreadPool(50)
        val ok = java.util.concurrent.atomic.AtomicInteger()
        repeat(200) { n ->
            clients.execute {
                val (s, rep) = connect(srv.localPort)
                val msg = ByteArray(64) { (n + it).toByte() }
                s.getOutputStream().write(msg)
                val back = ByteArray(64).also { DataInputStream(s.getInputStream()).readFully(it) }
                if (rep == 0 && back.contentEquals(msg)) ok.incrementAndGet()
                s.close()
            }
        }
        clients.shutdown(); assertTrue(clients.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(200, ok.get())
        echo.shutdownNow()
    }

    @Test fun attributesConnectionsToApps() {
        val srv = ServerSocket(0, 5, lo).also { closers += it }
        thread { srv.accept().use { it.getOutputStream().write(1) } }
        relay.appResolver = { proto, src, _ ->
            if (proto == "TCP" && src.port == 5555) AppInfo(10123, "com.game", "Shadow Game") else null
        }
        val (s, rep) = connect(srv.localPort, user = "${relay.username}|T|10.111.222.1|5555")
        assertEquals(0, rep)
        assertEquals(1, s.getInputStream().read())
        val c = ConnTracker.snapshot().first { it.dst.port == srv.localPort }
        assertEquals("Shadow Game", c.app?.label)
        assertNotNull(ConnTracker.perApp["Shadow Game"])
        // a wrong base username is still rejected
        val bad = Socket(lo, relay.port).also { closers += it; it.soTimeout = 5000 }
        val i = DataInputStream(bad.getInputStream())
        bad.getOutputStream().write(byteArrayOf(5, 1, 2)); i.readFully(ByteArray(2))
        val u = "nope|T|1.2.3.4|5"; val p = relay.password
        bad.getOutputStream().write(byteArrayOf(1, u.length.toByte()) + u.toByteArray() + byteArrayOf(p.length.toByte()) + p.toByteArray())
        i.readUnsignedByte(); assertEquals(1, i.readUnsignedByte())
    }

    @Test fun routerDecidesPerConnection() {
        val srv = ServerSocket(0, 5, lo).also { closers += it }
        thread { repeat(2) { runCatching { srv.accept().use { it.getOutputStream().write(7) } } } }
        val vpn = Socks5Server({ false }).start().also { closers += it }
        val up = SocksUpstream(vpn.port, vpn.username, vpn.password)
        relay.router = Router { c -> if (c.dst.port == 9) Route.Reject("test policy") else Route.Via(up, "🇳🇱 NL") }

        val (s, rep) = connect(srv.localPort)
        assertEquals(0, rep)
        assertEquals(7, s.getInputStream().read())
        // (the stand-in VPN relay logs the same connection again as Direct; check ours)
        assertTrue(ConnTracker.snapshot().any { it.dst.port == srv.localPort && it.route == "🇳🇱 NL" })

        val (_, rep2) = connect(9)
        assertEquals(2, rep2)
        val rejected = ConnTracker.snapshot().first { it.dst.port == 9 }
        assertEquals(Conn.Status.BLOCKED, rejected.status)
        assertEquals("test policy", rejected.note)
    }

    @Test fun encryptedDnsGuard() {
        val google = InetAddress.getByName("8.8.8.8")
        assertTrue(DohGuard.blocks(google, 443, null))
        assertTrue(DohGuard.blocks(InetAddress.getByName("5.6.7.8"), 853, null))
        assertTrue(DohGuard.blocks(InetAddress.getByName("5.6.7.8"), 443, "chrome.cloudflare-dns.com"))
        assertFalse(DohGuard.blocks(InetAddress.getByName("5.6.7.8"), 443, "www.google.com"))
        assertFalse(DohGuard.blocks(google, 53, null))
        DohGuard.enabled = false
        try { assertFalse(DohGuard.blocks(google, 443, null)) } finally { DohGuard.enabled = true }

        // a relayed connection to a DoH endpoint is refused and logged
        val (_, rep) = connect(443, target = google)
        assertEquals(2, rep)
        assertEquals("encrypted-DNS bypass blocked",
            ConnTracker.snapshot().first { it.dst == InetSocketAddress(google, 443) }.note)

        // and lookups of DoH hostnames get a blocked answer
        val q = byteArrayOf(1, 2, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3) + "dns".toByteArray() + byteArrayOf(6) +
            "google".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        assertEquals(listOf("0.0.0.0"), Dns.answerAddresses(DnsHandler.handle(q)!!))
    }
}
