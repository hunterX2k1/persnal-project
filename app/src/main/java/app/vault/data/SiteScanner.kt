package app.vault.data

import org.jsoup.Jsoup

/** Site types, as shown in My sites. */
object SiteKind {
    const val VIDEO = "video"; const val IMAGES = "images"; const val COMICS = "comics"; const val STORIES = "stories"
    const val GAMES = "games"; const val FORUM = "forum"; const val OTHER = "other"; const val UNREACHABLE = "unreachable"
    val LABELS = linkedMapOf(
        VIDEO to "Video", IMAGES to "Images", COMICS to "Comics & manga", STORIES to "Stories",
        GAMES to "Games", FORUM to "Forums", OTHER to "Other", UNREACHABLE to "Unreachable",
    )
}

/**
 * Works out what a site is from its name and home page, and finds the search page of video sites.
 * Scores signals for each type (video players, durations, /video/ links, chapters, galleries, threads…)
 * and picks the strongest.
 */
class SiteScanner(private val search: SiteSearch) {
    data class Result(val kind: String, val template: String?)

    private val NAME_HINTS = listOf(
        SiteKind.COMICS to Regex("hentai(?!.*(tube|video|tv|stream|haven|mama|gasm))|manga|manhwa|doujin|comic|toon|webtoon|8muses|nhentai|hitomi"),
        SiteKind.STORIES to Regex("stor(y|ies)|literotica|fiction|novel|chyoa|erotica"),
        SiteKind.GAMES to Regex("games?\\b|game\\.|repack|f95|itch\\.io|loverslab|gamcore|steam"),
        SiteKind.FORUM to Regex("forum|bbs|board|vipergirls|planetsuzy|akiba"),
        SiteKind.IMAGES to Regex("booru|pics|gif|imagefap|img|photo|wallpaper|pimpandhost|elitebabes"),
    )

    suspend fun scan(site: Site, sampleName: String): Result {
        val page = search.fetch("https://${site.domain}/")
        if (page.blocked) {
            // Bot-protected: guess from the name; video sites can still be searched by hand.
            return Result(nameHint(site.domain) ?: SiteKind.OTHER, null)
        }
        val html = page.html ?: return Result(SiteKind.UNREACHABLE, null)
        val kind = classify(site.domain, html)
        val tpl = if (kind == SiteKind.VIDEO) attempt { search.detectTemplate(site, sampleName) }.getOrNull() else null
        AppLog.i("Scan", "${site.domain}: $kind${if (tpl != null) ", searchable" else ""}")
        return Result(kind, tpl)
    }

    private fun nameHint(domain: String) = NAME_HINTS.firstOrNull { it.second.containsMatchIn(domain) }?.first

    fun classify(domain: String, html: String): String {
        val doc = Jsoup.parse(html)
        val text = doc.text().lowercase().take(60_000)
        val hrefs = doc.select("a[href]").map { it.attr("href").lowercase() }
        fun links(r: Regex) = hrefs.count { r.containsMatchIn(it) }
        fun words(r: Regex) = r.findAll(text).count()

        val score = mutableMapOf<String, Int>()
        score[SiteKind.VIDEO] = links(Regex("/(video|videos|watch|embed|v)/|watch\\?|/view_video")) * 2 +
            Regex("\\b\\d{1,2}:\\d{2}\\b").findAll(text).count().coerceAtMost(60) +
            doc.select("video, source[type*=video], meta[property=og:video], meta[property=og:type][content*=video]").size * 15 +
            words(Regex("\\b(views|hd|1080p|720p|4k|minutes?|pornstars?)\\b")).coerceAtMost(40)
        score[SiteKind.COMICS] = links(Regex("/(manga|comic|comics|doujin|doujinshi|chapter|g|read)/")) * 2 +
            words(Regex("\\b(chapter|manga|manhwa|doujin|doujinshi|comic|pages)\\b")).coerceAtMost(60)
        score[SiteKind.IMAGES] = links(Regex("/(gallery|galleries|album|albums|pics|photos|post|posts|image)/|[?&]tags=")) * 2 +
            words(Regex("\\b(photos?|pics|images?|gallery|galleries|albums?)\\b")).coerceAtMost(40)
        score[SiteKind.STORIES] = links(Regex("/(story|stories|s|fiction)/")) * 2 + words(Regex("\\b(story|stories|author|chapter|words)\\b")).coerceAtMost(40)
        score[SiteKind.GAMES] = links(Regex("/(game|games|download|repack)/")) * 2 + words(Regex("\\b(game|games|download|version|repack|android|windows)\\b")).coerceAtMost(40)
        score[SiteKind.FORUM] = links(Regex("/(threads?|forums?|topic|showthread|viewtopic|members)/|showthread|viewtopic")) * 2 +
            words(Regex("\\b(replies|threads?|posts?|members|forum|joined)\\b")).coerceAtMost(40) +
            (if (html.contains("xenforo", true) || html.contains("phpbb", true) || html.contains("vbulletin", true)) 40 else 0)

        nameHint(domain)?.let { score[it] = (score[it] ?: 0) + 25 }
        val best = score.maxByOrNull { it.value }!!
        return if (best.value < 12) SiteKind.OTHER else best.key
    }
}
