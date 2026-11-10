package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRateTest {
    private fun mode(id: Int, w: Int, h: Int, hz: Float) = Perf.DisplayMode(id, w, h, hz)

    @Test fun picksTheFastestModeAtTheCurrentResolution() {
        val current = mode(1, 1220, 2712, 60f)
        val modes = listOf(current, mode(2, 1220, 2712, 120f), mode(3, 1220, 2712, 90f),
            mode(4, 720, 1600, 144f)) // a faster mode at another resolution must not be chosen
        assertEquals(2, Perf.bestMode(current, modes)!!.id)
    }
}
