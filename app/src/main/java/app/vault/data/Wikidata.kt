package app.vault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

const val USER_AGENT = "VaultApp/1.2 (personal Android app)"

/**
 * Free, key-less performer source: Wikidata (CC0 data) + Wikipedia summaries.
 * Photos come from Wikimedia Commons (freely licensed).
 */
class WikidataSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    private suspend fun sparql(query: String): JSONObject = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val req = Request.Builder()
            .url("https://query.wikidata.org/sparql")
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("query", query).build())
            .build()
        val res = runCatching { client.newCall(req).execute() }
            .onFailure { AppLog.e("Wikidata", "Request failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { throw ApiException("Network error: ${it.message}") }
        res.use { r ->
            AppLog.i("Wikidata", "Query → ${r.code} in ${System.currentTimeMillis() - started} ms")
            if (r.code == 429) throw ApiException("Free database is busy — try again in a minute")
            if (!r.isSuccessful) throw ApiException("Free database error (HTTP ${r.code})")
            val body = r.body?.string().orEmpty()
            runCatching { JSONObject(body) }.getOrElse {
                AppLog.e("Wikidata", "Bad JSON: ${body.take(300)}")
                throw ApiException("Unexpected response from free database")
            }
        }
    }

    private fun JSONObject.rows() = optJSONObject("results")?.optJSONArray("bindings")
        ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    private fun JSONObject.v(name: String): String? =
        optJSONObject(name)?.optString("value")?.takeIf { it.isNotBlank() }

    /** All Q-ids of people whose occupation is pornographic actor. Fast (<1 s). */
    suspend fun allIds(): List<String> =
        sparql("SELECT ?p WHERE { ?p wdt:P106 wd:Q488111 . }").rows()
            .mapNotNull { it.v("p")?.substringAfterLast('/') }
            .distinct().sorted()

    suspend fun details(qids: List<String>): List<Performer> {
        if (qids.isEmpty()) return emptyList()
        val values = qids.joinToString(" ") { "wd:$it" }
        val q = """
            SELECT ?p ?pLabel ?birth ?countryLabel ?image ?heightM ?hairLabel ?eyeLabel ?start ?end ?genderLabel ?article WHERE {
              VALUES ?p { $values }
              OPTIONAL { ?p wdt:P569 ?birth }
              OPTIONAL { ?p wdt:P27 ?country }
              OPTIONAL { ?p wdt:P18 ?image }
              OPTIONAL { ?p p:P2048/psn:P2048/wikibase:quantityAmount ?heightM }
              OPTIONAL { ?p wdt:P1884 ?hair }
              OPTIONAL { ?p wdt:P1340 ?eye }
              OPTIONAL { ?p wdt:P2031 ?start }
              OPTIONAL { ?p wdt:P2032 ?end }
              OPTIONAL { ?p wdt:P21 ?gender }
              OPTIONAL { ?article schema:about ?p ; schema:isPartOf <https://en.wikipedia.org/> }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            }
        """.trimIndent()
        val rows = sparql(q).rows()
        // Several rows per person when a property has more than one value: keep the first of each.
        return rows.groupBy { it.v("p")?.substringAfterLast('/') }.mapNotNull { (qid, rs) ->
            qid ?: return@mapNotNull null
            fun first(k: String) = rs.firstNotNullOfOrNull { it.v(k) }
            val name = first("pLabel")?.takeIf { it != qid } ?: return@mapNotNull null
            val image = first("image")?.replaceFirst("http://", "https://")
            Performer(
                id = "wd:$qid",
                name = name,
                gender = first("genderLabel")?.cap(),
                birthday = first("birth")?.let { Regex("^\\d{4}-\\d{2}-\\d{2}").find(it)?.value },
                careerStart = first("start")?.take(4)?.toIntOrNull(),
                careerEnd = first("end")?.take(4)?.toIntOrNull(),
                nationality = first("countryLabel")?.takeIf { !Regex("^Q\\d+$").matches(it) },
                hair = first("hairLabel")?.removeSuffix(" hair")?.removeSuffix(" Hair")?.cap(),
                eyes = first("eyeLabel")?.removeSuffix(" eyes")?.cap(),
                heightCm = first("heightM")?.toDoubleOrNull()?.let { if (it < 3) (it * 100).toInt() else it.toInt() }
                    ?.takeIf { it in 120..230 },
                image = image?.let { "$it?width=900" },
                thumb = image?.let { "$it?width=400" },
                wiki = first("article"),
            )
        }
    }

    /** Short Wikipedia summary for a profile, or null. */
    suspend fun summary(articleUrl: String): String? = withContext(Dispatchers.IO) {
        val title = articleUrl.substringAfter("/wiki/", "").takeIf { it.isNotEmpty() } ?: return@withContext null
        val enc = URLEncoder.encode(java.net.URLDecoder.decode(title, "UTF-8"), "UTF-8").replace("+", "_")
        val req = Request.Builder().url("https://en.wikipedia.org/api/rest_v1/page/summary/$enc")
            .header("User-Agent", USER_AGENT).build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { AppLog.w("Wikipedia", "Summary HTTP ${r.code}"); return@use null }
                JSONObject(r.body?.string().orEmpty()).optString("extract").takeIf { it.isNotBlank() }
            }
        }.onFailure { AppLog.w("Wikipedia", "Summary failed: ${it.message}") }.getOrNull()
    }

    private fun String.cap() = split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
