package com.blockbridge

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.math.abs

/** Opens a TCP connection to host:port over some route (direct, a VPN server, Tor). */
fun interface Dialer {
    fun open(host: String, port: Int): Socket
}

object Dialers {
    val direct = Dialer { host, port -> Socket().apply { connect(InetSocketAddress(host, port), 10_000) } }
    fun via(up: SocksUpstream) = Dialer { host, port -> up.connect(host, port) }
}

/** Result of one speed test run. */
data class SpeedResult(
    val time: Long,
    val route: String,
    val pingMs: Double,
    val jitterMs: Double,
    val downMbps: Double,
    val upMbps: Double,
    val server: String,
    val ip: String,
)

/**
 * Speed test against Cloudflare's speed endpoints (the ones speed.cloudflare.com uses):
 * latency/jitter from repeated connection setups, then download and upload with 4
 * parallel streams for a fixed time. Runs over any [Dialer], so it measures the route
 * your traffic actually takes.
 */
class SpeedTest(
    private val dialer: Dialer,
    private val host: String = "speed.cloudflare.com",
    private val port: Int = 443,
    private val tls: Boolean = true,
    private val streams: Int = 4,
    private val phaseMillis: Long = 8_000,
) {
    enum class Phase { PING, DOWNLOAD, UPLOAD, DONE }

    /** Called often with the current phase, live Mbps (or ms for ping) and 0..1 progress. */
    var onProgress: (Phase, Double, Float) -> Unit = { _, _, _ -> }
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun run(routeLabel: String): SpeedResult {
        val (server, ip) = runCatching { trace() }.getOrDefault("?" to "?")
        val (ping, jitter) = latency()
        check(!cancelled.get()) { "cancelled" }
        val down = transfer(Phase.DOWNLOAD)
        check(!cancelled.get()) { "cancelled" }
        val up = transfer(Phase.UPLOAD)
        onProgress(Phase.DONE, 0.0, 1f)
        return SpeedResult(System.currentTimeMillis(), routeLabel, ping, jitter, down, up, server, ip)
    }

    // ---------------- HTTP/1.1 over a dialed socket ----------------

    private fun connect(): Socket {
        val raw = dialer.open(host, port)
        raw.soTimeout = 15_000
        raw.tcpNoDelay = true
        if (!tls) return raw
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        s.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
            s.close(); throw IOException("certificate does not match $host")
        }
        return s
    }

    private fun writeHead(out: OutputStream, method: String, path: String, length: Long? = null) {
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: BlockBridge-SpeedTest\r\n")
        if (length != null) sb.append("Content-Type: application/octet-stream\r\nContent-Length: $length\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    /** Reads status line + headers; returns the status code. */
    private fun readHead(input: InputStream): Int {
        val line = StringBuilder()
        var status = -1
        var blank = false
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("connection closed")
            if (c == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                if (status < 0) status = l.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (l.isEmpty()) blank = true
                line.clear()
                if (blank) return status
            } else line.append(c.toChar())
        }
    }

    private fun get(path: String): String = connect().use { s ->
        writeHead(s.getOutputStream(), "GET", path)
        val input = BufferedInputStream(s.getInputStream())
        if (readHead(input) != 200) throw IOException("HTTP error")
        input.readBytes().toString(Charsets.UTF_8)
    }

    /** Cloudflare's /cdn-cgi/trace: which data center answered and the IP it saw. */
    private fun trace(): Pair<String, String> {
        val kv = get("/cdn-cgi/trace").lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val colo = kv["colo"] ?: "?"
        val loc = kv["loc"]?.let { " (${Flags.of(it)} $it)" } ?: ""
        return "Cloudflare $colo$loc" to (kv["ip"] ?: "?")
    }

    /** Connection setup time through the route, 8 samples: median → ping, mean change → jitter. */
    private fun latency(): Pair<Double, Double> {
        val samples = ArrayList<Double>()
        repeat(8) { i ->
            if (cancelled.get()) return@repeat
            val t0 = System.nanoTime()
            runCatching { dialer.open(host, port).close() }.onSuccess {
                val ms = (System.nanoTime() - t0) / 1e6
                samples += ms
                onProgress(Phase.PING, ms, (i + 1) / 8f)
            }
        }
        if (samples.isEmpty()) throw IOException("server not reachable")
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val jitter = if (samples.size < 2) 0.0 else samples.zipWithNext { a, b -> abs(a - b) }.average()
        return median to jitter
    }

    /** Runs [streams] parallel transfers for [phaseMillis]; returns the average Mbps. */
    private fun transfer(phase: Phase): Double {
        val bytes = AtomicLong()
        val stop = AtomicBoolean(false)
        val workers = List(streams) {
            thread(isDaemon = true, name = "speed-${phase.name.lowercase()}-$it") {
                while (!stop.get() && !cancelled.get()) {
                    try {
                        if (phase == Phase.DOWNLOAD) downloadOnce(bytes, stop) else uploadOnce(bytes, stop)
                    } catch (_: IOException) {
                        if (!stop.get()) Thread.sleep(200)
                    }
                }
            }
        }
        // Skip the first second (TCP slow start) when computing the average.
        val start = System.nanoTime()
        var warmBytes = -1L
        var warmAt = 0L
        var lastBytes = 0L
        var lastAt = start
        var live = 0.0
        while (true) {
            Thread.sleep(200)
            val now = System.nanoTime()
            val elapsed = (now - start) / 1e6
            val b = bytes.get()
            val inst = (b - lastBytes) * 8 / ((now - lastAt) / 1e9) / 1e6
            live = if (live == 0.0) inst else live * 0.7 + inst * 0.3
            lastBytes = b; lastAt = now
            if (warmBytes < 0 && elapsed >= 1000) { warmBytes = b; warmAt = now }
            onProgress(phase, live, (elapsed / phaseMillis).toFloat().coerceAtMost(1f))
            if (elapsed >= phaseMillis || cancelled.get()) break
        }
        stop.set(true)
        workers.forEach { it.join(3000) }
        val end = System.nanoTime()
        val measured = bytes.get() - warmBytes.coerceAtLeast(0)
        val seconds = (end - (if (warmAt > 0) warmAt else start)) / 1e9
        return if (seconds > 0) measured * 8 / seconds / 1e6 else 0.0
    }

    private fun downloadOnce(counter: AtomicLong, stop: AtomicBoolean) = connect().use { s ->
        writeHead(s.getOutputStream(), "GET", "/__down?bytes=50000000")
        val input = s.getInputStream()
        if (readHead(input) != 200) throw IOException("HTTP error")
        val buf = ByteArray(64 * 1024)
        while (!stop.get() && !cancelled.get()) {
            val n = input.read(buf)
            if (n < 0) break
            counter.addAndGet(n.toLong())
        }
    }

    private fun uploadOnce(counter: AtomicLong, stop: AtomicBoolean) = connect().use { s ->
        val size = 25_000_000L
        val out = s.getOutputStream()
        writeHead(out, "POST", "/__up", size)
        val chunk = ByteArray(64 * 1024)
        var sent = 0L
        while (sent < size && !stop.get() && !cancelled.get()) {
            val n = minOf(chunk.size.toLong(), size - sent).toInt()
            out.write(chunk, 0, n)
            sent += n
            counter.addAndGet(n.toLong())
        }
        out.flush()
    }
}

/** Last results, newest first (kept in SharedPreferences as JSON). */
object SpeedHistory {
    fun all(ctx: android.content.Context): List<SpeedResult> {
        val arr = org.json.JSONArray(prefs(ctx).getString("speed_history", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SpeedResult(o.getLong("t"), o.getString("route"), o.getDouble("ping"), o.getDouble("jitter"),
                o.getDouble("down"), o.getDouble("up"), o.optString("server"), o.optString("ip"))
        }
    }

    fun add(ctx: android.content.Context, r: SpeedResult) {
        val list = (listOf(r) + all(ctx)).take(30)
        val arr = org.json.JSONArray(list.map {
            org.json.JSONObject().put("t", it.time).put("route", it.route).put("ping", it.pingMs)
                .put("jitter", it.jitterMs).put("down", it.downMbps).put("up", it.upMbps)
                .put("server", it.server).put("ip", it.ip)
        })
        prefs(ctx).edit().putString("speed_history", arr.toString()).apply()
    }

    fun clear(ctx: android.content.Context) = prefs(ctx).edit().remove("speed_history").apply()

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
}
