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
import java.net.InetAddress
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
        /** Re-read the VPN server choice (switch server / turn VPN on or off). */
        const val ACTION_APPLY_VPN = "com.blockbridge.APPLY_VPN"
        /** Rebuild the tunnel (e.g. after the app exception list changed). */
        const val ACTION_RESTART = "com.blockbridge.RESTART"
        private const val ADDR4 = "10.111.222.1"
        private const val DNS4 = "10.111.222.2"
        private const val ADDR6 = "fd00:b10c:b10c::1"
        private const val DNS6 = "fd00:b10c:b10c::2"

        @Volatile var isRunning = false
            private set
        @Volatile var lastError: String? = null
        /** True when every app's traffic goes through BlockBridge (not only DNS). */
        @Volatile var fullTunnel = false
            private set
        @Volatile var instance: ShieldVpnService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** Emergency kill: tear everything down right now, on the calling thread. */
    fun emergencyStop() {
        Settings(this).wantRunning = false
        shutdown()
        stopSelf()
    }

    private var socks: Socks5Server? = null

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
        if (intent?.action == ACTION_RESTART) {
            if (running) { shutdown(); start() }
            return START_STICKY
        }
        if (intent?.action == ACTION_APPLY_VPN) {
            if (running) {
                if (Settings(this).vpnEnabled && !fullTunnel) { shutdown(); start() } // needs full mode
                else thread(name = "blockbridge-vpn") { applyVpn() }
            }
            return START_STICKY
        }
        start()
        return START_STICKY
    }

    /** Connects to (or disconnects from) the selected VPN server. Blocking. */
    @Synchronized
    private fun applyVpn() {
        val server = socks ?: return
        val on = Settings(this).vpnEnabled
        VpnClient.stop()
        DnsHandler.via = null
        DnsHandler.viaUdp = null
        // Kill switch: while a VPN is wanted but not connected, nothing leaves directly.
        server.requireUpstream = on
        DnsHandler.requireVia = on
        server.resetConnections()
        if (!on) { VpnClient.error = null; return }

        val profile = VpnProfiles.selected(this)
        if (profile == null) { VpnClient.error = "Choose a VPN server first"; return }
        val up = VpnClient.start(this, profile) ?: return
        DnsHandler.viaDns = VpnClient.dnsOf(profile.conf)
        DnsHandler.viaUdp = if (TorClient.isTor(profile) && TorClient.dnsPort > 0)
            java.net.InetSocketAddress("127.0.0.1", TorClient.dnsPort) else null
        DnsHandler.via = up
        server.resetConnections()
    }

    private fun start() {
        if (running) return
        val settings = Settings(this)
        // A VPN server needs every connection to pass through, so it forces full mode.
        val full = (settings.fullTunnel || settings.vpnEnabled) && HevTunnel.available
        val v6 = !full || hasIpv6()
        val builder = Builder()
            .setSession("BlockBridge")
            .setMtu(if (full) 8500 else 1500)
            .addAddress(ADDR4, 24).addDnsServer(DNS4)
        if (full) builder.addRoute("0.0.0.0", 0) else builder.addRoute(DNS4, 32)
        if (v6) {
            builder.addAddress(ADDR6, 64).addDnsServer(DNS6)
            if (full) builder.addRoute("::", 0) else builder.addRoute(DNS6, 128)
        }
        builder.setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
        // Our own blocklist downloads go straight to the internet.
        runCatching { builder.addDisallowedApplication(packageName) }
        // App exceptions: these apps talk to the internet directly, so their ads load normally.
        for (pkg in settings.exceptedApps) runCatching { builder.addDisallowedApplication(pkg) }
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
        settings.wantRunning = true
        ControlService.show(this)
        thread(name = "blockbridge-lists") {
            Blocklists.loadAll(this)
            if (Blocklists.needsUpdate(this)) runCatching { Blocklists.update(this, force = true) }
        }

        if (full) {
            // Every app -> tunnel -> hev-socks5-tunnel -> Socks5Server -> internet
            val localDns = setOf(InetAddress.getByName(DNS4), InetAddress.getByName(DNS6))
            val server = Socks5Server({ it in localDns }, { VpnClient.upstream }).start()
            socks = server
            val conf = java.io.File(filesDir, "tunnel.yml").apply { writeText(HevTunnel.config(server, v6)) }
            if (settings.vpnEnabled) {
                server.requireUpstream = true
                DnsHandler.requireVia = true
            }
            if (HevTunnel.start(conf.path, iface.fd)) {
                fullTunnel = true
                if (settings.vpnEnabled) thread(name = "blockbridge-vpn") { applyVpn() }
                return
            }
            lastError = "Full-traffic engine failed to start. Turn off \"Send all app traffic\" " +
                "and the VPN in Settings, then try again."
            shutdown(); stopSelf()
            return
        }

        fullTunnel = false
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
    }

    /** Only route IPv6 into the tunnel when the real network has it, so apps don't wait on it. */
    @Suppress("DEPRECATION")
    private fun hasIpv6(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return false
        return lp.routes.any { it.isDefaultRoute && it.destination.address is java.net.Inet6Address }
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
        try {
            pool?.execute {
                val resp = DnsHandler.handle(pkt.payload) ?: return@execute
                write(Packets.buildReply(pkt, resp))
            }
        } catch (_: Exception) { /* shutting down */ }
    }

    private fun write(packet: ByteArray) {
        synchronized(writeLock) {
            try { output?.write(packet) } catch (_: IOException) { }
        }
    }

    private fun shutdown() {
        running = false
        isRunning = false
        if (fullTunnel) HevTunnel.stop()
        fullTunnel = false
        socks?.close(); socks = null
        VpnClient.stop()
        DnsHandler.via = null
        DnsHandler.requireVia = false
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
        instance = null
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
