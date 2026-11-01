package com.blockbridge

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

data class ListSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val defaultOn: Boolean,
)

object Sources {
    val all = listOf(
        ListSource(
            "adguard_dns", "AdGuard DNS filter",
            "Ads & trackers. Built from EasyList/EasyPrivacy — the same lists uBlock Origin uses.",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", true,
        ),
        ListSource(
            "peter_lowe", "Peter Lowe's ad & tracking list",
            "Enabled by default in uBlock Origin.",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            true,
        ),
        ListSource(
            "urlhaus", "Malicious sites (URLhaus)",
            "Malware & phishing domains. Enabled by default in uBlock Origin.",
            "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt", true,
        ),
        ListSource(
            "stevenblack", "StevenBlack unified hosts",
            "Large combined ads + malware list.",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", false,
        ),
        ListSource(
            "hagezi_pro", "HaGeZi Multi PRO",
            "Aggressive ads/tracking/analytics. May break a few sites.",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt", false,
        ),
    )
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isEnabled(s: ListSource) = p.getBoolean("list_${s.id}", s.defaultOn)
    fun setEnabled(s: ListSource, on: Boolean) = p.edit().putBoolean("list_${s.id}", on).apply()

    var upstream1: String
        get() = p.getString("up1", "1.1.1.1")!!
        set(v) = p.edit().putString("up1", v).apply()
    var upstream2: String
        get() = p.getString("up2", "8.8.8.8")!!
        set(v) = p.edit().putString("up2", v).apply()
    var userBlock: String
        get() = p.getString("user_block", "")!!
        set(v) = p.edit().putString("user_block", v).apply()
    var userAllow: String
        get() = p.getString("user_allow", "")!!
        set(v) = p.edit().putString("user_allow", v).apply()
    var lastUpdate: Long
        get() = p.getLong("last_update", 0)
        set(v) = p.edit().putLong("last_update", v).apply()
    var wantRunning: Boolean
        get() = p.getBoolean("want_running", false)
        set(v) = p.edit().putBoolean("want_running", v).apply()
}

/** Upstream DNS servers that allowed lookups are forwarded to. */
object Upstream {
    @Volatile var servers: List<InetAddress> = defaults()

    private fun defaults() = listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
    private val NUMERIC = Regex("^[0-9a-fA-F:.]+$")

    fun isValid(s: String) = s.isNotBlank() && NUMERIC.matches(s.trim()) &&
        runCatching { InetAddress.getByName(s.trim()) }.isSuccess

    fun reload(s: Settings) {
        val list = listOf(s.upstream1, s.upstream2).filter { isValid(it) }
            .map { InetAddress.getByName(it.trim()) }
        servers = list.ifEmpty { defaults() }
    }
}

object Blocklists {
    private val lock = Any()
    @Volatile var status: String = ""
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    private fun rawFile(ctx: Context, s: ListSource) =
        File(File(ctx.filesDir, "lists").apply { mkdirs() }, "${s.id}.txt")
    private fun compiledBlock(ctx: Context) = File(ctx.filesDir, "compiled_block.txt")
    private fun compiledAllow(ctx: Context) = File(ctx.filesDir, "compiled_allow.txt")

    fun needsUpdate(ctx: Context) = !compiledBlock(ctx).exists() ||
        System.currentTimeMillis() - Settings(ctx).lastUpdate > MAX_AGE_MS

    /** Loads everything saved on disk (used at app/VPN start). */
    fun loadAll(ctx: Context) {
        val s = Settings(ctx)
        Upstream.reload(s)
        loadUserRules(s)
        synchronized(lock) {
            if (Filter.listsLoaded) return
            val b = compiledBlock(ctx)
            if (b.exists()) Filter.setLists(readSet(b), readSet(compiledAllow(ctx)))
        }
    }

    fun loadUserRules(s: Settings) {
        fun parse(text: String) = text.lines().mapNotNull { ListParser.userDomain(it) }.toHashSet()
        Filter.setUserRules(parse(s.userBlock), parse(s.userAllow))
    }

    /**
     * Downloads enabled lists (all of them when [force], otherwise only missing ones),
     * then rebuilds the combined blocklist. Blocking — call from a background thread.
     */
    fun update(ctx: Context, force: Boolean): String = synchronized(lock) {
        val s = Settings(ctx)
        val enabled = Sources.all.filter { s.isEnabled(it) }
        val errors = mutableListOf<String>()
        var downloaded = 0
        try {
            for (src in enabled) {
                val f = rawFile(ctx, src)
                if (!force && f.exists()) continue
                status = "Downloading ${src.name}…"
                try {
                    download(src.url, f); downloaded++
                } catch (e: Exception) {
                    errors += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            status = "Building blocklist…"
            val block = HashSet<String>(250_000)
            val allow = HashSet<String>()
            for (src in enabled) {
                val f = rawFile(ctx, src)
                if (f.exists()) f.forEachLine { ListParser.parseLine(it, block, allow) }
            }
            writeSet(compiledBlock(ctx), block)
            writeSet(compiledAllow(ctx), allow)
            Filter.setLists(block, allow)
            if (downloaded > 0) s.lastUpdate = System.currentTimeMillis()
            return if (errors.isEmpty()) "Blocklist ready: ${"%,d".format(block.size)} domains"
            else "Updated with errors:\n" + errors.joinToString("\n")
        } finally {
            status = ""
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "BlockBridge/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val tmp = File(dest.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IOException("could not save list")
        } finally {
            conn.disconnect()
        }
    }

    private fun readSet(f: File): HashSet<String> {
        val set = HashSet<String>()
        if (f.exists()) f.forEachLine { if (it.isNotEmpty()) set.add(it) }
        return set
    }

    private fun writeSet(f: File, set: Set<String>) {
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w -> for (d in set) { w.write(d); w.newLine() } }
        tmp.renameTo(f)
    }
}

/** Live counters and the recent-requests log shown in the app. */
object Stats {
    class Entry(val time: Long, val domain: String, val blocked: Boolean)

    val total = AtomicLong()
    val blocked = AtomicLong()
    @Volatile var version = 0L
    private val recent = ArrayDeque<Entry>()

    fun record(domain: String, wasBlocked: Boolean) {
        total.incrementAndGet()
        if (wasBlocked) blocked.incrementAndGet()
        val now = System.currentTimeMillis()
        synchronized(recent) {
            // Browsers ask for A, AAAA and HTTPS records together; show the domain once.
            if (recent.take(6).any { it.domain == domain && now - it.time < 3000 }) return
            recent.addFirst(Entry(now, domain, wasBlocked))
            while (recent.size > 100) recent.removeLast()
        }
        version++
    }

    fun recent(): List<Entry> = synchronized(recent) { recent.toList() }
}
