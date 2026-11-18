package app.vault.data

import org.junit.Assert.*
import org.junit.Test

class HitGroupsTest {
    private fun h(site: String, title: String, dur: String?, ms: Long, score: Int = 90) = Hit(site, site, title, "https://$site/v/${title.hashCode()}", "t", dur, score, ms)

    @Test
    fun fullVersionAndFastestSiteWin() {
        val hits = listOf(
            h("a.com", "Jane Doe Summer Romance Trailer", "2:10", 300),
            h("b.com", "Jane Doe - Summer Romance (Full Video)", "34:12", 2400),
            h("c.com", "Summer Romance with Jane Doe", "34:10", 900),
            h("d.com", "Jane Doe office meeting", "28:00", 500),
        )
        val groups = HitGroups.group(hits)
        assertEquals(2, groups.size)
        val romance = groups.first { it.all.size == 3 }
        assertEquals("b.com", romance.best.domain) // marked "Full" beats an equally long untitled copy
        assertEquals("a.com", romance.others.last().domain) // trailer last
        assertEquals(false, HitGroups.isFull(hits[0]))
        assertEquals(true, HitGroups.isFull(hits[2]))
        assertEquals(2052, HitGroups.seconds("34:12"))
    }
}

class SceneJsonTest {
    @Test
    fun savedVideoRoundTrip() {
        val s = Scene(
            id = "abc", title = "A Title", date = "2024-01-02", image = "https://x/i.jpg", trailer = "https://x/t.mp4",
            url = "https://x/v", siteName = "Studio", siteUrl = "https://x", durationSec = 1800, description = "d",
            performers = listOf(ScenePerformer("p1", "Jane"), ScenePerformer(null, "John")), tags = listOf("Romantic", "MILF"),
            thumb = "https://x/s.jpg", imageFallbacks = listOf("https://x/m.jpg"),
        )
        assertEquals(s, sceneFromJson(sceneToJson(s)))
    }
}
