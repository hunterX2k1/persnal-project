package app.atlas.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Saved(val name: String, val url: String, val desc: String, val page: String, val time: Long)

/** Bookmarks, history and settings, kept in SharedPreferences. */
class Store(context: Context) {
    private val sp = context.getSharedPreferences("atlas", Context.MODE_PRIVATE)

    val bookmarks = MutableStateFlow(read("bookmarks"))
    val history = MutableStateFlow(read("history"))

    val adblock = flag("adblock", true)
    val blockRedirects = flag("block_redirects", true)
    val warnUnsafe = flag("warn_unsafe", true)
    val desktopMode = flag("desktop", false)
    val clearOnClose = flag("clear_on_close", false)
    val starredOnly = flag("starred_only", false)
    val allowAds = MutableStateFlow(sp.getStringSet("allow_ads", emptySet())!!.toSet())

    inner class Flag(private val key: String, default: Boolean) {
        val state = MutableStateFlow(sp.getBoolean(key, default))
        var value: Boolean
            get() = state.value
            set(v) { state.value = v; sp.edit().putBoolean(key, v).apply() }
    }
    private fun flag(key: String, d: Boolean) = Flag(key, d)

    fun isBookmarked(url: String?) = url != null && bookmarks.value.any { it.url == url }

    fun toggleBookmark(e: Entry) {
        val url = e.url ?: return
        val cur = bookmarks.value
        bookmarks.value = if (cur.any { it.url == url }) cur.filter { it.url != url }
        else listOf(Saved(e.name, url, e.desc, e.page, System.currentTimeMillis())) + cur
        write("bookmarks", bookmarks.value)
    }

    fun removeBookmark(url: String) { bookmarks.value = bookmarks.value.filter { it.url != url }; write("bookmarks", bookmarks.value) }

    fun addHistory(name: String, url: String, page: String = "") {
        history.value = (listOf(Saved(name, url, "", page, System.currentTimeMillis())) + history.value.filter { it.url != url }).take(100)
        write("history", history.value)
    }

    fun clearHistory() { history.value = emptyList(); write("history", emptyList()) }

    fun toggleAllowAds(site: String) {
        allowAds.value = if (site in allowAds.value) allowAds.value - site else allowAds.value + site
        sp.edit().putStringSet("allow_ads", allowAds.value).apply()
    }

    private fun read(key: String): List<Saved> = runCatching {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Saved(o.getString("n"), o.getString("u"), o.optString("d"), o.optString("p"), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun write(key: String, list: List<Saved>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("d", it.desc).put("p", it.page).put("t", it.time)) }
        sp.edit().putString(key, a.toString()).apply()
    }
}
