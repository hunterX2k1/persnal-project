package app.vault.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class Repo(context: Context, val prefs: Prefs) {
    private val db = Room.databaseBuilder(context, AppDb::class.java, "vault.db")
        .addMigrations(AppDb.MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .build()
    val performers = db.performers()
    val sites = db.sites()
    val api = TpdbApi { prefs.apiKey }
    val free = WikidataSource()
    private val syncLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val hasKey get() = prefs.apiKey.isNotBlank()

    /** True while a sync runs; [syncProgress] says how far along it is. */
    val syncing = MutableStateFlow(false)
    val syncProgress = MutableStateFlow<String?>(null)

    /** Starts a sync in the background (survives leaving the screen). No-op if one is running. */
    fun startSync(onDone: (String) -> Unit = {}) {
        if (syncing.value) return
        scope.launch {
            val msg = runCatching { sync() }.getOrElse {
                AppLog.e("Sync", "Sync failed", it)
                "Update failed: ${it.message}"
            }
            prefs.lastSyncMessage = msg
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    /**
     * Full update: the free database (no key needed), then ThePornDB too if a key is set.
     * The free part resumes where it stopped if it was interrupted.
     */
    suspend fun sync(tpdbPages: Int = 6): String = syncLock.withLock {
        syncing.value = true
        try {
            val parts = mutableListOf<String>()
            parts += "Free database: ${syncFree()} profiles"
            if (hasKey) parts += "ThePornDB: ${syncTpdb(tpdbPages)} profiles"
            prefs.lastSync = System.currentTimeMillis()
            parts.joinToString(" · ").also { AppLog.i("Sync", it) }
        } finally {
            syncing.value = false
            syncProgress.value = null
        }
    }

    private suspend fun syncFree(): Int {
        val ids = free.allIds()
        AppLog.i("Sync", "Free database lists ${ids.size} performers, resuming at ${prefs.freeCursor}")
        var i = prefs.freeCursor.coerceIn(0, ids.size)
        var saved = 0
        while (i < ids.size) {
            syncProgress.value = "Downloading profiles… $i / ${ids.size}"
            val batch = free.details(ids.subList(i, minOf(i + FREE_BATCH, ids.size)))
            // If ThePornDB already has this person, keep that richer profile instead.
            val paid = performers.paidNamesIn(batch.map { it.name.lowercase() }).toSet()
            val keep = batch.filter { it.name.lowercase() !in paid }
            // Keep bios already fetched from Wikipedia.
            val withBios = keep.map { p -> performers.get(p.id)?.bio?.let { p.copy(bio = it) } ?: p }
            if (withBios.isNotEmpty()) performers.upsert(withBios)
            saved += withBios.size
            i += FREE_BATCH
            prefs.freeCursor = if (i >= ids.size) 0 else i
        }
        return saved
    }

    private suspend fun syncTpdb(pages: Int): Int {
        AppLog.i("Sync", "ThePornDB starting at page ${prefs.syncCursor}")
        var page = prefs.syncCursor
        var fetched = 0
        for (n in 0 until pages) {
            syncProgress.value = "ThePornDB page $page…"
            val res = api.performers(page)
            upsertTpdb(res.items)
            fetched += res.items.size
            val atEnd = res.items.isEmpty() || (res.lastPage in 1..page)
            page = if (atEnd) 1 else page + 1
            if (atEnd) break
        }
        prefs.syncCursor = page
        for (id in performers.favoriteIds().filter { !it.startsWith("wd:") }.take(40)) {
            runCatching { api.performer(id) }.getOrNull()?.let { upsertTpdb(listOf(it)) }
        }
        return fetched
    }

    /** Saves ThePornDB profiles and folds any free-source duplicate (same name) into them, keeping favourites and tags. */
    private suspend fun upsertTpdb(list: List<Performer>) {
        if (list.isEmpty()) return
        performers.upsert(list)
        for (p in list) for (old in performers.freeIdsNamed(p.name)) {
            performers.moveFavorites(old, p.id); performers.moveTags(old, p.id)
            performers.deleteFavoritesOf(old); performers.deleteTagsOf(old); performers.delete(old)
            AppLog.i("Sync", "Merged free profile into ThePornDB profile")
        }
    }

    /** Searches ThePornDB (needs a key) and caches matches locally. */
    suspend fun searchOnline(query: String): String {
        if (!hasKey) return "Everything from the free database is already on your phone. Try another spelling, or add a ThePornDB key for more."
        val res = api.performers(1, query)
        upsertTpdb(res.items)
        return "Found ${res.items.size} online"
    }

    /** Refreshes one profile from its source; for free profiles, loads the Wikipedia summary once. */
    suspend fun refresh(id: String) {
        if (id.startsWith("wd:")) {
            val p = performers.get(id) ?: return
            if (p.bio == null && p.wiki != null) free.summary(p.wiki)?.let { performers.setBio(id, it) }
        } else if (hasKey) {
            api.performer(id)?.let { upsertTpdb(listOf(it)) }
        }
    }

    /** Scene lists come from ThePornDB only. Returns null when there is no key. */
    suspend fun scenes(p: Performer): List<Scene>? = if (hasKey) api.scenes(p) else null

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
        const val FREE_BATCH = 400
        fun prettyDomain(d: String) = d.substringBeforeLast('.').replaceFirstChar(Char::uppercase)
    }
}
