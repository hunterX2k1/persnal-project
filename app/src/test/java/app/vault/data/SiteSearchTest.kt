package app.vault.data

import org.junit.Assert.*
import org.junit.Test

class SiteSearchTest {
    private val site = Site(domain = "example-tube.com", title = "Example", url = "https://example-tube.com/")
    private val q = VideoQuery("Summer Road Trip Adventure", listOf("Jane Example", "John Sample"))

    private val html = """
        <html><body>
          <header><a href="/"><img src="/logo.png" alt="Example Tube home"></a></header>
          <a href="/search?q=x&page=2"><img src="/next.png" alt="Summer Road Trip next page"></a>
          <div class="thumb">
            <a href="/video/123/summer-road-trip-adventure" title="Summer Road Trip Adventure - Jane Example">
              <img data-src="https://cdn.example-tube.com/t/123.jpg" src="data:image/gif;base64,AAAA" alt="">
              <span class="dur">24:13</span>
            </a>
          </div>
          <div class="thumb">
            <a href="https://www.example-tube.com/watch/456"><img src="https://cdn.example-tube.com/t/456.jpg" alt="Jane Example beach day"></a>
          </div>
          <div class="thumb">
            <a href="/watch/789"><img src="https://cdn.example-tube.com/t/789.jpg" alt="Completely unrelated cooking show"></a>
          </div>
          <a href="/tags/road-trip"><img src="/tag.png" alt="Summer Road Trip tag page"></a>
          <a href="https://other-site.com/video/1"><img src="x.jpg" alt="Summer Road Trip Adventure mirror"></a>
        </body></html>
    """.trimIndent()

    @Test
    fun extractsAndRanksVideos() {
        val hits = SiteSearch.extract(html, "https://example-tube.com/search?q=summer", site, q)
        hits.forEach { println(it) }
        assertEquals(2, hits.size)
        val top = hits.first()
        assertEquals("https://example-tube.com/video/123/summer-road-trip-adventure", top.url)
        assertEquals("https://cdn.example-tube.com/t/123.jpg", top.thumb)
        assertEquals("24:13", top.duration)
        assertEquals(100, top.score)
        assertEquals("https://www.example-tube.com/watch/456", hits[1].url) // name-only match, lower score
        assertTrue(hits[1].score < top.score)
    }

    @Test
    fun fillsTemplates() {
        assertEquals("https://a.com/search?q=Jane+Example", SiteSearch.fill("https://a.com/search?q={q}", "Jane Example"))
        assertEquals("https://a.com/search/jane-example/", SiteSearch.fill("https://a.com/search/{q-}/", "Jane Example!"))
    }
}
