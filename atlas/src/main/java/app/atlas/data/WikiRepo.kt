package app.atlas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

const val USER_AGENT = "AtlasReader/1.0 (personal Android app)"

/**
 * Downloads FMHY's Markdown pages from GitHub, keeps them on the phone for offline use,
 * and refreshes them once a day.
 */
class WikiRepo(private val dir: File) {
    private val base = "https://raw.githubusercontent.com/fmhy/edit/main/docs/"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val memory = HashMap<String, WikiPage>()
    private val lock = Mutex()
    private var index: List<Entry>? = null

    /** Entry counts per page (for the home tiles), filled as pages load. */
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val refreshing = MutableStateFlow(false)

    init { dir.mkdirs() }

    private fun file(slug: String) = File(dir, "$slug.md")

    fun lastUpdated(): Long = PAGES.mapNotNull { file(it.slug).takeIf(File::exists)?.lastModified() }.minOrNull() ?: 0L

    suspend fun page(slug: String, force: Boolean = false): WikiPage = withContext(Dispatchers.IO) {
        lock.withLock { if (!force) memory[slug]?.let { return@withContext it } }
        val info = PAGE_BY_SLUG[slug] ?: throw IllegalArgumentException("Unknown page $slug")
        val f = file(slug)
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > DAY
        if (force || stale) {
            runCatching { download(slug) }
                .onSuccess { f.writeText(it) }
                .onFailure { AppLog.w("Wiki", "Download $slug failed: ${it.message}") }
        }
        if (!f.exists()) throw IllegalStateException("Couldn't download this page. Check your connection and try again.")
        val parsed = WikiParser.parse(info, f.readText())
        lock.withLock {
            memory[slug] = parsed
            index = null
            counts.value = counts.value + (slug to parsed.entries.size)
        }
        parsed
    }

    private fun download(slug: String): String {
        val started = System.currentTimeMillis()
        val req = Request.Builder().url("$base$slug.md").header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            AppLog.i("Wiki", "GET $slug.md → ${r.code} in ${System.currentTimeMillis() - started} ms")
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string().orEmpty().also { if (it.length < 50) throw IllegalStateException("Empty page") }
        }
    }

    /** Loads (and if needed downloads) every page. Used for search and "Refresh". */
    suspend fun loadAll(force: Boolean = false): List<WikiPage> = coroutineScope {
        if (force) refreshing.value = true
        try {
            val gate = Semaphore(4)
            PAGES.map { p -> async { gate.withPermit { runCatching { page(p.slug, force) }.getOrNull() } } }
                .awaitAll().filterNotNull()
        } finally {
            refreshing.value = false
        }
    }

    /** Every entry on every page, for global search. */
    suspend fun searchIndex(): List<Entry> {
        lock.withLock { index?.let { return it } }
        val all = loadAll().filter { it.info.slug != "unsafe" }.flatMap { it.entries }
        lock.withLock { index = all }
        AppLog.i("Wiki", "Search index: ${all.size} entries")
        return all
    }

    fun search(index: List<Entry>, query: String, starredOnly: Boolean): List<Entry> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val words = q.split(Regex("\\s+"))
        return index.asSequence()
            .filter { !starredOnly || it.rating > 0 }
            .mapNotNull { e ->
                val name = e.name.lowercase()
                val hay = name + " " + e.desc.lowercase() + " " + e.section.lowercase()
                if (!words.all { hay.contains(it) }) return@mapNotNull null
                val score = when {
                    name == q -> 100
                    name.startsWith(q) -> 60
                    name.contains(q) -> 40
                    else -> 10
                } + e.rating * 15
                e to score
            }
            .sortedByDescending { it.second }
            .take(300)
            .map { it.first }
            .toList()
    }

    companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
