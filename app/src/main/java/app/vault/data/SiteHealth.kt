package app.vault.data

import android.content.Context
import org.json.JSONObject

/**
 * How well each of my sites works for video searches, learned from real searches:
 * how often it finds something, how often it blocks or fails, and how fast it answers.
 * Kept on the phone only.
 */
class SiteHealth(context: Context) {
    private val sp = context.getSharedPreferences("site_health", Context.MODE_PRIVATE)
    private val stats: MutableMap<String, IntArray> = read() // [searches, found, blocked, failed, avgMs]

    @Synchronized
    fun record(p: SiteProgress) {
        if (p.status is SiteStatus.Searching) return
        val s = stats.getOrPut(p.site.domain) { IntArray(5) }
        s[0]++
        when (p.status) {
            is SiteStatus.Found -> s[1]++
            is SiteStatus.Blocked -> s[2]++
            is SiteStatus.Failed -> s[3]++
            else -> {}
        }
        val ms = p.hits.filter { it.ms > 0 }.minOfOrNull { it.ms }?.toInt()
        if (ms != null) s[4] = if (s[4] == 0) ms else (s[4] * 3 + ms) / 4
        sp.edit().putString(p.site.domain, s.joinToString(",")).apply()
    }

    /** 0–100, or null if the site hasn't been searched yet. */
    @Synchronized
    fun score(domain: String): Int? {
        val s = stats[domain] ?: return null
        if (s[0] == 0) return null
        val found = s[1].toDouble() / s[0]
        val bad = (s[2] + s[3]).toDouble() / s[0]
        val speed = when { s[4] == 0 -> 5; s[4] < 2000 -> 15; s[4] < 5000 -> 8; else -> 2 }
        return (found * 60 + (1 - bad) * 25 + speed).toInt().coerceIn(0, 100)
    }

    @Synchronized fun searches(domain: String) = stats[domain]?.get(0) ?: 0

    fun reset() { stats.clear(); sp.edit().clear().apply() }

    private fun read(): MutableMap<String, IntArray> = HashMap<String, IntArray>().apply {
        sp.all.forEach { (k, v) ->
            (v as? String)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 5 }?.let { put(k, it.toIntArray()) }
        }
    }
}

/** Named video collections ("Romantic nights"…), kept on the phone. */
class Playlists(context: Context) {
    private val sp = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    val all = kotlinx.coroutines.flow.MutableStateFlow(read())

    fun names() = all.value.keys.toList()
    fun contains(name: String, id: String) = all.value[name]?.any { it.id == id } == true

    fun create(name: String) { if (name.isNotBlank() && name !in all.value) update(all.value + (name.trim() to emptyList())) }
    fun delete(name: String) = update(all.value - name)
    fun rename(from: String, to: String) {
        if (to.isBlank() || to in all.value) return
        update(all.value.entries.associate { (k, v) -> (if (k == from) to.trim() else k) to v })
    }
    fun toggle(name: String, s: Scene) {
        val list = all.value[name].orEmpty()
        update(all.value + (name to if (list.any { it.id == s.id }) list.filter { it.id != s.id } else list + s))
    }
    fun move(name: String, from: Int, to: Int) {
        val list = all.value[name]?.toMutableList() ?: return
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        update(all.value + (name to list))
    }

    private fun update(m: Map<String, List<Scene>>) {
        all.value = LinkedHashMap(m)
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, org.json.JSONArray().apply { v.forEach { put(sceneToJson(it)) } }) }
        sp.edit().putString("all", o.toString()).apply()
    }

    private fun read(): Map<String, List<Scene>> = attempt {
        val o = JSONObject(sp.getString("all", "{}")!!)
        val m = LinkedHashMap<String, List<Scene>>()
        o.keys().forEach { k -> val a = o.getJSONArray(k); m[k] = (0 until a.length()).mapNotNull { sceneFromJson(a.getJSONObject(it)) } }
        m
    }.getOrDefault(emptyMap())
}

/**
 * New releases from the performers I follow (my favourites), checked daily in the background.
 * Shown as a "New for you" row with a badge — no notification text, so nothing shows on the lock screen.
 */
class NewReleases(context: Context) {
    private val sp = context.getSharedPreferences("new_releases", Context.MODE_PRIVATE)
    val items = kotlinx.coroutines.flow.MutableStateFlow(readList())
    val unseen = kotlinx.coroutines.flow.MutableStateFlow(sp.getInt("unseen", 0))

    fun lastDate(performerId: String): String? = sp.getString("d:$performerId", null)
    fun setLastDate(performerId: String, date: String) = sp.edit().putString("d:$performerId", date).apply()

    fun add(scenes: List<Scene>) {
        if (scenes.isEmpty()) return
        val fresh = scenes.filter { s -> items.value.none { it.id == s.id } }
        items.value = (fresh + items.value).sortedByDescending { it.date.orEmpty() }.take(120)
        unseen.value += fresh.size
        save()
    }

    fun markSeen() { unseen.value = 0; save() }
    fun clear() { items.value = emptyList(); unseen.value = 0; save() }

    private fun save() {
        val a = org.json.JSONArray(); items.value.forEach { a.put(sceneToJson(it)) }
        sp.edit().putString("list", a.toString()).putInt("unseen", unseen.value).apply()
    }

    private fun readList(): List<Scene> = attempt {
        val a = org.json.JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).mapNotNull { sceneFromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
