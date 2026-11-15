package app.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host-based blocking for the in-app browser:
 *  - ads/trackers/malware hosts from StevenBlack/hosts (MIT), refreshed weekly
 *  - a built-in list of ad networks common on adult sites (pop-unders, redirect ads)
 *  - known malware / scam sites (github.com/fmhy/FMHYFilterlist)
 */
object AdBlock {
    private const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val UNSAFE_URL = "https://raw.githubusercontent.com/fmhy/FMHYFilterlist/main/filterlist-domains.txt"
    private const val WEEK = 7 * 24 * 60 * 60 * 1000L

    @Volatile private var ads: Set<String> = emptySet()
    @Volatile private var unsafe: Set<String> = emptySet()
    val adHostCount get() = ads.size
    val unsafeCount get() = unsafe.size

    suspend fun load(dir: File, force: Boolean = false) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val adsFile = File(dir, "hosts.txt")
        val unsafeFile = File(dir, "unsafe.txt")
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        for ((url, f) in listOf(HOSTS_URL to adsFile, UNSAFE_URL to unsafeFile)) {
            if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) {
                runCatching {
                    client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        val tmp = File(dir, f.name + ".tmp")
                        r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(f)
                    }
                }.onFailure { AppLog.w("AdBlock", "Updating ${f.name} failed: ${it.message}") }
            }
        }
        ads = (if (adsFile.exists()) parse(adsFile) else emptySet()) + ADULT_AD_NETWORKS
        if (unsafeFile.exists()) unsafe = parse(unsafeFile)
        AppLog.i("AdBlock", "Loaded ${ads.size} ad hosts, ${unsafe.size} unsafe sites")
    }

    /** Ad networks that serve most pop-ups, pop-unders and redirect ads on adult video sites. */
    val ADULT_AD_NETWORKS = setOf(
        "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com", "magsrv.com", "trafficjunky.net", "trafficjunky.com",
        "juicyads.com", "plugrush.com", "trafficstars.com", "tsyndicate.com", "popads.net", "popcash.net", "adsterra.com",
        "propellerads.com", "hilltopads.net", "clickadu.com", "adcash.com", "ero-advertising.com", "trafficfactory.biz",
        "twinred.com", "adxpansion.com", "adspyglass.com", "onclkds.com", "a-ads.com", "pornbanners.net", "aucdn.net",
    )

    private fun parse(f: File): Set<String> {
        val out = HashSet<String>(100_000)
        f.forEachLine { line ->
            val t = line.substringBefore('#').trim()
            if (t.isEmpty() || t.startsWith("!")) return@forEachLine
            val parts = t.split(Regex("\\s+"))
            val host = (if (parts.size >= 2) parts[1] else parts[0]).trim('|', '^').lowercase()
            if (host.contains('.') && host != "0.0.0.0" && host != "localhost") out += host
        }
        return out
    }

    private fun matches(set: Set<String>, host: String?): Boolean {
        var h = host?.lowercase()?.removePrefix("www.") ?: return false
        while (true) {
            if (h in set || "www.$h" in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun isAd(host: String?) = matches(ads, host)
    fun isUnsafe(host: String?) = matches(unsafe, host)

    /** "sub.example.co.uk" → "example.co.uk"-ish; good enough to tell same-site from cross-site. */
    fun site(host: String?): String {
        val parts = host?.lowercase()?.removePrefix("www.")?.split('.') ?: return ""
        if (parts.size <= 2) return parts.joinToString(".")
        val twoLevel = parts[parts.size - 2].length <= 3 && parts.last().length == 2
        return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }
}
