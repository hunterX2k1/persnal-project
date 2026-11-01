package com.blockbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Runs the speed test against a local stand-in for Cloudflare's speed endpoints. */
class SpeedTestTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val closers = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closers.forEach { runCatching { it.close() } }

    private fun fakeCloudflare(): Int {
        val srv = ServerSocket(0, 100, lo).also { closers += it }
        thread(isDaemon = true) {
            while (true) {
                val c = runCatching { srv.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
        return srv.localPort
    }

    private fun serve(c: Socket) {
        val input = BufferedInputStream(c.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return; head.append(b.toChar()) }
        val first = head.lineSequence().first()
        val out = c.getOutputStream()
        when {
            first.startsWith("GET /cdn-cgi/trace") -> {
                val body = "fl=1\nip=203.0.113.7\ncolo=AMS\nloc=NL\n"
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
            }
            first.startsWith("GET /__down") -> {
                val n = Regex("bytes=(\\d+)").find(first)!!.groupValues[1].toLong()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: $n\r\n\r\n".toByteArray())
                val buf = ByteArray(64 * 1024); var sent = 0L
                while (sent < n) { val k = minOf(buf.size.toLong(), n - sent).toInt(); out.write(buf, 0, k); sent += k }
            }
            first.startsWith("POST /__up") -> {
                val len = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toLong()
                val buf = ByteArray(64 * 1024); var got = 0L
                while (got < len) { val k = input.read(buf); if (k < 0) break; got += k }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
        out.flush()
    }

    private fun check(r: SpeedResult) {
        assertEquals("Cloudflare AMS (🇳🇱 NL)", r.server)
        assertEquals("203.0.113.7", r.ip)
        assertTrue("ping ${r.pingMs}", r.pingMs > 0 && r.pingMs < 1000)
        assertTrue("download ${r.downMbps}", r.downMbps > 10)
        assertTrue("upload ${r.upMbps}", r.upMbps > 10)
    }

    @Test fun direct() {
        val port = fakeCloudflare()
        val phases = mutableSetOf<SpeedTest.Phase>()
        val t = SpeedTest(Dialers.direct, host = "127.0.0.1", port = port, tls = false, phaseMillis = 1500)
        t.onProgress = { p, _, _ -> phases += p }
        val r = t.run("Direct")
        check(r)
        assertEquals(SpeedTest.Phase.values().toSet(), phases)
    }

    @Test fun throughVpnRoute() {
        val port = fakeCloudflare()
        Filter.setLists(Ruleset.EMPTY); Filter.setUser(Ruleset.EMPTY, emptySet())
        val vpn = Socks5Server({ false }).start().also { closers += it }
        val dialer = Dialers.via(SocksUpstream(vpn.port, vpn.username, vpn.password))
        check(SpeedTest(dialer, host = "127.0.0.1", port = port, tls = false, phaseMillis = 1500).run("VPN"))
    }
}
