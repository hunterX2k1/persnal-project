package app.atlas.data

import org.junit.Assert.*
import org.junit.Test

class EpisodeParserTest {
    @Test
    fun parsesCommonNames() {
        assertEquals(EpisodeParser.Episode("Show Name", 1, 3), EpisodeParser.parse("Show.Name.S01E03.1080p.WEB.mkv"))
        assertEquals(EpisodeParser.Episode("Another Show", 2, 10), EpisodeParser.parse("another_show_2x10_720p.mp4"))
        assertEquals(EpisodeParser.Episode("My Series", 3, 7), EpisodeParser.parse("My Series - Season 3 Episode 7.mp4"))
        assertEquals(EpisodeParser.Episode("Show Name", 1, 12), EpisodeParser.parse("Show Name S01 E12.mkv"))
        assertNull(EpisodeParser.parse("lecture-notes.pdf"))
        assertEquals("Show Name/Season 1", EpisodeParser.suggestFolder("Show.Name.S01E03.mkv"))
        assertEquals("Videos", EpisodeParser.suggestFolder("movie.2020.mkv"))
        assertEquals("Documents", EpisodeParser.suggestFolder("Some Book.epub"))
        assertEquals("Apps", EpisodeParser.suggestFolder("tool-v2.apk"))
        assertEquals("", EpisodeParser.suggestFolder("README"))
        assertEquals("Bad Name/Season 1", EpisodeParser.safe("Bad:Name/Season 1"))
    }
}
