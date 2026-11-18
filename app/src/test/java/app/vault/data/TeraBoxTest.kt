package app.vault.data

import org.junit.Assert.*
import org.junit.Test

class TeraBoxTest {
    @Test
    fun recognisesTeraBoxLinks() {
        assertTrue(TeraBox.isTeraBoxLink("https://terabox.com/s/1AbCdEf"))
        assertTrue(TeraBox.isTeraBoxLink("https://www.1024terabox.com/s/1xyz "))
        assertTrue(TeraBox.isTeraBoxLink("https://teraboxapp.com/sharing/link?surl=abc"))
        assertTrue(TeraBox.isTeraBox("dm.terabox.app"))
        assertFalse(TeraBox.isTeraBoxLink("https://example.com/terabox.com/s/1"))
        assertFalse(TeraBox.isTeraBoxLink("terabox link here"))
    }
}
