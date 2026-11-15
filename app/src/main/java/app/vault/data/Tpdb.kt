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
    val description: String? = null,
    val performers: List<ScenePerformer> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** A performer credited in a scene. [id] is the main profile id when known. */
data class ScenePerformer(val id: String?, val name: String)

data class ScenePage(val scenes: List<Scene>, val page: Int, val lastPage: Int, val total: Int?)

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
        val started = System.currentTimeMillis()
        val r0 = runCatching { client.newCall(req).execute() }
            .onFailure { AppLog.e("API", "GET $path failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { throw ApiException("Network error: ${it.message}") }
        r0.use { r ->
            AppLog.i("API", "GET $path → ${r.code} in ${System.currentTimeMillis() - started} ms")
            when {
                r.code == 401 || r.code == 403 -> throw ApiException("API key was rejected (HTTP ${r.code})")
                r.code == 429 -> throw ApiException("Rate limited — try again in a minute")
                !r.isSuccessful -> throw ApiException("Server error (HTTP ${r.code})")
            }
            val body = r.body?.string().orEmpty()
            runCatching { JSONObject(body) }.getOrElse {
                AppLog.e("API", "Bad JSON from $path: ${body.take(300)}")
                throw ApiException("Unexpected response from server")
            }
        }
    }

    suspend fun performers(page: Int, query: String? = null): PageResult {
        val q = query?.let { "&q=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val json = get("/performers?page=$page&per_page=50$q")
        val data = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parsePerformer) }
        if (data.length() > 0 && items.isEmpty()) AppLog.w("API", "Could not parse performers. First item: ${data.optJSONObject(0)?.toString()?.take(400)}")
        val meta = json.optJSONObject("meta")
        val last = meta?.optInt("last_page", 0) ?: 0
        return PageResult(items, page, last)
    }

    suspend fun performer(id: String): Performer? {
        val json = get("/performers/${URLEncoder.encode(id, "UTF-8")}")
        return (json.optJSONObject("data") ?: json).let(::parsePerformer)
    }

    /**
     * One page of a performer's scenes, newest first. Uses the performer's own scene list,
     * falling back to a name search for profiles that didn't come from ThePornDB.
     */
    suspend fun scenes(p: Performer, page: Int = 1): ScenePage {
        val enc = URLEncoder.encode(p.id, "UTF-8")
        val byId = if (p.id.startsWith("wd:")) null
            else runCatching { get("/performers/$enc/scenes?per_page=40&page=$page&orderBy=recently_released") }.getOrNull()
        val json = byId ?: get("/scenes?per_page=40&page=$page&q=" + URLEncoder.encode(p.name, "UTF-8"))
        val data = json.optJSONArray("data") ?: return ScenePage(emptyList(), page, page, 0).also { AppLog.w("API", "Scenes response had no data array") }
        var scenes = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parseScene) }
        if (byId == null) {
            // Name search can match other people: keep scenes that credit this name.
            val n = p.name.lowercase()
            scenes = scenes.filter { s -> s.performers.isEmpty() || s.performers.any { it.name.lowercase() == n } }
        }
        val meta = json.optJSONObject("meta")
        val last = meta?.optInt("last_page", page)?.takeIf { it > 0 } ?: page
        val total = meta?.optInt("total", -1)?.takeIf { it >= 0 }
        AppLog.i("API", "Scenes page $page/$last: ${scenes.size} (via ${if (byId != null) "performer endpoint" else "search fallback"}), with site: ${scenes.count { it.siteName != null }}, with trailer: ${scenes.count { it.trailer != null }}")
        return ScenePage(scenes, page, last, total)
    }

    /** Scene search across the whole database. [tags] are also sent as tag filters (ignored by the server if unsupported). */
    suspend fun searchScenes(q: String, tags: List<String>, page: Int): ScenePage {
        val params = buildString {
            append("/scenes?per_page=40&page=$page&orderBy=recently_released")
            if (q.isNotBlank()) append("&q=").append(URLEncoder.encode(q.trim(), "UTF-8"))
            tags.forEach { append("&tags[]=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val json = get(params)
        val data = json.optJSONArray("data")
        val scenes = (0 until (data?.length() ?: 0)).mapNotNull { data?.optJSONObject(it)?.let(::parseScene) }
        val meta = json.optJSONObject("meta")
        val last = meta?.optInt("last_page", page)?.takeIf { it > 0 } ?: page
        AppLog.i("API", "Scene search page $page/$last: ${scenes.size} results (${tags.size} tags, text ${q.isNotBlank()})")
        return ScenePage(scenes, page, last, meta?.optInt("total", -1)?.takeIf { it >= 0 })
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
        val m = parseMeasurements(x.str("measurements"))
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
            cup = cupLetters(x.str("cupsize")) ?: m?.cup,
            bust = cupBand(x.str("cupsize")) ?: m?.bust,
            waist = x.intOrNull("waist") ?: m?.waist,
            hips = x.intOrNull("hips") ?: m?.hips,
            natural = (x.opt("fake_boobs").takeIf { it is Boolean } ?: x.opt("fakeboobs").takeIf { it is Boolean })
                ?.let { if (it == true) 0 else 1 },
            zodiac = x.str("astrology")?.cap() ?: zodiacOf(birthday),
            birthplace = x.str("birthplace"),
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
        val perfs = o.optJSONArray("performers")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p ->
                val parent = p.optJSONObject("parent")
                val name = parent?.str("name") ?: p.str("name") ?: return@mapNotNull null
                ScenePerformer(parent?.str("id") ?: p.str("id"), name)
            }
        }.orEmpty()
        val tags = o.optJSONArray("tags")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("name") ?: a.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
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
            description = o.str("description"),
            performers = perfs,
            tags = tags,
        )
    }

    private data class Meas(val bust: Int?, val cup: String?, val waist: Int?, val hips: Int?)

    /** "34D-24-36" → bust 34, cup D, waist 24, hips 36. */
    private fun parseMeasurements(s: String?): Meas? {
        val m = Regex("(\\d{2})\\s*([A-Za-z]{1,4})?\\s*-\\s*(\\d{2})\\s*-\\s*(\\d{2})").find(s ?: return null) ?: return null
        return Meas(m.groupValues[1].toIntOrNull(), m.groupValues[2].uppercase().ifEmpty { null }, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
    }

    private fun cupLetters(s: String?) = s?.let { Regex("[A-Za-z]{1,4}").find(it)?.value?.uppercase() }
    private fun cupBand(s: String?) = s?.let { Regex("\\d{2}").find(it)?.value?.toIntOrNull() }


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
