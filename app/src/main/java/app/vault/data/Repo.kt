package app.vault.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class Repo(private val context: Context, val prefs: Prefs) {
    private val db = Room.databaseBuilder(context, AppDb::class.java, "vault.db")
        .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4)
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

    // ---------------- Scenes ----------------

    /** Scenes seen this session, so the video screen can open by id. */
    val sceneCache = java.util.concurrent.ConcurrentHashMap<String, Scene>()

    /** One page of a performer's videos (ThePornDB only). Null when there is no key. */
    suspend fun scenes(p: Performer, page: Int = 1): ScenePage? {
        if (!hasKey) return null
        return api.scenes(p, page).also { r -> r.scenes.forEach { sceneCache[it.id] = it } }
    }

    // ---------------- Search my sites for a video ----------------

    val siteSearch = SiteSearch { site, template ->
        // No search page found → leave the site out of video searches (the user can switch it back on).
        sites.upsert(site.copy(
            searchTemplate = template ?: site.searchTemplate,
            searchCheckedAt = System.currentTimeMillis(),
            inVideoSearch = template != null || site.searchTemplate != null,
        ))
    }

    suspend fun videoSearchSites(): List<Site> {
        val month = 30L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        return sites.list().filter {
            it.inVideoSearch && !it.domain.contains('/') && !SiteSearch.isNonVideo(it.domain) &&
                (it.kind == null || it.kind == SiteKind.VIDEO) &&
                // already checked, no search page found: skip for a month
                !(it.searchTemplate.isNullOrBlank() && it.searchCheckedAt > 0 && now - it.searchCheckedAt < month)
        }.sortedByDescending { !it.searchTemplate.isNullOrBlank() } // working sites first
    }

    // ---------------- Site scanner ----------------

    private val scanner = SiteScanner(siteSearch)
    val scanning = MutableStateFlow(false)
    val scanStatus = MutableStateFlow<String?>(null)
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private suspend fun sampleName() = performers.samplePerformerName() ?: "Jane Doe"

    /** Scans sites (unscanned ones, or all with [rescan]) to sort them by type and find search pages of video sites. */
    fun scanSites(rescan: Boolean = false) {
        if (scanning.value) return
        bg.launch {
            scanning.value = true
            try {
                val todo = sites.list().filter { !it.domain.contains('/') && (rescan || it.kind == null) }
                val sample = sampleName()
                var done = 0; var video = 0; var searchable = 0
                val gate = kotlinx.coroutines.sync.Semaphore(6)
                kotlinx.coroutines.coroutineScope {
                    todo.forEach { s ->
                        launch {
                            gate.withPermit {
                                val r = attempt { kotlinx.coroutines.withTimeoutOrNull(45_000) { scanner.scan(s, sample) } }.getOrNull()
                                    ?: SiteScanner.Result(SiteKind.UNREACHABLE, null)
                                val tpl = r.template ?: s.searchTemplate
                                sites.upsert(s.copy(kind = r.kind, searchTemplate = tpl, searchCheckedAt = System.currentTimeMillis(),
                                    inVideoSearch = r.kind == SiteKind.VIDEO && !tpl.isNullOrBlank()))
                                synchronized(this@Repo) {
                                    done++; if (r.kind == SiteKind.VIDEO) video++; if (r.kind == SiteKind.VIDEO && !tpl.isNullOrBlank()) searchable++
                                    scanStatus.value = "Scanned $done / ${todo.size} · $video video sites · $searchable searchable"
                                }
                            }
                        }
                    }
                }
                AppLog.i("Scan", "Finished: ${todo.size} sites, $video video, $searchable searchable")
            } finally { scanning.value = false }
        }
    }

    /** Adds a site (e.g. from the browser) and scans it in the background. */
    suspend fun addSiteAndScan(url: String): String {
        val host = cleanDomain(attempt { java.net.URI(url).host }.getOrNull()) ?: return "That isn't a website address"
        if (sites.get(host) != null) return "${prettyDomain(host)} is already in My sites"
        val site = Site(host, prettyDomain(host), "https://$host/")
        sites.upsert(site)
        bg.launch {
            val r = attempt { scanner.scan(site, sampleName()) }.getOrNull() ?: return@launch
            sites.upsert(site.copy(kind = r.kind, searchTemplate = r.template, searchCheckedAt = System.currentTimeMillis(), inVideoSearch = r.kind == SiteKind.VIDEO && r.template != null))
        }
        return "Added ${prettyDomain(host)} — checking what kind of site it is"
    }

    // ---------------- Discovering new sites ----------------

    private val discovery = Discovery(siteSearch)

    /** Web-searches for a video and returns matches on sites not in my list (adding those sites if enabled). */
    suspend fun discover(q: VideoQuery): List<SiteProgress> {
        if (!prefs.discoverSites) return emptyList()
        val known = sites.list().map { it.domain }.toSet()
        val hits = attempt { discovery.discover(q, known) }.getOrDefault(emptyList())
        return hits.groupBy { it.domain }.map { (domain, hs) ->
            val site = Site(domain, "${prettyDomain(domain)} (new)", "https://$domain/", kind = SiteKind.VIDEO, discovered = true)
            if (prefs.autoAddDiscovered && sites.get(domain) == null) {
                sites.upsert(site.copy(title = prettyDomain(domain)))
                AppLog.i("Discover", "Added new site $domain")
                bg.launch {
                    val tpl = attempt { siteSearch.detectTemplate(site, sampleName()) }.getOrNull()
                    sites.get(domain)?.let { sites.upsert(it.copy(searchTemplate = tpl, searchCheckedAt = System.currentTimeMillis(), inVideoSearch = tpl != null)) }
                }
            }
            SiteProgress(site, SiteStatus.Found(hs.size), hs)
        }
    }

    /** One-time tidy-up of imported sites: fix "site.com)" style names, drop .onion and dot-less hosts, switch off non-video sites. */
    suspend fun tidySites() {
        if (prefs.sitesTidied) return
        var fixed = 0; var removed = 0; var off = 0
        val existing = sites.list()
        val have = existing.map { it.domain }.toMutableSet()
        for (s in existing) {
            if (s.domain.contains('/')) continue // archived copies
            val clean = cleanDomain(s.domain)
            when {
                clean == null -> { sites.delete(s); removed++ }
                clean != s.domain -> {
                    sites.delete(s); have -= s.domain
                    if (clean !in have) { sites.upsert(s.copy(domain = clean, url = s.url.replace(s.domain, clean))); have += clean }
                    fixed++
                }
                SiteSearch.isNonVideo(clean) && s.inVideoSearch -> { sites.upsert(s.copy(inVideoSearch = false)); off++ }
            }
        }
        prefs.sitesTidied = true
        AppLog.i("Sites", "Tidy-up: $fixed fixed, $removed removed, $off non-video sites left out of video search")
    }

    val categories = CategoryStore(context)
    val saved = SavedSearches(context)
    val taste = Taste(context)

    /** "For you": searches your favourite tags in turn and ranks results by how well they fit your taste. */
    suspend fun forYou(page: Int): ScenePage? {
        if (!hasKey) return null
        val tags = taste.topTags(4)
        if (tags.isEmpty()) return ScenePage(emptyList(), page, 0, 0)
        val tag = tags[(page - 1) % tags.size]
        val apiPage = (page - 1) / tags.size + 1
        val r = api.searchScenes(tag, apiPage)
        val kept = r.scenes.filterNot { Categories.isExcluded(*it.tags.toTypedArray()) }.sortedByDescending { taste.score(it) }
        kept.forEach { sceneCache[it.id] = it }
        AppLog.i("ForYou", "Page $page (tag ${tags.indexOf(tag) + 1}/${tags.size}): ${kept.size}")
        return r.copy(scenes = kept, page = page, lastPage = r.lastPage * tags.size)
    }

    /** Performers who look like [p]: same gender/ethnicity/hair where known, similar age and height. */
    suspend fun similar(p: Performer): List<Performer> {
        val w = mutableListOf("p.id <> ?", "(p.birthday IS NULL OR p.birthday <= date('now','-18 years'))", "COALESCE(p.thumb, p.image) IS NOT NULL")
        val a = mutableListOf<Any>(p.id)
        p.gender?.let { w += "p.gender = ?"; a += it }
        p.ethnicity?.let { w += "p.ethnicity = ?"; a += it }
        p.hair?.let { w += "p.hair = ?"; a += it }
        p.heightCm?.let { w += "p.heightCm BETWEEN ? AND ?"; a += it - 6; a += it + 6 }
        p.birthday?.take(4)?.toIntOrNull()?.let { y -> w += "CAST(substr(p.birthday,1,4) AS INTEGER) BETWEEN ? AND ?"; a += y - 4; a += y + 4 }
        val q = androidx.sqlite.db.SimpleSQLiteQuery("SELECT p.* FROM performers p WHERE ${w.joinToString(" AND ")} ORDER BY (p.sceneCount IS NULL), p.sceneCount DESC LIMIT 15", a.toTypedArray())
        return performers.query(q).first()
    }

    /**
     * Category browsing from the database (professional studio scenes, adult performers only).
     * Searches with the category's phrasing, then keeps scenes whose title or tags mention every word,
     * plus any extra categories / words the user stacked on top.
     */
    suspend fun browseCategory(main: Category?, extra: List<Category>, text: String, page: Int): ScenePage? {
        if (!hasKey) return null
        if (main?.code == Categories.FOR_YOU && text.isBlank()) return forYou(page)
        val need = listOfNotNull(main) + extra
        fun keep(r: ScenePage) = r.scenes.filter { s ->
            val hay = s.title + " " + s.tags.joinToString(" ") + " " + s.performers.joinToString(" ") { it.name }
            need.all { c -> c.words.isEmpty() || Categories.score(hay, c.words) >= (if (c.words.size >= 3) 66 else 100) } &&
                (text.isBlank() || main == null || SiteSearch.tokens(text).all { hay.lowercase().contains(it) }) &&
                (need.isEmpty() || !Categories.isExcluded(*s.tags.toTypedArray()))
        }
        // Nothing chosen = latest videos feed.
        val primary = text.ifBlank { main?.queries?.last().orEmpty() }
        var r = api.searchScenes(primary, page)
        var kept = keep(r)
        // "stepmom stepson" found nothing usable? try the plain wording ("mom son").
        if (kept.isEmpty() && text.isBlank() && main != null && main.queries.size > 1) {
            r = api.searchScenes(main.queries.first(), page)
            kept = keep(r)
        }
        kept.forEach { sceneCache[it.id] = it }
        AppLog.i("Categories", "Page $page: ${r.scenes.size} from server, ${kept.size} kept")
        return r.copy(scenes = kept)
    }

    /** Saves a performer picked from online suggestions. */
    suspend fun saveFromOnline(p: Performer) = upsertTpdb(listOf(p))

    /** Online name suggestions (ThePornDB) for people not in the library yet. */
    suspend fun onlineSuggestions(q: String): List<Performer> =
        if (!hasKey || q.trim().length < 3) emptyList()
        else api.performers(1, q.trim()).items.filter { p -> p.age()?.let { it >= 18 } != false }.take(6)

    /** Makes sure a performer credited in a scene is in the library (fetching from ThePornDB if needed). */
    suspend fun ensurePerformer(id: String): Performer? =
        performers.get(id) ?: if (hasKey) runCatching { api.performer(id) }.getOrNull()?.also { upsertTpdb(listOf(it)) } else null

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
        val uri = runCatching { Uri.parse(href.trim().trimEnd(')', ']', '.', ',', ';', '>', '"', '\'')) }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") return
        val domain = cleanDomain(uri.host) ?: return
        if (domain in into) return
        val title = text.trim().takeIf { it.isNotEmpty() && it.length < 60 } ?: prettyDomain(domain)
        into[domain] = Site(domain = domain, title = title, url = "https://$domain/")
    }

    /**
     * A site from a typed/pasted address. The exact address is kept, so it opens that page.
     * Wayback Machine links become their own entry ("example.com (archived)").
     */
    fun addSite(rawUrl: String, title: String?): Site? {
        val url = rawUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return null
        val archived = Regex("^https?://web\\.archive\\.org/web/[^/]+/(?:https?://)?([^/]+)", RegexOption.IGNORE_CASE).find(url)
        if (archived != null) {
            val orig = archived.groupValues[1].lowercase().removePrefix("www.")
            return Site(
                domain = "web.archive.org/$orig",
                title = title?.takeIf { it.isNotBlank() } ?: "${prettyDomain(orig)} (archived)",
                url = url,
                inVideoSearch = false, // archived copies have no working search
            )
        }
        return Site(host, title?.takeIf { it.isNotBlank() } ?: prettyDomain(host), url)
    }

    fun siteSearchUrl(site: Site, name: String): String {
        val tpl = site.searchTemplate
        return if (!tpl.isNullOrBlank() && tpl.contains("{q")) {
            SiteSearch.fill(tpl, name)
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
        /** "WWW.Site.com)" → "site.com"; null for things that aren't usable web domains. */
        fun cleanDomain(host: String?): String? {
            val h = host?.lowercase()?.trim()?.trimEnd(')', ']', '.', ',', ';', '>', '"', '\'')
                ?.removePrefix("www.")?.removePrefix("m.") ?: return null
            if (!h.contains('.') || h.endsWith(".onion") || !Regex("^[a-z0-9.-]+$").matches(h)) return null
            return h
        }
        const val FREE_BATCH = 400
        fun prettyDomain(d: String) = d.substringBeforeLast('.').replaceFirstChar(Char::uppercase)
    }
}
