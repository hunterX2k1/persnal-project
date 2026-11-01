package com.blockbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real end-to-end run on Linux: kernel socket → TUN device → patched hev-socks5-tunnel →
 * BlockBridge's Socks5Server. Needs root, /dev/net/tun and a Linux hev build, so it only
 * runs when -Dbb.e2e=<dir> points at a folder with hev-patched, ifup.py and (optionally)
 * v4shim.so for kernels without IPv6, plus a SOCKS echo server on 127.0.0.1:18080.
 */
class TunnelE2ETest {
    private val dir = System.getProperty("bb.e2e")?.takeIf { it.isNotEmpty() }?.let { File(it) }
    private var hev: Process? = null
    private var relay: Socks5Server? = null

    @After fun tearDown() {
        hev?.destroy()
        relay?.close()
    }

    private fun dnsQuery(name: String) = byteArrayOf(0x42, 0x42, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
        name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }.toByteArray() +
        byteArrayOf(0, 0, 1, 0, 1)

    @Test fun appTrafficThroughRealTunnel() {
        assumeTrue(dir != null)
        val c = UboCompiler.Compiler()
        c.addList("easylist", sequenceOf("||ads.example.com^"))
        Filter.setLists(c.build())
        Filter.setUser(Ruleset.EMPTY, emptySet())

        val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val r = Socks5Server({ it.hostAddress == "198.18.0.53" }).start()
        relay = r
        r.appResolver = { proto, src, dst -> seen.add("$proto ${src.port} -> ${dst.port}"); AppInfo(1, "test", "port ${src.port}") }
        // Allowed traffic leaves through an "upstream" (a SOCKS echo server), so nothing
        // loops back into the tunnel on this test machine.
        val echoUp = SocksUpstream(18080, "base", "pw")
        r.router = Router { conn -> if (conn.dst.port == 9) Route.Reject("test") else Route.Via(echoUp, "echo-upstream") }

        val conf = File(dir, "bb-e2e.yml")
        conf.writeText(HevTunnel.config(r, false).replace("tunnel:\n", "tunnel:\n  name: bbtest0\n"))
        val env = ProcessBuilder(File(dir, "hev-patched").path, conf.path).redirectErrorStream(true)
        File(dir, "v4shim.so").takeIf { it.exists() }?.let { env.environment()["LD_PRELOAD"] = it.path }
        hev = env.start()
        Thread.sleep(800)
        assertEquals(0, ProcessBuilder("python3", "-I", File(dir, "ifup.py").path).inheritIO().start().waitFor())

        // 1) DNS over UDP through the tunnel: blocked name → 0.0.0.0, answered by BlockBridge
        DatagramSocket().use { u ->
            u.soTimeout = 5000
            val q = dnsQuery("ads.example.com")
            u.send(DatagramPacket(q, q.size, InetAddress.getByName("198.18.0.53"), 53))
            val buf = ByteArray(512); val p = DatagramPacket(buf, buf.size); u.receive(p)
            assertEquals(listOf("0.0.0.0"), Dns.answerAddresses(buf.copyOf(p.length)))
        }

        // 2) DNS over TCP through the tunnel
        Socket().use { s ->
            s.connect(InetSocketAddress("198.18.0.53", 53), 5000); s.soTimeout = 5000
            val q = dnsQuery("x.ads.example.com")
            s.getOutputStream().write(byteArrayOf(0, q.size.toByte()) + q)
            val i = DataInputStream(s.getInputStream())
            val resp = ByteArray(i.readUnsignedShort()).also { i.readFully(it) }
            assertEquals(listOf("0.0.0.0"), Dns.answerAddresses(resp))
        }

        // 3) An allowed TCP connection, attributed to its exact source port
        val app = Socket()
        app.connect(InetSocketAddress("198.18.0.77", 443), 5000); app.soTimeout = 5000
        val myPort = app.localPort
        app.getOutputStream().write("hello".toByteArray())
        assertEquals("echo:hello", String(app.getInputStream().readNBytes(10)))
        app.close()
        assertTrue("resolver saw $seen", seen.contains("TCP $myPort -> 443"))
        val conn = ConnTracker.snapshot().first { it.dst.port == 443 && it.app?.label == "port $myPort" }
        assertEquals("echo-upstream", conn.route)
        assertTrue(conn.bytesUp.get() >= 5 && conn.bytesDown.get() >= 10)

        // 4) A connection the router rejects is reset for the app
        val rejected = try {
            Socket().use { s ->
                s.connect(InetSocketAddress("198.18.0.77", 9), 5000); s.soTimeout = 5000
                s.getOutputStream().write(1); s.getInputStream().read()
            }
        } catch (e: IOException) { -2 }
        assertTrue("expected reset/EOF, got $rejected", rejected < 0)
    }
}
