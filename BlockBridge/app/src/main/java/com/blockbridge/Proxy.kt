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
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
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

    fun clear() = map.clear()
}

/** Routing settings read on every connection, cached here instead of SharedPreferences. */
object RouteConfig {
    @Volatile var vpnOn = false
    @Volatile var bypassApps: Set<String> = emptySet()

    fun reload(ctx: android.content.Context) {
        val s = Settings(ctx)
        vpnOn = s.vpnEnabled
        bypassApps = s.vpnBypassApps
        LoadBalancer.mode = s.lbMode
    }
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
            var verdict = Filter.match(q.name)
            if (!verdict.blocked && DohGuard.enabled && DohGuard.isDohHost(q.name)) {
                verdict = Filter.Verdict(true, DohGuard.BY)
            }
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
 *  - refuses connections to blocked hostnames and encrypted-DNS bypass endpoints
 *  - finds the app behind each connection (hev tags it with the app's socket address)
 *  - routes each connection direct or through a VPN server ([router]) and records it.
 * BlockBridge itself is excluded from the VPN, so its own sockets go straight out.
 */
class Socks5Server(
    private val isLocalDns: (InetAddress) -> Boolean,
    /** Default routing when no [router] is set: a VPN upstream, or null for direct. */
    private val upstream: () -> SocksUpstream? = { null },
) : Closeable {
    /** Kill switch for the default router: no upstream means refuse, not go direct. */
    @Volatile var requireUpstream = false
    /** Finds the app that owns a socket (Android: ConnectivityManager.getConnectionOwnerUid). */
    @Volatile var appResolver: ((proto: String, src: InetSocketAddress, dst: InetSocketAddress) -> AppInfo?)? = null
    /** Per-connection routing (split tunneling, load balancer); null = default router. */
    @Volatile var router: Router? = null

    private val rnd = SecureRandom()
    val username = token()
    val password = token()
    private val server: ServerSocketChannel = ServerSocketChannel.open().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 512)
    }
    val port: Int get() = (server.localAddress as InetSocketAddress).port
    @Volatile private var running = true
    private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(null, r, "bb-relay", 192 * 1024).apply { isDaemon = true }
    }
    private val pipes = PipePool()

    private fun token() = (1..24).map { "abcdefghijkmnopqrstuvwxyz23456789"[rnd.nextInt(33)] }.joinToString("")

    fun start(): Socks5Server {
        pool.execute {
            while (running) {
                val ch = try { server.accept() } catch (_: IOException) { break }
                pool.execute { session(ch) }
            }
        }
        return this
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        open.clear()
        pipes.close()
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

    private fun session(ch: SocketChannel) {
        track(ch)
        val s = ch.socket()
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()

            // greeting: require username/password so other apps can't use this proxy
            if (input.readUnsignedByte() != 5) return release(ch)
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (methods.none { it.toInt() == 2 }) { out.write(byteArrayOf(5, 0xFF.toByte())); return release(ch) }
            out.write(byteArrayOf(5, 2))
            input.readUnsignedByte()
            val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val source = parseSource(user)
            val ok = (source?.first ?: user) == username && pass == password
            out.write(byteArrayOf(1, if (ok) 0 else 1))
            if (!ok) return release(ch)

            // request
            input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val addr = readAddress(input)
            val port = input.readUnsignedShort()
            when (cmd) {
                1 -> connect(ch, input, out, addr, port, source?.second)
                3 -> udpAssociate(ch, out, source?.second)
                else -> { reply(out, 7); release(ch) }
            }
        } catch (_: Exception) {
            release(ch)
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

    private fun appFor(proto: String, src: InetSocketAddress?, dst: InetSocketAddress): AppInfo? {
        val r = appResolver ?: return null
        return src?.let { runCatching { r(proto, it, dst) }.getOrNull() }
    }

    private fun route(c: Conn): Route {
        router?.let { return it.route(c) }
        val up = upstream()
        return when {
            up != null -> Route.Via(up, "VPN")
            requireUpstream -> Route.Reject("VPN not connected (kill switch)")
            else -> Route.Direct
        }
    }

    // ---------------- TCP ----------------

    private fun connect(
        ch: SocketChannel, input: DataInputStream, out: OutputStream,
        addr: InetAddress, port: Int, src: InetSocketAddress?,
    ) {
        if (port == 53) return tcpDns(ch, input, out)
        if (isLocalDns(addr)) { reply(out, 5); return release(ch) } // e.g. Private DNS probe on 853

        val dst = InetSocketAddress(addr, port)
        val host = DnsCache.host(addr.hostAddress ?: "")
        val conn = ConnTracker.open("TCP", dst, host, appFor("TCP", src, dst))
        val blockedBy = when {
            host != null && Filter.isBlocked(host) -> "blocked by filters"
            DohGuard.blocks(addr, port, host) -> "encrypted-DNS bypass blocked"
            else -> null
        }
        if (blockedBy != null) {
            TrafficStats.blockedConnections.incrementAndGet()
            ConnTracker.finish(conn, Conn.Status.BLOCKED, blockedBy)
            reply(out, 2)
            return release(ch)
        }

        val remote: SocketChannel = try {
            when (val r = route(conn)) {
                is Route.Reject -> {
                    ConnTracker.finish(conn, Conn.Status.BLOCKED, r.reason)
                    reply(out, 2); return release(ch)
                }
                is Route.Via -> { conn.route = r.name; r.up.connectChannel(addr, port) }
                Route.Direct -> SocketChannel.open().apply {
                    socket().connect(dst, 10_000)
                    socket().tcpNoDelay = true
                }
            }
        } catch (e: IOException) {
            ConnTracker.finish(conn, Conn.Status.FAILED, e.message ?: "connect failed")
            reply(out, if (addr is Inet4Address) 5 else 4)
            return release(ch)
        }
        track(remote)
        reply(out, 0)
        TrafficStats.connections.incrementAndGet()
        TrafficStats.active.incrementAndGet()
        pipes.splice(ch, remote, conn) {
            open.remove(ch); open.remove(remote)
            TrafficStats.active.decrementAndGet()
            ConnTracker.finish(conn, Conn.Status.CLOSED)
        }
    }

    /** DNS over TCP (any server): 2-byte length + message, possibly several per connection. */
    private fun tcpDns(ch: SocketChannel, input: DataInputStream, out: OutputStream) {
        reply(out, 0)
        ch.socket().soTimeout = 30_000
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
        release(ch)
    }

    // ---------------- UDP ----------------

    private fun udpAssociate(ch: SocketChannel, out: OutputStream, src: InetSocketAddress?) {
        val s = ch.socket()
        // Default routing with the kill switch on and no VPN: refuse right away so apps fail fast.
        if (router == null && requireUpstream && upstream() == null) { reply(out, 2); return release(ch) }
        val local = track(DatagramSocket(0, InetAddress.getByName("127.0.0.1")))
        reply(out, 0, local.localAddress, local.localPort)
        s.soTimeout = 0
        val client = AtomicReference<SocketAddress?>(null)
        // The outbound side is chosen on the first non-DNS datagram, once the app is known.
        val remoteRef = AtomicReference<UdpOut?>(null)
        val connRef = AtomicReference<Conn?>(null)

        fun startReceiver(remote: UdpOut, conn: Conn) = pool.execute {
            val buf = ByteArray(65_535)
            try {
                while (true) {
                    val d = remote.receive(buf) ?: continue
                    val to = client.get() ?: continue
                    val wrapped = udpHeader(d.addr, d.port, d.data)
                    local.send(DatagramPacket(wrapped, wrapped.size, to))
                    ConnTracker.traffic(conn, 0, d.data.size.toLong())
                }
            } catch (_: IOException) { }
        }

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
                        continue
                    }
                    var remote = remoteRef.get()
                    if (remote == null) {
                        val dst = InetSocketAddress(d.addr, d.port)
                        val host = DnsCache.host(d.addr.hostAddress ?: "")
                        val conn = ConnTracker.open("UDP", dst, host, appFor("UDP", src, dst))
                        connRef.set(conn)
                        val blockedBy = when {
                            host != null && Filter.isBlocked(host) -> "blocked by filters"
                            DohGuard.blocks(d.addr, d.port, host) -> "encrypted-DNS bypass blocked"
                            else -> null
                        }
                        if (blockedBy != null) {
                            TrafficStats.blockedConnections.incrementAndGet()
                            ConnTracker.finish(conn, Conn.Status.BLOCKED, blockedBy)
                            break
                        }
                        remote = try {
                            when (val r = route(conn)) {
                                is Route.Reject -> { ConnTracker.finish(conn, Conn.Status.BLOCKED, r.reason); break }
                                is Route.Via -> { conn.route = r.name; r.up.associate() }
                                Route.Direct -> DirectUdp()
                            }
                        } catch (e: IOException) {
                            ConnTracker.finish(conn, Conn.Status.FAILED, e.message ?: "UDP not available")
                            break
                        }
                        track(remote)
                        remoteRef.set(remote)
                        startReceiver(remote, conn)
                    }
                    remote.send(d.addr, d.port, d.data)
                    connRef.get()?.let { ConnTracker.traffic(it, d.data.size.toLong(), 0) }
                }
            } catch (_: IOException) { }
        }
        // The association lives as long as its TCP control connection.
        try { while (s.getInputStream().read() >= 0) { } } catch (_: IOException) { }
        release(ch, local, remoteRef.get())
        connRef.get()?.let { if (it.end == 0L) ConnTracker.finish(it, Conn.Status.CLOSED) }
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
        val s = SocketChannel.open().socket() // channel-backed so the pipe pool can splice it
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

    /** Same as [connect], for the relay's pipe pool. */
    fun connectChannel(addr: InetAddress, port: Int): SocketChannel = connect(addr, port).channel

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
