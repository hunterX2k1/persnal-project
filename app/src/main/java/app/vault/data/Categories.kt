package app.vault.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * A browsable category. Picking one searches the user's own sites with [queries]
 * and keeps results whose titles mention every word in [words] (step- variants count).
 */
data class Category(val code: String, val label: String, val group: String, val queries: List<String>, val words: List<String>)

object Categories {
    /** Never offered, imported or searched (minors, "real" incest, incapacitated / non-consent, hidden cameras). */
    private val EXCLUDED = listOf(
        "realincest", "ageplay", "forced", "drugged", "drunk", "sleeping", "sleepwalking", "reluctant", "oblivious",
        "voyeur", "hiddencam", "spycam", "teen", "school", "barely", "young", "underage", "minor", "child", "kid", "loli", "preteen",
    )

    fun isExcluded(vararg text: String) = text.any { t -> val n = t.lowercase().filter(Char::isLetterOrDigit); EXCLUDED.any { n.contains(it) } }

    private val WORD = mapOf(
        "father" to "dad", "mother" to "mom", "grandmother" to "grandma", "grandfather" to "grandpa",
        "son" to "son", "sons" to "sons", "daughter" to "daughter", "daughters" to "daughters", "sister" to "sister",
        "sisters" to "sisters", "brother" to "brother", "brothers" to "brothers", "aunt" to "aunt", "aunts" to "aunts",
        "uncle" to "uncle", "nephew" to "nephew", "nephews" to "nephews", "niece" to "niece", "cousin" to "cousin",
        "grandson" to "grandson", "granddaughter" to "granddaughter", "moms" to "moms", "twins" to "twins", "triplets" to "triplets",
    )
    private val STEPPABLE = setOf("mom", "dad", "son", "sons", "daughter", "daughters", "sister", "sisters", "brother", "brothers")

    /** Builds a category from a site-style label such as "Father, Mother, Daughter" or "Mother and Two Sons". */
    fun fromLabel(code: String, label: String, group: String): Category? {
        if (isExcluded(code, label)) return null
        val clean = label.substringBefore('(').ifBlank { label }.replace("/", " ").lowercase()
        val words = clean.split(Regex("[^a-z]+")).mapNotNull { WORD[it] }.distinct()
        val queries = if (words.isEmpty()) listOf(label.replace('-', ' '))
        else listOf(words.joinToString(" "), words.joinToString(" ") { if (it in STEPPABLE) "step$it" else it }).distinct()
        val keep = if (words.isEmpty()) label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 } else words
        // Relations: 2 people = Pairs, 3+ (or "two/three ...") = Groups. Anything else keeps the group it was given.
        val g = when {
            words.isEmpty() || group == "Themes" && words.isEmpty() -> group
            words.size >= 3 || Regex("(?i)\\b(two|three|four|five)\\b").containsMatchIn(label) -> "Groups"
            else -> "Pairs"
        }
        return Category(code, label, g, queries, keep)
    }

    private fun rel(code: String, label: String) = fromLabel(code, label, "Pairs")

    val DEFAULTS: List<Category> = listOfNotNull(
        rel("BS", "Brother, Sister"), rel("MS", "Mother, Son"), rel("FD", "Father, Daughter"), rel("MD", "Mother, Daughter"),
        rel("SS", "Sister, Sister"), rel("BB", "Brother, Brother"), rel("FS", "Father, Son"), rel("CZ", "Cousin"),
        rel("Twins", "Twins"), rel("Triplets", "Triplets"), rel("AN", "Aunt, Nephew"), rel("UN", "Uncle, Niece"),
        rel("GMGS", "Grandmother, Grandson"), rel("GMGD", "Grandmother, Granddaughter"), rel("GFGD", "Grandfather, Granddaughter"),
        rel("FSD", "Father, Son, Daughter"), rel("MSD", "Mother, Son, Daughter"), rel("FMD", "Father, Mother, Daughter"),
        rel("FMS", "Father, Mother, Son"), rel("FMDD", "Father, Mother and Two Daughters"), rel("FMSS", "Father, Mother and Two Sons"),
        rel("FMAD", "Father, Mother, Aunt, Daughter"), rel("FMAS", "Father, Mother, Aunt, Son"), rel("FDD", "Father and Two Daughters"),
        rel("FDDD", "Father and Three Daughters"), rel("FDN", "Father, Daughter, Niece"), rel("FDA", "Father, Daughter, Aunt"),
        rel("FSA", "Father, Son, Aunt"), rel("FSN", "Father, Son, Niece"), rel("MSS", "Mother and Two Sons"), rel("MSSS", "Mother and Three Sons"),
        rel("FSS", "Father and Two Sons"), rel("MSN", "Mother, Son, Nephew"), rel("MDN", "Mother, Daughter, Niece"),
        rel("MDD", "Mother and Two Daughters"), rel("MDDD", "Mother and Three Daughters"), rel("MMS", "Two Moms and Son"),
        rel("MMD", "Two Moms and Daughter"), rel("BBS", "Two Brothers, One Sister"), rel("BSS", "One Brother, Two Sisters"),
        rel("BBB", "Three Brothers"), rel("SSS", "Three Sisters"), rel("SIBCZ", "Siblings with Cousin"), rel("AMS", "Aunt, Mother, Son"),
        rel("AMD", "Aunt, Mother, Daughter"), rel("AMSD", "Aunt, Mother, Son, Daughter"), rel("AAN", "Two Aunts, Nephew"),
        rel("UAN", "Uncle, Aunt, Niece"), rel("UMD", "Uncle, Mother, Daughter"), rel("UMS", "Uncle, Mother, Son"), rel("UFD", "Uncle, Father, Daughter"),
        rel("GMMS", "Grandmother, Mother, Son"), rel("GMMD", "Grandmother, Mother, Daughter"), rel("MSGS", "Mother, Son, Grandson"),
        rel("FSGM", "Father, Son, Grandmother"), rel("GFFD", "Grandfather, Father, Daughter"), rel("MDGM", "Mother, Daughter, Grandmother"),
        fromLabel("Full-Family", "Full Family", "Groups")?.copy(queries = listOf("family", "step family"), words = listOf("family")),
        fromLabel("InLaws", "In-Laws", "Pairs")?.copy(queries = listOf("in law", "mother in law"), words = listOf("law")),
    ) + listOf(
        "Christmas", "Dirty Talk", "Virtual Sex", "Free Use", "Sneaky", "Extreme", "Foot Fetish", "Gaming", "Uncensored JAV",
        "JOI", "Fantasy", "Bondage", "Petite", "Pregnant", "Lactating", "Punishment", "Humiliation", "Femdom", "Threesome",
        "Foursome", "Fivesome", "Office", "Outdoor", "Gangbang", "Orgy", "Interracial Family", "Amateur", "Vintage", "60fps",
        "Quarantine", "POV", "Taboo",
    ).mapNotNull { fromLabel(it.replace(' ', '-'), it, "Themes") }

    /** Special category: videos ranked by the tags you watch most. */
    const val FOR_YOU = "__foryou"

    /** Mood mixes: broader searches with no strict word matching. Adults only, like everything else. */
    val MOODS: List<Category> = listOf(
        Category(FOR_YOU, "For you", "Moods", emptyList(), emptyList()),
        Category("mood-mature-younger", "Mature women & younger men", "Moods", listOf("milf", "older woman younger man"), emptyList()),
        Category("mood-gilf", "GILF", "Moods", listOf("granny", "gilf"), emptyList()),
        Category("mood-cougar", "Cougar", "Moods", listOf("mature", "cougar"), emptyList()),
        Category("mood-stepmom", "Stepmom & stepson", "Moods", listOf("mom son", "stepmom stepson"), listOf("mom", "son")),
        Category("mood-romantic", "Romantic & passionate", "Moods", listOf("romantic", "passionate"), emptyList()),
        Category("mood-intimate", "Intimate", "Moods", listOf("lovemaking", "intimate"), emptyList()),
        Category("mood-sensual", "Sensual", "Moods", listOf("erotic", "sensual"), emptyList()),
        Category("mood-fetish", "Fetish", "Moods", listOf("kink", "fetish"), emptyList()),
    )

    /** Result of reading a saved page: categories found, and how many were left out by the rules above. */
    data class Imported(val found: List<Category>, val hidden: Int)

    /** Link paths that usually mean "category" on video sites: /tag/x, /tags/x, /category/x, /categories/x, /c/x, /niche/x, /genre/x, /channels/x. */
    private val CATEGORY_PATH = Regex("/(?:tags?|categor(?:y|ies)|c|niches?|genres?|channels?|kategorie|kategori)/([^/?#]+)", RegexOption.IGNORE_CASE)

    /** Reads every category link from a saved page of any site (HTML or Chrome .mht). */
    fun importFrom(raw: String): Imported {
        val html = if (raw.contains("Content-Transfer-Encoding: quoted-printable", true)) decodeQuotedPrintable(raw) else raw
        var hidden = 0
        val found = Jsoup.parse(html).select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            // Skip the Wayback Machine's own toolbar links (archived pages keep theirs under /web/<time>/...).
            if (href.contains("archive.org") && !href.contains("/web/")) return@mapNotNull null
            val m = CATEGORY_PATH.find(a.attr("href").substringAfter("/web/").let { if (it.contains("://")) it.substringAfter("://") else it }) ?: return@mapNotNull null
            val slug = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").trim()
            if (slug.isEmpty() || slug.all { it.isDigit() }) return@mapNotNull null
            var text = a.text().trim().replace(Regex("\\s*\\(?[\\d,.]+[kKmM]?\\)?\\s*$"), "") // drop video counts like "(1,234)"
            if (text.isEmpty()) text = slug.replace('-', ' ').replace('_', ' ')
            if (text.startsWith("$slug ")) text = text.removePrefix(slug).trim() // "MS Mother, Son" → "Mother, Son"
            text = text.replace(Regex("^\\W+"), "").take(60)
            if (text.length < 2) return@mapNotNull null
            fromLabel(slug, text, "Themes") ?: run { hidden++; null }
        }.distinctBy { it.code.lowercase() }
        return Imported(found, hidden)
    }

    private fun decodeQuotedPrintable(s: String): String {
        val noSoft = s.replace("=\r\n", "").replace("=\n", "")
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < noSoft.length) {
            val c = noSoft[i]
            if (c == '=' && i + 2 < noSoft.length && noSoft[i + 1].isLetterOrDigit() && noSoft[i + 2].isLetterOrDigit()) {
                val v = noSoft.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
        }
        return out.toString("UTF-8")
    }

    /** Title relevance for a category: share of its words present (stepmom counts as mom). 0–100. */
    fun score(title: String, words: List<String>): Int {
        if (words.isEmpty()) return 50
        val toks = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val hit = words.count { w -> toks.any { t -> t == w || t.startsWith(w) || t.endsWith(w) || t.endsWith(w + "s") } }
        return hit * 100 / words.size
    }
}

/** The user's category list (defaults + imported + added), kept on the phone. */
class CategoryStore(context: Context) {
    private val sp = context.getSharedPreferences("categories", Context.MODE_PRIVATE)

    fun load(): List<Category> = runCatching {
        val a = JSONArray(sp.getString("list", null) ?: return Categories.DEFAULTS)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Category(o.getString("c"), o.getString("l"), o.getString("g"), o.getJSONArray("q").strings(), o.getJSONArray("w").strings())
        }.filterNot { Categories.isExcluded(it.code, it.label) }
    }.getOrDefault(Categories.DEFAULTS)

    fun save(list: List<Category>) {
        val a = JSONArray()
        list.forEach { c -> a.put(JSONObject().put("c", c.code).put("l", c.label).put("g", c.group).put("q", JSONArray(c.queries)).put("w", JSONArray(c.words))) }
        sp.edit().putString("list", a.toString()).apply()
    }

    fun reset() = sp.edit().remove("list").apply()

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
