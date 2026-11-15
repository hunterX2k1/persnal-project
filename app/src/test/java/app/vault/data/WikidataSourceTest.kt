package app.vault.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live check against Wikidata/Wikipedia (needs network). */
class WikidataSourceTest {
    @Test
    fun fetchesAndParsesProfiles() = runBlocking {
        try { check() } catch (e: Exception) {
            if (e !is ApiException && e !is java.io.IOException) throw e
            // Live service: skip (not fail) when Wikidata rate-limits this machine.
            org.junit.Assume.assumeTrue("Wikidata busy: ${e.message}", false)
        }
    }

    private suspend fun check() {
        val src = WikidataSource()
        val ids = src.allIds()
        println("ids: ${ids.size}")
        assertTrue(ids.size > 5000)
        val batch = src.details(ids.take(Repo.FREE_BATCH))
        println("parsed ${batch.size} of ${Repo.FREE_BATCH}")
        assertTrue(batch.size > Repo.FREE_BATCH * 0.8)
        fun pct(f: (Performer) -> Any?) = "${batch.count { f(it) != null } * 100 / batch.size}%"
        println("birthday ${pct { it.birthday }}, country ${pct { it.nationality }}, image ${pct { it.image }}, height ${pct { it.heightCm }}, hair ${pct { it.hair }}, eyes ${pct { it.eyes }}, debut ${pct { it.careerStart }}, wiki ${pct { it.wiki }}")
        batch.filter { it.image != null && it.heightCm != null }.take(3).forEach { println(it) }
        val withWiki = batch.first { it.wiki != null }
        val bio = src.summary(withWiki.wiki!!)
        println("bio for ${withWiki.name}: ${bio?.take(120)}")
        // Not asserted: Wikipedia rate-limits shared servers; the app shows no bio in that case.
    }
}
