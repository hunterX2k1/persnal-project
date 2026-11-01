package com.blockbridge

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.json.JSONObject
import org.torproject.jni.TorService
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tor as a free VPN exit. Tor (tor-android) and its bridges (IPtProxy) run in a separate
 * ":tor" process via [TorHostService]:
 *  - a Tor crash can't take down the app, the tunnel or ad blocking;
 *  - every (re)start is a fresh process — libtor can't safely restart inside one process.
 * This side starts/stops that process and reads its progress from a small status file.
 * Tor carries TCP only; UDP (QUIC, calls) is refused so apps fall back to TCP.
 */
object TorClient {
    /** Country choices for the exit (ISO code → name). "" = any country. */
    val COUNTRIES = linkedMapOf(
        "" to "Any country (fastest)",
        "US" to "United States", "CA" to "Canada", "GB" to "United Kingdom", "DE" to "Germany",
        "NL" to "Netherlands", "FR" to "France", "CH" to "Switzerland", "SE" to "Sweden",
        "NO" to "Norway", "FI" to "Finland", "AT" to "Austria", "PL" to "Poland", "RO" to "Romania",
        "ES" to "Spain", "IT" to "Italy", "JP" to "Japan", "SG" to "Singapore", "AU" to "Australia",
        "IN" to "India", "BR" to "Brazil",
    )

    /** How Tor reaches the network: directly, or through bridges where Tor is blocked. */
    enum class Bridges(val label: String) { OFF("Off"), SNOWFLAKE("Snowflake"), OBFS4("obfs4"), CUSTOM("Custom") }

    @Volatile var progress = 0
        private set
    @Volatile var dnsPort = 0
        private set
    private var appCtx: Context? = null

    fun isTor(profile: VpnProfile) = profile.conf.startsWith("tor:")
    fun countryOf(profile: VpnProfile) = profile.conf.removePrefix("tor:").trim().uppercase()
    fun profileName(code: String) = if (code.isEmpty()) "Tor · Any country" else "Tor · ${COUNTRIES[code] ?: code}"

    internal fun statusFile(ctx: Context) = File(ctx.filesDir, "tor_status.json")
    fun logFile(ctx: Context) = File(ctx.filesDir, "tor.log")

    /** Bridge lines for [mode]: Tor Browser's built-in ones, or the user's own. */
    fun bridgeLines(ctx: Context, mode: Bridges, custom: String): List<String> = when (mode) {
        Bridges.OFF -> emptyList()
        Bridges.CUSTOM -> custom.lines().map { it.trim().removePrefix("Bridge ").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        else -> {
            val o = JSONObject(ctx.assets.open("tor_bridges.json").bufferedReader().use { it.readText() })
            val arr = o.getJSONArray(if (mode == Bridges.SNOWFLAKE) "snowflake" else "obfs4")
            (0 until arr.length()).map { arr.getString(it) }
        }
    }

    private fun readStatus(ctx: Context): JSONObject? =
        runCatching { JSONObject(statusFile(ctx).readText()) }.getOrNull()

    /** Starts Tor in its own process and waits for a working circuit. Blocking. */
    fun start(ctx: Context, country: String, onProgress: (Int) -> Unit): SocksUpstream? {
        stop()
        val app = ctx.applicationContext
        appCtx = app
        progress = 0
        dnsPort = ServerSocket(0).use { it.localPort }
        val s = Settings(app)
        val bridges = bridgeLines(app, s.torBridges, s.torBridgeLines)
        statusFile(app).delete()
        DiagLog.add("tor", "starting in separate process (exit ${country.ifEmpty { "any" }}, bridges ${s.torBridges.label})")
        app.startService(Intent(app, TorHostService::class.java).setAction(TorHostService.ACTION_START)
            .putExtra("country", country)
            .putExtra("dnsPort", dnsPort)
            .putStringArrayListExtra("bridges", ArrayList(bridges)))

        val deadline = System.currentTimeMillis() + if (bridges.isEmpty()) 150_000 else 240_000
        var lastSummary = ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(700)
            val st = readStatus(app) ?: continue
            val pct = st.optInt("progress")
            if (pct != progress) { progress = pct; onProgress(pct) }
            val summary = st.optString("summary")
            if (summary.isNotEmpty() && summary != lastSummary) {
                lastSummary = summary
                DiagLog.add("tor", "bootstrap $pct%: $summary")
            }
            st.optString("error").takeIf { it.isNotEmpty() }?.let { err ->
                DiagLog.add("tor", "failed: $err")
                stop(); return null
            }
            // The Tor process died (crash) or stopped writing — give up and let the supervisor retry.
            if (System.currentTimeMillis() - st.optLong("heartbeat") > 15_000) {
                DiagLog.add("tor", "Tor process stopped responding")
                stop(); return null
            }
            if (st.optBoolean("ready") && st.optInt("socks") > 0) {
                progress = 100
                DiagLog.add("tor", "connected (SOCKS ${st.optInt("socks")})")
                // Tor accepts any SOCKS username/password (it only uses them to separate circuits).
                return SocksUpstream(st.optInt("socks"), "blockbridge", "tor")
            }
        }
        DiagLog.add("tor", "timed out at $progress% (${lastSummary.ifEmpty { "no progress" }})")
        tailLog(app)?.let { DiagLog.add("tor", "tor.log:\n$it") }
        stop()
        return null
    }

    /** Last lines of Tor's own log (warnings explain most connection problems). */
    fun tailLog(ctx: Context, lines: Int = 15): String? = runCatching {
        logFile(ctx).readLines().takeLast(lines).joinToString("\n").ifBlank { null }
    }.getOrNull()

    /** Tor's process is alive and connected (heartbeat within the last 15 s). */
    fun healthy(): Boolean {
        val app = appCtx ?: return false
        val st = readStatus(app) ?: return false
        return st.optBoolean("ready") && System.currentTimeMillis() - st.optLong("heartbeat") < 15_000
    }

    /** Stops Tor; its process exits, so the next start is completely fresh. */
    fun stop() {
        val app = appCtx ?: return
        runCatching { app.stopService(Intent(app, TorHostService::class.java)) }
        // Wait briefly for the old process to finish shutting down before any restart.
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val until = System.currentTimeMillis() + if (onMain) 0 else 3000
        while (System.currentTimeMillis() < until) {
            val st = readStatus(app)
            if (st == null || st.optBoolean("stopped")) break
            Thread.sleep(100)
        }
        dnsPort = 0
        progress = 0
    }
}

/**
 * Runs in the ":tor" process: starts bridge transports, writes the torrc, runs TorService
 * and reports progress to [TorClient.statusFile]. Ends its own process when stopped.
 */
class TorHostService : Service() {
    companion object { const val ACTION_START = "com.blockbridge.tor.START" }

    @Volatile private var running = false
    private var tor: TorService? = null
    private var conn: ServiceConnection? = null
    private var pt: IPtProxy.Controller? = null
    private val transports = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && !running) {
            running = true
            val country = intent.getStringExtra("country") ?: ""
            val dns = intent.getIntExtra("dnsPort", 0)
            val bridges = intent.getStringArrayListExtra("bridges") ?: arrayListOf()
            thread(name = "tor-host") { runTor(country, dns, bridges) }
        }
        return START_NOT_STICKY
    }

    private fun status(progress: Int = 0, summary: String = "", ready: Boolean = false, socks: Int = 0,
                       error: String = "", stopped: Boolean = false) {
        val f = TorClient.statusFile(this)
        val tmp = File(f.path + ".tmp")
        runCatching {
            tmp.writeText(JSONObject().put("progress", progress).put("summary", summary).put("ready", ready)
                .put("socks", socks).put("error", error).put("stopped", stopped)
                .put("heartbeat", System.currentTimeMillis()).toString())
            tmp.renameTo(f)
        }
    }

    private fun runTor(country: String, dnsPort: Int, bridges: List<String>) {
        try {
            status(summary = "Starting Tor")
            val bridgeConf = startTransports(bridges)
            TorClient.logFile(this).delete()
            TorService.getTorrc(this).writeText(buildString {
                appendLine("DNSPort 127.0.0.1:$dnsPort")
                appendLine("ClientUseIPv6 1")
                appendLine("AvoidDiskWrites 1")
                appendLine("Log notice file ${TorClient.logFile(this@TorHostService).path}")
                bridgeConf.forEach { appendLine(it) }
                if (country.isNotEmpty()) {
                    appendLine("ExitNodes {${country.lowercase()}}")
                    appendLine("StrictNodes 1")
                }
            })

            val bound = CountDownLatch(1)
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    tor = (binder as? TorService.LocalBinder)?.service
                    bound.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) { tor = null }
            }
            conn = c
            if (!bindService(Intent(this, TorService::class.java), c, Context.BIND_AUTO_CREATE) ||
                !bound.await(20, TimeUnit.SECONDS)
            ) {
                status(error = "Tor service didn't start"); return
            }

            var pct = 0
            var summary = "Starting"
            while (running) {
                val svc = tor ?: break
                runCatching {
                    val phase = svc.getInfo("status/bootstrap-phase") ?: ""
                    Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toInt()?.let { pct = it }
                    Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1)?.let { summary = it }
                    if (svc.getInfo("status/circuit-established") == "1") {
                        val listeners = svc.getInfo("net/listeners/socks") ?: ""
                        val port = Regex(":(\\d+)").find(listeners)?.groupValues?.get(1)?.toInt() ?: svc.socksPort
                        if (port > 0) { status(100, "Connected", ready = true, socks = port); return@runCatching }
                    }
                    status(pct, summary)
                }
                Thread.sleep(700)
                // keep the heartbeat fresh once ready
                if (pct >= 100) runCatching {
                    val listeners = svc.getInfo("net/listeners/socks") ?: ""
                    val port = Regex(":(\\d+)").find(listeners)?.groupValues?.get(1)?.toInt() ?: svc.socksPort
                    status(100, "Connected", ready = port > 0, socks = port)
                }
            }
        } catch (e: Throwable) {
            status(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Starts each pluggable transport the bridge lines need; returns torrc lines. */
    private fun startTransports(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val names = lines.map { it.substringBefore(' ').lowercase() }
            .filter { Regex("^[a-z][a-z0-9_]*$").matches(it) }.toSet()
        val dir = File(filesDir, "pt").apply { mkdirs() }
        val c = IPtProxy.IPtProxy.newController(dir.path, false, false, "INFO", object : IPtProxy.OnTransportEvents {
            override fun connected(t: String) {}
            override fun error(t: String, e: Exception?) {}
            override fun stopped(t: String, e: Exception?) {}
        }).also { pt = it }
        val out = mutableListOf("UseBridges 1")
        for (t in names) {
            val name = if (t == "meek") "meek_lite" else t
            runCatching { c.start(name, "") }
            transports += name
            val port = c.port(name)
            if (port > 0) out += "ClientTransportPlugin $t socks5 127.0.0.1:$port"
        }
        lines.forEach { out += "Bridge $it" }
        return out
    }

    override fun onDestroy() {
        running = false
        conn?.let { runCatching { unbindService(it) } }
        runCatching { stopService(Intent(this, TorService::class.java)) }
        pt?.let { c -> transports.forEach { runCatching { c.stop(it) } } }
        status(stopped = true)
        super.onDestroy()
        // A fresh process for the next start: libtor can't be restarted in the same one.
        android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }
}
