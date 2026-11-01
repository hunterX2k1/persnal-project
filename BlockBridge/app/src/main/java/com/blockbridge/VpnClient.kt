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
 * Runs bbwg (the WireGuard helper bundled as libbbwg.so) and exposes it as a [SocksUpstream].
 * BlockBridge is excluded from its own VPN, so the helper's encrypted UDP goes straight out.
 */
object VpnClient {
    @Volatile var upstream: SocksUpstream? = null
        private set
    @Volatile var state: String = "Off"
        private set
    @Volatile var error: String? = null
    @Volatile var connectedTo: String? = null
        private set
    @Volatile var lastHandshake = 0L
        private set
    @Volatile var exitInfo: String? = null

    private var process: Process? = null
    private val rnd = SecureRandom()

    private fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libbbwg.so")
    /** Screenshot tests only: draw the VPN screens without the native engines. */
    @Volatile var previewEngines = false
    fun available(ctx: Context) = previewEngines || binary(ctx).canExecute()

    @Synchronized
    fun start(ctx: Context, profile: VpnProfile): SocksUpstream? {
        stop()
        error = null
        exitInfo = null
        state = "Connecting to ${profile.name}…"
        if (!available(ctx)) { fail("This build doesn't include the VPN engine"); return null }

        val conf = try {
            resolveEndpoints(profile.conf)
        } catch (e: Exception) {
            fail("Can't find the server address: ${e.message}"); return null
        }
        val file = File(ctx.filesDir, "wg.conf").apply { writeText(conf) }
        val user = token()
        val pass = token()
        val proc = try {
            ProcessBuilder(binary(ctx).path, "-config", file.path)
                .redirectErrorStream(true)
                .apply { environment()["BB_USER"] = user; environment()["BB_PASS"] = pass }
                .start()
        } catch (e: Exception) {
            fail("VPN engine failed to start: ${e.message}"); return null
        }
        process = proc

        val first = LinkedBlockingQueue<String>()
        thread(name = "bbwg-out", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("READY ") || line.startsWith("ERROR ") -> first.offer(line)
                        line.startsWith("HANDSHAKE ") -> {
                            lastHandshake = (line.substringAfter(' ').toLongOrNull() ?: 0) * 1000
                            state = "Connected to ${profile.name}"
                        }
                    }
                }
            }
            if (process === proc) { // helper exited on its own
                upstream = null
                DnsHandler.via = null
                process = null
                connectedTo = null
                fail(if (error == null) "VPN disconnected" else error!!)
            }
        }

        val line = first.poll(8, TimeUnit.SECONDS)
        if (line == null || !line.startsWith("READY ")) {
            val msg = line?.removePrefix("ERROR ") ?: "VPN engine didn't respond"
            stop()
            fail(msg)
            return null
        }
        val up = SocksUpstream(line.substringAfter(' ').trim().toInt(), user, pass)
        upstream = up
        connectedTo = profile.name
        state = "Waiting for ${profile.name} to answer…"
        return up
    }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        upstream = null
        connectedTo = null
        lastHandshake = 0
        p?.let { runCatching { it.outputStream.close() }; it.destroy() }
        state = "Off"
    }

    private fun fail(msg: String) {
        error = msg
        state = "Not connected"
    }

    private fun token() = (1..24).map { "abcdefghijkmnopqrstuvwxyz23456789"[rnd.nextInt(33)] }.joinToString("")

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
            val via = if (upstream != null) "through VPN" else "direct (VPN off)"
            "Websites see: ${o.optString("ip")} — $place\n${o.optString("org")} · $via"
        } catch (e: Exception) {
            "Couldn't read location answer"
        }.also { exitInfo = it }
    }

    private fun countryName(code: String) =
        if (code.length == 2) java.util.Locale("", code).displayCountry.ifBlank { code } else code
}
