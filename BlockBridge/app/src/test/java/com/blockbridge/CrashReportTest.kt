package com.blockbridge

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportTest {
    @Test fun crashesAreKeptInHistoryNewestFirst() {
        val ctx = RuntimeEnvironment.getApplication()
        CrashGuard.install(ctx)
        CrashGuard.clearHistory()
        assertNull(CrashGuard.history())
        CrashGuard.addHistory("first crash")
        CrashGuard.addHistory("second crash")
        val h = CrashGuard.history()
        assertNotNull(h)
        assertTrue(h!!.indexOf("second crash") < h.indexOf("first crash"))
        CrashGuard.clearHistory()
        assertNull(CrashGuard.history())
    }

    @Test fun systemLogNeverThrows() {
        // On a test machine there's no Android logcat; a report must still be built.
        val log = CrashGuard.systemLog(10, 3_000)
        assertTrue(log.isNotEmpty())
    }

    @Test fun headerNamesVersionAndDevice() {
        val h = CrashGuard.header(RuntimeEnvironment.getApplication())
        assertTrue(h.startsWith("BlockBridge ") && "Android" in h)
    }

    @Test fun reportPutsProblemAreaFirstAndKeepsOnlySystemErrors() {
        val log = listOf(
            "11-10 07:53:00.000 [tor] bootstrap 50%",
            "11-10 07:54:00.000 [youtube] picture-in-picture on",
            "11-10 07:54:01.000 [yt-page] YouTube paused while entering picture-in-picture — resuming",
            "11-10 07:55:00.000 [vpn] connecting",
        )
        val crashes = "=".repeat(40) + "\nnewest crash\n\n" + "=".repeat(40) + "\nolder crash\n"
        val sys = "11-10 07:54:00.100  1234  1250 I GeckoView: chatter\n" +
            "11-10 07:54:00.200  1234  1250 E GeckoConsole: real error\n" +
            "11-10 07:54:00.300  1234  1250 W ActivityTaskManager: pip warning"
        val r = CrashGuard.buildReport("HEADER", "SUMMARY", log, crashes, sys, setOf("youtube", "yt-page"))
        val focus = r.substringAfter("youtube/yt-page log").substringBefore("---- Debug log")
        assertTrue("picture-in-picture on" in focus && "resuming" in focus && "bootstrap" !in focus)
        assertTrue(r.indexOf("youtube/yt-page log") < r.indexOf("---- Debug log"))
        assertTrue("newest crash" in r && "older crash" !in r)
        assertTrue("real error" in r && "pip warning" in r && "chatter" !in r)
    }

    @Test fun repeatedLinesAreCollapsed() {
        DiagLog.clear()
        repeat(5) { DiagLog.add("vpn", "same warning") }
        DiagLog.add("vpn", "different")
        val t = DiagLog.text()
        assertTrue(Regex("same warning").findAll(t).count() == 1)
        assertTrue("repeated 4 more times" in t)
    }
}
