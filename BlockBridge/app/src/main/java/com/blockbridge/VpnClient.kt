package com.blockbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/** A WireGuard server imported from a .conf file (Proton VPN free, your own server, …). */
class VpnProfile(val id: String, val name: String, val conf: String) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("conf", conf)

    companion object {
        fun fromJson(o: JSONObject) = VpnProfile(o.getString("id"), o.getString("name"), o.getString("conf"))

        /** Returns an error message, or null if the text looks like a WireGuard config. */
        fun validate(conf: String): String? {
            val t = conf.lowercase()
            return when {
                "[interface]" !in t -> "Missing [Interface] section"
                "privatekey" !in t -> "Missing PrivateKey"
                "[peer]" !in t -> "Missing [Peer] section"
                "publickey" !in t -> "Missing the server's PublicKey"
                "endpoint" !in t -> "Missing Endpoint (server address)"
                else -> null
            }
        }

        /** "wg-NL-FREE-12.conf" → "NL-FREE-12". */
        fun nameFrom(fileName: String?): String =
            fileName?.substringBeforeLast('.')?.removePrefix("wg-")?.takeIf { it.isNotBlank() } ?: "VPN server"
    }
}

object VpnProfiles {
    fun all(ctx: Context): List<VpnProfile> {
        val arr = JSONArray(prefs(ctx).getString("vpn_profiles", "[]"))
        return (0 until arr.length()).map { VpnProfile.fromJson(arr.getJSONObject(it)) }
    }

    fun add(ctx: Context, name: String, conf: String): VpnProfile {
        val p = VpnProfile(UUID.randomUUID().toString(), name, conf.trim())
        save(ctx, all(ctx) + p)
        if (selected(ctx) == null) select(ctx, p.id)
        return p
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
        if (prefs(ctx).getString("vpn_selected", null) == id) select(ctx, all(ctx).firstOrNull()?.id)
    }

    fun select(ctx: Context, id: String?) = prefs(ctx).edit().putString("vpn_selected", id).apply()
    fun selected(ctx: Context): VpnProfile? {
        val id = prefs(ctx).getString("vpn_selected", null)
        return all(ctx).firstOrNull { it.id == id }
    }

    private fun save(ctx: Context, list: List<VpnProfile>) =
        prefs(ctx).edit().putString("vpn_profiles", JSONArray(list.map { it.toJson() }).toString()).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

/**
 * One VPN server connection: bbwg (WireGuard helper bundled as libbbwg.so) or Tor, exposed
 * as a [SocksUpstream]. BlockBridge is excluded from its own VPN, so the helper's encrypted
 * traffic goes straight out.
 */
class Tunnel(val profile: VpnProfile) {
    @Volatile var upstream: SocksUpstream? = null
        internal set
    @Volatile var state = "Starting…"
        private set
    @Volatile var error: String? = null
    @Volatile var lastHandshake = 0L
        internal set
    @Volatile var latencyMs = -1L
    @Volatile var probeFailures = 0
    val active = java.util.concurrent.atomic.AtomicInteger()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var process: Process? = null
    @Volatile var dead = false
        private set

    val isTor get() = TorClient.isTor(profile)
    val name get() = profile.name
    val display get() = "${Flags.of(profile.name)} ${profile.name}"

    /** Usable for new connections: up, and not failing health checks. */
    val usable: Boolean
        get() {
            if (dead || upstream == null || probeFailures >= 2) return false
            if (isTor) return TorClient.healthy()
            // WireGuard: a recent handshake, or still within the first moments after start
            val now = System.currentTimeMillis()
            return lastHandshake > 0 && now - lastHandshake < 200_000 || now - startedAt < 25_000
        }

    fun start(ctx: Context): Boolean {
        if (isTor) return startTor(ctx)
        if (!VpnClient.available(ctx)) return fail("This build doesn't include the VPN engine")
        val conf = try {
            VpnClient.resolveEndpoints(profile.conf)
        } catch (e: Exception) {
            return fail("Can't find the server address: ${e.message}")
        }
        val file = File(ctx.filesDir, "wg-${profile.id}.conf").apply { writeText(conf) }
        val user = VpnClient.token()
        val pass = VpnClient.token()
        val proc = try {
            ProcessBuilder(File(ctx.applicationInfo.nativeLibraryDir, "libbbwg.so").path, "-config", file.path)
                .redirectErrorStream(true)
                .apply { environment()["BB_USER"] = user; environment()["BB_PASS"] = pass }
                .start()
        } catch (e: Exception) {
            return fail("VPN engine failed to start: ${e.message}")
        }
        process = proc
        val first = LinkedBlockingQueue<String>()
        thread(name = "bbwg-${profile.name}", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("READY ") || line.startsWith("ERROR ") -> first.offer(line)
                        line.startsWith("HANDSHAKE ") -> {
                            val first = lastHandshake == 0L
                            lastHandshake = (line.substringAfter(' ').toLongOrNull() ?: 0) * 1000
                            state = "Connected"
                            if (first) StateBus.changed()
                        }
                    }
                    DiagLog.add("vpn", "${profile.name}: $line")
                }
            }
            if (process === proc) { dead = true; upstream = null; fail(error ?: "Disconnected") }
        }
        val line = first.poll(8, TimeUnit.SECONDS)
        if (line == null || !line.startsWith("READY ")) {
            stop()
            return fail(line?.removePrefix("ERROR ") ?: "VPN engine didn't respond")
        }
        upstream = SocksUpstream(line.substringAfter(' ').trim().toInt(), user, pass)
        state = "Waiting for server…"
        return true
    }

    private fun startTor(ctx: Context): Boolean {
        state = "Starting Tor…"
        val up = TorClient.start(ctx, TorClient.countryOf(profile)) { pct -> state = "Building Tor circuit… $pct%" }
            ?: run {
                TorClient.stop()
                return fail("Tor couldn't connect. Try \"Any country\" — some countries have few Tor exits.")
            }
        upstream = up
        lastHandshake = System.currentTimeMillis()
        state = "Connected"
        StateBus.changed()
        return true
    }

    fun stop() {
        dead = true
        if (isTor) TorClient.stop()
        val p = process
        process = null
        upstream = null
        p?.let { runCatching { it.outputStream.close() }; it.destroy() }
    }

    /** Health probe: time a TCP connection through this server. */
    fun probe() {
        val up = upstream ?: return
        val t0 = System.nanoTime()
        try {
            up.connect(InetAddress.getByName("1.1.1.1"), 443).use { }
            latencyMs = (System.nanoTime() - t0) / 1_000_000
            probeFailures = 0
        } catch (_: Exception) {
            probeFailures++
        }
    }

    private fun fail(msg: String): Boolean {
        error = msg
        state = "Not connected"
        StateBus.changed()
        DiagLog.add("vpn", "${profile.name}: $msg")
        return false
    }
}

/** How traffic is spread over several VPN servers. */
enum class LbMode(val label: String) {
    OFF("Off"), FAILOVER("Failover"), BALANCED("Balanced"), FASTEST("Fastest");
}

/**
 * Picks a server for each new connection:
 *  - Failover: the first working server in your order; the next one takes over if it fails.
 *  - Balanced: the server with the fewest open connections; a website sticks to the same
 *    server for 10 minutes so logins and sessions don't jump between countries.
 *  - Fastest: the working server with the lowest measured latency.
 */
object LoadBalancer {
    @Volatile var mode = LbMode.OFF
    private val sticky = java.util.concurrent.ConcurrentHashMap<String, kotlin.Pair<Tunnel, Long>>()
    private val rr = java.util.concurrent.atomic.AtomicInteger()

    fun pick(key: String?, tunnels: List<Tunnel>): Tunnel? {
        val usable = tunnels.filter { it.usable }
        if (usable.isEmpty()) return null
        return when (mode) {
            LbMode.OFF, LbMode.FAILOVER -> usable.first()
            LbMode.FASTEST -> usable.minByOrNull { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs }
            LbMode.BALANCED -> {
                val now = System.currentTimeMillis()
                key?.let { k -> sticky[k]?.let { (t, exp) -> if (exp > now && t.usable && t in usable) return t } }
                val least = usable.minOf { it.active.get() }
                val candidates = usable.filter { it.active.get() == least }
                val t = candidates[Math.floorMod(rr.getAndIncrement(), candidates.size)]
                if (key != null) {
                    if (sticky.size > 5000) sticky.clear()
                    sticky[key] = t to now + 10 * 60_000
                }
                t
            }
        }
    }

    fun reset() = sticky.clear()
}

/** All active VPN server connections (one, or several with the load balancer). */
object VpnClient {
    @Volatile var tunnels: List<Tunnel> = emptyList()
        private set
    @Volatile var exitInfo: String? = null
    @Volatile var error: String? = null

    /** The server used for DNS and location checks: the first working one. */
    val primary: Tunnel? get() = tunnels.firstOrNull { it.usable } ?: tunnels.firstOrNull { it.upstream != null }
    val upstream: SocksUpstream? get() = primary?.upstream
    val lastHandshake: Long get() = primary?.lastHandshake ?: 0L
    val state: String
        get() {
            val ts = tunnels
            if (ts.isEmpty()) return if (error != null) "Not connected" else "Off"
            val ok = ts.count { it.usable }
            return if (ts.size == 1) "${ts[0].state} · ${ts[0].name}"
            else "$ok of ${ts.size} servers working · ${LoadBalancer.mode.label}"
        }

    /** Screenshot tests only: draw the VPN screens without the native engines. */
    @Volatile var previewEngines = false
    fun available(ctx: Context) = previewEngines ||
        File(ctx.applicationInfo.nativeLibraryDir, "libbbwg.so").canExecute()

    private val rnd = SecureRandom()
    internal fun token() = (1..24).map { "abcdefghijkmnopqrstuvwxyz23456789"[rnd.nextInt(33)] }.joinToString("")

    /** Connects to every profile in parallel. Blocking; true if at least one is up. */
    @Synchronized
    fun startAll(ctx: Context, profiles: List<VpnProfile>): Boolean {
        stop()
        error = null
        exitInfo = null
        // Only one Tor instance can run; keep the first Tor profile.
        var torSeen = false
        val list = profiles.filter { p -> if (TorClient.isTor(p)) !torSeen.also { torSeen = true } else true }
            .map { Tunnel(it) }
        tunnels = list
        val threads = list.map { t -> thread(name = "vpn-start-${t.name}") { t.start(ctx) } }
        threads.forEach { it.join(130_000) }
        LoadBalancer.reset()
        val up = list.any { it.upstream != null }
        if (!up) error = list.mapNotNull { it.error }.distinct().joinToString("\n").ifBlank { "Couldn't connect" }
        return up
    }

    /** Restarts one dead tunnel in place (auto-reconnect). */
    fun restart(ctx: Context, t: Tunnel): Tunnel {
        t.stop()
        val fresh = Tunnel(t.profile)
        tunnels = tunnels.map { if (it === t) fresh else it }
        fresh.start(ctx)
        return fresh
    }

    @Synchronized
    fun stop() {
        tunnels.forEach { it.stop() }
        tunnels = emptyList()
        LoadBalancer.reset()
    }

    /** DNS servers from the config, used for lookups inside the tunnel. */
    fun dnsOf(conf: String): List<InetAddress> = conf.lines()
        .map { it.trim() }
        .filter { it.lowercase().startsWith("dns") && it.contains('=') }
        .flatMap { it.substringAfter('=').split(',') }
        .mapNotNull { v -> v.trim().takeIf { Regex("^[0-9a-fA-F:.]+$").matches(it) } }
        .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

    /** wireguard-go needs IP:port endpoints; resolve hostnames here (outside the tunnel). */
    fun resolveEndpoints(conf: String): String = conf.lines().joinToString("\n") { line ->
        val t = line.trim()
        if (!t.lowercase().startsWith("endpoint") || !t.contains('=')) return@joinToString line
        val v = t.substringAfter('=').trim()
        if (v.startsWith("[")) return@joinToString line // already an IPv6 literal
        val host = v.substringBeforeLast(':')
        val port = v.substringAfterLast(':')
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) return@joinToString line
        val all = InetAddress.getAllByName(host)
        val ip = all.firstOrNull { it is Inet4Address } ?: all.first()
        val addr = if (ip is Inet6Address) "[${ip.hostAddress}]" else ip.hostAddress
        "Endpoint = $addr:$port"
    }

    /**
     * Asks ipinfo.io which IP and country websites see — through the VPN when it's on.
     * Blocking; call from a background thread.
     */
    fun checkLocation(): String {
        val json = try {
            val up = upstream
            if (up != null) {
                up.connect("ipinfo.io", 443).use { raw ->
                    raw.soTimeout = 10_000
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, "ipinfo.io", 443, true) as SSLSocket
                    tls.use {
                        it.startHandshake()
                        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify("ipinfo.io", it.session)) {
                            throw java.io.IOException("certificate mismatch")
                        }
                        it.outputStream.write(
                            "GET /json HTTP/1.0\r\nHost: ipinfo.io\r\nUser-Agent: BlockBridge\r\nAccept: application/json\r\n\r\n"
                                .toByteArray()
                        )
                        it.outputStream.flush()
                        it.inputStream.readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n")
                    }
                }
            } else {
                (URL("https://ipinfo.io/json").openConnection() as HttpsURLConnection).run {
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("User-Agent", "BlockBridge")
                    inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }
        } catch (e: Exception) {
            return "Couldn't check location: ${e.message}".also { exitInfo = it }
        }
        return try {
            val o = JSONObject(json)
            val place = listOf(o.optString("city"), o.optString("region"), countryName(o.optString("country")))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (upstream != null) "✓ Through the VPN, websites see: ${o.optString("ip")} — $place\n${o.optString("org")}"
            else "⚠ VPN not connected — this is your REAL location: ${o.optString("ip")} — $place\n${o.optString("org")}"
        } catch (e: Exception) {
            "Couldn't read location answer"
        }.also { exitInfo = it }
    }

    private fun countryName(code: String) =
        if (code.length == 2) java.util.Locale("", code).displayCountry.ifBlank { code } else code
}

/** The VPN's real state (not just the switch), shown the same way everywhere. */
object VpnStatus {
    enum class Link { OFF, NO_SERVER, PROTECTION_OFF, UNAVAILABLE, CONNECTING, CONNECTED, FAILED }

    class State(val link: Link, val text: String, val flag: String = "🌐")

    fun of(ctx: android.content.Context): State {
        val s = Settings(ctx)
        if (!s.vpnEnabled) return State(Link.OFF, "VPN off — websites see your real location")
        val profiles = VpnProfiles.all(ctx)
        val wanted = if (s.lbMode == LbMode.OFF) listOfNotNull(VpnProfiles.selected(ctx))
        else profiles.filter { it.id in s.lbMembers }.ifEmpty { listOfNotNull(VpnProfiles.selected(ctx)) }
        if (wanted.isEmpty()) return State(Link.NO_SERVER, "No VPN server yet — add one in the VPN tab")
        if (!ShieldVpnService.isRunning) return State(Link.PROTECTION_OFF, "Turn protection on to connect the VPN")
        if (!ShieldVpnService.fullTunnel) return State(Link.UNAVAILABLE,
            if (s.safeMode) "VPN can't run in Safe mode (Settings › Safe mode)"
            else "VPN needs all-traffic mode, which didn't start — see Settings › Diagnostics")
        val ts = VpnClient.tunnels
        val up = ts.firstOrNull { it.usable && it.lastHandshake > 0 }
        if (up != null) {
            val extra = if (ts.size > 1) " · ${ts.count { it.usable }}/${ts.size} servers" else ""
            return State(Link.CONNECTED, "Connected · ${up.name}$extra", Flags.of(up.name))
        }
        val failed = ts.isNotEmpty() && ts.all { it.dead || it.upstream == null && it.error != null } || (ts.isEmpty() && VpnClient.error != null)
        if (failed) return State(Link.FAILED, "Not connected: ${VpnClient.error ?: ts.firstNotNullOfOrNull { it.error } ?: "server unreachable"} — retrying; internet paused (kill switch)")
        val t = ts.firstOrNull()
        return State(Link.CONNECTING, if (t != null) "${t.state} · ${t.name}" else "Connecting…")
    }
}
