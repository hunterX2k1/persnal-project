package app.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One video found on one of my sites. [score] is 0–100 similarity to what was searched for. */
data class Hit(val domain: String, val siteTitle: String, val title: String, val url: String, val thumb: String?, val duration: String?, val score: Int)

sealed interface SiteStatus {
    data object Searching : SiteStatus
    data class Found(val count: Int) : SiteStatus
    data object Nothing : SiteStatus
    /** The site refused automated requests (bot check / captcha / 403). */
    data class Blocked(val code: Int) : SiteStatus
    data object NoSearchLink : SiteStatus
    data class Failed(val reason: String) : SiteStatus
}

data class SiteProgress(val site: Site, val status: SiteStatus, val hits: List<Hit> = emptyList())

/** What we're looking for: a scene title plus the people in it. */
data class VideoQuery(val title: String, val names: List<String>)

/**
 * Meta-search over the user's own sites, like a tube aggregator: runs each site's search,
 * pulls video links (title, thumbnail, duration) out of the result page, and ranks them by
 * how closely the title and cast match.
 */
class SiteSearch(private val onTemplateFound: suspend (Site, String?) -> Unit = { _, _ -> }) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun searchAll(sites: List<Site>, q: VideoQuery, parallel: Int = 6): Flow<SiteProgress> = channelFlow {
        val gate = Semaphore(parallel)
        for (site in sites) launch(Dispatchers.IO) {
            send(SiteProgress(site, SiteStatus.Searching))
            val result = gate.withPermit { runCatching { searchSite(site, q) }.getOrElse { SiteProgress(site, SiteStatus.Failed(it.message ?: "error")) } }
            AppLog.i("SiteSearch", "${site.domain}: ${result.status::class.simpleName} ${result.hits.size}")
            send(result)
        }
        awaitClose { }
    }

    private suspend fun searchSite(site: Site, q: VideoQuery): SiteProgress {
        var template = site.searchTemplate
        if (template.isNullOrBlank()) {
            val recentlyChecked = System.currentTimeMillis() - site.searchCheckedAt < WEEK
            if (recentlyChecked) return SiteProgress(site, SiteStatus.NoSearchLink)
            template = detectTemplate(site, q.names.firstOrNull() ?: q.title)
            onTemplateFound(site, template)
            if (template == null) return SiteProgress(site, SiteStatus.NoSearchLink)
        }
        // 1) search by title, 2) if that finds little, search by the main performer's name.
        val first = fetchHits(site, template, q.title, q)
        if (first.first != null) return SiteProgress(site, first.first!!)
        var hits = first.second
        if (hits.size < 3 && q.names.isNotEmpty()) {
            val second = fetchHits(site, template, q.names.first(), q)
            hits = (hits + second.second).distinctBy { it.url }
        }
        hits = hits.sortedByDescending { it.score }.take(PER_SITE)
        return SiteProgress(site, if (hits.isEmpty()) SiteStatus.Nothing else SiteStatus.Found(hits.size), hits)
    }

    /** Returns (blocking status or null, hits). */
    private suspend fun fetchHits(site: Site, template: String, term: String, q: VideoQuery): Pair<SiteStatus?, List<Hit>> {
        val url = fill(template, term)
        val page = fetch(url)
        if (page.blocked) return SiteStatus.Blocked(page.code) to emptyList()
        if (page.html == null) return SiteStatus.Failed("HTTP ${page.code}") to emptyList()
        return null to extract(page.html, url, site, q)
    }

    /** Tries common search URL patterns and keeps the first that returns matching videos. */
    suspend fun detectTemplate(site: Site, sample: String): String? {
        for (pattern in PATTERNS) {
            val tpl = "https://${site.domain}$pattern"
            val page = fetch(fill(tpl, sample))
            if (page.blocked) return null
            val html = page.html ?: continue
            val hits = extract(html, fill(tpl, sample), site, VideoQuery(sample, listOf(sample)))
            if (hits.count { it.score >= 40 } >= 2) {
                AppLog.i("SiteSearch", "Detected search link for ${site.domain}: $pattern")
                return tpl
            }
        }
        AppLog.i("SiteSearch", "No search link found for ${site.domain}")
        return null
    }

    private class Page(val code: Int, val html: String?, val blocked: Boolean)

    private suspend fun fetch(url: String): Page = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                val body = r.body?.source()?.use { src -> src.request(MAX_BYTES); src.buffer.readUtf8(minOf(src.buffer.size, MAX_BYTES)) }
                val bot = body != null && BOT_MARKERS.any { body.contains(it, ignoreCase = true) } && body.length < 60_000
                when {
                    r.code == 403 || r.code == 503 || r.code == 429 || bot -> Page(r.code, null, true)
                    !r.isSuccessful -> Page(r.code, null, false)
                    else -> Page(r.code, body, false)
                }
            }
        }.getOrElse { Page(0, null, false) }
    }

    companion object {
        const val PER_SITE = 6
        const val WEEK = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_BYTES = 3L * 1024 * 1024
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private val BOT_MARKERS = listOf("cf-chl", "Just a moment...", "captcha", "Attention Required", "ddos-guard", "challenge-platform")

        /** Common search link patterns. {q} = URL-encoded term, {q-} = words joined with hyphens. */
        val PATTERNS = listOf(
            "/search?q={q}", "/search/{q-}/", "/search/{q-}", "/?s={q}", "/search?search={q}",
            "/search?query={q}", "/videos/search?q={q}", "/search/videos?q={q}", "/search?k={q}", "/s/{q-}/",
        )

        fun fill(template: String, term: String): String {
            val enc = URLEncoder.encode(term.trim(), "UTF-8")
            val hy = term.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
            return template.replace("{q-}", hy).replace("{q}", enc)
        }

        private val STOP = setOf("the", "and", "with", "her", "his", "for", "from", "she", "you", "your", "this", "that", "are", "was", "gets", "get", "in", "on", "of", "to", "a", "an", "hd", "4k", "1080p", "720p", "video", "videos", "porn", "xxx", "full", "free", "new")

        fun tokens(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP }.toSet()

        /** 0–100: share of the wanted title's words present, plus a bonus when a cast member's name appears. */
        fun score(candidate: String, q: VideoQuery): Int {
            val want = tokens(q.title)
            val have = tokens(candidate)
            val titlePart = if (want.isEmpty()) 0.0 else want.count { it in have }.toDouble() / want.size
            val lc = candidate.lowercase()
            val nameHit = q.names.any { n -> n.length >= 4 && lc.contains(n.lowercase()) } ||
                q.names.any { n -> tokens(n).let { t -> t.size >= 2 && t.all { it in have } } }
            return ((titlePart * 75) + (if (nameHit) 35 else 0)).toInt().coerceAtMost(100)
        }

        /** Pulls video-like links out of a search result page. Works on most tube-style layouts. */
        fun extract(html: String, pageUrl: String, site: Site, q: VideoQuery): List<Hit> {
            val doc = Jsoup.parse(html, pageUrl)
            val pagePath = runCatching { URI(pageUrl).path }.getOrNull()
            val out = LinkedHashMap<String, Hit>()
            for (a in doc.select("a[href]:has(img)")) {
                val href = a.absUrl("href").substringBefore('#')
                if (href.isEmpty() || href in out) continue
                val host = runCatching { URI(href).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: continue
                if (!(host == site.domain || host.endsWith("." + site.domain) || site.domain.endsWith(".$host"))) continue
                val path = runCatching { URI(href).path }.getOrNull().orEmpty()
                if (path.length < 2 || path == pagePath || NON_VIDEO.containsMatchIn(path)) continue
                val img = a.selectFirst("img")
                val title = listOf(a.attr("title"), img?.attr("alt").orEmpty(), a.text(), a.parent()?.selectFirst("[title]")?.attr("title").orEmpty())
                    .map { it.trim() }.filter { it.length in 6..220 }.maxByOrNull { it.length } ?: continue
                val s = score(title, q)
                if (s < 30) continue
                out[href] = Hit(site.domain, site.title, title, href, thumbOf(img), durationNear(a), s)
            }
            return out.values.sortedByDescending { it.score }
        }

        private val NON_VIDEO = Regex("/(search|tags?|categor(y|ies)|channels?|models?|pornstars?|users?|members?|login|signup|register|account|playlists?|page/\\d+)(/|$)", RegexOption.IGNORE_CASE)

        private fun thumbOf(img: Element?): String? {
            img ?: return null
            for (attr in listOf("data-src", "data-original", "data-thumb", "data-lazy-src", "data-mediumthumb", "data-thumb_url", "src")) {
                val v = img.absUrl(attr).ifEmpty { img.attr(attr) }
                if (v.startsWith("http") && !v.endsWith(".gif")) return v
            }
            return null
        }

        private val DURATION = Regex("\\b(\\d{1,2}:)?\\d{1,2}:\\d{2}\\b")
        private fun durationNear(a: Element): String? =
            DURATION.find(a.text())?.value ?: a.parent()?.let { DURATION.find(it.text())?.value }
    }
}
