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
}
