package app.vault.data

import org.junit.Assert.*
import org.junit.Test

class CategoriesTest {
    @Test
    fun importsFromSavedMhtAndDropsExcluded() {
        // Chrome .mht pages are quoted-printable encoded ("=3D" for "=", soft line breaks "=\n").
        val mht = """
            Content-Type: text/html
            Content-Transfer-Encoding: quoted-printable

            <a href=3D"https://web.archive.org/web/2024/http://site.example/tag/MS">MS <s=
            pan>Mother, Son</span></a>
            <a href=3D"/tag/FMDD">FMDD Father, Mother and Two Daughters</a>
            <a href=3D"/tag/Foot-Fetish">Foot Fetish</a>
            <a href=3D"/tag/Real-Incest">Real Incest</a>
            <a href=3D"/tag/Age-Play">Age Play</a>
            <a href=3D"/tag/Sleeping">Sleeping</a>
        """.trimIndent()
        val cats = Categories.importFrom(mht)
        println(cats)
        assertEquals(listOf("MS", "FMDD", "Foot-Fetish"), cats.map { it.code })
        val ms = cats.first()
        assertEquals("Mother, Son", ms.label)
        assertEquals(listOf("mom son", "stepmom stepson"), ms.queries)
        assertEquals("Pairs", ms.group)
        assertEquals("Groups", cats[1].group)
        assertEquals(listOf("dad", "mom", "daughters"), cats[1].words)
        assertEquals("Themes", cats[2].group)
    }

    @Test
    fun defaultsHaveNoExcludedCategories() {
        assertTrue(Categories.DEFAULTS.size > 80)
        assertTrue(Categories.DEFAULTS.none { Categories.isExcluded(it.code, it.label) })
        val groups = Categories.DEFAULTS.groupingBy { it.group }.eachCount()
        println(groups)
        assertTrue(groups.getValue("Pairs") >= 15 && groups.getValue("Groups") >= 30 && groups.getValue("Themes") >= 25)
        assertEquals("Pairs", Categories.DEFAULTS.first { it.code == "BS" }.group)
        assertEquals("Groups", Categories.DEFAULTS.first { it.code == "MSS" }.group)
    }

    @Test
    fun stepVariantsCountAsMatches() {
        val words = listOf("mom", "son")
        assertEquals(100, Categories.score("Stepmom Helps Her Stepson Study", words))
        assertEquals(100, Categories.score("Mom and son road trip", words))
        assertEquals(50, Categories.score("Stepmom cooking show", words))
    }
}
