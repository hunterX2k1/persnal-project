package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTest {
    @Test fun adBlockSwitchStopsBlockingButKeepsRules() {
        val c = UboCompiler.Compiler()
        c.addList("l", sequenceOf("||ads.example.com^"))
        Filter.setLists(c.build())
        Filter.setUser(Ruleset.EMPTY, emptySet())
        try {
            assertTrue(Filter.isBlocked("ads.example.com"))
            Filter.enabled = false
            assertFalse(Filter.isBlocked("ads.example.com"))
            assertEquals(1, Filter.blockCount) // rules stay loaded for instant re-enable
            Filter.enabled = true
            assertTrue(Filter.isBlocked("ads.example.com"))
        } finally {
            Filter.enabled = true
        }
    }

    @Test fun countryFlags() {
        assertEquals("NL", Flags.code("NL-FREE-12"))
        assertEquals("JP", Flags.code("JP-FREE-3"))
        assertEquals("NL", Flags.code("Netherlands"))
        assertEquals("US", Flags.code("US-FREE-5"))
        assertNull(Flags.code("My server"))           // "My" isn't treated as Malaysia
        assertEquals("🇳🇱", Flags.of("NL-FREE-12")) // 🇳🇱
        assertEquals("🌐", Flags.of("home box"))
    }
}
