package com.netsplit.app.vpn

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue

data class FlowKey(
    val proto: Int,
    val src: InetAddress, val sport: Int,
    val dst: InetAddress, val dport: Int,
)

/**
 * Userspace TCP/UDP forwarder. Packets from the VPN interface are terminated here and
 * re-sent through ordinary sockets; reading from / writing to those sockets is throttled
 * per app with token buckets. All session state is owned by the single engine thread.
 */
class Engine(
    private val tunOut: OutputStream,
    val limiters: List<AppLimiter>,
    private val uidOf: (proto: Int, local: InetSocketAddress, remote: InetSocketAddress) -> Int,
    @Volatile var blockQuic: Boolean = true,
    private val log: (String) -> Unit = {},
    /** Where to actually connect for a given destination (identity on device; remapped in tests). */
    internal val dial: (InetAddress, Int) -> InetSocketAddress = { a, p -> InetSocketAddress(a, p) },
) {
    internal val selector: Selector = Selector.open()
    private val inbound = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var running = true

    private val tcp = HashMap<FlowKey, TcpSession>()
    private val udp = HashMap<FlowKey, UdpSession>()
    internal val readBuf: ByteBuffer = ByteBuffer.allocate(65536)
    private var lastTimer = 0L
    private var lastSweep = 0L

    val tcpCount get() = tcp.size

    /** Called from the TUN reader thread. */
    fun submit(packet: ByteArray) {
        inbound.add(packet)
        selector.wakeup()
    }

    fun stop() {
        running = false
        selector.wakeup()
    }

    internal fun writeTun(b: ByteArray) {
        try {
            tunOut.write(b)
        } catch (e: IOException) {
            log("tun write failed: $e")
        }
    }

    private fun limiterFor(proto: Int, ip: IpInfo, sport: Int, dport: Int): AppLimiter? {
        val uid = try {
            uidOf(
                proto,
                InetSocketAddress(InetAddress.getByAddress(ip.src), sport),
                InetSocketAddress(InetAddress.getByAddress(ip.dst), dport),
            )
        } catch (e: Exception) {
            -1
        }
        limiters.firstOrNull { it.uid == uid }?.let { return it }
        // Only the selected apps are routed into the VPN, so with a single app every flow is its.
        // With a game + background app and an unknown owner, treat it as the (limited) background app.
        return if (limiters.size == 1) limiters[0] else limiters.firstOrNull { !it.monitorOnly }
    }

    fun run() {
        try {
            while (running) {
                val starving = tcp.values.any { it.waitingTokens || it.upWaiting }
                selector.select(if (starving) 8L else 200L)
                if (!running) break

                while (true) {
                    val p = inbound.poll() ?: break
                    try {
                        handlePacket(p)
                    } catch (e: Exception) {
                        log("packet error: $e")
                    }
                }

                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    it.remove()
                    if (!k.isValid) continue
                    when (val a = k.attachment()) {
                        is TcpSession -> a.onSelected(k)
                        is UdpSession -> a.onReadable()
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastTimer >= 8) {
                    lastTimer = now
                    for (s in tcp.values.toList()) s.onTimer(now)
                }
                if (now - lastSweep >= 5000) {
                    lastSweep = now
                    sweep(now)
                }
            }
        } finally {
            for (s in tcp.values.toList()) s.close(sendRst = false)
            for (s in udp.values.toList()) s.close()
            tcp.clear(); udp.clear()
            try { selector.close() } catch (_: IOException) {}
        }
    }

    private fun sweep(now: Long) {
        for (s in tcp.values.toList()) s.checkIdle(now)
        for (s in udp.values.toList()) {
            val limit = if (s.remPort == 53) 15_000 else 90_000
            if (now - s.lastActivity > limit) s.close()
        }
    }

    internal fun removeTcp(s: TcpSession) { tcp.remove(s.key) }
    internal fun removeUdp(s: UdpSession) { udp.remove(s.key) }

    private fun handlePacket(d: ByteArray) {
        val ip = IpInfo.parse(d) ?: return
        when (ip.proto) {
            Pk.TCP -> handleTcp(ip)
            Pk.UDP -> handleUdp(ip)
            else -> {} // ICMP etc. are dropped
        }
    }

    private fun handleTcp(ip: IpInfo) {
        val d = ip.data
        val o = ip.l4Off
        if (ip.l4Len < 20) return
        val doff = ((d[o + 12].toInt() ushr 4) and 0xf) * 4
        if (doff < 20 || doff > ip.l4Len) return
        val seg = TcpSegment(
            sport = Pk.u16(d, o), dport = Pk.u16(d, o + 2),
            seq = Pk.u32(d, o + 4), ack = Pk.u32(d, o + 8),
            flags = Pk.u8(d, o + 13), window = Pk.u16(d, o + 14),
            data = d, optOff = o + 20, optLen = doff - 20,
            payOff = o + doff, payLen = ip.l4Len - doff,
        )
        val key = FlowKey(
            Pk.TCP, InetAddress.getByAddress(ip.src), seg.sport,
            InetAddress.getByAddress(ip.dst), seg.dport,
        )
        val s = tcp[key]
        if (s != null) {
            s.onPacket(seg)
            return
        }
        if (seg.has(Pk.RST)) return
        if (seg.has(Pk.SYN) && !seg.has(Pk.ACK)) {
            val lim = limiterFor(Pk.TCP, ip, seg.sport, seg.dport)
            val ns = TcpSession(this, key, ip.v6, ip.src, seg.sport, ip.dst, seg.dport, lim)
            tcp[key] = ns
            ns.start(seg)
            return
        }
        // Unknown connection (e.g. from before the VPN started): reset it so the app reconnects.
        val fin = if (seg.has(Pk.FIN)) 1 else 0
        val syn = if (seg.has(Pk.SYN)) 1 else 0
        writeTun(
            Pk.tcp(
                ip.v6, ip.dst, ip.src, seg.dport, seg.sport,
                if (seg.has(Pk.ACK)) seg.ack else 0, seg.seq + seg.payLen + fin + syn,
                Pk.RST or Pk.ACK, 0,
            ),
        )
    }

    private fun handleUdp(ip: IpInfo) {
        val d = ip.data
        val o = ip.l4Off
        if (ip.l4Len < 8) return
        val sport = Pk.u16(d, o)
        val dport = Pk.u16(d, o + 2)
        val udpLen = Pk.u16(d, o + 4).coerceAtMost(ip.l4Len)
        if (udpLen < 8) return
        val key = FlowKey(
            Pk.UDP, InetAddress.getByAddress(ip.src), sport,
            InetAddress.getByAddress(ip.dst), dport,
        )
        var s = udp[key]
        if (s == null) {
            val lim = limiterFor(Pk.UDP, ip, sport, dport)
            if (blockQuic && lim != null && !lim.monitorOnly && dport == 443) {
                // QUIC ignores our TCP-level throttling tricks less gracefully; make the app use TCP.
                writeTun(Pk.portUnreachable(ip))
                return
            }
            s = try {
                UdpSession.open(this, key, ip.v6, ip.src, sport, ip.dst, dport, lim)
            } catch (e: IOException) {
                log("udp open failed: $e")
                writeTun(Pk.portUnreachable(ip))
                return
            }
            udp[key] = s
        }
        s.sendToServer(d, o + 8, udpLen - 8)
    }
}

class TcpSegment(
    val sport: Int, val dport: Int,
    val seq: Int, val ack: Int,
    val flags: Int, val window: Int,
    val data: ByteArray, val optOff: Int, val optLen: Int,
    val payOff: Int, val payLen: Int,
) {
    fun has(f: Int) = (flags and f) != 0
}

internal class UdpSession private constructor(
    private val engine: Engine,
    val key: FlowKey,
    private val v6: Boolean,
    private val appAddr: ByteArray, private val appPort: Int,
    private val remAddr: ByteArray, val remPort: Int,
    private val limiter: AppLimiter?,
    private val ch: DatagramChannel,
) {
    var lastActivity = System.currentTimeMillis()

    companion object {
        fun open(
            engine: Engine, key: FlowKey, v6: Boolean,
            appAddr: ByteArray, appPort: Int, remAddr: ByteArray, remPort: Int, limiter: AppLimiter?,
        ): UdpSession {
            val ch = DatagramChannel.open()
            try {
                ch.configureBlocking(false)
                ch.connect(engine.dial(InetAddress.getByAddress(remAddr), remPort))
                val s = UdpSession(engine, key, v6, appAddr, appPort, remAddr, remPort, limiter, ch)
                ch.register(engine.selector, SelectionKey.OP_READ, s)
                return s
            } catch (e: IOException) {
                ch.close()
                throw e
            }
        }
    }

    fun sendToServer(d: ByteArray, off: Int, len: Int) {
        lastActivity = System.currentTimeMillis()
        if (limiter != null && !limiter.up.tryConsume(len)) return // over the limit: drop
        try {
            ch.write(ByteBuffer.wrap(d, off, len))
            limiter?.bytesUp?.addAndGet(len.toLong())
        } catch (e: IOException) {
            // ICMP errors etc. – keep the session, the app will retry or time out.
        }
    }

    fun onReadable() {
        val buf = engine.readBuf
        repeat(32) {
            buf.clear()
            val n = try {
                ch.read(buf)
            } catch (e: IOException) {
                return
            }
            if (n <= 0) return
            lastActivity = System.currentTimeMillis()
            if (limiter != null) {
                if (!limiter.down.tryConsume(n)) return@repeat // drop; the protocol backs off
                limiter.bytesDown.addAndGet(n.toLong())
            }
            engine.writeTun(Pk.udp(v6, remAddr, appAddr, remPort, appPort, buf.array(), 0, n))
        }
    }

    fun close() {
        try { ch.close() } catch (_: IOException) {}
        engine.removeUdp(this)
    }
}
