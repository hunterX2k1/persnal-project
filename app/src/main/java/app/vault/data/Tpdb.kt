package app.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

data class PageResult(val items: List<Performer>, val page: Int, val lastPage: Int)

data class Scene(
    val id: String,
    val title: String,
    val date: String?,
    val image: String?,
    val trailer: String?,
    val url: String?,
    val siteName: String?,
    val siteUrl: String?,
    val durationSec: Int?,
)

/** Client for the ThePornDB API (https://api.theporndb.net). Parsing is lenient on purpose. */
class TpdbApi(private val key: () -> String) {
    private val base = "https://api.theporndb.net"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val k = key().trim()
        if (k.isEmpty()) throw ApiException("Add your ThePornDB API key in Settings")
        val req = Request.Builder().url(base + path)
            .header("Authorization", "Bearer $k")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException("API key was rejected (HTTP ${r.code})")
                r.code == 429 -> throw ApiException("Rate limited — try again in a minute")
                !r.isSuccessful -> throw ApiException("Server error (HTTP ${r.code})")
            }
            JSONObject(r.body?.string().orEmpty())
        }
    }

    suspend fun performers(page: Int, query: String? = null): PageResult {
        val q = query?.let { "&q=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val json = get("/performers?page=$page&per_page=50$q")
        val data = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parsePerformer) }
        val meta = json.optJSONObject("meta")
        val last = meta?.optInt("last_page", 0) ?: 0
        return PageResult(items, page, last)
    }

    suspend fun performer(id: String): Performer? {
        val json = get("/performers/${URLEncoder.encode(id, "UTF-8")}")
        return (json.optJSONObject("data") ?: json).let(::parsePerformer)
    }

    suspend fun scenes(p: Performer): List<Scene> {
        val byId = runCatching { get("/performers/${URLEncoder.encode(p.id, "UTF-8")}/scenes?per_page=50") }.getOrNull()
        val json = byId ?: get("/scenes?per_page=50&q=" + URLEncoder.encode(p.name, "UTF-8"))
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parseScene) }
    }

    // ---- parsing ----

    private fun JSONObject.str(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.intOrNull(name: String): Int? = str(name)?.let { s ->
        Regex("\\d+").find(s)?.value?.toIntOrNull()
    }

    private fun parsePerformer(o: JSONObject): Performer? {
        val id = o.str("id") ?: o.str("_id") ?: return null
        val name = o.str("name") ?: return null
        val x = o.optJSONObject("extras") ?: JSONObject()
        val posters = o.optJSONArray("posters")
        val firstPoster = (0 until (posters?.length() ?: 0))
            .firstNotNullOfOrNull { posters?.optJSONObject(it)?.str("url") }
        val aliases = o.optJSONArray("aliases")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.joinToString(", ")
        }?.takeIf { it.isNotBlank() }
        val birthday = x.str("birthday")?.let { Regex("\\d{4}-\\d{2}-\\d{2}").find(it)?.value }
        return Performer(
            id = id,
            name = name,
            aliases = aliases,
            gender = x.str("gender")?.cap(),
            birthday = birthday,
            careerStart = x.intOrNull("career_start_year"),
            careerEnd = x.intOrNull("career_end_year"),
            ethnicity = x.str("ethnicity")?.cap(),
            nationality = x.str("nationality")?.cap(),
            hair = x.str("hair_colour")?.cap(),
            eyes = x.str("eye_colour")?.cap(),
            heightCm = parseHeight(x.str("height")),
            weightKg = parseWeight(x.str("weight")),
            measurements = x.str("measurements"),
            cup = x.str("cupsize")?.uppercase(),
            tattoos = x.str("tattoos"),
            piercings = x.str("piercings"),
            bio = o.str("bio"),
            image = o.str("image") ?: firstPoster ?: o.str("face"),
            thumb = o.str("thumbnail") ?: o.str("face") ?: o.str("image"),
            sceneCount = o.intOrNull("scene_count") ?: o.intOrNull("scenes_count"),
        )
    }

    private fun parseScene(o: JSONObject): Scene? {
        val id = o.str("id") ?: o.str("_id") ?: return null
        val site = o.optJSONObject("site")
        val bg = o.optJSONObject("background")
        return Scene(
            id = id,
            title = o.str("title") ?: "Untitled",
            date = o.str("date"),
            image = o.str("image") ?: bg?.str("large") ?: bg?.str("full") ?: o.str("poster"),
            trailer = o.str("trailer"),
            url = o.str("url"),
            siteName = site?.str("name"),
            siteUrl = site?.str("url"),
            durationSec = o.intOrNull("duration"),
        )
    }

    private fun String.cap() = lowercase().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun parseHeight(s: String?): Int? {
        s ?: return null
        Regex("(\\d)\\s*'\\s*(\\d{1,2})").find(s)?.let { m ->
            val (ft, inch) = m.destructured
            return ((ft.toInt() * 12 + inch.toInt()) * 2.54).toInt()
        }
        val n = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: return null
        return n.takeIf { it in 120..230 }
    }

    private fun parseWeight(s: String?): Int? {
        s ?: return null
        val n = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: return null
        return if (s.contains("lb", true)) (n * 0.4536).toInt() else n.takeIf { it in 30..200 }
    }
}
