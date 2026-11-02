package com.blockbridge

import android.content.Context
import android.content.Intent
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Uses the Orbot app's Tor (Guardian Project) instead of BlockBridge's built-in one.
 * Orbot must run in its normal (proxy) mode, not its own VPN mode — Android allows one VPN.
 * Orbot's Tor listens on 127.0.0.1:9050 (SOCKS) and 127.0.0.1:5400 (DNS).
 */
object Orbot {
    const val PKG = "org.torproject.android"
    private const val SOCKS = 9050
    private const val DNS = 5400

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    /** Orbot's DNS port, if it answers (null = resolve through Tor's SOCKS instead). */
    @Volatile var dns: InetSocketAddress? = null
        private set

    /** Asks Orbot to start Tor and waits until its SOCKS port carries traffic. Blocking. */
    fun start(ctx: Context, isCancelled: () -> Boolean, onProgress: (String) -> Unit): SocksUpstream? {
        if (!installed(ctx)) { DiagLog.add("orbot", "Orbot is not installed"); return null }
        runCatching {
            ctx.sendBroadcast(Intent("org.torproject.android.intent.action.START")
                .setPackage(PKG)
                .putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", ctx.packageName))
        }
        DiagLog.add("orbot", "asked Orbot to start")
        val up = SocksUpstream(SOCKS, "blockbridge", "orbot")
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return null
            if (portOpen()) {
                onProgress("Orbot is running — checking Tor…")
                val ok = runCatching { up.connect(InetAddress.getByName("1.1.1.1"), 443).close() }.isSuccess
                if (ok) {
                    dns = if (dnsWorks()) InetSocketAddress("127.0.0.1", DNS) else null
                    DiagLog.add("orbot", "connected through Orbot (DNS ${if (dns != null) "port $DNS" else "via SOCKS"})")
                    return up
                }
            } else onProgress("Waiting for Orbot to start Tor… (open Orbot and tap Start if needed)")
            Thread.sleep(2000)
        }
        DiagLog.add("orbot", "Orbot's Tor didn't become ready")
        return null
    }

    fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS), 1000) }; true
    } catch (_: IOException) { false }

    private fun dnsWorks(): Boolean = try {
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7) + "example".toByteArray() +
                byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
            s.send(DatagramPacket(q, q.size, InetSocketAddress("127.0.0.1", DNS)))
            val b = ByteArray(512); s.receive(DatagramPacket(b, b.size)); true
        }
    } catch (_: IOException) { false }
}
