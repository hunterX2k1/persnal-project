package app.vault.data

import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

fun Performer.age(): Int? = birthday?.let {
    runCatching { Period.between(LocalDate.parse(it), LocalDate.now()).years }.getOrNull()
}

fun Performer.isActive(): Boolean = careerEnd == null || careerEnd >= CURRENT_YEAR - 1

fun Performer.careerText(): String? = when {
    careerStart == null && careerEnd == null -> null
    careerEnd == null || careerEnd >= CURRENT_YEAR - 1 -> "${careerStart ?: "?"} – present"
    else -> "${careerStart ?: "?"} – $careerEnd"
}

fun Performer.heightText(): String? = heightCm?.let {
    val inches = (it / 2.54).toInt()
    "$it cm (${inches / 12}'${inches % 12}\")"
}

fun Performer.weightText(): String? = weightKg?.let { "$it kg (${(it * 2.2046).toInt()} lb)" }

fun Performer.birthdayText(): String? = birthday?.let {
    runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrNull()
}

/** Western zodiac sign for a yyyy-MM-dd birthday. */
fun zodiacOf(birthday: String?): String? {
    val d = birthday?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val md = d.monthValue * 100 + d.dayOfMonth
    return when {
        md >= 1222 || md <= 119 -> "Capricorn"
        md <= 218 -> "Aquarius"
        md <= 320 -> "Pisces"
        md <= 419 -> "Aries"
        md <= 520 -> "Taurus"
        md <= 620 -> "Gemini"
        md <= 722 -> "Cancer"
        md <= 822 -> "Leo"
        md <= 922 -> "Virgo"
        md <= 1022 -> "Libra"
        md <= 1121 -> "Scorpio"
        else -> "Sagittarius"
    }
}

fun Performer.debutAge(): Int? {
    val by = birthday?.take(4)?.toIntOrNull() ?: return null
    return careerStart?.let { it - by }?.takeIf { it in 18..80 }
}

fun Performer.measurementsText(): String? = measurements ?: if (bust != null || waist != null || hips != null)
    "${bust ?: "?"}${cup.orEmpty()}-${waist ?: "?"}-${hips ?: "?"}" else null

/** Fill this profile's missing details from another source's copy of the same person. */
fun Performer.fillFrom(o: Performer): Performer = copy(
    aliases = aliases ?: o.aliases, gender = gender ?: o.gender, birthday = birthday ?: o.birthday,
    careerStart = careerStart ?: o.careerStart, careerEnd = careerEnd ?: o.careerEnd, ethnicity = ethnicity ?: o.ethnicity,
    nationality = nationality ?: o.nationality, hair = hair ?: o.hair, eyes = eyes ?: o.eyes, heightCm = heightCm ?: o.heightCm,
    weightKg = weightKg ?: o.weightKg, measurements = measurements ?: o.measurements, cup = cup ?: o.cup, bust = bust ?: o.bust,
    waist = waist ?: o.waist, hips = hips ?: o.hips, natural = natural ?: o.natural, zodiac = zodiac ?: o.zodiac,
    birthplace = birthplace ?: o.birthplace, tattoos = tattoos ?: o.tattoos, piercings = piercings ?: o.piercings,
    bio = bio ?: o.bio, image = image ?: o.image, thumb = thumb ?: o.thumb, sceneCount = sceneCount ?: o.sceneCount,
    wiki = wiki ?: o.wiki, links = listOfNotNull(links, o.links).joinToString("\n").lines().filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { null },
)

/** (site name, url) pairs from [Performer.links]. */
fun Performer.linkList(): List<Pair<String, String>> = links?.lines()?.mapNotNull { l ->
    l.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
}.orEmpty()

fun formatDuration(sec: Int?): String? = sec?.takeIf { it > 0 }?.let {
    if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60)
}
