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
        val imp = Categories.importFrom(mht)
        assertEquals(3, imp.hidden)
        val cats = imp.found
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
    fun importsFromOtherSiteLayouts() {
        val html = """
            <a href="https://tube.example/categories/foot-fetish/">Foot Fetish (1,234)</a>
            <a href="/category/pov">POV</a>
            <a href="/c/milf-12/">MILF 56K</a>
            <a href="/tags/step-family">Step Family</a>
            <a href="/tags/teen">Teen (18+)</a>
            <a href="/categories/123">123</a>
            <a href="/videos/some-video">Some video</a>
        """.trimIndent()
        val imp = Categories.importFrom(html)
        println(imp)
        assertEquals(listOf("Foot Fetish", "POV", "MILF", "Step Family"), imp.found.map { it.label })
        assertEquals(1, imp.hidden)
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

class SavedSearchesTest {
    @Test
    fun filterRoundTrip() {
        val f = Filter(
            text = "jane", ageFrom = 25, ageTo = 40, status = Status.ACTIVE, genders = setOf("Female"),
            cups = setOf("D", "DD"), tattoos = TriState.NO, breasts = Breasts.NATURAL, zodiacs = setOf("Leo"),
            birthplace = "Texas", yearsActiveMin = 5, hasPhoto = true, sort = Sort.TALLEST, weightTo = 60,
        )
        val back = filterFromJson(f.toJson())
        assertEquals(f, back)
        // favourites screen keeps its own "favourites only" flag
        assertTrue(filterFromJson(f.toJson(), Filter(favoritesOnly = true)).favoritesOnly)
        assertEquals(Filter(), filterFromJson("not json"))
    }
}

class SiteSearchRulesTest {
    @Test
    fun onlyThemesAndMoodsGoToMySites() {
        val byCode = (Categories.DEFAULTS + Categories.MOODS).associateBy { it.code }
        assertTrue(Categories.allowedOnSites(byCode.getValue("mood-romantic")))
        assertTrue(Categories.allowedOnSites(byCode.getValue("mood-mature-younger")))
        assertTrue(Categories.allowedOnSites(byCode.getValue("Foot-Fetish")))
        assertTrue(Categories.allowedOnSites(byCode.getValue("MS")))
        assertTrue(Categories.allowedOnSites(byCode.getValue("mood-stepmom")))
        assertTrue(Categories.allowedOnSites(byCode.getValue("Taboo")))
        assertTrue(Categories.allowedOnSites(byCode.getValue("Interracial-Family")))
        assertFalse(Categories.allowedOnSites(byCode.getValue(Categories.FOR_YOU)))
        assertTrue(Categories.allowedText("passionate massage"))
        assertTrue(Categories.allowedText("step sister"))
        assertFalse(Categories.unsafeTitle("Stepmom and son"))
        assertFalse(Categories.unsafeTitle("Cougar seduces a younger guy"))
        assertTrue(Categories.unsafeTitle("young boy"))
        assertTrue(Categories.isExcluded("young girl"))
        assertFalse(Categories.unsafeTitle("Romantic evening with Jane"))
    }
}
