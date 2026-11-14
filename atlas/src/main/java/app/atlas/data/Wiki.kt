package app.atlas.data

/** One FMHY wiki page (a Markdown file in github.com/fmhy/edit/docs). */
data class PageInfo(val slug: String, val title: String, val emoji: String, val group: String)

val PAGES = listOf(
    PageInfo("privacy", "Adblocking / Privacy", "🛡️", "Wiki"),
    PageInfo("ai", "Artificial Intelligence", "🤖", "Wiki"),
    PageInfo("video", "Movies / TV / Anime", "📺", "Wiki"),
    PageInfo("audio", "Music / Podcasts / Radio", "🎵", "Wiki"),
    PageInfo("gaming", "Gaming / Emulation", "🎮", "Wiki"),
    PageInfo("reading", "Books / Comics / Manga", "📗", "Wiki"),
    PageInfo("downloading", "Downloading", "💾", "Wiki"),
    PageInfo("torrenting", "Torrenting", "🌀", "Wiki"),
    PageInfo("educational", "Educational", "🧠", "Wiki"),
    PageInfo("mobile", "Android / iOS", "📱", "Wiki"),
    PageInfo("linux-macos", "Linux / macOS", "🐧", "Wiki"),
    PageInfo("non-english", "Non-English", "🌏", "Wiki"),
    PageInfo("misc", "Miscellaneous", "📁", "Wiki"),
    PageInfo("system-tools", "System Tools", "💻", "Tools"),
    PageInfo("file-tools", "File Tools", "🗃️", "Tools"),
    PageInfo("internet-tools", "Internet Tools", "📎", "Tools"),
    PageInfo("social-media-tools", "Social Media Tools", "💬", "Tools"),
    PageInfo("text-tools", "Text Tools", "📝", "Tools"),
    PageInfo("gaming-tools", "Gaming Tools", "👾", "Tools"),
    PageInfo("image-tools", "Image Tools", "📷", "Tools"),
    PageInfo("video-tools", "Video Tools", "📼", "Tools"),
    PageInfo("developer-tools", "Developer Tools", "👨‍💻", "Tools"),
    PageInfo("beginners-guide", "Beginners Guide", "📚", "More"),
    PageInfo("storage", "Storage (extra lists)", "🗄️", "More"),
    PageInfo("unsafe", "Unsafe Sites", "⚠️", "More"),
)
val PAGE_BY_SLUG = PAGES.associateBy { it.slug }

data class Link(val text: String, val url: String)

sealed interface Item { val key: String }

data class Heading(val level: Int, val title: String, val link: String?, override val key: String) : Item {
    val norm = title.normKey()
}

/** Free text (guides, quotes, "!!!note" boxes). [md] keeps inline links. */
data class Para(val md: String, val callout: Boolean, override val key: String) : Item

data class Entry(
    val name: String,
    val url: String?,
    val alts: List<Link>,
    /** Description, still with inline Markdown links. */
    val desc: String,
    /** 2 = 🌟 top pick, 1 = ⭐ recommended, 0 = listed. */
    val rating: Int,
    val note: Boolean,
    val page: String,
    val section: String,
    override val key: String,
) : Item

data class WikiPage(val info: PageInfo, val items: List<Item>) {
    val entries get() = items.filterIsInstance<Entry>().filter { !it.note }
    val headings get() = items.filterIsInstance<Heading>()
}

fun String.normKey() = lowercase().filter { it.isLetterOrDigit() }

object WikiParser {
    val LINK = Regex("\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")

    fun parse(info: PageInfo, md: String): WikiPage {
        val items = ArrayList<Item>()
        var h1 = ""
        var h2 = ""
        var n = 0
        for (raw in md.lineSequence()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t.isEmpty() || t == "***" || t == "---" || t.contains("Back to Wiki Index") || t.startsWith("<") || t.startsWith(":::")) continue
            val key = "${info.slug}:${n++}"
            when {
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length
                    val text = t.drop(level).trim().trimStart('►', '▷', ' ').trim()
                    val m = LINK.find(text)
                    val title = (m?.groupValues?.get(1) ?: text).replace("*", "").trim()
                    if (title.isEmpty()) continue
                    items += Heading(level, title, m?.groupValues?.get(2), key)
                    if (level <= 1) { h1 = title; h2 = "" } else if (level == 2) h2 = title else if (h2.isEmpty()) h2 = title
                }
                t.startsWith("* ") || t.startsWith("- ") -> {
                    parseEntry(t.drop(2).trim(), info.slug, listOf(h1, h2).filter { it.isNotEmpty() }.joinToString(" › "), key)
                        ?.let { items += it }
                }
                t.startsWith("!!!") -> items += Para(t.dropWhile { it == '!' }.substringAfter(' ', "").trim(), true, key)
                t.startsWith(">") -> items += Para(t.trimStart('>', ' '), true, key)
                else -> items += Para(t, false, key)
            }
        }
        return WikiPage(info, items)
    }

    fun parseEntry(s0: String, page: String, section: String, key: String): Entry? {
        var s = s0
        val rating = when {
            s.startsWith("🌟") -> 2
            s.startsWith("⭐") -> 1
            else -> 0
        }
        if (rating > 0) s = s.substring(s.offsetByCodePoints(0, 1)).trimStart('️').trim()
        val split = descSplit(s)
        val head = if (split >= 0) s.substring(0, split) else s
        val desc = if (split >= 0) s.substring(split + 3).trim() else ""
        val links = LINK.findAll(head).map { Link(clean(it.groupValues[1]), it.groupValues[2]) }.toList()
        val name = links.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: clean(head)
        if (name.isBlank()) return null
        val isNote = clean(head).equals("Note", ignoreCase = true)
        return Entry(
            name = name,
            url = links.firstOrNull()?.url,
            alts = links.drop(1),
            desc = desc,
            rating = rating,
            note = isNote,
            page = page,
            section = section,
            key = key,
        )
    }

    /** Index of the " - " that separates name/links from the description, ignoring ones inside [..] or (..). */
    private fun descSplit(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '[', '(' -> depth++
                ']', ')' -> if (depth > 0) depth--
                ' ' -> if (depth == 0 && s.startsWith(" - ", i)) return i
            }
        }
        return -1
    }

    fun clean(s: String) = s.replace("**", "").replace("`", "").replace(Regex("^[🌐↪️🔗\\s]+"), "").trim()
}

/** Turns FMHY internal links (Reddit wiki, fmhy.net, relative) into (page slug, anchor). */
object InternalLinks {
    private val REDDIT = mapOf(
        "ai" to "ai", "adblock-vpn-privacy" to "privacy", "android" to "mobile", "games" to "gaming",
        "reading" to "reading", "download" to "downloading", "torrent" to "torrenting", "edu" to "educational",
        "system-tools" to "system-tools", "file-tools" to "file-tools", "internet-tools" to "internet-tools",
        "social-media" to "social-media-tools", "text-tools" to "text-tools", "video-tools" to "video-tools",
        "audio-tools" to "audio", "game-tools" to "gaming-tools", "video" to "video", "audio" to "audio",
        "linux" to "linux-macos", "non-eng" to "non-english", "misc" to "misc", "storage" to "storage",
        "dev-tools" to "developer-tools", "image-tools" to "image-tools", "index" to "", "tools-index" to "",
        "beginners-guide" to "beginners-guide", "unsafe" to "unsafe",
    )
    private val FMHY_HOSTS = setOf("fmhy.net", "www.fmhy.net", "fmhy.pages.dev", "fmhy.xyz", "fmhy.vercel.app")

    /** Returns slug ("" = home) and optional anchor, or null if the link is external. */
    fun resolve(url: String): Pair<String, String?>? {
        val anchor = url.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val noAnchor = url.substringBefore('#')
        Regex("reddit\\.com/r/FREEMEDIAHECKYEAH/wiki/([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE).find(noAnchor)?.let {
            return REDDIT[it.groupValues[1].lowercase()]?.let { s -> s to anchor }
        }
        val path = when {
            noAnchor.startsWith("/") -> noAnchor
            noAnchor.startsWith("http") -> {
                val host = noAnchor.substringAfter("://").substringBefore('/').lowercase()
                if (host !in FMHY_HOSTS) return null
                "/" + noAnchor.substringAfter("://").substringAfter('/', "")
            }
            noAnchor.endsWith(".md") || noAnchor.isEmpty() -> "/$noAnchor"
            else -> return null
        }
        val slug = path.trim('/').removeSuffix(".md").removeSuffix(".html").substringBefore('/').lowercase()
        if (slug.isEmpty()) return "" to anchor
        return if (slug in PAGE_BY_SLUG) slug to anchor else null
    }

    /** Index of the heading an anchor points at, e.g. "wiki_.25B7_p-stream_forks" → "P-Stream Forks". */
    fun findAnchor(page: WikiPage, anchor: String?): Int? {
        anchor ?: return null
        val key = anchor.removePrefix("wiki_")
            .replace(Regex("\\.[0-9A-Fa-f]{2}[0-9A-Fa-f]{2}"), "")
            .replace(Regex("%[0-9A-Fa-f]{2}"), "")
            .normKey()
        if (key.isEmpty()) return null
        val idx = page.items.indexOfFirst { it is Heading && it.norm == key }
        if (idx >= 0) return idx
        return page.items.indexOfFirst { it is Heading && (it.norm.startsWith(key) || key.startsWith(it.norm) && it.norm.length > 3) }
            .takeIf { it >= 0 }
    }
}
