package app.vault.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Personal taste, kept on the phone only:
 *  - which tags you watch (opening a video, saving it for later) → "For you" ranking,
 *  - recently viewed videos and a Watch later list.
 */
class Taste(context: Context) {
    private val sp = context.getSharedPreferences("taste", Context.MODE_PRIVATE)
    private val weights: MutableMap<String, Double> = readWeights()
    val recent = MutableStateFlow(readScenes("recent"))
    val later = MutableStateFlow(readScenes("later"))

    /** Learn from a video: tags get points; older points slowly fade so taste can change. */
    fun learn(s: Scene, points: Double) {
        if (s.tags.isEmpty()) return
        weights.replaceAll { _, v -> v * 0.995 }
        s.tags.filterNot { Categories.isExcluded(it) }.forEach { t -> weights[t] = (weights[t] ?: 0.0) + points }
        sp.edit().putString("w", JSONObject(weights.filterValues { it > 0.05 } as Map<*, *>).toString()).apply()
    }

    fun topTags(n: Int): List<String> = weights.entries.sortedByDescending { it.value }.take(n).map { it.key }
    fun score(s: Scene): Double = s.tags.sumOf { weights[it] ?: 0.0 }
    val hasTaste get() = weights.size >= 3

    fun viewed(s: Scene) {
        recent.value = (listOf(s) + recent.value.filter { it.id != s.id }).take(60)
        writeScenes("recent", recent.value)
        learn(s, 1.0)
    }

    fun isLater(id: String) = later.value.any { it.id == id }
    fun toggleLater(s: Scene) {
        later.value = if (isLater(s.id)) later.value.filter { it.id != s.id } else { learn(s, 2.0); listOf(s) + later.value }
        writeScenes("later", later.value)
    }

    fun clearHistory() { recent.value = emptyList(); writeScenes("recent", emptyList()) }
    fun resetTaste() { weights.clear(); sp.edit().remove("w").apply() }

    private fun readWeights(): MutableMap<String, Double> = attempt {
        val o = JSONObject(sp.getString("w", "{}")!!)
        o.keys().asSequence().associateWith { o.getDouble(it) }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun readScenes(key: String): List<Scene> = attempt {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).mapNotNull { sceneFromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeScenes(key: String, list: List<Scene>) {
        val a = JSONArray(); list.forEach { a.put(sceneToJson(it)) }
        sp.edit().putString(key, a.toString()).apply()
    }
}

fun sceneToJson(s: Scene): JSONObject = JSONObject()
    .put("id", s.id).put("title", s.title).put("date", s.date).put("image", s.image).put("thumb", s.thumb)
    .put("trailer", s.trailer).put("url", s.url).put("site", s.siteName).put("siteUrl", s.siteUrl)
    .put("dur", s.durationSec).put("desc", s.description)
    .put("fb", JSONArray(s.imageFallbacks)).put("tags", JSONArray(s.tags))
    .put("cast", JSONArray(s.performers.map { JSONObject().put("id", it.id).put("n", it.name) }))

fun sceneFromJson(o: JSONObject): Scene? = attempt {
    fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
    fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    Scene(
        id = o.getString("id"), title = o.optString("title", "Untitled"), date = str("date"), image = str("image"),
        trailer = str("trailer"), url = str("url"), siteName = str("site"), siteUrl = str("siteUrl"),
        durationSec = o.optInt("dur", 0).takeIf { it > 0 }, description = str("desc"),
        performers = o.optJSONArray("cast")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p -> ScenePerformer(p.optString("id").takeIf { it.isNotBlank() && it != "null" }, p.getString("n")) } } }.orEmpty(),
        tags = arr("tags"), thumb = str("thumb"), imageFallbacks = arr("fb"),
    )
}.getOrNull()
