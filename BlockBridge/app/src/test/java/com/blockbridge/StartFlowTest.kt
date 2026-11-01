package com.blockbridge

import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Walks the "tap the power button" path: activity → VPN service → control notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartFlowTest {
    @Test fun tapPowerStartsWithoutCrashing() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val power = MainActivity::class.java.getDeclaredField("power").apply { isAccessible = true }.get(act) as PowerButton
        power.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        // Run whatever the tap asked for: the VPN consent screen or the service start.
        val app = shadowOf(act.application)
        var started: Intent? = app.nextStartedService
        while (started != null && started.component?.className != ShieldVpnService::class.java.name) started = app.nextStartedService
        val consent = shadowOf(act).nextStartedActivityForResult
        assertNotNull("expected the service to start or the VPN consent screen", started ?: consent)

        val svc = Robolectric.buildService(ShieldVpnService::class.java,
            Intent(act, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START)).create()
        try {
            svc.startCommand(0, 1)
        } catch (e: NoSuchMethodError) {
            // Robolectric can't run VpnService.Builder.addAddress (Android-only libcore call);
            // everything before the VPN interface is created has run by now.
        }
        // ControlService gets startForegroundService() again from the VPN service: must not crash.
        Robolectric.buildService(ControlService::class.java).create().startCommand(0, 1).startCommand(0, 2)
        repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
        svc.destroy()
    }
}
