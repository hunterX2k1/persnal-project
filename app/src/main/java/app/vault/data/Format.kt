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

fun formatDuration(sec: Int?): String? = sec?.takeIf { it > 0 }?.let {
    if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60)
}
