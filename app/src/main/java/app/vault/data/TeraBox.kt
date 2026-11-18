package app.vault.data

import android.webkit.CookieManager

/** TeraBox share links: opened as the desktop site (no app prompts) and kept signed in. */
object TeraBox {
    private val DOMAINS = listOf(
        "terabox.com", "1024terabox.com", "teraboxapp.com", "terabox.app", "teraboxlink.com", "terasharelink.com",
        "freeterabox.com", "4funbox.com", "mirrobox.com", "nephobox.com", "momerybox.com", "tibibox.com",
        "1024tera.com", "teraboxshare.com", "terafileshare.com",
    )

    fun isTeraBox(host: String?): Boolean {
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    fun isTeraBoxLink(text: String?): Boolean =
        text != null && Regex("^https?://[^\\s/]+/", RegexOption.IGNORE_CASE).containsMatchIn(text.trim()) &&
            isTeraBox(runCatching { java.net.URI(text.trim()).host }.getOrNull())

    /** Cookies to put back after the browser's "clear data on close", so the TeraBox sign-in survives. */
    fun saveCookies(): Map<String, String> = DOMAINS.mapNotNull { d ->
        CookieManager.getInstance().getCookie("https://www.$d/")?.let { d to it }
    }.toMap()

    fun restoreCookies(saved: Map<String, String>) {
        val cm = CookieManager.getInstance()
        saved.forEach { (d, header) ->
            header.split(';').map { it.trim() }.filter { '=' in it }.forEach { kv ->
                cm.setCookie("https://www.$d/", "$kv; Domain=.$d; Path=/; Max-Age=2592000; Secure")
            }
        }
        cm.flush()
    }
}
