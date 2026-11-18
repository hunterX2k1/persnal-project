package com.netsplit.app.vpn

import java.io.IOException
import java.net.InetAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.random.Random

/**
 * One TCP connection from an app. Towards the app we behave like the remote server
 * (we answer the handshake, ACK data, send data segments); towards the internet we use
 * a normal non-blocking SocketChannel.
 *
 * Throttling: bytes are only pulled out of the server socket when the app's token bucket
 * allows it. The kernel socket buffer then fills up and the real server slows down, so the
 * limited app genuinely uses less of the mobile link.
 */
internal class TcpSession(
    private val engine: Engine,
    val key: FlowKey,
    private val v6: Boolean,
    private val appAddr: ByteArray, private val appPort: Int,
    private val remAddr: ByteArray, private val remPort: Int,
    private val limiter: AppLimiter?,
) {
    private enum class State { CONNECTING, SYN_RCVD, ESTABLISHED, CLOSED }

    private companion object {
        const val TO_SERVER_MAX = 65535 // our advertised receive window (no window scaling on our side)
        const val TO_APP_MAX = 256 * 1024
        const val MIN_READ = 8 * 1024
        const val RTO_MS = 600L
        const val CONNECT_TIMEOUT_MS = 20_000L
    }

    private var state = State.CONNECTING
    private val ch: SocketChannel = SocketChannel.open()
    private var selKey: SelectionKey? = null

    private var rcvNxt = 0
    private val ourIsn = Random.nextInt()
    private var sndUna = ourIsn + 1
    private var sndNxt = ourIsn + 1

    private var appWnd = 65535L
    private var appWs = 0
    private var appSentWs = false
    private var mss = 536

    private val toApp = ByteQueue(16384)    // index 0 == sndUna
    private val toServer = ByteQueue(16384)

    private var serverEof = false
    private var appFin = false
    private var finSent = false
    private var finAcked = false
    private var outputShut = false

    private var rtoAt = 0L
    private var synAckAt = 0L
    private var dupAcks = 0
    private var lastAdvWnd = TO_SERVER_MAX
    private val created = System.currentTimeMillis()
    private var lastActivity = created

    var waitingTokens = false
        private set
    var upWaiting = false
        private set

    // ---------------------------------------------------------------- setup

    fun start(syn: TcpSegment) {
        rcvNxt = syn.seq + 1
        parseOptions(syn)
        try {
            ch.configureBlocking(false)
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true)
            if (limiter != null && !limiter.down.unlimited) {
                // A small kernel buffer keeps the throttle tight (less data sneaks in as bursts).
                val rb = (limiter.rateBytesPerSec / 4).coerceIn(16_384, 262_144).toInt()
                ch.setOption(StandardSocketOptions.SO_RCVBUF, rb)
            }
            val connected = ch.connect(engine.dial(InetAddress.getByAddress(remAddr), remPort))
            selKey = ch.register(engine.selector, if (connected) 0 else SelectionKey.OP_CONNECT, this)
            if (connected) onConnected()
        } catch (e: IOException) {
            failConnect()
        }
    }

    private fun parseOptions(s: TcpSegment) {
        val d = s.data
        var i = s.optOff
        val end = s.optOff + s.optLen
        var appMss = 536
        while (i < end) {
            val kind = Pk.u8(d, i)
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= end) break
            val len = Pk.u8(d, i + 1)
            if (len < 2 || i + len > end) break
            when (kind) {
                2 -> if (len == 4) appMss = Pk.u16(d, i + 2)
                3 -> if (len == 3) { appWs = Pk.u8(d, i + 2).coerceAtMost(14); appSentWs = true }
            }
            i += len
        }
        val ours = if (v6) 1440 else 1460
        mss = appMss.coerceIn(536, ours)
        appWnd = s.window.toLong() // windows in SYN are never scaled
    }

    private fun onConnected() {
        state = State.SYN_RCVD
        sendSynAck()
        updateInterest()
    }

    private fun sendSynAck() {
        val ours = if (v6) 1440 else 1460
        val opts = if (appSentWs) {
            byteArrayOf(2, 4, (ours ushr 8).toByte(), ours.toByte(), 1, 3, 3, 0)
        } else {
            byteArrayOf(2, 4, (ours ushr 8).toByte(), ours.toByte())
        }
        send(ourIsn, Pk.SYN or Pk.ACK, opts)
        synAckAt = System.currentTimeMillis()
    }

    private fun failConnect() {
        engine.writeTun(Pk.tcp(v6, remAddr, appAddr, remPort, appPort, 0, rcvNxt, Pk.RST or Pk.ACK, 0))
        close(sendRst = false)
    }

    // ---------------------------------------------------------------- packets from the app

    fun onPacket(s: TcpSegment) {
        if (state == State.CLOSED) return
        lastActivity = System.currentTimeMillis()

        if (s.has(Pk.RST)) {
            close(sendRst = false)
            return
        }
        if (s.has(Pk.SYN)) {
            // Retransmitted SYN: re-answer if we already did.
            if (state == State.SYN_RCVD) sendSynAck()
            return
        }
        if (state == State.CONNECTING) return

        if (state == State.SYN_RCVD) {
            if (s.has(Pk.ACK) && s.ack == ourIsn + 1) {
                state = State.ESTABLISHED
                synAckAt = 0
            } else {
                return
            }
        }

        if (s.has(Pk.ACK)) onAck(s)
        onData(s)

        service()
    }

    private fun onAck(s: TcpSegment) {
        val newWnd = s.window.toLong() shl (if (appSentWs) appWs else 0)
        val delta = s.ack - sndUna
        val inFlight = sndNxt - sndUna
        if (delta > 0 && delta <= inFlight) {
            val dataAcked = minOf(delta, toApp.size)
            toApp.drop(dataAcked)
            sndUna += dataAcked
            if (finSent && delta > dataAcked) {
                finAcked = true
                sndUna += 1
            }
            dupAcks = 0
            rtoAt = if (sndNxt != sndUna) System.currentTimeMillis() + RTO_MS else 0
        } else if (delta == 0 && s.payLen == 0 && !s.has(Pk.FIN) && inFlight > 0 && newWnd == appWnd) {
            if (++dupAcks == 3) retransmit()
        }
        appWnd = newWnd
    }

    private fun onData(s: TcpSegment) {
        val len = s.payLen
        var needAck = false
        if (len > 0) {
            val already = rcvNxt - s.seq
            if (already < 0) {
                // Out of order (a gap) – ask for the missing data again.
                sendAck()
                return
            }
            if (already < len) {
                val room = TO_SERVER_MAX - toServer.size
                val take = minOf(len - already, room)
                if (take > 0) {
                    toServer.append(s.data, s.payOff + already, take)
                    rcvNxt += take
                }
            }
            needAck = true
        }
        if (s.has(Pk.FIN) && !appFin && s.seq + len == rcvNxt) {
            rcvNxt += 1
            appFin = true
            needAck = true
        }
        if (needAck) sendAck()
    }

    // ---------------------------------------------------------------- socket events

    fun onSelected(k: SelectionKey) {
        try {
            if (k.isConnectable) {
                if (ch.finishConnect()) onConnected() else return
            }
            if (k.isValid && k.isReadable) readServer()
            if (k.isValid && k.isWritable) writeServer()
        } catch (e: IOException) {
            if (state == State.CONNECTING) failConnect() else close(sendRst = true)
            return
        }
        afterIo()
    }

    private fun service() {
        try {
            writeServer()
        } catch (e: IOException) {
            close(sendRst = true)
            return
        }
        afterIo()
    }

    private fun afterIo() {
        if (state == State.CLOSED) return
        pumpToApp()
        if (appFin && finAcked && toServer.size == 0) {
            close(sendRst = false)
            return
        }
        updateInterest()
    }

    private fun readServer() {
        if (serverEof || state != State.ESTABLISHED && state != State.SYN_RCVD) return
        val space = TO_APP_MAX - toApp.size
        // Avoid dribbling tiny reads (=> tiny TCP segments) when the buffer is nearly full.
        if (space < MIN_READ && toApp.size > 0) return
        var allow = space
        if (limiter != null) {
            val tokens = limiter.down.available()
            // Wait until at least a full segment's worth of budget has built up.
            if (tokens < minOf(mss, allow)) { waitingTokens = true; return }
            allow = minOf(allow, tokens)
        }
        waitingTokens = false
        val buf = engine.readBuf
        buf.clear()
        buf.limit(minOf(allow, buf.capacity()))
        val n = ch.read(buf)
        if (n < 0) {
            serverEof = true
        } else if (n > 0) {
            lastActivity = System.currentTimeMillis()
            limiter?.down?.consume(n)
            limiter?.bytesDown?.addAndGet(n.toLong())
            toApp.append(buf.array(), 0, n)
        }
    }

    private fun writeServer() {
        if (state == State.CONNECTING) return
        if (toServer.size > 0) {
            var allow = toServer.size
            if (limiter != null) {
                val tokens = limiter.up.available()
                if (tokens < minOf(1024, allow)) { upWaiting = true; return }
                allow = minOf(allow, tokens)
            }
            upWaiting = false
            val n = ch.write(ByteBuffer.wrap(toServer.buf, toServer.head, allow))
            if (n > 0) {
                limiter?.up?.consume(n)
                limiter?.bytesUp?.addAndGet(n.toLong())
                toServer.drop(n)
                // Re-open the app's send window once there is room again.
                val wnd = ourWindow()
                if (lastAdvWnd < mss * 2 && wnd >= TO_SERVER_MAX / 2) sendAck()
            }
        }
        if (toServer.size == 0 && appFin && !outputShut) {
            outputShut = true
            ch.shutdownOutput()
        }
    }

    // ---------------------------------------------------------------- sending to the app

    private fun pumpToApp() {
        if (state != State.ESTABLISHED) return
        while (!finSent) {
            val off = sndNxt - sndUna
            val unsent = toApp.size - off
            if (unsent <= 0) break
            val room = appWnd - off
            if (room <= 0) break
            val n = minOf(unsent.toLong(), mss.toLong(), room).toInt()
            val flags = if (n == unsent) Pk.ACK or Pk.PSH else Pk.ACK
            engine.writeTun(
                Pk.tcp(
                    v6, remAddr, appAddr, remPort, appPort,
                    sndNxt, rcvNxt, flags, ourWindow(), null, toApp, off, n,
                ),
            )
            lastAdvWnd = ourWindow()
            sndNxt += n
        }
        if (serverEof && !finSent && sndNxt - sndUna == toApp.size) {
            send(sndNxt, Pk.FIN or Pk.ACK)
            sndNxt += 1
            finSent = true
        }
        if (sndNxt != sndUna && rtoAt == 0L) rtoAt = System.currentTimeMillis() + RTO_MS
    }

    private fun retransmit() {
        // Go-back-N: the local link almost never loses packets, so keep it simple.
        if (finSent && !finAcked) {
            finSent = false
            sndNxt = sndUna
        } else {
            sndNxt = sndUna
        }
        dupAcks = 0
        rtoAt = 0
        pumpToApp()
    }

    private fun ourWindow() = (TO_SERVER_MAX - toServer.size).coerceAtLeast(0)

    private fun sendAck() {
        send(sndNxt, Pk.ACK)
    }

    private fun send(seq: Int, flags: Int, opts: ByteArray? = null) {
        val w = ourWindow()
        lastAdvWnd = w
        engine.writeTun(Pk.tcp(v6, remAddr, appAddr, remPort, appPort, seq, rcvNxt, flags, w, opts))
    }

    // ---------------------------------------------------------------- timers & teardown

    fun onTimer(now: Long) {
        if (state == State.CLOSED) return
        if (state == State.CONNECTING && now - created > CONNECT_TIMEOUT_MS) {
            failConnect(); return
        }
        if (state == State.SYN_RCVD && synAckAt != 0L && now - synAckAt > 1000) sendSynAck()

        if (waitingTokens || upWaiting) {
            try {
                if (upWaiting) writeServer()
                if (waitingTokens) readServer()
            } catch (e: IOException) {
                close(sendRst = true); return
            }
            afterIo()
            if (state == State.CLOSED) return
        }

        if (rtoAt != 0L && now >= rtoAt) {
            retransmit()
        } else if (state == State.ESTABLISHED && appWnd == 0L && toApp.size > sndNxt - sndUna && now - lastActivity > 500) {
            // Zero-window probe: make the app tell us its current window.
            send(sndNxt - 1, Pk.ACK)
            lastActivity = now
        }
        if (state != State.CLOSED) updateInterest()
    }

    fun checkIdle(now: Long) {
        val idle = now - lastActivity
        val halfClosed = appFin || serverEof
        if (idle > 15 * 60_000L || (halfClosed && idle > 60_000L)) close(sendRst = true)
    }

    private fun updateInterest() {
        val k = selKey ?: return
        if (!k.isValid) return
        var ops = 0
        if (state == State.CONNECTING) {
            ops = SelectionKey.OP_CONNECT
        } else {
            if (!serverEof && !waitingTokens && (toApp.size == 0 || TO_APP_MAX - toApp.size >= MIN_READ)) {
                ops = ops or SelectionKey.OP_READ
            }
            if (toServer.size > 0 && !upWaiting) ops = ops or SelectionKey.OP_WRITE
        }
        if (k.interestOps() != ops) k.interestOps(ops)
    }

    fun close(sendRst: Boolean) {
        if (state == State.CLOSED) return
        if (sendRst && state != State.CONNECTING) {
            engine.writeTun(Pk.tcp(v6, remAddr, appAddr, remPort, appPort, sndNxt, rcvNxt, Pk.RST or Pk.ACK, 0))
        }
        state = State.CLOSED
        selKey?.cancel()
        try { ch.close() } catch (_: IOException) {}
        engine.removeTcp(this)
    }
}
