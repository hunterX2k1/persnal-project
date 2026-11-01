package com.blockbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Local "VPN" that only captures DNS. Android is told to use a fake DNS server
 * (10.111.222.2 / fd00:b10c:b10c::2) that exists only inside this tunnel, so every
 * domain lookup on the phone arrives here. Blocked domains get 0.0.0.0 back;
 * everything else is forwarded to the real DNS server. All other traffic (pages,
 * videos, downloads) never touches the tunnel, so speed and battery are unaffected.
 */
class ShieldVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.blockbridge.START"
        const val ACTION_STOP = "com.blockbridge.STOP"
        private const val ADDR4 = "10.111.222.1"
        private const val DNS4 = "10.111.222.2"
        private const val ADDR6 = "fd00:b10c:b10c::1"
        private const val DNS6 = "fd00:b10c:b10c::2"

        @Volatile var isRunning = false
            private set
        @Volatile var lastError: String? = null
    }

    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var loop: Thread? = null
    private var pool: ExecutorService? = null
    private var output: FileOutputStream? = null
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).wantRunning = false
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        start()
        return START_STICKY
    }

    private fun start() {
        if (running) return
        val builder = Builder()
            .setSession("BlockBridge")
            .setMtu(1500)
            .addAddress(ADDR4, 24).addDnsServer(DNS4).addRoute(DNS4, 32)
            .addAddress(ADDR6, 64).addDnsServer(DNS6).addRoute(DNS6, 128)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
        // Our own blocklist downloads go straight to the internet.
        runCatching { builder.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val iface = try {
            builder.establish()
        } catch (e: Exception) {
            lastError = "Could not start: ${e.message}"; null
        }
        if (iface == null) {
            if (lastError == null) lastError = "VPN permission missing — open BlockBridge and tap Start."
            stopSelf()
            return
        }
        tun = iface
        running = true
        isRunning = true
        lastError = null
        Settings(this).wantRunning = true
        pool = Executors.newFixedThreadPool(24)
        loop = thread(name = "blockbridge-dns") {
            try {
                readLoop(iface)
            } catch (e: Exception) {
                if (running) lastError = "Stopped: ${e.message}"
            } finally {
                if (running) { shutdown(); stopSelf() }
            }
        }
        thread(name = "blockbridge-lists") {
            Blocklists.loadAll(this)
            if (Blocklists.needsUpdate(this)) runCatching { Blocklists.update(this, force = true) }
        }
    }

    private fun readLoop(iface: ParcelFileDescriptor) {
        val fd = iface.fileDescriptor
        val input = FileInputStream(fd)
        output = FileOutputStream(fd)
        val buf = ByteArray(32767)
        val pfd = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        while (running) {
            val ready = try {
                Os.poll(arrayOf(pfd), 1000)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else throw e
            }
            if (!running) break
            if (ready == 0) continue
            if ((pfd.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP)) != 0) break
            val len = try { input.read(buf) } catch (e: IOException) { continue }
            if (len > 0) handle(buf, len)
        }
    }

    private fun handle(buf: ByteArray, len: Int) {
        val pkt = Packets.parseUdp(buf, len) ?: return
        if (pkt.dstPort != 53) return
        val q = Dns.parseQuestion(pkt.payload)
        if (q != null) {
            val verdict = Filter.match(q.name)
            Stats.record(q.name, verdict)
            if (verdict.blocked) {
                write(Packets.buildReply(pkt, Dns.blockedReply(pkt.payload, q)))
                return
            }
        }
        try {
            pool?.execute { forward(pkt) }
        } catch (_: Exception) { /* shutting down */ }
    }

    /** Sends an allowed lookup to the real DNS server, trying the backup if needed. */
    private fun forward(pkt: UdpPacket) {
        for (server in Upstream.servers) {
            if (!running) return
            try {
                DatagramSocket().use { sock ->
                    protect(sock) // keep this socket outside the tunnel
                    sock.soTimeout = 4000
                    sock.send(DatagramPacket(pkt.payload, pkt.payload.size, server, 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    sock.receive(rp)
                    write(Packets.buildReply(pkt, rb.copyOf(rp.length)))
                }
                return
            } catch (_: IOException) {
                // try next server
            }
        }
    }

    private fun write(packet: ByteArray) {
        synchronized(writeLock) {
            try { output?.write(packet) } catch (_: IOException) { }
        }
    }

    private fun shutdown() {
        running = false
        isRunning = false
        pool?.shutdownNow(); pool = null
        synchronized(writeLock) { output = null }
        runCatching { tun?.close() }; tun = null
        val t = loop
        loop = null
        if (t != null && t != Thread.currentThread()) runCatching { t.join(1500) }
    }

    override fun onRevoke() {
        // Another VPN took over or the user disconnected from Android settings.
        Settings(this).wantRunning = false
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}

/** Turns protection back on after a reboot if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings(ctx).wantRunning || VpnService.prepare(ctx) != null) return
        runCatching {
            ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
        }
    }
}
