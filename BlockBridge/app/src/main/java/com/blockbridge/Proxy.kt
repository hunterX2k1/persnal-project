package com.blockbridge

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** IP → hostname, learned from DNS answers, so connections can be matched to a hostname. */
object DnsCache {
    private val map = ConcurrentHashMap<String, String>()

    fun put(ip: String, host: String) {
        if (map.size > 50_000) map.clear()
        map[ip] = host
    }

    fun host(ip: String): String? = map[ip]
}

/** Applies the uBO filter to a DNS query and resolves allowed ones upstream. */
object DnsHandler {
    /** VPN tunnel to resolve through (null = resolve directly). */
    @Volatile var via: SocksUpstream? = null
    /** Resolvers inside the VPN (from the WireGuard config's DNS line). */
    @Volatile var viaDns: List<InetAddress> = emptyList()
    /** A local resolver that already goes through the VPN (Tor's DNSPort). */
    @Volatile var viaUdp: InetSocketAddress? = null
    /** Kill switch: with a VPN selected but not connected, don't leak lookups. */
    @Volatile var requireVia = false

    /** Returns the reply to send back, or null if the upstream servers didn't answer. */
    fun handle(query: ByteArray, tcp: Boolean = false): ByteArray? {
        val q = Dns.parseQuestion(query)
        if (q != null) {
            val verdict = Filter.match(q.name)
            Stats.record(q.name, verdict)
            if (verdict.blocked) return Dns.blockedReply(query, q)
        }
        val up = via
        val udpVia = viaUdp
        val resp = when {
            up != null && udpVia != null -> forwardUdpTo(udpVia, query)
            up != null -> forwardVia(up, query)
            requireVia -> null
            tcp -> forwardTcp(query)
            else -> forwardUdp(query)
        } ?: return null
        if (q != null) for (ip in Dns.answerAddresses(resp)) DnsCache.put(ip, q.name)
        return resp
    }

    private fun forwardUdpTo(server: InetSocketAddress, query: ByteArray): ByteArray? = try {
        DatagramSocket().use { sock ->
            sock.soTimeout = 8000 // Tor lookups are slower
            sock.send(DatagramPacket(query, query.size, server))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            buf.copyOf(p.length)
        }
    } catch (_: IOException) { null }

    private fun forwardUdp(query: ByteArray): ByteArray? {
        for (server in Upstream.servers) {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 4000
                    sock.send(DatagramPacket(query, query.size, server, Upstream.port))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    return buf.copyOf(p.length)
                }
            } catch (_: IOException) { /* try the next server */ }
        }
        return null
    }

    /** DNS over TCP through the VPN, so answers match the VPN server's country. */
    private fun forwardVia(up: SocksUpstream, query: ByteArray): ByteArray? {
        for (server in viaDns.ifEmpty { Upstream.servers }) {
            try {
                up.connect(server, 53).use { s -> return tcpExchange(s, query) }
            } catch (_: IOException) { }
        }
        return null
    }

    private fun tcpExchange(s: Socket, query: ByteArray): ByteArray {
        s.soTimeout = 5000
        val out = s.getOutputStream()
        out.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
        out.write(query)
        out.flush()
        val input = DataInputStream(s.getInputStream())
        val len = input.readUnsignedShort()
        return ByteArray(len).also { input.readFully(it) }
    }

    private fun forwardTcp(query: ByteArray): ByteArray? {
        for (server in Upstream.servers) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(server, Upstream.port), 4000)
                    s.soTimeout = 5000
                    val out = s.getOutputStream()
                    out.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
                    out.write(query)
                    out.flush()
                    val input = DataInputStream(s.getInputStream())
                    val len = input.readUnsignedShort()
                    return ByteArray(len).also { input.readFully(it) }
                }
            } catch (_: IOException) { }
        }
        return null
    }
}

/** Counters for the full-tunnel mode. */
object TrafficStats {
    val connections = AtomicLong()
    val active = AtomicInteger()
    val blockedConnections = AtomicLong()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
}

/**
 * Local SOCKS5 server (RFC 1928 CONNECT + UDP ASSOCIATE, RFC 1929 password auth) that every
 * app's traffic reaches through the tunnel. It:
 *  - answers all DNS (UDP or TCP, to any server — so apps that hard-code 8.8.8.8 are filtered too)
 *  - refuses connections to hostnames the filters block (when the IP came from a recent lookup)
 *  - relays everything else to the internet.
 * BlockBridge itself is excluded from the VPN, so its own sockets go straight out.
 */
class Socks5Server(
    private val isLocalDns: (InetAddress) -> Boolean,
    /** Where allowed traffic goes: a VPN tunnel, or null for straight to the internet. */
    private val upstream: () -> SocksUpstream? = { null },
) : Closeable {
    /** Kill switch: when true and there's no upstream, refuse instead of going direct. */
    @Volatile var requireUpstream = false
    private val rnd = SecureRandom()
    val username = token()
    val password = token()
    private val server = ServerSocket(0, 512, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true
    private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(null, r, "bb-relay", 256 * 1024).apply { isDaemon = true }
    }

    private fun token() = (1..24).map { "abcdefghijkmnopqrstuvwxyz23456789"[rnd.nextInt(33)] }.joinToString("")

    fun start(): Socks5Server {
        pool.execute {
            while (running) {
                val s = try { server.accept() } catch (_: IOException) { break }
                pool.execute { session(s) }
            }
        }
        return this
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        open.clear()
        pool.shutdownNow()
    }

    /** Drops every relayed connection so apps reconnect through the new route. */
    fun resetConnections() {
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun <T : Closeable> track(c: T): T = c.also { open.add(it) }
    private fun release(vararg cs: Closeable?) = cs.forEach { c ->
        if (c != null) { open.remove(c); runCatching { c.close() } }
    }

    private fun session(s: Socket) {
        track(s)
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()

            // greeting: require username/password so other apps can't use this proxy
            if (input.readUnsignedByte() != 5) return release(s)
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (methods.none { it.toInt() == 2 }) { out.write(byteArrayOf(5, 0xFF.toByte())); return release(s) }
            out.write(byteArrayOf(5, 2))
            input.readUnsignedByte()
            val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val ok = user == username && pass == password
            out.write(byteArrayOf(1, if (ok) 0 else 1))
            if (!ok) return release(s)

            // request
            input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val addr = readAddress(input)
            val port = input.readUnsignedShort()
            when (cmd) {
                1 -> connect(s, input, out, addr, port)
                3 -> udpAssociate(s, out)
                else -> { reply(out, 7); release(s) }
            }
        } catch (_: Exception) {
            release(s)
        }
    }

    private fun readAddress(input: DataInputStream): InetAddress = when (input.readUnsignedByte()) {
        1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) })
        4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) })
        3 -> InetAddress.getByName(String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }))
        else -> throw IOException("bad address type")
    }

    private fun reply(out: OutputStream, rep: Int, bind: InetAddress? = null, bindPort: Int = 0) {
        val a = (bind ?: InetAddress.getByAddress(ByteArray(4))).address
        val b = ByteArray(6 + a.size)
        b[0] = 5; b[1] = rep.toByte(); b[3] = if (a.size == 4) 1 else 4
        a.copyInto(b, 4)
        b[4 + a.size] = (bindPort shr 8).toByte(); b[5 + a.size] = bindPort.toByte()
        out.write(b)
        out.flush()
    }

    // ---------------- TCP ----------------

    private fun connect(s: Socket, input: DataInputStream, out: OutputStream, addr: InetAddress, port: Int) {
        if (port == 53) return tcpDns(s, input, out)
        if (isLocalDns(addr)) { reply(out, 5); return release(s) } // e.g. Private DNS probe on 853

        val host = DnsCache.host(addr.hostAddress ?: "")
        if (host != null && Filter.isBlocked(host)) {
            TrafficStats.blockedConnections.incrementAndGet()
            reply(out, 2)
            return release(s)
        }

        val up = upstream()
        if (up == null && requireUpstream) { reply(out, 2); return release(s) }
        val remote = try {
            track(up?.connect(addr, port) ?: Socket().apply {
                connect(InetSocketAddress(addr, port), 10_000)
                tcpNoDelay = true
            })
        } catch (e: IOException) {
            reply(out, if (addr is Inet4Address) 5 else 4)
            return release(s)
        }
        reply(out, 0)
        s.soTimeout = 0
        TrafficStats.connections.incrementAndGet()
        TrafficStats.active.incrementAndGet()

        val done = AtomicInteger()
        val finish = {
            if (done.incrementAndGet() == 2) {
                release(s, remote)
                TrafficStats.active.decrementAndGet()
            }
        }
        pool.execute {
            pipe(input, remote.getOutputStream(), TrafficStats.bytesUp) { runCatching { remote.shutdownOutput() } }
            finish()
        }
        pipe(remote.getInputStream(), out, TrafficStats.bytesDown) { runCatching { s.shutdownOutput() } }
        finish()
    }

    private fun pipe(from: InputStream, to: OutputStream, counter: AtomicLong, onEof: () -> Unit) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                counter.addAndGet(n.toLong())
            }
            onEof()
        } catch (_: IOException) {
            onEof()
        }
    }

    /** DNS over TCP (any server): 2-byte length + message, possibly several per connection. */
    private fun tcpDns(s: Socket, input: DataInputStream, out: OutputStream) {
        reply(out, 0)
        s.soTimeout = 30_000
        try {
            while (true) {
                val len = input.readUnsignedShort()
                val query = ByteArray(len).also { input.readFully(it) }
                val resp = DnsHandler.handle(query, tcp = true) ?: break
                out.write(byteArrayOf((resp.size shr 8).toByte(), resp.size.toByte()))
                out.write(resp)
                out.flush()
            }
        } catch (_: IOException) { }
        release(s)
    }

    // ---------------- UDP ----------------

    private fun udpAssociate(s: Socket, out: OutputStream) {
        val up = upstream()
        if (up == null && requireUpstream) { reply(out, 2); return release(s) }
        val remote: UdpOut = try {
            track(up?.associate() ?: DirectUdp())
        } catch (_: IOException) {
            reply(out, 1); return release(s)
        }
        val local = track(DatagramSocket(0, InetAddress.getByName("127.0.0.1")))
        reply(out, 0, local.localAddress, local.localPort)
        s.soTimeout = 0
        val client = AtomicReference<SocketAddress?>(null)

        // app -> internet
        pool.execute {
            val buf = ByteArray(65_535)
            try {
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    local.receive(p)
                    client.set(p.socketAddress)
                    val d = parseUdpHeader(buf, p.length) ?: continue
                    if (d.port == 53) {
                        val from = client.get() ?: continue
                        pool.execute {
                            val resp = DnsHandler.handle(d.data) ?: return@execute
                            val wrapped = udpHeader(d.addr, d.port, resp)
                            runCatching { local.send(DatagramPacket(wrapped, wrapped.size, from)) }
                        }
                    } else {
                        remote.send(d.addr, d.port, d.data)
                        TrafficStats.bytesUp.addAndGet(d.data.size.toLong())
                    }
                }
            } catch (_: IOException) { }
        }
        // internet -> app
        pool.execute {
            val buf = ByteArray(65_535)
            try {
                while (true) {
                    val d = remote.receive(buf) ?: continue
                    val to = client.get() ?: continue
                    val wrapped = udpHeader(d.addr, d.port, d.data)
                    local.send(DatagramPacket(wrapped, wrapped.size, to))
                    TrafficStats.bytesDown.addAndGet(d.data.size.toLong())
                }
            } catch (_: IOException) { }
        }
        // The association lives as long as its TCP control connection.
        try { while (s.getInputStream().read() >= 0) { } } catch (_: IOException) { }
        release(s, local, remote)
    }

    class UdpDatagram(val addr: InetAddress, val port: Int, val data: ByteArray)

    companion object {
        /** Parses "RSV RSV FRAG ATYP ADDR PORT DATA" (RFC 1928 §7). Fragments are dropped. */
        fun parseUdpHeader(b: ByteArray, len: Int): UdpDatagram? {
            if (len < 10 || b[2].toInt() != 0) return null
            val (addr, off) = when (b[3].toInt()) {
                1 -> InetAddress.getByAddress(b.copyOfRange(4, 8)) to 8
                4 -> if (len < 22) return null else InetAddress.getByAddress(b.copyOfRange(4, 20)) to 20
                else -> return null
            }
            val port = Packets.u16(b, off)
            return UdpDatagram(addr, port, b.copyOfRange(off + 2, len))
        }

        fun udpHeader(addr: InetAddress, port: Int, data: ByteArray): ByteArray {
            val a = addr.address
            val out = ByteArray(6 + a.size + data.size)
            out[3] = if (a.size == 4) 1 else 4
            a.copyInto(out, 4)
            out[4 + a.size] = (port shr 8).toByte(); out[5 + a.size] = port.toByte()
            data.copyInto(out, 6 + a.size)
            return out
        }
    }
}

/** Where relayed UDP goes: straight out, or through the VPN. */
interface UdpOut : Closeable {
    fun send(addr: InetAddress, port: Int, data: ByteArray)
    /** Blocks for the next datagram; null for one to skip. */
    fun receive(buf: ByteArray): Socks5Server.UdpDatagram?
}

class DirectUdp : UdpOut {
    private val sock = DatagramSocket()
    override fun send(addr: InetAddress, port: Int, data: ByteArray) =
        sock.send(DatagramPacket(data, data.size, InetSocketAddress(addr, port)))
    override fun receive(buf: ByteArray): Socks5Server.UdpDatagram? {
        val p = DatagramPacket(buf, buf.size)
        sock.receive(p)
        return Socks5Server.UdpDatagram(p.address, p.port, buf.copyOf(p.length))
    }
    override fun close() = sock.close()
}

/**
 * SOCKS5 client for the local VPN helper (bbwg). Every connection opened here leaves
 * the phone through the WireGuard tunnel.
 */
class SocksUpstream(val port: Int, private val user: String, private val pass: String) {
    private val lo: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun open(): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(lo, port), 3000)
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 2))
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("vpn: no auth")
            out.write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() +
                byteArrayOf(pass.length.toByte()) + pass.toByteArray())
            input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw IOException("vpn: auth failed")
            return s
        } catch (e: IOException) {
            s.close(); throw e
        }
    }

    /** Sends a request and returns the bound address from the reply. */
    private fun request(s: Socket, cmd: Int, target: ByteArray, port: Int): InetSocketAddress {
        s.getOutputStream().write(byteArrayOf(5, cmd.toByte(), 0) + target +
            byteArrayOf((port shr 8).toByte(), port.toByte()))
        val input = DataInputStream(s.getInputStream())
        input.readUnsignedByte()
        val rep = input.readUnsignedByte()
        input.readUnsignedByte()
        val addr = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) })
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) })
            3 -> { val n = input.readUnsignedByte(); input.readFully(ByteArray(n)); lo }
            else -> throw IOException("vpn: bad reply")
        }
        val bport = input.readUnsignedShort()
        if (rep != 0) throw IOException("vpn: request refused ($rep)")
        return InetSocketAddress(if (addr.isAnyLocalAddress) lo else addr, bport)
    }

    private fun ipTarget(a: InetAddress): ByteArray =
        byteArrayOf(if (a.address.size == 4) 1 else 4) + a.address

    fun connect(addr: InetAddress, port: Int): Socket = open().also { s ->
        try { request(s, 1, ipTarget(addr), port); s.soTimeout = 0 } catch (e: IOException) { s.close(); throw e }
    }

    /** Connects by hostname; the name is resolved inside the tunnel. */
    fun connect(host: String, port: Int): Socket = open().also { s ->
        val h = host.toByteArray()
        try { request(s, 1, byteArrayOf(3, h.size.toByte()) + h, port); s.soTimeout = 0 } catch (e: IOException) { s.close(); throw e }
    }

    fun associate(): UdpOut {
        val ctrl = open()
        try {
            val relay = request(ctrl, 3, byteArrayOf(1, 0, 0, 0, 0), 0)
            ctrl.soTimeout = 0
            return SocksUdp(ctrl, relay)
        } catch (e: IOException) {
            ctrl.close(); throw e
        }
    }

    private class SocksUdp(private val ctrl: Socket, private val relay: InetSocketAddress) : UdpOut {
        private val sock = DatagramSocket()
        override fun send(addr: InetAddress, port: Int, data: ByteArray) {
            val pkt = Socks5Server.udpHeader(addr, port, data)
            sock.send(DatagramPacket(pkt, pkt.size, relay))
        }
        override fun receive(buf: ByteArray): Socks5Server.UdpDatagram? {
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            return Socks5Server.parseUdpHeader(buf, p.length)
        }
        override fun close() { runCatching { ctrl.close() }; sock.close() }
    }
}

/**
 * JNI bridge to hev-socks5-tunnel (MIT), which turns the VPN's raw IP packets into
 * SOCKS5 connections to [Socks5Server]. Optional: if the native library isn't in the
 * build, BlockBridge falls back to DNS-only filtering.
 */
object HevTunnel {
    val available: Boolean = try {
        System.loadLibrary("hev-socks5-tunnel"); true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic private external fun TProxyStopService(): Boolean
    @JvmStatic private external fun TProxyIsRunning(): Boolean
    // hev registers all four methods at load time; every one must exist or loading fails.
    @JvmStatic private external fun TProxyGetStats(): LongArray

    fun config(socks: Socks5Server, ipv6: Boolean) = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 8500")
        appendLine("  ipv4: 10.111.222.1")
        if (ipv6) appendLine("  ipv6: 'fd00:b10c:b10c::1'")
        appendLine("socks5:")
        appendLine("  port: ${socks.port}")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        appendLine("  username: '${socks.username}'")
        appendLine("  password: '${socks.password}'")
        appendLine("misc:")
        appendLine("  task-stack-size: 81920")
        appendLine("  connect-timeout: 10000")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: warn")
    }

    fun start(configPath: String, fd: Int) = available && TProxyStartService(configPath, fd)
    fun stop() { if (available) runCatching { TProxyStopService() } }
    fun isRunning() = available && runCatching { TProxyIsRunning() }.getOrDefault(false)
    /** [txPackets, txBytes, rxPackets, rxBytes] seen on the tunnel. */
    fun stats(): LongArray? = if (available) runCatching { TProxyGetStats() }.getOrNull() else null
}
