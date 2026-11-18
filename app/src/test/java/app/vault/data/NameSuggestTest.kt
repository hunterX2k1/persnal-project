package app.vault.data

import org.junit.Assert.*
import org.junit.Test

class NameSuggestTest {
    private val idx = listOf(
        NameRow("1", "Jane Evans", null, "t", null),
        NameRow("2", "Janet Evanson", "JE, Jan Evans", null, null),
        NameRow("3", "Mia Évans", null, "t", null),
        NameRow("4", "Bob Smith", "Robert Smith", "t", null),
        NameRow("5", "Evangeline Moore", null, null, null),
    )
    private fun names(q: String) = NameSuggest.suggest(idx, q).map { it.row.name }

    @Test fun prefixFirst() = assertEquals("Jane Evans", names("jane e").first())
    @Test fun missingSpaces() = assertEquals("Jane Evans", names("janeevans").first())
    @Test fun lastNameAndAccents() = assertTrue(names("evans").containsAll(listOf("Jane Evans", "Mia Évans")))
    @Test fun alias() = assertEquals("Bob Smith", names("robert").first())
    @Test fun typos() {
        assertEquals("Jane Evans", names("jnae evans").first()) // swapped letters
        assertEquals("Jane Evans", names("jane evnas").first())
        assertEquals("Bob Smith", names("bob smtih").first())
    }
    @Test fun shortQueriesStayStrict() = assertTrue(names("xyz").isEmpty())
}
