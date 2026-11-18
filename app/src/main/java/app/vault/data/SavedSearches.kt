package app.vault.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A named filter the user saved. [kind] = "discover" (performer filters) or "categories" (category stack + words). */
data class SavedSearch(val name: String, val kind: String, val data: String)

class SavedSearches(context: Context) {
    private val sp = context.getSharedPreferences("saved_searches", Context.MODE_PRIVATE)
    val all = MutableStateFlow(read())

    fun of(kind: String) = all.value.filter { it.kind == kind }

    fun save(s: SavedSearch) {
        all.value = all.value.filterNot { it.kind == s.kind && it.name.equals(s.name, true) } + s
        write()
    }

    fun remove(s: SavedSearch) { all.value = all.value - s; write() }

    private fun read(): List<SavedSearch> = attempt {
        val a = JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SavedSearch(o.getString("n"), o.getString("k"), o.getString("d")) } }
    }.getOrDefault(emptyList())

    private fun write() {
        val a = JSONArray()
        all.value.forEach { a.put(JSONObject().put("n", it.name).put("k", it.kind).put("d", it.data)) }
        sp.edit().putString("list", a.toString()).apply()
    }
}

// ---- Filter <-> JSON (unknown or missing fields fall back to defaults, so old saves keep working) ----

private fun JSONObject.set(k: String, v: Set<String>) = put(k, JSONArray(v.toList()))
private fun JSONObject.strSet(k: String): Set<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
private inline fun <reified E : Enum<E>> JSONObject.enumOr(k: String, d: E): E = optString(k).let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: d

fun Filter.toJson(): String = JSONObject()
    .put("text", text).put("ageFrom", ageFrom).put("ageTo", ageTo).put("status", status.name)
    .put("debutFrom", debutFrom).put("debutTo", debutTo).put("heightFrom", heightFrom).put("heightTo", heightTo)
    .set("genders", genders).set("ethnicities", ethnicities).set("nationalities", nationalities)
    .set("hair", hair).set("eyes", eyes).set("cups", cups)
    .put("tattoos", tattoos.name).put("piercings", piercings.name).set("tags", tags)
    .put("weightFrom", weightFrom).put("weightTo", weightTo).put("bustFrom", bustFrom).put("bustTo", bustTo)
    .put("waistFrom", waistFrom).put("waistTo", waistTo).put("hipsFrom", hipsFrom).put("hipsTo", hipsTo)
    .put("breasts", breasts.name).set("zodiacs", zodiacs).put("birthplace", birthplace)
    .put("debutAgeFrom", debutAgeFrom).put("debutAgeTo", debutAgeTo).put("yearsActiveMin", yearsActiveMin)
    .put("hasPhoto", hasPhoto).put("sort", sort.name)
    .toString()

fun filterFromJson(json: String, base: Filter = Filter()): Filter = attempt {
    val o = JSONObject(json)
    val d = Filter()
    base.copy(
        text = o.optString("text", d.text),
        ageFrom = o.optInt("ageFrom", d.ageFrom), ageTo = o.optInt("ageTo", d.ageTo),
        status = o.enumOr("status", d.status),
        debutFrom = o.optInt("debutFrom", d.debutFrom), debutTo = o.optInt("debutTo", d.debutTo),
        heightFrom = o.optInt("heightFrom", d.heightFrom), heightTo = o.optInt("heightTo", d.heightTo),
        genders = o.strSet("genders"), ethnicities = o.strSet("ethnicities"), nationalities = o.strSet("nationalities"),
        hair = o.strSet("hair"), eyes = o.strSet("eyes"), cups = o.strSet("cups"),
        tattoos = o.enumOr("tattoos", d.tattoos), piercings = o.enumOr("piercings", d.piercings), tags = o.strSet("tags"),
        weightFrom = o.optInt("weightFrom", d.weightFrom), weightTo = o.optInt("weightTo", d.weightTo),
        bustFrom = o.optInt("bustFrom", d.bustFrom), bustTo = o.optInt("bustTo", d.bustTo),
        waistFrom = o.optInt("waistFrom", d.waistFrom), waistTo = o.optInt("waistTo", d.waistTo),
        hipsFrom = o.optInt("hipsFrom", d.hipsFrom), hipsTo = o.optInt("hipsTo", d.hipsTo),
        breasts = o.enumOr("breasts", d.breasts), zodiacs = o.strSet("zodiacs"), birthplace = o.optString("birthplace", ""),
        debutAgeFrom = o.optInt("debutAgeFrom", d.debutAgeFrom), debutAgeTo = o.optInt("debutAgeTo", d.debutAgeTo),
        yearsActiveMin = o.optInt("yearsActiveMin", 0), hasPhoto = o.optBoolean("hasPhoto", false),
        sort = o.enumOr("sort", d.sort),
    )
}.getOrDefault(base)

/** Categories save: picked category codes + typed words. */
fun categorySearchJson(codes: List<String>, text: String): String = JSONObject().put("codes", JSONArray(codes)).put("text", text).toString()
fun categorySearchFrom(json: String): Pair<List<String>, String> = attempt {
    val o = JSONObject(json)
    val a = o.optJSONArray("codes")
    (0 until (a?.length() ?: 0)).map { a!!.getString(it) } to o.optString("text", "")
}.getOrDefault(emptyList<String>() to "")
