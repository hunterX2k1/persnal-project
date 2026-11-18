package com.netsplit.app.vpn

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * End-to-end test against the real Linux TCP stack via a TUN device (needs root + /dev/net/tun).
 * Run with: NETSPLIT_TUN_TEST=1 ./gradlew :app:testDebugUnitTest
 */
class EngineKernelTest {
    private val fakeServer = InetAddress.getByName("198.18.0.1")

    private class Rig(rateBytes: Long, ifname: String) : AutoCloseable {
        val limiter = AppLimiter("test.app", "Test", 12345, rateBytes)
        private val bridgeServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private lateinit var sock: Socket
        val engine: Engine
        private val proc: Process

        init {
            val out = object : OutputStream() {
                override fun write(b: Int) = throw UnsupportedOperationException()
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val f = ByteArray(len + 2)
                    f[0] = (len ushr 8).toByte(); f[1] = len.toByte()
                    System.arraycopy(b, off, f, 2, len)
                    synchronized(this) { sock.getOutputStream().write(f) }
                }
            }
            engine = Engine(
                out, listOf(limiter), { _, _, _ -> -1 }, blockQuic = true,
                log = { println("engine: $it") },
                dial = { _, p -> InetSocketAddress(InetAddress.getLoopbackAddress(), p) },
            )
            val script = File("../tools/tun_bridge.py").absolutePath
            proc = ProcessBuilder("python3", script, ifname, bridgeServer.localPort.toString())
                .redirectErrorStream(true).start()
            sock = bridgeServer.accept()
            sock.tcpNoDelay = true
            val rd = proc.inputStream.bufferedReader()
            while (true) {
                val line = rd.readLine() ?: error("bridge failed to start")
                if (line == "ready") break
                println("bridge: $line")
            }
            thread(isDaemon = true) { engine.run() }
            thread(isDaemon = true) {
                val inp = DataInputStream(sock.getInputStream().buffered())
                try {
                    while (true) {
                        val n = inp.readUnsignedShort()
                        val p = ByteArray(n); inp.readFully(p)
                        engine.submit(p)
                    }
                } catch (_: Exception) {}
            }
            Thread.sleep(300)
        }

        override fun close() {
            engine.stop(); proc.destroy(); sock.close(); bridgeServer.close()
        }
    }

    private fun canRun() = System.getenv("NETSPLIT_TUN_TEST") == "1" && File("/dev/net/tun").exists()

    /** Local server that sends [data] then reads everything the client uploads and replies with its sha256. */
    private fun startServer(data: ByteArray): ServerSocket {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (true) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use {
                        val cmd = it.getInputStream().read()
                        if (cmd == 'D'.code) {
                            it.getOutputStream().write(data)
                        } else {
                            val md = MessageDigest.getInstance("SHA-256")
                            val b = ByteArray(65536)
                            while (true) { val n = it.getInputStream().read(b); if (n < 0) break; md.update(b, 0, n) }
                            it.getOutputStream().write(md.digest())
                        }
                    }
                }
            }
        }
        return ss
    }

    private fun download(port: Int, server: InetAddress = fakeServer): Pair<ByteArray, Double> {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, port), 5000)
            s.soTimeout = 30_000
            val t0 = System.nanoTime()
            s.getOutputStream().write('D'.code)
            val bytes = s.getInputStream().readBytes()
            return bytes to (System.nanoTime() - t0) / 1e9
        }
    }

    @Test
    fun throttledDownloadUploadAndUdp() {
        assumeTrue(canRun())
        val data = Random(1).nextBytes(3_000_000)
        val ss = startServer(data)
        Rig(rateBytes = 1_000_000, ifname = "nstest1").use { rig ->
            // Download: correct bytes, at ~1 MB/s.
            val (got, secs) = download(ss.localPort)
            assertArrayEquals(data, got)
            val rate = got.size / secs
            println("throttled download: %.0f KB/s over %.2fs".format(rate / 1000, secs))
            assertTrue("rate $rate too high", rate < 1_250_000)
            assertTrue("rate $rate too low", rate > 700_000)

            // Upload: correct bytes (sha256 echoed back), throttled too.
            val up = Random(2).nextBytes(1_500_000)
            Socket().use { s ->
                s.connect(InetSocketAddress(fakeServer, ss.localPort), 5000)
                s.soTimeout = 30_000
                val t0 = System.nanoTime()
                s.getOutputStream().write('U'.code)
                s.getOutputStream().write(up)
                s.shutdownOutput()
                val digest = s.getInputStream().readBytes()
                val upRate = up.size / ((System.nanoTime() - t0) / 1e9)
                println("throttled upload: %.0f KB/s".format(upRate / 1000))
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(up), digest)
                assertTrue("upload rate $upRate", upRate < 1_300_000)
            }

            // Several parallel downloads share the same app budget.
            val t0 = System.nanoTime()
            val ts = (1..4).map { thread { assertArrayEquals(data, download(ss.localPort).first) } }
            ts.forEach { it.join() }
            val agg = 4 * data.size / ((System.nanoTime() - t0) / 1e9)
            println("4 parallel downloads aggregate: %.0f KB/s".format(agg / 1000))
            assertTrue("aggregate $agg", agg < 1_250_000 && agg > 700_000)

            // UDP echo + QUIC blocking.
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { echo ->
                thread(isDaemon = true) {
                    val b = ByteArray(2048)
                    while (true) {
                        val p = DatagramPacket(b, b.size)
                        try { echo.receive(p) } catch (_: Exception) { break }
                        echo.send(DatagramPacket(p.data, p.length, p.socketAddress))
                    }
                }
                DatagramSocket().use { c ->
                    c.soTimeout = 3000
                    val msg = "hello-udp".toByteArray()
                    c.send(DatagramPacket(msg, msg.size, InetSocketAddress(fakeServer, echo.localPort)))
                    val r = DatagramPacket(ByteArray(100), 100)
                    c.receive(r)
                    assertEquals("hello-udp", String(r.data, 0, r.length))
                }
                DatagramSocket().use { c ->
                    c.soTimeout = 3000
                    c.connect(InetSocketAddress(fakeServer, 443))
                    c.send(DatagramPacket(ByteArray(10), 10))
                    val err = runCatching { c.receive(DatagramPacket(ByteArray(10), 10)) }.exceptionOrNull()
                    println("QUIC probe result: $err")
                    assertTrue(err is java.net.PortUnreachableException)
                }
            }
            assertTrue(rig.limiter.bytesDown.get() >= 5 * data.size)
        }
        ss.close()
    }

    @Test
    fun ipv6TcpAndUdp() {
        assumeTrue(canRun() && File("/proc/net/if_inet6").exists()) // needs IPv6 in the kernel
        val v6Server = InetAddress.getByName("fd00:198::1")
        val data = Random(4).nextBytes(1_000_000)
        val ss = startServer(data)
        Rig(rateBytes = 500_000, ifname = "nstest3").use {
            Thread.sleep(1500) // IPv6 duplicate address detection
            val (got, secs) = download(ss.localPort, v6Server)
            assertArrayEquals(data, got)
            val rate = got.size / secs
            println("ipv6 throttled download: %.0f KB/s".format(rate / 1000))
            assertTrue("rate $rate", rate in 350_000.0..650_000.0)
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { echo ->
                thread(isDaemon = true) {
                    val b = ByteArray(2048)
                    val p = DatagramPacket(b, b.size)
                    echo.receive(p)
                    echo.send(DatagramPacket(p.data, p.length, p.socketAddress))
                }
                DatagramSocket(java.net.InetSocketAddress("::", 0)).use { c ->
                    c.soTimeout = 3000
                    val msg = "hello-v6".toByteArray()
                    c.send(DatagramPacket(msg, msg.size, InetSocketAddress(v6Server, echo.localPort)))
                    val r = DatagramPacket(ByteArray(100), 100)
                    c.receive(r)
                    assertEquals("hello-v6", String(r.data, 0, r.length))
                    println("ipv6 udp ok")
                }
            }
        }
        ss.close()
    }

    @Test
    fun unlimitedIsFastAndCorrect() {
        assumeTrue(canRun())
        val data = Random(3).nextBytes(20_000_000)
        val ss = startServer(data)
        Rig(rateBytes = 0, ifname = "nstest2").use {
            val (got, secs) = download(ss.localPort)
            assertArrayEquals(data, got)
            println("unlimited download: %.1f MB/s".format(got.size / secs / 1e6))
            assertTrue(got.size / secs > 3_000_000)
        }
        ss.close()
    }
}
