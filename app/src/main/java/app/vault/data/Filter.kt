package app.vault.data

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Calendar

enum class Status(val label: String) { ANY("Any"), ACTIVE("Active"), RETIRED("Retired") }
enum class TriState(val label: String) { ANY("Any"), YES("Yes"), NO("No") }
enum class Breasts(val label: String) { ANY("Any"), NATURAL("Natural"), ENHANCED("Enhanced") }
enum class Sort(val label: String, val sql: String) {
    NAME("Name A–Z", "p.name COLLATE NOCASE ASC"),
    YOUNGEST("Youngest", "p.birthday IS NULL, p.birthday DESC"),
    OLDEST("Oldest", "p.birthday IS NULL, p.birthday ASC"),
    NEWEST("Newest debut", "p.careerStart IS NULL, p.careerStart DESC"),
    VETERAN("Longest career", "p.careerStart IS NULL, (COALESCE(p.careerEnd, 9999) - p.careerStart) DESC"),
    SCENES("Most scenes", "p.sceneCount IS NULL, p.sceneCount DESC"),
    TALLEST("Tallest", "p.heightCm IS NULL, p.heightCm DESC"),
    SHORTEST("Shortest", "p.heightCm IS NULL, p.heightCm ASC"),
    UPDATED("Recently updated", "p.updatedAt DESC"),
}

const val MIN_AGE = 18
const val MAX_AGE = 70
const val MIN_HEIGHT = 140
const val MAX_HEIGHT = 200
const val MIN_DEBUT = 1970
val CURRENT_YEAR: Int = Calendar.getInstance().get(Calendar.YEAR)
const val MIN_WEIGHT = 40
const val MAX_WEIGHT = 110
const val MIN_BUST = 28
const val MAX_BUST = 46
const val MIN_WAIST = 20
const val MAX_WAIST = 40
const val MIN_HIPS = 28
const val MAX_HIPS = 50
const val MAX_DEBUT_AGE = 45
const val MAX_YEARS_ACTIVE = 30
val CUP_ORDER = listOf("AA", "A", "B", "C", "D", "DD", "DDD", "E", "F", "FF", "G", "GG", "H", "HH", "I", "J", "K")

data class Filter(
    val text: String = "",
    val ageFrom: Int = MIN_AGE,
    val ageTo: Int = MAX_AGE,
    val status: Status = Status.ANY,
    val debutFrom: Int = MIN_DEBUT,
    val debutTo: Int = CURRENT_YEAR,
    val heightFrom: Int = MIN_HEIGHT,
    val heightTo: Int = MAX_HEIGHT,
    val genders: Set<String> = emptySet(),
    val ethnicities: Set<String> = emptySet(),
    val nationalities: Set<String> = emptySet(),
    val hair: Set<String> = emptySet(),
    val eyes: Set<String> = emptySet(),
    val cups: Set<String> = emptySet(),
    val tattoos: TriState = TriState.ANY,
    val piercings: TriState = TriState.ANY,
    val tags: Set<String> = emptySet(),
    val weightFrom: Int = MIN_WEIGHT,
    val weightTo: Int = MAX_WEIGHT,
    val bustFrom: Int = MIN_BUST,
    val bustTo: Int = MAX_BUST,
    val waistFrom: Int = MIN_WAIST,
    val waistTo: Int = MAX_WAIST,
    val hipsFrom: Int = MIN_HIPS,
    val hipsTo: Int = MAX_HIPS,
    val breasts: Breasts = Breasts.ANY,
    val zodiacs: Set<String> = emptySet(),
    val birthplace: String = "",
    val debutAgeFrom: Int = MIN_AGE,
    val debutAgeTo: Int = MAX_DEBUT_AGE,
    val yearsActiveMin: Int = 0,
    val hasPhoto: Boolean = false,
    val favoritesOnly: Boolean = false,
    val sort: Sort = Sort.NAME,
) {
    /** Number of active filters (for the badge), not counting text, sort and favourites. */
    val activeCount: Int
        get() = listOf(
            ageFrom != MIN_AGE || ageTo != MAX_AGE,
            status != Status.ANY,
            debutFrom != MIN_DEBUT || debutTo != CURRENT_YEAR,
            heightFrom != MIN_HEIGHT || heightTo != MAX_HEIGHT,
            tattoos != TriState.ANY, piercings != TriState.ANY,
            weightFrom != MIN_WEIGHT || weightTo != MAX_WEIGHT,
            bustFrom != MIN_BUST || bustTo != MAX_BUST,
            waistFrom != MIN_WAIST || waistTo != MAX_WAIST,
            hipsFrom != MIN_HIPS || hipsTo != MAX_HIPS,
            breasts != Breasts.ANY, birthplace.isNotBlank(),
            debutAgeFrom != MIN_AGE || debutAgeTo != MAX_DEBUT_AGE,
            yearsActiveMin > 0, hasPhoto,
        ).count { it } + genders.size + ethnicities.size + nationalities.size +
            hair.size + eyes.size + cups.size + tags.size + zodiacs.size

    fun toQuery(limit: Int): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        // Hard rule: never show anyone whose known birthday makes them under 18.
        where += "(p.birthday IS NULL OR p.birthday <= date('now', '-18 years'))"

        if (text.isNotBlank()) {
            where += "(p.name LIKE ? OR p.aliases LIKE ?)"
            args += "%${text.trim()}%"; args += "%${text.trim()}%"
        }
        if (ageFrom > MIN_AGE) {
            where += "(p.birthday IS NOT NULL AND p.birthday <= date('now', ?))"; args += "-$ageFrom years"
        }
        if (ageTo < MAX_AGE) {
            where += "(p.birthday IS NOT NULL AND p.birthday > date('now', ?))"; args += "-${ageTo + 1} years"
        }
        when (status) {
            Status.ACTIVE -> { where += "(p.careerEnd IS NULL OR p.careerEnd >= ?)"; args += CURRENT_YEAR - 1 }
            Status.RETIRED -> { where += "(p.careerEnd IS NOT NULL AND p.careerEnd < ?)"; args += CURRENT_YEAR - 1 }
            Status.ANY -> {}
        }
        if (debutFrom > MIN_DEBUT) { where += "(p.careerStart >= ?)"; args += debutFrom }
        if (debutTo < CURRENT_YEAR) { where += "(p.careerStart <= ?)"; args += debutTo }
        if (heightFrom > MIN_HEIGHT) { where += "(p.heightCm >= ?)"; args += heightFrom }
        if (heightTo < MAX_HEIGHT) { where += "(p.heightCm <= ?)"; args += heightTo }

        fun inSet(col: String, set: Set<String>) {
            if (set.isEmpty()) return
            where += "(p.$col IN (${set.joinToString(",") { "?" }}))"
            args.addAll(set)
        }
        inSet("gender", genders); inSet("ethnicity", ethnicities); inSet("nationality", nationalities)
        inSet("hair", hair); inSet("eyes", eyes); inSet("cup", cups)

        fun tri(col: String, t: TriState) {
            val has = "(p.$col IS NOT NULL AND TRIM(p.$col) <> '' AND LOWER(TRIM(p.$col)) NOT IN ('none','no','n/a','unknown'))"
            when (t) {
                TriState.YES -> where += has
                TriState.NO -> where += "(NOT $has)"
                TriState.ANY -> {}
            }
        }
        tri("tattoos", tattoos); tri("piercings", piercings)

        fun range(col: String, from: Int, to: Int, min: Int, max: Int) {
            if (from > min) { where += "(p.$col >= ?)"; args += from }
            if (to < max) { where += "(p.$col <= ?)"; args += to }
        }
        range("weightKg", weightFrom, weightTo, MIN_WEIGHT, MAX_WEIGHT)
        range("bust", bustFrom, bustTo, MIN_BUST, MAX_BUST)
        range("waist", waistFrom, waistTo, MIN_WAIST, MAX_WAIST)
        range("hips", hipsFrom, hipsTo, MIN_HIPS, MAX_HIPS)
        when (breasts) {
            Breasts.NATURAL -> where += "(p.natural = 1)"
            Breasts.ENHANCED -> where += "(p.natural = 0)"
            Breasts.ANY -> {}
        }
        inSet("zodiac", zodiacs)
        if (birthplace.isNotBlank()) { where += "(p.birthplace LIKE ?)"; args += "%${birthplace.trim()}%" }
        val debutAge = "(p.careerStart - CAST(substr(p.birthday, 1, 4) AS INTEGER))"
        if (debutAgeFrom > MIN_AGE) { where += "(p.birthday IS NOT NULL AND p.careerStart IS NOT NULL AND $debutAge >= ?)"; args += debutAgeFrom }
        if (debutAgeTo < MAX_DEBUT_AGE) { where += "(p.birthday IS NOT NULL AND p.careerStart IS NOT NULL AND $debutAge <= ?)"; args += debutAgeTo }
        if (yearsActiveMin > 0) {
            where += "(p.careerStart IS NOT NULL AND (COALESCE(p.careerEnd, ?) - p.careerStart) >= ?)"
            args += CURRENT_YEAR; args += yearsActiveMin
        }
        if (hasPhoto) where += "(COALESCE(p.thumb, p.image) IS NOT NULL)"

        if (favoritesOnly) where += "(p.id IN (SELECT performerId FROM favorites))"
        for (t in tags) { where += "(p.id IN (SELECT performerId FROM tags WHERE tag = ?))"; args += t }

        val sql = "SELECT p.* FROM performers p WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY ${sort.sql}, p.name COLLATE NOCASE LIMIT $limit"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
