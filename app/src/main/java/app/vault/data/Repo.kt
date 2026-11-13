package app.vault.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class Repo(context: Context, val prefs: Prefs) {
    private val db = Room.databaseBuilder(context, AppDb::class.java, "vault.db")
        .fallbackToDestructiveMigration()
        .build()
    val performers = db.performers()
    val sites = db.sites()
    val api = TpdbApi { prefs.apiKey }
    private val syncLock = Mutex()

    /**
     * Pulls the next few pages of the catalogue, carrying on from where the last sync stopped,
     * so the local database fills up over time and keeps refreshing. Favourites are always refreshed.
     */
    suspend fun syncStep(pages: Int = 6): String = syncLock.withLock {
        AppLog.i("Sync", "Starting at page ${prefs.syncCursor}")
        var page = prefs.syncCursor
        var fetched = 0
        for (i in 0 until pages) {
            val res = api.performers(page)
            if (res.items.isNotEmpty()) performers.upsert(res.items)
            fetched += res.items.size
            val atEnd = res.items.isEmpty() || (res.lastPage in 1..page)
            page = if (atEnd) 1 else page + 1
            if (atEnd) break
        }
        prefs.syncCursor = page
        for (id in performers.favoriteIds().take(40)) {
            runCatching { api.performer(id) }.getOrNull()?.let { performers.upsert(listOf(it)) }
        }
        prefs.lastSync = System.currentTimeMillis()
        "Updated $fetched profiles".also { prefs.lastSyncMessage = it; AppLog.i("Sync", "$it, next page $page") }
    }

    /** Searches the online database and caches matches locally. */
    suspend fun searchOnline(query: String): Int {
        val res = api.performers(1, query)
        performers.upsert(res.items)
        return res.items.size
    }

    suspend fun refresh(id: String) {
        api.performer(id)?.let { performers.upsert(listOf(it)) }
    }

    suspend fun scenes(p: Performer): List<Scene> = api.scenes(p)

    suspend fun toggleFavorite(id: String, fav: Boolean) =
        if (fav) performers.removeFavorite(id) else performers.addFavorite(Favorite(id))

    // ---------------- Sites ----------------

    /** Reads every link from an HTML file (bookmarks export or any page) and adds one entry per domain. */
    suspend fun importSites(context: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Could not read the file")
        importSitesText(text)
    }

    /**
     * Imports pasted text: HTML, full links, or bare domains (one per line or separated by spaces/commas).
     * Returns (sites found, sites newly added).
     */
    suspend fun importSitesText(text: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Site>()
        if (text.contains("<a", ignoreCase = true)) {
            for (a in Jsoup.parse(text).select("a[href]")) addLink(found, a.attr("href"), a.text())
        }
        Regex("https?://[^\\s\"'<>]+").findAll(text).forEach { addLink(found, it.value, "") }
        // Bare domains like "example.com" or "www.example.co.uk/page"
        if (!text.contains("<")) {
            text.split(Regex("[\\s,;]+")).map { it.trim() }
                .filter { !it.startsWith("http") && Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(/.*)?$").matches(it) }
                .forEach { addLink(found, "https://$it", "") }
        }
        if (found.isEmpty()) {
            AppLog.w("Sites", "Import found no links in ${text.length} chars (starts: ${text.take(80).replace('\n', ' ')})")
            throw IllegalStateException("No website links found")
        }
        val inserted = sites.insertAll(found.values.toList()).count { it != -1L }
        AppLog.i("Sites", "Import: ${text.length} chars → ${found.size} sites, $inserted new")
        found.size to inserted
    }

    private fun addLink(into: MutableMap<String, Site>, href: String, text: String) {
        val uri = runCatching { Uri.parse(href.trim()) }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") return
        val domain = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return
        if (domain in into) return
        val title = text.trim().takeIf { it.isNotEmpty() && it.length < 60 } ?: prettyDomain(domain)
        into[domain] = Site(domain = domain, title = title, url = "https://$domain/")
    }

    fun addSite(rawUrl: String, title: String?): Site? {
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        val domain = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return null
        return Site(domain, title?.takeIf { it.isNotBlank() } ?: prettyDomain(domain), "https://$domain/")
    }

    fun siteSearchUrl(site: Site, name: String): String {
        val tpl = site.searchTemplate
        return if (!tpl.isNullOrBlank() && tpl.contains("{q}")) {
            tpl.replace("{q}", URLEncoder.encode(name, "UTF-8"))
        } else {
            prefs.webSearchUrl("site:${site.domain} \"$name\"")
        }
    }

    /** Splits the user's sites into ones the database confirms the performer appeared on, and the rest. */
    fun matchSites(scenes: List<Scene>, all: List<Site>): Pair<List<Site>, List<Site>> {
        val keys = scenes.flatMap { s ->
            listOfNotNull(
                s.siteUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.lowercase()?.removePrefix("www."),
                s.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.lowercase()?.removePrefix("www."),
                s.siteName?.let(::norm),
            )
        }.filter { it.isNotEmpty() }.toSet()
        val confirmed = all.filter { site ->
            val d = site.domain
            val dn = norm(d.substringBeforeLast('.'))
            keys.any { k -> k == d || d.endsWith(".$k") || k.endsWith(".$d") || (k.length >= 4 && k == dn) }
        }
        AppLog.i("Sites", "Match: ${scenes.size} scenes, ${keys.size} site keys, ${confirmed.size}/${all.size} of my sites confirmed")
        return confirmed to (all - confirmed.toSet())
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        fun prettyDomain(d: String) = d.substringBeforeLast('.').replaceFirstChar(Char::uppercase)
    }
}
