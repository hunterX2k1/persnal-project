package com.blockbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The built-in YouTube Plus helper must match what the app expects, or GeckoView won't load it. */
class YtPlusTest {
    private val dir = listOf("src/main/assets/ytplus", "app/src/main/assets/ytplus").map(::File).first { it.isDirectory }

    @Test fun manifestMatchesApp() {
        val m = JSONObject(File(dir, "manifest.json").readText())
        assertEquals(Gecko.PLUS_ID, m.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id"))
        val perms = m.getJSONArray("permissions").let { a -> (0 until a.length()).map(a::getString) }
        assertTrue(perms.containsAll(listOf("nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons")))
        val cs = m.getJSONArray("content_scripts").getJSONObject(0)
        for (key in listOf("js", "css")) {
            val f = cs.getJSONArray(key).getString(0)
            assertTrue("$f missing", File(dir, f).isFile)
        }
        assertEquals("document_start", cs.getString("run_at"))
    }

    @Test fun contentScriptTalksToApp() {
        val js = File(dir, "content.js").readText()
        // YouTubeActivity registers its message delegate under this name.
        assertTrue(js.contains("connectNative(\"blockbridge\")"))
        assertTrue(File(dir, "content.css").readText().contains("html.bb-pip video"))
    }
}
