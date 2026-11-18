package app.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * StashDB (stashdb.org): community database of adult performers with an official GraphQL API.
 * Needs a free account's API key (Settings). Profiles get the id prefix "sdb:".
 */
class StashDbApi(private val key: () -> String) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    private val FIELDS = """
        id name disambiguation aliases gender birth_date ethnicity country eye_color hair_color height
        cup_size band_size waist_size hip_size breast_type career_start_year career_end_year scene_count
        tattoos { location description } piercings { location description }
        images { url width } urls { url site { name } }
    """.trimIndent()

    private suspend fun gql(query: String, variables: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val k = key().trim()
        if (k.isEmpty()) throw ApiException("Add your StashDB API key in Settings")
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val req = Request.Builder().url("https://stashdb.org/graphql")
            .header("ApiKey", k).header("Content-Type", "application/json").header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val t0 = System.currentTimeMillis()
        val text = attempt { client.newCall(req).execute().use { r ->
            AppLog.i("StashDB", "Query → ${r.code} in ${System.currentTimeMillis() - t0} ms")
            if (r.code == 401 || r.code == 403) throw ApiException("StashDB API key was rejected")
            if (!r.isSuccessful) throw ApiException("StashDB error (HTTP ${r.code})")
            r.body?.string().orEmpty()
        } }.getOrElse { if (it is ApiException) throw it else throw ApiException("Network error: ${it.message}") }
        val json = JSONObject(text)
        json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.let { msg ->
            throw ApiException(if (msg.contains("not authorized", true)) "StashDB API key was rejected" else "StashDB: $msg")
        }
        json.getJSONObject("data")
    }

    /** One page of performers (stable order, for syncing the whole database over time). */
    suspend fun performers(page: Int, perPage: Int = 100): Pair<List<Performer>, Int> {
        val q = "query(\$i: PerformerQueryInput!) { queryPerformers(input: \$i) { count performers { $FIELDS } } }"
        val input = JSONObject().put("page", page).put("per_page", perPage).put("sort", "CREATED_AT").put("direction", "ASC")
        val r = gql(q, JSONObject().put("i", input)).getJSONObject("queryPerformers")
        return parseList(r.optJSONArray("performers")) to r.optInt("count")
    }

    suspend fun search(name: String): List<Performer> {
        val q = "query(\$i: PerformerQueryInput!) { queryPerformers(input: \$i) { performers { $FIELDS } } }"
        val input = JSONObject().put("name", name).put("page", 1).put("per_page", 25).put("sort", "SCENE_COUNT").put("direction", "DESC")
        return parseList(gql(q, JSONObject().put("i", input)).getJSONObject("queryPerformers").optJSONArray("performers"))
    }

    private fun parseList(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.let(::parse) }

    private fun JSONObject.s(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.i(k: String) = if (isNull(k)) null else optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE && it > 0 }
    private fun enumText(v: String?) = v?.takeIf { it != "NA" }?.lowercase()?.split('_')?.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    fun parse(o: JSONObject): Performer? {
        val id = o.s("id") ?: return null
        val name = o.s("name") ?: return null
        val mods = { k: String ->
            o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { m ->
                listOfNotNull(m.s("location"), m.s("description")).joinToString(": ")
            }.filter { it.isNotBlank() }.joinToString("; ") }?.ifBlank { null }
        }
        val images = o.optJSONArray("images")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .sortedByDescending { it.optInt("width") }
        val links = o.optJSONArray("urls")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { u ->
                val url = u.s("url") ?: return@mapNotNull null
                (u.optJSONObject("site")?.s("name") ?: url.substringAfter("://").substringBefore('/')) + "|" + url
            }
        }.orEmpty()
        val country = o.s("country")?.let { c -> if (c.length == 2) Locale("", c).getDisplayCountry(Locale.ENGLISH).ifBlank { c } else c }
        val birthday = o.s("birth_date")?.let { Regex("^\\d{4}-\\d{2}-\\d{2}").find(it)?.value }
        return Performer(
            id = "sdb:$id",
            name = name,
            aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.joinToString(", ") }?.ifBlank { null },
            gender = enumText(o.s("gender")),
            birthday = birthday,
            careerStart = o.i("career_start_year"),
            careerEnd = o.i("career_end_year"),
            ethnicity = enumText(o.s("ethnicity")),
            nationality = country,
            hair = enumText(o.s("hair_color")),
            eyes = enumText(o.s("eye_color")),
            heightCm = o.i("height")?.takeIf { it in 120..230 },
            cup = o.s("cup_size")?.uppercase(),
            bust = o.i("band_size"),
            waist = o.i("waist_size"),
            hips = o.i("hip_size"),
            natural = when (o.s("breast_type")) { "NATURAL" -> 1; "FAKE" -> 0; else -> null },
            tattoos = mods("tattoos"),
            piercings = mods("piercings"),
            image = images.firstOrNull()?.s("url"),
            thumb = images.lastOrNull { it.optInt("width") >= 300 }?.s("url") ?: images.firstOrNull()?.s("url"),
            sceneCount = o.i("scene_count"),
            zodiac = zodiacOf(birthday),
            links = links.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }
}
