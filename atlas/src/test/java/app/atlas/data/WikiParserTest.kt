package app.atlas.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WikiParserTest {
    @Test
    fun parsesEntryLine() {
        val e = WikiParser.parseEntry(
            "🌟 **[7Movies](https://7movies.ac/)**, [2](https://7movies.ws/), [3](https://7shows.st/) - 4K / Dual Audio / [Discord](https://discord.gg/x)",
            "video", "Streaming", "k"
        )!!
        assertEquals("7Movies", e.name)
        assertEquals("https://7movies.ac/", e.url)
        assertEquals(listOf("2", "3"), e.alts.map { it.text })
        assertEquals(2, e.rating)
        assertTrue(e.desc.startsWith("4K / Dual Audio"))
        val note = WikiParser.parseEntry("**Note** - Sites that check - and play", "video", "", "n")!!
        assertTrue(note.note)
        val plain = WikiParser.parseEntry("OceanOfGames - Caught with malware", "unsafe", "", "u")!!
        assertEquals("OceanOfGames", plain.name); assertNull(plain.url)
    }

    @Test
    fun resolvesInternalLinks() {
        assertEquals("privacy" to "wiki_.25BA_adblocking", InternalLinks.resolve("https://www.reddit.com/r/FREEMEDIAHECKYEAH/wiki/adblock-vpn-privacy#wiki_.25BA_adblocking"))
        assertEquals("mobile" to null, InternalLinks.resolve("https://www.reddit.com/r/FREEMEDIAHECKYEAH/wiki/android"))
        assertEquals("video" to "p-stream-forks", InternalLinks.resolve("https://fmhy.net/video#p-stream-forks"))
        assertNull(InternalLinks.resolve("https://github.com/fmhy/FMHY/wiki/Stream-Site-Grading"))
    }

    /** Live: downloads every page and checks parsing + anchors on real data. */
    @Test
    fun parsesAllLivePages() = runBlocking {
        val repo = WikiRepo(Files.createTempDirectory("wiki").toFile())
        val pages = repo.loadAll()
        println("pages ${pages.size}/${PAGES.size}")
        assertEquals(PAGES.size, pages.size)
        pages.forEach { p -> println("${p.info.slug}: ${p.entries.size} entries, ${p.headings.size} headings, ${p.entries.count { it.url == null }} without link") }
        val total = pages.sumOf { it.entries.size }
        println("total $total")
        assertTrue(total > 10_000)
        val video = pages.first { it.info.slug == "video" }
        println(video.entries.take(3))
        assertNotNull(InternalLinks.findAnchor(video, "wiki_.25B7_p-stream_forks"))
        // How many internal anchors across the wiki resolve to a real heading?
        var ok = 0; var all = 0
        val bySlug = pages.associateBy { it.info.slug }
        pages.flatMap { it.entries }.flatMap { e -> WikiParser.LINK.findAll(e.desc).map { it.groupValues[2] } + listOfNotNull(e.url) }
            .mapNotNull { InternalLinks.resolve(it) }.filter { it.second != null && it.first.isNotEmpty() }
            .forEach { (slug, a) -> all++; if (bySlug[slug]?.let { InternalLinks.findAnchor(it, a) } != null) ok++ }
        println("internal anchors resolved $ok / $all")
        val idx = repo.searchIndex()
        println("search 'stremio': " + repo.search(idx, "stremio", false).take(5).map { "${it.name} (${it.page})" })
    }
}
