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
    private val home = "https://m.youtube.com/"
    private fun start(u: String) = youTubeStartUrl(Uri.parse(u))

    @Test fun otherAppsCanOnlyOpenYouTubePages() {
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", start("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", start("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("https://music.youtube.com/watch?v=abc", start("http://music.youtube.com/watch?v=abc"))
        // anything else falls back to YouTube's home page
        assertEquals(home, start("file:///data/data/com.blockbridge/shared_prefs/settings.xml"))
        assertEquals(home, start("javascript:alert(1)"))
        assertEquals(home, start("about:config"))
        assertEquals(home, start("https://evil.example/phish"))
        assertEquals(home, start("https://youtube.com.evil.example/"))
        assertEquals(home, start("https://youtu.be/../../etc"))
    }

    @Test fun onlyBlockBridgesOwnAddonsAreTrusted() {
        assertTrue(Gecko.UBO_ID in Gecko.TRUSTED_ADDONS && Gecko.SPONSOR_ID in Gecko.TRUSTED_ADDONS)
        assertFalse("evil@example.com" in Gecko.TRUSTED_ADDONS)
    }

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
