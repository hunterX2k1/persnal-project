package app.vault.data

/** Copies of the same video found on different sites, with the best copy first. */
data class HitGroup(val best: Hit, val others: List<Hit>) {
    val all get() = listOf(best) + others
}

object HitGroups {
    private val CLIP_WORDS = Regex("\\b(trailer|teaser|preview|sample|promo|snippet|short|clip|gif|compilation of clips)\\b", RegexOption.IGNORE_CASE)
    private val FULL_WORDS = Regex("\\b(full|complete|full video|full movie|full scene|uncut)\\b", RegexOption.IGNORE_CASE)

    fun seconds(d: String?): Int? = d?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
        ?.fold(0) { acc, v -> acc * 60 + v }

    /** true = full version, false = trailer/clip, null = can't tell. */
    fun isFull(h: Hit): Boolean? {
        val s = seconds(h.duration)
        return when {
            CLIP_WORDS.containsMatchIn(h.title) -> false
            s != null && s < 6 * 60 -> false
            FULL_WORDS.containsMatchIn(h.title) -> true
            s != null && s >= 12 * 60 -> true
            else -> null
        }
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.count { it in b }
        return inter.toDouble() / minOf(a.size, b.size) >= 0.7 && inter >= 2
    }

    /**
     * Groups the same video across sites (by title words) and orders each group:
     * full versions first, then longer, then the site that answered fastest, then ones with a thumbnail.
     * Groups are ordered by relevance, with full versions ahead of clips.
     */
    fun group(hits: List<Hit>): List<HitGroup> {
        val groups = ArrayList<MutableList<Pair<Hit, Set<String>>>>()
        for (h in hits.distinctBy { it.url }) {
            val t = SiteSearch.tokens(h.title)
            val g = groups.firstOrNull { grp -> grp.any { similar(it.second, t) } }
            if (g != null) g += h to t else groups += mutableListOf(h to t)
        }
        val copyOrder = compareByDescending<Hit> { isFull(it) == true }
            .thenBy { isFull(it) == false }
            .thenByDescending { seconds(it.duration) ?: 0 }
            .thenBy { if (it.ms > 0) it.ms else Long.MAX_VALUE }
            .thenByDescending { it.thumb != null }
        return groups.map { g -> g.map { it.first }.sortedWith(copyOrder) }
            .map { HitGroup(it.first(), it.drop(1)) }
            .sortedWith(compareByDescending<HitGroup> { g -> g.all.maxOf { it.score } }
                .thenByDescending { isFull(it.best) == true }
                .thenByDescending { it.all.size })
    }
}
