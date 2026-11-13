package app.vault.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import java.security.MessageDigest
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** SharedPreferences backed by Compose state, so the UI updates when a setting changes. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    private inner class Pref<T>(
        private val key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(sp.read(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            sp.edit().apply { write(key, value) }.apply()
        }
    }

    private fun bool(key: String, d: Boolean) =
        Pref(key, d, { k, v -> getBoolean(k, v) }, { k, v -> putBoolean(k, v) })
    private fun str(key: String, d: String) =
        Pref(key, d, { k, v -> getString(k, v) ?: v }, { k, v -> putString(k, v) })
    private fun int(key: String, d: Int) =
        Pref(key, d, { k, v -> getInt(k, v) }, { k, v -> putInt(k, v) })
    private fun long(key: String, d: Long) =
        Pref(key, d, { k, v -> getLong(k, v) }, { k, v -> putLong(k, v) })

    var ageConfirmed by bool("age_confirmed", false)
    var apiKey by str("api_key", "")
    var pinHash by str("pin_hash", "")
    var hideInRecents by bool("hide_recents", true)
    var blurImages by bool("blur_images", false)
    var searchEngine by str("search_engine", "duckduckgo")
    var clearBrowserOnExit by bool("clear_browser", true)
    var iconAlias by str("icon_alias", "Vault")
    var syncCursor by int("sync_cursor", 1)
    var lastSync by long("last_sync", 0L)
    var lastSyncMessage by str("last_sync_msg", "")

    val hasPin get() = pinHash.isNotEmpty()

    fun setPin(pin: String?) {
        pinHash = if (pin.isNullOrEmpty()) "" else hash(pin)
    }

    fun checkPin(pin: String) = hasPin && hash(pin) == pinHash

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("vault:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun webSearchUrl(query: String): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return when (searchEngine) {
            "google" -> "https://www.google.com/search?safe=off&q=$q"
            "bing" -> "https://www.bing.com/search?adlt=off&q=$q"
            else -> "https://duckduckgo.com/?kp=-2&q=$q"
        }
    }

    companion object {
        val ENGINES = listOf("duckduckgo" to "DuckDuckGo", "google" to "Google", "bing" to "Bing")
    }
}
