package com.blockbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.torproject.jni.TorService
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tor as a free VPN exit: runs tor inside BlockBridge (tor-android) with an optional exit
 * country, and hands the relay a SOCKS upstream plus a DNS port that resolves through Tor.
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

    @Volatile var progress = 0
        private set
    @Volatile var dnsPort = 0
        private set

    private var service: TorService? = null
    private var connection: ServiceConnection? = null
    private var appCtx: Context? = null

    fun isTor(profile: VpnProfile) = profile.conf.startsWith("tor:")
    fun countryOf(profile: VpnProfile) = profile.conf.removePrefix("tor:").trim().uppercase()

    fun profileName(code: String) = if (code.isEmpty()) "Tor · Any country" else "Tor · ${COUNTRIES[code] ?: code}"

    /** Starts tor and waits for a working circuit (up to ~2 minutes). Blocking. */
    fun start(ctx: Context, country: String, onProgress: (Int) -> Unit): SocksUpstream? {
        stop()
        val app = ctx.applicationContext
        appCtx = app
        progress = 0
        dnsPort = freePort()
        TorService.getTorrc(app).writeText(buildString {
            appendLine("DNSPort 127.0.0.1:$dnsPort")
            appendLine("ClientUseIPv6 1")
            appendLine("AvoidDiskWrites 1")
            if (country.isNotEmpty()) {
                appendLine("ExitNodes {${country.lowercase()}}")
                appendLine("StrictNodes 1")
            }
        })

        val bound = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? TorService.LocalBinder)?.service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        connection = conn
        if (!app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE)) return null
        if (!bound.await(15, TimeUnit.SECONDS)) { stop(); return null }

        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val svc = service ?: return null
            runCatching {
                val phase = svc.getInfo("status/bootstrap-phase") ?: ""
                Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toInt()?.let {
                    if (it != progress) { progress = it; onProgress(it) }
                }
                if (svc.getInfo("status/circuit-established") == "1" && svc.socksPort > 0) {
                    progress = 100
                    // Tor accepts any SOCKS username/password (it only uses them to separate circuits).
                    return SocksUpstream(svc.socksPort, "blockbridge", "tor")
                }
            }
            Thread.sleep(700)
        }
        stop()
        return null
    }

    fun stop() {
        val app = appCtx ?: return
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        service = null
        runCatching { app.stopService(Intent(app, TorService::class.java)) }
        dnsPort = 0
        progress = 0
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
