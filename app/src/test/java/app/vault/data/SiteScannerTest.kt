package app.vault.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteScannerTest {
    private val scanner = SiteScanner(SiteSearch())

    @Test
    fun classifiesCommonSiteTypes() {
        val tube = """<html><head><meta property="og:type" content="video.other"></head><body>
            ${(1..20).joinToString("") { """<a href="/video/$it/some-title"><img src="t.jpg"><span>1$it:2$it</span> 1.2M views HD</a>""" }}
            </body></html>"""
        val manga = """<html><body>${(1..20).joinToString("") { """<a href="/g/$it/">Doujin $it - 24 pages</a> <a href="/chapter/$it">Chapter $it</a>""" }} manga manhwa</body></html>"""
        val forum = """<html><body class="xenforo">${(1..15).joinToString("") { """<a href="/threads/topic-$it.$it/">Thread $it</a> 12 replies, members joined""" }}</body></html>"""
        val stories = """<html><body>${(1..15).joinToString("") { """<a href="/stories/story-$it">A story</a> by author, chapter 3, 4,000 words""" }}</body></html>"""
        assertEquals(SiteKind.VIDEO, scanner.classify("sometube.com", tube))
        assertEquals(SiteKind.COMICS, scanner.classify("example-reader.net", manga))
        assertEquals(SiteKind.FORUM, scanner.classify("community.example", forum))
        assertEquals(SiteKind.STORIES, scanner.classify("words.example", stories))
        assertEquals(SiteKind.OTHER, scanner.classify("blank.example", "<html><body>Hello</body></html>"))
    }
}
