package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniPlayerTest {
    @Test fun youTubeAppIntentStaysInBlockBridge() {
        // What m.youtube.com's "Open App" button links to.
        val link = "intent://www.youtube.com/watch?v=abc123#Intent;scheme=https;package=com.google.android.youtube;end"
        assertEquals("https://www.youtube.com/watch?v=abc123", youTubeAppLink(link))
    }

    @Test fun vndYoutubeLinkBecomesWatchPage() =
        assertEquals("https://m.youtube.com/watch?v=xyz789", youTubeAppLink("vnd.youtube:xyz789"))

    @Test fun fallbackUrlIsUsedWhenThereIsNoWebAddress() {
        val link = "intent://open#Intent;package=com.google.android.youtube;" +
            "S.browser_fallback_url=https%3A%2F%2Fm.youtube.com%2F;end"
        assertEquals("https://m.youtube.com/", youTubeAppLink(link))
    }

    @Test fun otherAppsLinksAreLeftAlone() {
        assertNull(youTubeAppLink("mailto:someone@example.com"))
        assertNull(youTubeAppLink("intent://scan#Intent;scheme=zxing;package=com.example.scanner;end"))
    }

    @Test fun lowEndDetection() {
        assertTrue(Perf.isLowEnd(false, 3_800_000_000L, 8))   // "4 GB" phone (e.g. Nokia G42 4 GB)
        assertTrue(Perf.isLowEnd(true, 8_000_000_000L, 8))    // Android's own low-RAM flag
        assertTrue(Perf.isLowEnd(false, 6_000_000_000L, 4))   // only 4 cores
        assertFalse(Perf.isLowEnd(false, 12_000_000_000L, 8)) // e.g. edge 60 pro
    }

    @Test fun smoothModeFollowsTheVideoChip() {
        assertFalse(Perf.smoothByDefault(lowEnd = false, hwVp9 = true))  // strong phone with a VP9 chip: full quality formats
        assertTrue(Perf.smoothByDefault(lowEnd = false, hwVp9 = false))  // no VP9 chip: H.264
        assertTrue(Perf.smoothByDefault(lowEnd = true, hwVp9 = true))    // low-end: H.264 regardless
    }

    @Test fun profileOnThisDeviceIsReadable() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val d = Perf.describe(ctx)
        assertTrue(d.contains("RAM") && d.contains("video chip"))
    }
}
