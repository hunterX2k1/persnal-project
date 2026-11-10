package com.blockbridge

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityFixesTest {
    @Test fun craftedBackupWithAbsurdKeyCostIsRefused() {
        val good = BackupCrypto.encrypt("hello", "pw")
        assertEquals("hello", BackupCrypto.decrypt(good, "pw"))
        for (iter in listOf(Int.MAX_VALUE, 1)) {
            try {
                BackupCrypto.decrypt(JSONObject(good.toString()).put("iter", iter), "pw")
                fail("iter=$iter should be refused")
            } catch (e: IllegalArgumentException) { /* refused */ }
        }
    }

    @Test fun automaticSafeModeEndsAfter24Hours() {
        val ctx = RuntimeEnvironment.getApplication()
        val s = Settings(ctx)
        s.enableSafeModeAfterCrash()
        assertTrue(s.safeMode)
        ctx.getSharedPreferences("settings", 0).edit()
            .putLong("safe_mode_auto_at", System.currentTimeMillis() - Settings.SAFE_MODE_AUTO_MS - 1000).apply()
        assertFalse(s.safeMode)
        // safe mode you turned on yourself stays on
        s.safeMode = true
        assertTrue(s.safeMode)
        s.safeMode = false
    }
}
