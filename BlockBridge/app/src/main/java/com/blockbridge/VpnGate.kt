package com.blockbridge

import android.content.Context
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/**
 * VPN Gate (vpngate.net) — free volunteer OpenVPN servers run as an academic project of the
 * University of Tsukuba. The public list includes each server's country, ping, speed and
 * load, measured by VPN Gate, plus a ready-made OpenVPN config.
 */
object VpnGate {
    class Server(
        val host: String, val ip: String, val score: Long, val pingMs: Int, val speedMbps: Double,
        val country: String, val code: String, val sessions: Int, val uptimeDays: Long,
        val proto: String, val port: Int, val config: String,
    ) {
        val profileName get() = "$code · $country · $host"
    }

    @Volatile var cached: List<Server> = emptyList()
        private set
    @Volatile var fetchedAt = 0L
        private set

    /** Downloads and parses the current server list. Blocking. */
    fun fetch(): List<Server> {
        val conn = URL("https://www.vpngate.net/api/iphone/").openConnection() as HttpsURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "BlockBridge")
        val text = try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        return parse(text).also { cached = it; fetchedAt = System.currentTimeMillis() }
    }

    /** Parses VPN Gate's CSV ("*vpn_servers", header "#HostName,...", rows, "*"). */
    fun parse(csv: String): List<Server> = csv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("*") && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 15) return@mapNotNull null
            val config = runCatching { String(Base64.getDecoder().decode(f[14].trim()), Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            val proto = Regex("(?m)^proto\\s+(\\w+)").find(config)?.groupValues?.get(1) ?: "?"
            val port = Regex("(?m)^remote\\s+\\S+\\s+(\\d+)").find(config)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Server(
                host = f[0], ip = f[1], score = f[2].toLongOrNull() ?: 0, pingMs = f[3].toIntOrNull() ?: -1,
                speedMbps = (f[4].toLongOrNull() ?: 0) / 1e6, country = f[5], code = f[6].uppercase(),
                sessions = f[7].toIntOrNull() ?: 0, uptimeDays = (f[8].toLongOrNull() ?: 0) / 86_400_000,
                proto = proto, port = port, config = config,
            )
        }
        .sortedByDescending { it.score }
        .toList()

    /** Adds [s] as a VPN server (OpenVPN profile) and returns it. */
    fun add(ctx: Context, s: Server): VpnProfile {
        VpnProfiles.all(ctx).firstOrNull { it.conf == Ovpn.PREFIX + s.config }?.let { return it }
        return VpnProfiles.add(ctx, s.profileName, Ovpn.PREFIX + s.config)
    }
}

/** OpenVPN profiles are stored with this prefix in front of the .ovpn text. */
object Ovpn {
    const val PREFIX = "ovpn:"

    fun isOvpn(p: VpnProfile) = p.conf.startsWith(PREFIX)
    fun text(p: VpnProfile) = p.conf.removePrefix(PREFIX)

    /** True if [text] looks like an OpenVPN client config. */
    fun looksLikeOvpn(text: String): Boolean {
        val t = text.lowercase()
        return Regex("(?m)^\\s*remote\\s+\\S+").containsMatchIn(t) &&
            ("<ca>" in t || Regex("(?m)^\\s*ca\\s").containsMatchIn(t)) &&
            (Regex("(?m)^\\s*client\\s*$").containsMatchIn(t) || "tls-client" in t)
    }
}
