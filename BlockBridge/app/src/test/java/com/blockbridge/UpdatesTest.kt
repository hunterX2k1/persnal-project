package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private val notes = "Built from main @ abc1234. Download and open to install. versionCode=25 videoVersionCode=3"

    @Test fun eachAppReadsItsOwnVersion() {
        assertEquals(25L, Updates.versionIn(notes, Updates.MAIN))
        assertEquals(3L, Updates.versionIn(notes, Updates.VIDEO))
    }

    @Test fun oldReleaseWithoutVideoHasNoVideoVersion() =
        assertNull(Updates.versionIn("versionCode=24", Updates.VIDEO))
}
