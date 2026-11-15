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

    /** Result links from DuckDuckGo, falling back to Bing if DuckDuckGo shows a bot check. */
    suspend fun webResults(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val ddg = search.fetch("https://html.duckduckgo.com/html/?q=$q&kp=-2").html?.let { html ->
            Jsoup.parse(html).select("a.result__a[href]").map { a ->
                val h = a.attr("href")
                Regex("[?&]uddg=([^&]+)").find(h)?.let { URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: h
            }
        }.orEmpty()
        if (ddg.isNotEmpty()) return ddg
        val bing = search.fetch("https://www.bing.com/search?q=$q&adlt=off").html?.let { html ->
            Jsoup.parse(html).select("li.b_algo").mapNotNull { li ->
                val h = li.selectFirst("h2 a[href], a[href^=http]")?.attr("href") ?: return@mapNotNull null
                Regex("[?&]u=a1([^&]+)").find(h)?.let { m ->
                    val t = m.groupValues[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                    attempt { String(java.util.Base64.getUrlDecoder().decode(t)) }.getOrNull()
                } ?: h
            }
        }.orEmpty()
        AppLog.i("Discover", "Web search: ${ddg.size} DuckDuckGo, ${bing.size} Bing results")
        return bing
    }

    /** New sites (domain → matching video) not in [known]. */
    suspend fun discover(q: VideoQuery, known: Set<String>, maxSites: Int = 8): List<Hit> = coroutineScope {
        val query = (if (q.title != q.names.firstOrNull()) "\"${q.title}\" " else "") + q.names.take(1).joinToString(" ") + " video"
        val candidates = webResults(query).filter { it.startsWith("http") }.mapNotNull { url ->
            val d = Repo.cleanDomain(attempt { URI(url).host }.getOrNull()) ?: return@mapNotNull null
            if (d in known || SKIP.any { d == it || d.endsWith(".$it") || d.startsWith(it) } || SiteSearch.isNonVideo(d) || AdBlock.isUnsafe(d) || AdBlock.isAd(d)) null
            else d to url
        }.distinctBy { it.first }.take(maxSites)
        val gate = Semaphore(4)
        candidates.map { (domain, url) ->
            async { gate.withPermit { attempt { checkPage(domain, url, q) }.getOrNull() } }
        }.awaitAll().filterNotNull().also { AppLog.i("Discover", "${candidates.size} new sites checked, ${it.size} had the video") }
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
