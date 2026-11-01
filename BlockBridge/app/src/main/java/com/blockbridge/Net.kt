package com.blockbridge

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The app that opened a connection (resolved from its socket's owner UID). */
data class AppInfo(val uid: Int, val pkg: String, val label: String)

/** One relayed connection, as shown in the network analyzer. */
class Conn(
    val id: Long,
    val proto: String,              // "TCP" / "UDP"
    val dst: InetSocketAddress,
    val host: String?,
    val app: AppInfo?,
) {
    val start = System.currentTimeMillis()
    @Volatile var end = 0L
    @Volatile var route = "Direct"
    @Volatile var status = Status.OPEN
    @Volatile var note: String? = null
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    @Volatile var lastActive = start
    /** Called once when the connection ends (e.g. load-balancer bookkeeping). */
    @Volatile var onFinish: (() -> Unit)? = null

    enum class Status { OPEN, CLOSED, BLOCKED, FAILED }

    val label: String get() = host ?: dst.address.hostAddress ?: "?"
}

/** Keeps live and recent connections plus per-app totals. */
object ConnTracker {
    private val ids = AtomicLong()
    private val live = ConcurrentHashMap<Long, Conn>()
    private val recent = ArrayDeque<Conn>()
    private const val KEEP = 600
    @Volatile var version = 0L
        private set

    class AppTotal(val name: String) {
        val up = AtomicLong(); val down = AtomicLong()
        val conns = AtomicInteger(); val blocked = AtomicInteger()
    }
    val perApp = ConcurrentHashMap<String, AppTotal>()

    fun open(proto: String, dst: InetSocketAddress, host: String?, app: AppInfo?): Conn {
        val c = Conn(ids.incrementAndGet(), proto, dst, host, app)
        live[c.id] = c
        total(app).conns.incrementAndGet()
        version++
        return c
    }

    fun finish(c: Conn, status: Conn.Status, note: String? = null) {
        synchronized(c) {
            if (c.end != 0L) return
            c.end = System.currentTimeMillis()
        }
        runCatching { c.onFinish?.invoke() }
        c.status = status
        if (note != null) c.note = note
        live.remove(c.id)
        if (status == Conn.Status.BLOCKED) total(c.app).blocked.incrementAndGet()
        synchronized(recent) {
            recent.addFirst(c)
            while (recent.size > KEEP) recent.removeLast()
        }
        version++
    }

    fun traffic(c: Conn, up: Long, down: Long) {
        if (up > 0) { c.bytesUp.addAndGet(up); TrafficStats.bytesUp.addAndGet(up); total(c.app).up.addAndGet(up) }
        if (down > 0) { c.bytesDown.addAndGet(down); TrafficStats.bytesDown.addAndGet(down); total(c.app).down.addAndGet(down) }
        c.lastActive = System.currentTimeMillis()
    }

    private fun total(app: AppInfo?) = perApp.getOrPut(app?.label ?: "Unknown / system") { AppTotal(app?.label ?: "Unknown / system") }

    /** Live connections first, then finished ones, newest first. */
    fun snapshot(): List<Conn> {
        val l = live.values.sortedByDescending { it.start }
        val r = synchronized(recent) { recent.toList() }
        return l + r
    }

    fun liveCount() = live.size

    fun clear() {
        synchronized(recent) { recent.clear() }
        perApp.clear()
        version++
    }
}

/** Where a connection goes. */
sealed class Route {
    object Direct : Route()
    class Via(val up: SocksUpstream, val name: String) : Route()
    class Reject(val reason: String) : Route()
}

/** Decides the route for each new connection (split tunneling, load balancer, kill switch). */
fun interface Router {
    fun route(c: Conn): Route
}

/**
 * Encrypted-DNS guard: apps or browsers that use their own DNS-over-HTTPS / DNS-over-TLS
 * would skip BlockBridge's filters. These endpoints are refused so they fall back to the
 * system DNS (which BlockBridge filters).
 */
object DohGuard {
    const val BY = "doh-guard"
    @Volatile var enabled = true

    private val hosts = setOf(
        "dns.google", "dns.google.com", "8888.google", "cloudflare-dns.com", "one.one.one.one",
        "1dot1dot1dot1.cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com",
        "security.cloudflare-dns.com", "family.cloudflare-dns.com", "dns.quad9.net", "dns9.quad9.net",
        "dns10.quad9.net", "dns11.quad9.net", "doh.opendns.com", "dns.opendns.com", "doh.familyshield.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "unfiltered.adguard-dns.com", "family.adguard-dns.com",
        "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.dns.sb", "dns.sb", "doh.mullvad.net",
        "dns.mullvad.net", "dns.controld.com", "freedns.controld.com", "doh.libredns.gr",
        "dns.alidns.com", "doh.pub", "dns.twnic.tw", "dns.switch.ch", "doh.applied-privacy.net",
        "dns-family.adguard.com", "dns-unfiltered.adguard.com", "anycast.dns.nextdns.io",
    )
    private val ips = setOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        "9.9.9.9", "149.112.112.112", "9.9.9.10", "9.9.9.11", "94.140.14.14", "94.140.15.15",
        "94.140.14.140", "94.140.14.141", "208.67.222.222", "208.67.220.220", "185.228.168.9",
        "185.228.169.9", "76.76.2.0", "76.76.10.0", "45.90.28.0", "45.90.30.0", "194.242.2.2",
        "2001:4860:4860:0:0:0:0:8888", "2001:4860:4860:0:0:0:0:8844", "2606:4700:4700:0:0:0:0:1111",
        "2606:4700:4700:0:0:0:0:1001", "2620:fe:0:0:0:0:0:fe", "2620:fe:0:0:0:0:0:9",
    )

    fun isDohHost(name: String): Boolean {
        val d = name.lowercase().trimEnd('.')
        return hosts.any { d == it || d.endsWith(".$it") }
    }

    /** True if a connection to [addr]:[port] (resolved from [host]) is an encrypted-DNS bypass. */
    fun blocks(addr: InetAddress, port: Int, host: String?): Boolean {
        if (!enabled) return false
        if (port == 853) return true                          // DNS-over-TLS / QUIC
        if (port != 443) return false
        if (host != null && isDohHost(host)) return true
        return addr.hostAddress in ips
    }
}

/**
 * Moves bytes between relayed socket pairs on a couple of selector threads instead of
 * two blocking threads per connection: far fewer threads under heavy traffic, with
 * backpressure (a full buffer pauses reading) and an idle reaper for half-dead links.
 */
class PipePool(threads: Int = 2) {
    private val loops = List(threads) { Loop(it) }
    private val next = AtomicInteger()

    fun splice(client: SocketChannel, remote: SocketChannel, conn: Conn, onDone: () -> Unit) {
        loops[Math.floorMod(next.getAndIncrement(), loops.size)].add(client, remote, conn, onDone)
    }

    fun close() = loops.forEach { it.stop() }

    private class End(val ch: SocketChannel, val toRemote: Boolean, val pair: Pair) {
        lateinit var peer: End
        var key: SelectionKey? = null
        /** Data waiting to be written to [ch] (fill mode). */
        val pending: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024)
        var readEof = false
        var outputShut = false
    }

    private class Pair(val conn: Conn, val onDone: () -> Unit) {
        lateinit var a: End
        lateinit var b: End
        var closed = false
    }

    private inner class Loop(i: Int) : Runnable {
        private val selector: Selector = Selector.open()
        private val queue = ConcurrentLinkedQueue<() -> Unit>()
        @Volatile private var running = true
        private val thread = Thread(null, this, "bb-pipe-$i", 256 * 1024).apply { isDaemon = true; start() }
        private var lastReap = System.currentTimeMillis()

        fun add(client: SocketChannel, remote: SocketChannel, conn: Conn, onDone: () -> Unit) {
            queue.add {
                val p = Pair(conn, onDone)
                val a = End(client, true, p)
                val b = End(remote, false, p)
                a.peer = b; b.peer = a; p.a = a; p.b = b
                try {
                    client.configureBlocking(false); remote.configureBlocking(false)
                    a.key = client.register(selector, SelectionKey.OP_READ, a)
                    b.key = remote.register(selector, SelectionKey.OP_READ, b)
                    // Data may already be waiting from before the switch to non-blocking.
                    flush(a); flush(b)
                } catch (e: IOException) {
                    close(p)
                }
            }
            selector.wakeup()
        }

        fun stop() {
            running = false
            selector.wakeup()
        }

        override fun run() {
            while (running) {
                try {
                    selector.select(30_000)
                } catch (e: IOException) {
                    break
                }
                while (true) (queue.poll() ?: break).invoke()
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next(); it.remove()
                    val e = key.attachment() as End
                    if (e.pair.closed) continue
                    try {
                        if (key.isValid && key.isReadable) onReadable(e)
                        if (key.isValid && key.isWritable && !e.pair.closed) flush(e)
                    } catch (_: IOException) {
                        close(e.pair)
                    } catch (_: java.nio.channels.CancelledKeyException) {
                        close(e.pair)
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastReap > 60_000) { lastReap = now; reap(now) }
            }
            for (k in selector.keys()) (k.attachment() as? End)?.let { close(it.pair) }
            runCatching { selector.close() }
        }

        /** Read from [e] into its peer's outgoing buffer, then try to send it on. */
        private fun onReadable(e: End) {
            val buf = e.peer.pending
            if (!buf.hasRemaining()) { interest(e, read = false); return }
            val n = e.ch.read(buf)
            if (n < 0) {
                e.readEof = true
                interest(e, read = false)
                flush(e.peer)
                return
            }
            if (n > 0) {
                if (e.toRemote) ConnTracker.traffic(e.pair.conn, n.toLong(), 0)
                else ConnTracker.traffic(e.pair.conn, 0, n.toLong())
                flush(e.peer)
            }
        }

        /** Write what's pending for [t]; manage backpressure and half-close. */
        private fun flush(t: End) {
            val buf = t.pending
            buf.flip()
            if (buf.hasRemaining()) t.ch.write(buf)
            buf.compact()
            val empty = buf.position() == 0
            interest(t, write = !empty)
            // Room again: let the source read more (unless it already ended).
            if (!t.peer.readEof) interest(t.peer, read = buf.hasRemaining())
            if (empty && t.peer.readEof && !t.outputShut) {
                t.outputShut = true
                runCatching { t.ch.shutdownOutput() }
            }
            val p = t.pair
            if (p.a.readEof && p.b.readEof && p.a.pending.position() == 0 && p.b.pending.position() == 0) close(p)
        }

        private fun interest(e: End, read: Boolean? = null, write: Boolean? = null) {
            val key = e.key ?: return
            if (!key.isValid) return
            var ops = key.interestOps()
            if (read != null) ops = if (read) ops or SelectionKey.OP_READ else ops and SelectionKey.OP_READ.inv()
            if (write != null) ops = if (write) ops or SelectionKey.OP_WRITE else ops and SelectionKey.OP_WRITE.inv()
            key.interestOps(ops)
        }

        /** Closes pairs idle for 15 minutes (e.g. the far end vanished without a FIN). */
        private fun reap(now: Long) {
            for (k in selector.keys()) {
                val e = k.attachment() as? End ?: continue
                if (now - e.pair.conn.lastActive > 15 * 60_000) close(e.pair)
            }
        }

        private fun close(p: Pair) {
            if (p.closed) return
            p.closed = true
            for (e in listOf(p.a, p.b)) {
                runCatching { e.key?.cancel() }
                runCatching { e.ch.close() }
            }
            runCatching { p.onDone() }
        }
    }

}

/** Remembers which app owns a source address so UDP datagrams can be attributed too. */
internal fun parseSource(user: String): Triple<String, InetSocketAddress, String>? {
    // hev (patched) sends "<base>|T|<ip>|<port>" or "<base>|U|<ip>|<port>"
    val parts = user.split('|')
    if (parts.size != 4) return null
    val port = parts[3].toIntOrNull() ?: return null
    val ip = runCatching { InetAddress.getByName(parts[2]) }.getOrNull() ?: return null
    return Triple(parts[0], InetSocketAddress(ip, port), parts[1])
}
