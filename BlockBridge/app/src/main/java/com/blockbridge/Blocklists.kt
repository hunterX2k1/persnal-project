package com.blockbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** One entry of uBO's filter list catalogue (assets/filter_lists.json, from uBO's assets.json). */
class FilterList(
    val key: String,
    val title: String,
    val group: String,
    val defaultOn: Boolean,
    val urls: List<String>,
    val updateDays: Int,
)

object Catalog {
    val GROUP_TITLES = linkedMapOf(
        "default" to "Built-in (uBlock filters)",
        "ads" to "Ads",
        "privacy" to "Privacy",
        "malware" to "Malware protection, security",
        "multipurpose" to "Multipurpose",
        "annoyances" to "Annoyances",
        "regions" to "Regions, languages",
    )

    @Volatile private var cache: List<FilterList>? = null

    fun all(ctx: Context): List<FilterList> = cache ?: synchronized(this) {
        cache ?: run {
            val json = ctx.assets.open("filter_lists.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val urls = o.getJSONArray("urls")
                FilterList(
                    key = o.getString("key"),
                    title = o.getString("title"),
                    group = o.getString("group"),
                    defaultOn = o.getBoolean("on"),
                    urls = (0 until urls.length()).map { urls.getString(it) },
                    updateDays = o.optInt("days", 5).coerceIn(1, 30),
                )
            }.also { cache = it }
        }
    }

    fun title(ctx: Context, key: String?): String = when (key) {
        null -> ""
        Filter.BY_USER -> "My filters"
        Filter.BY_TRUSTED -> "Trusted site"
        else -> all(ctx).firstOrNull { it.key == key }?.title ?: key
    }
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Carry over rules from version 1.0 ("always block" / "never block").
        if (!p.contains("my_filters") && (p.contains("user_block") || p.contains("user_allow"))) {
            val block = p.getString("user_block", "")!!.lines().filter { it.isNotBlank() }
                .joinToString("\n") { "||${it.trim()}^" }
            val allow = p.getString("user_allow", "")!!.lines().filter { it.isNotBlank() }
                .joinToString("\n") { "@@||${it.trim()}^" }
            p.edit().putString("my_filters", listOf(block, allow).filter { it.isNotEmpty() }.joinToString("\n"))
                .remove("user_block").remove("user_allow").apply()
        }
    }

    fun isEnabled(l: FilterList) = p.getBoolean("list_${l.key}", l.defaultOn)
    fun setEnabled(l: FilterList, on: Boolean) = p.edit().putBoolean("list_${l.key}", on).apply()

    var upstream1: String
        get() = p.getString("up1", "1.1.1.1")!!
        set(v) = p.edit().putString("up1", v).apply()
    var upstream2: String
        get() = p.getString("up2", "8.8.8.8")!!
        set(v) = p.edit().putString("up2", v).apply()
    /** uBO "My filters": rules in uBO syntax. */
    var myFilters: String
        get() = p.getString("my_filters", "")!!
        set(v) = p.edit().putString("my_filters", v).apply()
    /** uBO "Trusted sites": hostnames that are never filtered. */
    var trustedSites: String
        get() = p.getString("trusted_sites", "")!!
        set(v) = p.edit().putString("trusted_sites", v).apply()
    var lastUpdate: Long
        get() = p.getLong("last_update", 0)
        set(v) = p.edit().putLong("last_update", v).apply()
    var wantRunning: Boolean
        get() = p.getBoolean("want_running", false)
        set(v) = p.edit().putBoolean("want_running", v).apply()

    fun listCount(key: String): String? = p.getString("count_$key", null)
    fun setListCounts(counts: Map<String, UboCompiler.ListCount>) {
        val e = p.edit()
        for ((k, c) in counts) e.putString("count_$k", "%,d used out of %,d".format(c.used, c.total))
        e.apply()
    }

    fun expiresDays(key: String, default: Int) = p.getInt("exp_$key", default)
    fun setExpiresDays(key: String, days: Int) = p.edit().putInt("exp_$key", days).apply()

    /** Same idea as uBO's "Back up to file": selected lists, my filters, trusted sites, DNS. */
    fun backup(ctx: Context): String = JSONObject().apply {
        put("app", "BlockBridge")
        put("selectedFilterLists", JSONArray(Catalog.all(ctx).filter { isEnabled(it) }.map { it.key }))
        put("userFilters", myFilters)
        put("trustedSites", trustedSites)
        put("dns", JSONArray(listOf(upstream1, upstream2)))
    }.toString(2)

    fun restore(ctx: Context, text: String) {
        val o = JSONObject(text)
        o.optJSONArray("selectedFilterLists")?.let { arr ->
            val keys = (0 until arr.length()).map { arr.getString(it) }.toSet()
            for (l in Catalog.all(ctx)) setEnabled(l, l.key in keys)
        }
        if (o.has("userFilters")) myFilters = o.getString("userFilters")
        if (o.has("trustedSites")) trustedSites = o.getString("trustedSites")
        // uBO's own backups call it "whitelist" / "netWhitelist"
        if (!o.has("trustedSites") && o.has("whitelist")) {
            val w = o.get("whitelist")
            trustedSites = if (w is JSONArray) (0 until w.length()).joinToString("\n") { w.getString(it) } else w.toString()
        }
        o.optJSONArray("dns")?.let { if (it.length() > 0) upstream1 = it.getString(0); if (it.length() > 1) upstream2 = it.getString(1) }
    }
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
    private val EXPIRES = Regex("^!\\s*expires:\\s*(\\d+)\\s*(day|hour)", RegexOption.IGNORE_CASE)

    private fun rawFile(ctx: Context, key: String) =
        File(File(ctx.filesDir, "lists").apply { mkdirs() }, "$key.txt")
    private fun compiledFile(ctx: Context) = File(ctx.filesDir, "compiled_v2.tsv")

    private fun isStale(ctx: Context, s: Settings, l: FilterList): Boolean {
        val f = rawFile(ctx, l.key)
        if (!f.exists()) return true
        val days = s.expiresDays(l.key, l.updateDays)
        return System.currentTimeMillis() - f.lastModified() > days * 86_400_000L
    }

    /** True when the compiled set is missing or a selected list is past its "Expires". */
    fun needsUpdate(ctx: Context): Boolean {
        if (!compiledFile(ctx).exists()) return true
        val s = Settings(ctx)
        return Catalog.all(ctx).any { s.isEnabled(it) && isStale(ctx, s, it) }
    }

    /** Loads everything saved on disk (used at app/VPN start). */
    fun loadAll(ctx: Context) {
        val s = Settings(ctx)
        Upstream.reload(s)
        loadUserRules(s)
        synchronized(lock) {
            if (Filter.listsLoaded) return
            val f = compiledFile(ctx)
            if (f.exists()) Filter.setLists(readCompiled(f))
        }
    }

    /** Compiles "My filters" with the same engine as the lists, plus "Trusted sites". */
    fun loadUserRules(s: Settings) {
        val c = UboCompiler.Compiler()
        c.addList(Filter.BY_USER, s.myFilters.lineSequence())
        val trusted = s.trustedSites.lines().mapNotNull { Filter.hostOf(it) }.toHashSet()
        Filter.setUser(c.build(), trusted)
    }

    /**
     * Downloads selected lists (all when [force], otherwise missing/expired ones), then
     * compiles them together. Blocking — call from a background thread.
     */
    fun update(ctx: Context, force: Boolean): String = synchronized(lock) {
        val s = Settings(ctx)
        val selected = Catalog.all(ctx).filter { s.isEnabled(it) }
        val errors = mutableListOf<String>()
        var downloaded = 0
        try {
            for (l in selected) {
                if (!force && !isStale(ctx, s, l)) continue
                status = "Downloading ${l.title}…"
                val f = rawFile(ctx, l.key)
                val ok = l.urls.any { url -> runCatching { download(url, f) }.isSuccess }
                if (ok) {
                    downloaded++
                    readExpires(f)?.let { s.setExpiresDays(l.key, it) }
                } else if (!f.exists()) {
                    errors += l.title
                } else {
                    f.setLastModified(System.currentTimeMillis()) // keep old copy, retry later
                }
            }
            status = "Compiling filters…"
            val c = UboCompiler.Compiler()
            for (l in selected) {
                val f = rawFile(ctx, l.key)
                if (f.exists()) f.bufferedReader().useLines { c.addList(l.key, it) }
            }
            val rules = c.build()
            writeCompiled(compiledFile(ctx), rules)
            Filter.setLists(rules)
            s.setListCounts(c.counts)
            if (downloaded > 0) s.lastUpdate = System.currentTimeMillis()
            return if (errors.isEmpty()) "Filters ready: ${"%,d".format(rules.size)} hostnames blocked"
            else "Could not download: " + errors.joinToString(", ")
        } finally {
            status = ""
        }
    }

    private fun readExpires(f: File): Int? {
        f.bufferedReader().useLines { lines ->
            for (line in lines.take(30)) {
                val m = EXPIRES.find(line.trim()) ?: continue
                val n = m.groupValues[1].toInt()
                return if (m.groupValues[2].equals("hour", true)) 1 else n.coerceIn(1, 30)
            }
        }
        return null
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "BlockBridge/2.0 (Android; uBO-compatible)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val tmp = File(dest.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L) throw IOException("empty")
            if (!tmp.renameTo(dest)) throw IOException("could not save list")
        } finally {
            conn.disconnect()
        }
    }

    // Compiled file: "<b|i|a>\t<hostname>\t<list key>" per line
    private fun writeCompiled(f: File, r: Ruleset) {
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((tag, map) in listOf("b" to r.block, "i" to r.important, "a" to r.allow)) {
                for ((d, l) in map) { w.write(tag); w.write('\t'.code); w.write(d); w.write('\t'.code); w.write(l); w.newLine() }
            }
        }
        tmp.renameTo(f)
    }

    private fun readCompiled(f: File): Ruleset {
        val b = HashMap<String, String>(200_000)
        val i = HashMap<String, String>()
        val a = HashMap<String, String>()
        val keys = HashMap<String, String>()
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size != 3) return@forEachLine
            val key = keys.getOrPut(p[2]) { p[2] }
            when (p[0]) { "b" -> b[p[1]] = key; "i" -> i[p[1]] = key; "a" -> a[p[1]] = key }
        }
        return Ruleset(b, i, a)
    }
}

/** Live counters and the logger shown in the app. */
object Stats {
    class Entry(val time: Long, val domain: String, val blocked: Boolean, val by: String?)

    val total = AtomicLong()
    val blocked = AtomicLong()
    @Volatile var version = 0L
    private val recent = ArrayDeque<Entry>()

    fun record(domain: String, verdict: Filter.Verdict) {
        total.incrementAndGet()
        if (verdict.blocked) blocked.incrementAndGet()
        val now = System.currentTimeMillis()
        synchronized(recent) {
            // Browsers ask for A, AAAA and HTTPS records together; log the hostname once.
            if (recent.take(6).any { it.domain == domain && now - it.time < 3000 }) return
            recent.addFirst(Entry(now, domain, verdict.blocked, verdict.by))
            while (recent.size > 200) recent.removeLast()
        }
        version++
    }

    fun recent(): List<Entry> = synchronized(recent) { recent.toList() }

    fun clear() {
        synchronized(recent) { recent.clear() }
        version++
    }
}
