package com.blockbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun compile(vararg lists: Pair<String, String>): Ruleset {
        val c = UboCompiler.Compiler()
        for ((key, text) in lists) c.addList(key, text.trimIndent().lineSequence())
        return c.build()
    }

    @Test fun parsesUboSyntaxAndSkipsBrowserOnlyRules() {
        val r = compile("l" to """
            ! Title: test
            [Adblock Plus 2.0]
            # hosts comment
            0.0.0.0 ads.one.com
            127.0.0.1 localhost
            plain.org
            ||tracker.net^
            ||pop.net^${'$'}popup,third-party
            ||imp.net^${'$'}important
            ||typed.net^${'$'}script
            ||site.net^${'$'}domain=example.com
            ||path.com/ads.js
            @@||good.tracker.net^
            @@||cosm.net^${'$'}generichide
            example.com##.ad-banner
            youtube.com##+js(json-prune, ads)
            /regex.*/
        """)
        assertEquals(setOf("ads.one.com", "plain.org", "tracker.net", "pop.net"), r.block.keys)
        assertEquals(setOf("imp.net"), r.important.keys)
        assertEquals(setOf("good.tracker.net"), r.allow.keys)
    }

    @Test fun preprocessorFollowsUboOnChromiumMobile() {
        val r = compile("l" to """
            !#if env_firefox
            ||firefox-only.com^
            !#else
            ||not-firefox.com^
            !#endif
            !#if env_mobile && !ext_ubol
            ||mobile.com^
            !#endif
            !#if (env_safari || cap_html_filtering)
            ||nope.com^
            !#endif
        """)
        assertEquals(setOf("not-firefox.com", "mobile.com"), r.block.keys)
        assertTrue(UboCompiler.evalCondition("!(env_firefox || env_safari) && env_chromium"))
    }

    @Test fun badfilterCancelsAcrossLists() {
        val r = compile(
            "a" to "||bad.com^\n||keep.com^\n||x.com^${'$'}third-party",
            "b" to "||bad.com^${'$'}badfilter\n||x.com^${'$'}3p,badfilter",
        )
        assertEquals(setOf("keep.com"), r.block.keys)
        assertEquals("a", r.block["keep.com"])
    }

    @Test fun matchOrderLikeUbo() {
        Filter.setLists(compile(
            "easylist" to "||tracker.net^\n||ads.com^\n@@||ok.tracker.net^\n||must.com^${'$'}important\n@@||must.com^",
        ))
        val c = UboCompiler.Compiler()
        c.addList(Filter.BY_USER, sequenceOf("||evil.org^", "@@||ads.com^"))
        Filter.setUser(c.build(), setOf("trusted.net"))

        assertEquals("easylist", Filter.match("a.b.tracker.net.").by)
        assertTrue(Filter.isBlocked("tracker.net"))
        assertFalse(Filter.isBlocked("x.ok.tracker.net"))     // list exception
        assertTrue(Filter.isBlocked("must.com"))               // ${'$'}important beats exception
        assertFalse(Filter.isBlocked("ads.com"))               // my exception beats list block
        assertEquals(Filter.BY_USER, Filter.match("cdn.evil.org").by)
        assertEquals(Filter.BY_TRUSTED, Filter.match("trusted.net").by)
        assertFalse(Filter.isBlocked("nottracker.net"))       // no partial-label match
        assertFalse(Filter.isBlocked("google.com"))
    }

    @Test fun hostOfNormalises() {
        assertEquals("ads.x.com", Filter.hostOf("https://Ads.X.com/path?q=1"))
        assertEquals("x.com", Filter.hostOf("*.x.com"))
        assertNull(Filter.hostOf("# note"))
        assertNull(Filter.hostOf("not a domain"))
    }

    private fun dnsQuery(name: String, type: Int): ByteArray {
        val out = mutableListOf<Byte>(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)
        for (label in name.split('.')) {
            out += label.length.toByte(); out += label.toByteArray().toList()
        }
        out += 0; out += 0; out += type.toByte(); out += 0; out += 1
        return out.toByteArray()
    }

    @Test fun blockedReplyIsValidDns() {
        val q = dnsQuery("ads.example.com", Dns.TYPE_A)
        val question = Dns.parseQuestion(q)!!
        assertEquals("ads.example.com", question.name)
        assertEquals(Dns.TYPE_A, question.type)
        val r = Dns.blockedReply(q, question)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertTrue((r[2].toInt() and 0x80) != 0)          // is a response
        assertEquals(1, r[7].toInt())                      // one answer
        assertEquals(question.end + 16, r.size)            // A record with 0.0.0.0
        assertNull(Dns.parseQuestion(r))                   // responses aren't treated as queries

        val r6 = Dns.blockedReply(dnsQuery("x.com", Dns.TYPE_AAAA), Dns.parseQuestion(dnsQuery("x.com", Dns.TYPE_AAAA))!!)
        assertEquals(1, r6[7].toInt())
        val https = dnsQuery("x.com", 65)
        val rh = Dns.blockedReply(https, Dns.parseQuestion(https)!!)
        assertEquals(0, rh[7].toInt())                     // empty answer for other types
    }

    private fun ipv4Packet(payload: ByteArray): ByteArray {
        val req = UdpPacket(false, byteArrayOf(10, 111, 222.toByte(), 2), byteArrayOf(10, 111, 222.toByte(), 1), 53, 40000, payload)
        // buildReply swaps src/dst, so this yields a packet from .1:40000 to .2:53
        return Packets.buildReply(req, payload)
    }

    @Test fun ipv4RoundTrip() {
        val payload = dnsQuery("chrome.com", Dns.TYPE_A)
        val raw = ipv4Packet(payload)
        assertEquals(0, Packets.fold(Packets.sum(raw, 0, 20)))   // header checksum valid
        val pkt = Packets.parseUdp(raw, raw.size)!!
        assertEquals(53, pkt.dstPort); assertEquals(40000, pkt.srcPort)
        assertArrayEquals(payload, pkt.payload)
        val reply = Packets.parseUdp(Packets.buildReply(pkt, byteArrayOf(1, 2, 3)), 31)!!
        assertEquals(40000, reply.dstPort); assertEquals(53, reply.srcPort)
        assertArrayEquals(pkt.src, reply.dst)
    }

    @Test fun ipv6ChecksumValid() {
        val src = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val dst = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val payload = dnsQuery("odd.example.org", Dns.TYPE_AAAA) + byteArrayOf(7) // odd length
        val raw = Packets.buildReply(UdpPacket(true, dst, src, 53, 5555, payload), payload)
        val udpLen = raw.size - 40
        val verify = Packets.sum(raw, 8, 32) + udpLen + 17 + Packets.sum(raw, 40, udpLen)
        assertEquals(0, Packets.fold(verify))
        val pkt = Packets.parseUdp(raw, raw.size)
        assertNotNull(pkt)
        assertTrue(pkt!!.v6)
        assertArrayEquals(payload, pkt.payload)
    }

    @Test fun ignoresNonUdp() {
        val raw = ipv4Packet(dnsQuery("a.com", 1)).also { it[9] = 6 } // TCP
        assertNull(Packets.parseUdp(raw, raw.size))
    }
}
