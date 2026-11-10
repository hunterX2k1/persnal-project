package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipAspectTest {
    private fun ratio(w: Int, h: Int) = pipAspect(w, h).toFloat()

    @Test fun normalVideoKeepsItsShape() = assertEquals(16f / 9, ratio(1920, 1080), 0.001f)
    @Test fun shortsAreTall() = assertEquals(9f / 16, ratio(1080, 1920), 0.001f)
    @Test fun ultraWideIsClampedToAndroidsLimit() = assertEquals(2.39f, ratio(3000, 1000), 0.001f)
    @Test fun unknownSizeFallsBackTo16by9() = assertEquals(16f / 9, ratio(0, 0), 0.001f)
}
