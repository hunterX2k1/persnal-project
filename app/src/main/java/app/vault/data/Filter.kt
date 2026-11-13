package app.vault.data

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Calendar

enum class Status(val label: String) { ANY("Any"), ACTIVE("Active"), RETIRED("Retired") }
enum class TriState(val label: String) { ANY("Any"), YES("Yes"), NO("No") }
enum class Sort(val label: String, val sql: String) {
    NAME("Name A–Z", "p.name COLLATE NOCASE ASC"),
    YOUNGEST("Youngest", "p.birthday IS NULL, p.birthday DESC"),
    OLDEST("Oldest", "p.birthday IS NULL, p.birthday ASC"),
    NEWEST("Newest debut", "p.careerStart IS NULL, p.careerStart DESC"),
    VETERAN("Longest career", "p.careerStart IS NULL, (COALESCE(p.careerEnd, 9999) - p.careerStart) DESC"),
    SCENES("Most scenes", "p.sceneCount IS NULL, p.sceneCount DESC"),
    UPDATED("Recently updated", "p.updatedAt DESC"),
}

const val MIN_AGE = 18
const val MAX_AGE = 70
const val MIN_HEIGHT = 140
const val MAX_HEIGHT = 200
const val MIN_DEBUT = 1970
val CURRENT_YEAR: Int = Calendar.getInstance().get(Calendar.YEAR)

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
        ).count { it } + genders.size + ethnicities.size + nationalities.size +
            hair.size + eyes.size + cups.size + tags.size

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

        if (favoritesOnly) where += "(p.id IN (SELECT performerId FROM favorites))"
        for (t in tags) { where += "(p.id IN (SELECT performerId FROM tags WHERE tag = ?))"; args += t }

        val sql = "SELECT p.* FROM performers p WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY ${sort.sql}, p.name COLLATE NOCASE LIMIT $limit"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
