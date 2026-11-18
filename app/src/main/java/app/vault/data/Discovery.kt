package app.vault.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Finds new sites while searching: runs a web search for the video, opens results on sites
 * not in my list, and keeps the ones whose page really is a video with a matching title.
 */
class Discovery(private val search: SiteSearch) {
    private val SKIP = listOf(
        "youtube.com", "youtu.be", "imdb.com", "wikipedia.org", "facebook.com", "instagram.com", "tiktok.com", "twitter.com",
        "x.com", "pinterest.com", "amazon.", "ebay.", "bing.com", "duckduckgo.com", "google.", "apple.com", "spotify.com",
        "theporndb.net", "iafd.com", "data18.com", "freeones.", "babepedia.com",
    )

    /** Engines tried for finding new sites, in order, until enough results are found. */
    private fun engines(custom: List<Pair<String, String>>): List<Pair<String, String>> = listOf(
        "DuckDuckGo" to "https://html.duckduckgo.com/html/?q={q}&kp=-2",
        "Bing" to "https://www.bing.com/search?q={q}&adlt=off",
        "Startpage" to "https://www.startpage.com/do/search?query={q}&qadf=none",
        "Yandex" to "https://yandex.com/search/?text={q}",
    ) + custom

    /** Result links from several search engines (bot checks / captchas are skipped automatically). */
    suspend fun webResults(query: String, custom: List<Pair<String, String>> = emptyList(), want: Int = 15): List<String> {
        val out = LinkedHashSet<String>()
        for ((name, tpl) in engines(custom)) {
            if (out.size >= want) break
            val url = SiteSearch.fill(tpl, query)
            val page = search.fetch(url)
            val html = page.html
            if (html == null || page.blocked || (page.finalUrl ?: "").contains("captcha", true)) {
                AppLog.i("Discover", "$name: no results (blocked or captcha)"); continue
            }
            val links = parse(name, html, url)
            AppLog.i("Discover", "$name: ${links.size} results")
            out += links
        }
        return out.toList()
    }

    private fun parse(engine: String, html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html, pageUrl)
        val specific = when (engine) {
            "DuckDuckGo" -> doc.select("a.result__a[href]").map { a ->
                val h = a.attr("href")
                Regex("[?&]uddg=([^&]+)").find(h)?.let { URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: h
            }
            "Bing" -> doc.select("li.b_algo").mapNotNull { li ->
                val h = li.selectFirst("h2 a[href], a[href^=http]")?.attr("href") ?: return@mapNotNull null
                Regex("[?&]u=a1([^&]+)").find(h)?.let { m ->
                    val t = m.groupValues[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                    attempt { String(java.util.Base64.getUrlDecoder().decode(t)) }.getOrNull()
                } ?: h
            }
            else -> emptyList()
        }
        if (specific.isNotEmpty()) return specific
        // Any other engine: outbound links that leave the engine's own site.
        val engineHost = Repo.cleanDomain(attempt { URI(pageUrl).host }.getOrNull()).orEmpty()
        return doc.select("a[href^=http]").map { it.attr("href") }.filter { h ->
            val d = Repo.cleanDomain(attempt { URI(h).host }.getOrNull()) ?: return@filter false
            !d.endsWith(engineHost) && !engineHost.endsWith(d) && !d.contains("yandex") && !d.contains("startpage") && !d.contains("microsoft")
        }.distinct()
    }

    /** New sites (domain → matching video) not in [known]. */
    suspend fun discover(q: VideoQuery, known: Set<String>, custom: List<Pair<String, String>> = emptyList(), maxSites: Int = 8): List<Hit> = coroutineScope {
        val query = (if (q.title != q.names.firstOrNull()) "\"${q.title}\" " else "") + q.names.take(1).joinToString(" ") + " video"
        val candidates = webResults(query, custom).filter { it.startsWith("http") }.mapNotNull { url ->
            val d = Repo.cleanDomain(attempt { URI(url).host }.getOrNull()) ?: return@mapNotNull null
            if (SKIP.any { d == it || d.endsWith(".$it") || d.startsWith(it) } || SiteSearch.isNonVideo(d) || AdBlock.isUnsafe(d) || AdBlock.isAd(d)) null
            else d to url
        }.distinctBy { it.first }.sortedByDescending { it.first in known }.take(maxSites)
        val gate = Semaphore(4)
        candidates.map { (domain, url) ->
            async { gate.withPermit { attempt { checkPage(domain, url, q) }.getOrNull() } }
        }.awaitAll().filterNotNull().also { AppLog.i("Discover", "${candidates.size} new sites checked, ${it.size} had the video") }
    }

    /** Searches one site through web engines ("site:domain words") when it has no usable search page. */
    suspend fun siteResults(domain: String, q: VideoQuery, custom: List<Pair<String, String>> = emptyList()): List<Hit> = coroutineScope {
        val term = (if (q.title != q.names.firstOrNull()) q.title else q.names.firstOrNull().orEmpty())
        val urls = webResults("site:$domain $term", custom, want = 6).filter { u ->
            Repo.cleanDomain(attempt { URI(u).host }.getOrNull())?.let { it == domain || it.endsWith(".$domain") } == true
        }.distinct().take(4)
        urls.map { u -> async { attempt { checkPage(domain, u, q) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /** A result counts if its page is a video page (player / og:video / VideoObject) with a matching title. */
    private suspend fun checkPage(domain: String, url: String, q: VideoQuery): Hit? {
        val t0 = System.currentTimeMillis()
        val p = search.fetch(url)
        val html = p.html ?: return null
        val doc = Jsoup.parse(html, url)
        val isVideo = doc.select("video, meta[property=og:video], meta[property=og:video:url], iframe[src*=embed], iframe[src*=player]").isNotEmpty() ||
            html.contains("\"VideoObject\"") || doc.selectFirst("meta[property=og:type][content*=video]") != null
        if (!isVideo) return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title()
        val score = SiteSearch.score(title, q)
        if (score < 60) return null
        val iso = Regex("\"duration\"\\s*:\\s*\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?\"").find(html)
        val duration = iso?.let { m ->
            val (h, mi, s) = m.destructured
            val total = (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
            if (total > 0) formatDuration(total) else null
        }
        return Hit(domain, Repo.prettyDomain(domain), title.take(200), url, doc.selectFirst("meta[property=og:image]")?.absUrl("content"), duration, score, System.currentTimeMillis() - t0)
    }
}
