package app.vault.data

import java.text.Normalizer

/** Light row for typing suggestions (kept in memory; ~1 MB for 15k names). */
data class NameRow(val id: String, val name: String, val aliases: String?, val thumb: String?, val image: String?)

data class Suggestion(val row: NameRow, val rank: Int, val matchedAlias: String? = null)

/**
 * Name autocomplete with typo tolerance:
 *  0 exact · 1 name starts with it (spaces optional, so "janedoe" finds "Jane Doe")
 *  2 a word in the name starts with it · 3 an alias starts with it · 4 contains it
 *  5+ close spelling (1–2 letters off), so small typos still find the person.
 */
object NameSuggest {
    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun suggest(index: List<NameRow>, query: String, limit: Int = 8): List<Suggestion> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val qs = q.replace(" ", "")
        val maxDist = when { qs.length <= 3 -> 0; qs.length <= 6 -> 1; else -> 2 }
        val out = ArrayList<Suggestion>()
        for (r in index) {
            val n = norm(r.name)
            val ns = n.replace(" ", "")
            var rank = when {
                n == q || ns == qs -> 0
                n.startsWith(q) || ns.startsWith(qs) -> 1
                n.split(' ').any { it.startsWith(q) } -> 2
                else -> -1
            }
            var alias: String? = null
            if (rank < 0 && r.aliases != null) {
                alias = r.aliases.split(',').map { it.trim() }.firstOrNull { a -> norm(a).let { it.startsWith(q) || it.replace(" ", "").startsWith(qs) } }
                if (alias != null) rank = 3
            }
            if (rank < 0 && qs.length >= 3 && ns.contains(qs)) rank = 4
            if (rank < 0 && maxDist > 0) {
                // compare with the start of the name (same length as typed) and with each word
                val d = minOf(
                    dist(qs, ns.take(qs.length)),
                    dist(qs, ns.take(qs.length + 1)),
                    n.split(' ').minOf { dist(q, it) },
                )
                if (d <= maxDist) rank = 5 + d
            }
            if (rank >= 0) out += Suggestion(r, rank, alias)
        }
        return out.sortedWith(compareBy<Suggestion> { it.rank }.thenBy { it.row.image == null && it.row.thumb == null }.thenBy { it.row.name.length })
            .distinctBy { it.row.id }
            .take(limit)
    }

    /** Edit distance with adjacent swaps (Damerau–Levenshtein, optimal string alignment). */
    fun dist(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
