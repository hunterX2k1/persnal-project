package com.netsplit.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.netsplit.app.MainActivity
import com.netsplit.app.R
import com.netsplit.app.Settings
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread

class NetSplitVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.netsplit.app.START"
        const val ACTION_STOP = "com.netsplit.app.STOP"
        private const val TAG = "NetSplit"
        private const val NOTIF_ID = 1
        private const val CHANNEL = "netsplit"

        @Volatile var running = false
            private set
        /** One line per limited app, refreshed every second (read by the UI). */
        @Volatile var liveLines: List<String> = emptyList()
            private set
        @Volatile var lastError: String? = null
        /** Auto mode status line (live speed + ping), empty when not in auto mode. */
        @Volatile var autoLine: String = ""
            private set
        /** Live estimated connection speed in Mbps (0 if unknown). */
        @Volatile var liveMbps: Float = 0f
            private set

        fun fmtMbps(bytesPerSec: Double) = when {
            bytesPerSec * 8 >= 1e6 -> "%.1f Mbps".format(bytesPerSec * 8 / 1e6)
            else -> "%.0f kbps".format(bytesPerSec * 8 / 1e3)
        }
    }

    private var pfd: ParcelFileDescriptor? = null
    private var engine: Engine? = null
    private var engineThread: Thread? = null
    private var readerThread: Thread? = null
    @Volatile private var readerRunning = false
    private var activePkgs: Set<String> = emptySet()

    @Volatile private var tuner: AutoTuner? = null
    private var tunerThread: Thread? = null
    @Volatile private var tunerRunning = false
    private var dnsForProbe: List<InetAddress> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private var prev = LongArray(0)
    private var prevAt = 0L
    private var tickCount = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (engine == null) goForeground("Starting…")
        try {
            applyConfig()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            lastError = "Could not start: ${e.message}"
            shutdown()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        lastError = "VPN permission was taken by another app"
        shutdown()
    }

    override fun onDestroy() {
        stopEngine()
        running = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ config

    private fun applyConfig() {
        val s = Settings(this)
        val plan = s.plan()
        if (plan.isEmpty()) {
            lastError = "Choose the app to limit first"
            shutdown(); return
        }
        val limiters = plan.mapNotNull { l ->
            try {
                val ai = packageManager.getApplicationInfo(l.pkg, 0)
                AppLimiter(l.pkg, ai.loadLabel(packageManager).toString(), ai.uid, l.bytesPerSec)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        if (limiters.isEmpty()) {
            lastError = "Selected app is not installed"
            shutdown(); return
        }

        val e = engine
        if (e != null && limiters.map { it.packageName }.toSet() == activePkgs) {
            // Same apps: just change the limits live, no reconnect needed.
            e.blockQuic = s.blockQuic
            val t = tuner
            if (s.autoMode && t != null && t.priorityMode == s.priorityMode) {
                t.thresholdMs = s.thresholdMs
                t.splitA = s.splitA
            } else {
                stopTuner()
                for (l in limiters) e.limiters.firstOrNull { it.packageName == l.packageName }?.rateBytesPerSec = l.rateBytesPerSec
                if (s.autoMode) startTuner(e, s)
            }
            return
        }
        stopEngine()
        startEngine(limiters, s.blockQuic)
        if (s.autoMode) engine?.let { startTuner(it, s) }
    }

    // ------------------------------------------------------------------ live auto-adjust

    private fun startTuner(eng: Engine, s: Settings) {
        val initial = if (s.priorityMode) eng.limiters[0].rateBytesPerSec.toDouble() else s.totalMbps * 125_000.0
        val t = AutoTuner(s.priorityMode, s.splitA, s.thresholdMs, initial)
        tuner = t
        tunerRunning = true
        val targets = (dnsForProbe.take(2) + listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))).distinct()
        tunerThread = thread(name = "netsplit-tuner") {
            LatencyProbe(targets).use { probe ->
                var lastAt = System.nanoTime()
                var lastTotal = TrafficStats.getTotalRxBytes()
                val lastUsed = LongArray(eng.limiters.size) { eng.limiters[it].bytesDown.get() }
                var ticks = 0
                while (tunerRunning) {
                    val started = System.nanoTime()
                    val rtt = probe.probe(400)
                    val now = System.nanoTime()
                    val dt = (now - lastAt) / 1e9
                    lastAt = now
                    val usage = DoubleArray(eng.limiters.size) { i ->
                        val b = eng.limiters[i].bytesDown.get()
                        val u = (b - lastUsed[i]) / dt
                        lastUsed[i] = b; u
                    }
                    // Total phone traffic. Bytes of limited apps show up twice (mobile + VPN interface): count once.
                    val total = TrafficStats.getTotalRxBytes()
                    val totalRate = if (total >= 0 && lastTotal >= 0) {
                        ((total - lastTotal) / dt - usage.sum()).coerceAtLeast(usage.sum())
                    } else usage.sum()
                    lastTotal = total
                    if (!tunerRunning) break
                    val limits = t.tick(now / 1_000_000L, rtt, totalRate, usage)
                    for (i in limits.indices) eng.limiters[i].rateBytesPerSec = limits[i]

                    liveMbps = (t.linkBytesPerSec * 8 / 1e6).toFloat()
                    autoLine = when {
                        !t.probesOk -> "Live: waiting for ping replies… (limits held)"
                        else -> "Live speed ≈ %.1f Mbps · ping %s (+%.0f ms)".format(
                            liveMbps, t.pingMs?.let { "%.0f ms".format(it) } ?: "lost", t.extraMs,
                        )
                    }
                    // Remember the live speed so the next start begins from a good guess.
                    if (++ticks % 20 == 0 && t.probesOk) Settings(this).totalMbps = liveMbps.coerceAtLeast(0.5f)
                    val sleepMs = 500 - (System.nanoTime() - started) / 1_000_000L
                    if (sleepMs > 0) try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    private fun stopTuner() {
        tunerRunning = false
        tunerThread?.interrupt()
        try { tunerThread?.join(1500) } catch (_: InterruptedException) {}
        tunerThread = null
        tuner = null
        autoLine = ""
    }

    private fun startEngine(limiters: List<AppLimiter>, blockQuic: Boolean) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers
            ?.filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            .orEmpty()
            .ifEmpty { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")) }

        val b = Builder()
            .setSession("NetSplit")
            .setMtu(1500)
            .addAddress("10.215.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:215::2", 128)
            .addRoute("::", 0)
            .setBlocking(false)
        dnsForProbe = dns
        for (d in dns) b.addDnsServer(d)
        if (dns.none { it is Inet4Address }) b.addDnsServer("8.8.8.8")
        for (l in limiters) b.addAllowedApplication(l.packageName)
        b.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )

        val fd = b.establish() ?: throw IllegalStateException("VPN permission missing – open NetSplit and tap Start")
        pfd = fd

        val eng = Engine(
            tunOut = FileOutputStream(fd.fileDescriptor),
            limiters = limiters,
            uidOf = { proto, local, remote -> cm.getConnectionOwnerUid(proto, local, remote) },
            blockQuic = blockQuic,
            log = { Log.w(TAG, it) },
        )
        engine = eng
        activePkgs = limiters.map { it.packageName }.toSet()
        engineThread = thread(name = "netsplit-engine") { eng.run() }
        readerRunning = true
        readerThread = thread(name = "netsplit-tun") { readLoop(fd, eng) }

        running = true
        lastError = null
        prev = LongArray(limiters.size * 2)
        prevAt = 0L
        main.removeCallbacksAndMessages(null)
        main.post(ticker)
        Log.i(TAG, "started for ${activePkgs}")
    }

    private fun readLoop(fd: ParcelFileDescriptor, eng: Engine) {
        val f = fd.fileDescriptor
        val buf = ByteArray(32767)
        val pfds = arrayOf(StructPollfd().apply { this.fd = f; events = OsConstants.POLLIN.toShort() })
        try {
            while (readerRunning) {
                val r = try { Os.poll(pfds, 400) } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (r <= 0) continue
                while (true) {
                    val n = try { Os.read(f, buf, 0, buf.size) } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) break else throw e
                    }
                    if (n <= 0) break
                    eng.submit(buf.copyOf(n))
                }
            }
        } catch (e: Exception) {
            if (readerRunning) Log.w(TAG, "tun reader stopped: $e")
        }
    }

    private fun stopEngine() {
        stopTuner()
        readerRunning = false
        engine?.stop()
        try { readerThread?.join(1500) } catch (_: InterruptedException) {}
        try { engineThread?.join(1500) } catch (_: InterruptedException) {}
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null; engine = null; readerThread = null; engineThread = null
        activePkgs = emptySet()
    }

    private fun shutdown() {
        stopEngine()
        running = false
        liveLines = emptyList()
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ stats + notification

    private val ticker = object : Runnable {
        override fun run() {
            val e = engine ?: return
            val now = System.nanoTime()
            val dt = if (prevAt == 0L) 0.0 else (now - prevAt) / 1e9
            val lines = e.limiters.mapIndexed { i, l ->
                val d = l.bytesDown.get(); val u = l.bytesUp.get()
                val ds = if (dt > 0) (d - prev[i * 2]) / dt else 0.0
                val us = if (dt > 0) (u - prev[i * 2 + 1]) / dt else 0.0
                prev[i * 2] = d; prev[i * 2 + 1] = u
                "${l.label}: ↓ ${fmtMbps(ds)}  ↑ ${fmtMbps(us)}   (limit ${fmtMbps(l.rateBytesPerSec.toDouble())}${if (tuner != null) ", auto" else ""})"
            }
            prevAt = now
            val all = if (autoLine.isNotEmpty()) lines + autoLine else lines
            liveLines = all
            if (tickCount++ % 2 == 0) goForeground(all.joinToString("\n"))
            main.postDelayed(this, 1000)
        }
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "NetSplit status", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NetSplitVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NetSplit is limiting bandwidth")
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
