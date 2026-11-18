package com.netsplit.app

import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Quick download speed test (runs outside the VPN because NetSplit itself is never routed). */
object SpeedTest {
    private val urls = listOf(
        "https://speed.cloudflare.com/__down?bytes=200000000",
        "https://proof.ovh.net/files/100Mb.dat",
    )

    fun run(onProgress: (Float) -> Unit, onDone: (Float?) -> Unit) = thread(name = "speedtest") {
        for (u in urls) {
            val r = try { measure(u, onProgress) } catch (e: Exception) { null }
            if (r != null && r > 0f) { onDone(r); return@thread }
        }
        onDone(null)
    }

    private fun measure(url: String, onProgress: (Float) -> Unit): Float? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.useCaches = false
        try {
            if (c.responseCode !in 200..299) return null
            val inp = c.inputStream
            val buf = ByteArray(64 * 1024)
            val start = System.nanoTime()
            var warmBytes = 0L
            var warmAt = 0L
            var bytes = 0L
            var lastReport = start
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                bytes += n
                val now = System.nanoTime()
                // Ignore the first second (TCP slow start).
                if (warmAt == 0L && now - start > 1_000_000_000L) { warmAt = now; warmBytes = bytes }
                if (warmAt != 0L && now - lastReport > 400_000_000L) {
                    lastReport = now
                    onProgress(mbps(bytes - warmBytes, now - warmAt))
                }
                if (now - start > 9_000_000_000L) break
            }
            val end = System.nanoTime()
            return if (warmAt != 0L) mbps(bytes - warmBytes, end - warmAt) else mbps(bytes, end - start)
        } finally {
            c.disconnect()
        }
    }

    private fun mbps(bytes: Long, nanos: Long) = if (nanos <= 0) 0f else (bytes * 8 / (nanos / 1e9) / 1e6).toFloat()
}
