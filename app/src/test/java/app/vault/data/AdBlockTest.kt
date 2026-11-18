package app.vault.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AdBlockTest {
    @Test
    fun blocksAdNetworksButNotNormalSites() = runBlocking {
        AdBlock.load(Files.createTempDirectory("bl").toFile()) // downloads the public lists; built-in list works offline
        println("ad hosts ${AdBlock.adHostCount}, unsafe ${AdBlock.unsafeCount}")
        assertTrue(AdBlock.isAd("syndication.exoclick.com"))   // subdomain of a built-in adult ad network
        assertTrue(AdBlock.isAd("ads.trafficjunky.net"))
        assertFalse(AdBlock.isAd("example.com"))
        assertFalse(AdBlock.isAd("theporndb.net"))
        assertEquals("example.co.uk", AdBlock.site("www.cdn.example.co.uk"))
        assertEquals("example.com", AdBlock.site("video.example.com"))
    }
}
