package com.blockbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun parse(vararg lines: String): Pair<Set<String>, Set<String>> {
        val b = HashSet<String>(); val a = HashSet<String>()
        lines.forEach { ListParser.parseLine(it, b, a) }
        return b to a
    }

    @Test fun parsesAllListFormats() {
        val (b, a) = parse(
            "# comment", "! adblock comment", "[Adblock Plus 2.0]",
            "0.0.0.0 ads.one.com", "127.0.0.1 localhost", "0.0.0.0 0.0.0.0",
            "127.0.0.1 two.com three.com # trailing",
            "||tracker.net^", "||imp.net^\$important", "||skip.net^\$third-party,client=1",
            "@@||good.tracker.net^", "plain.org", "*.wild.io",
            "example.com##.ad-banner", "/regex.*/", "||path.com/ads.js",
            "1.2.3.4", ":: v6host.com",
        )
        assertEquals(
            setOf("ads.one.com", "two.com", "three.com", "tracker.net", "imp.net", "plain.org",
                "wild.io", "v6host.com"),
            b,
        )
        assertEquals(setOf("good.tracker.net"), a)
    }

    @Test fun userDomainNormalises() {
        assertEquals("ads.x.com", ListParser.userDomain("https://Ads.X.com/path?q=1"))
        assertEquals("x.com", ListParser.userDomain("||x.com^"))
        assertEquals("x.com", ListParser.userDomain("*.x.com"))
        assertNull(ListParser.userDomain("# note"))
        assertNull(ListParser.userDomain("not a domain"))
    }

    @Test fun filterPriorityAndSubdomains() {
        Filter.setLists(setOf("tracker.net", "ads.com"), setOf("ok.tracker.net"))
        Filter.setUserRules(setOf("evil.org"), setOf("ads.com"))
        assertTrue(Filter.isBlocked("tracker.net"))
        assertTrue(Filter.isBlocked("a.b.tracker.net."))
        assertFalse(Filter.isBlocked("ok.tracker.net"))
        assertFalse(Filter.isBlocked("x.ok.tracker.net"))
        assertFalse(Filter.isBlocked("ads.com"))          // user allow beats list
        assertTrue(Filter.isBlocked("cdn.evil.org"))       // user block
        assertFalse(Filter.isBlocked("nottracker.net"))   // no partial-label match
        assertFalse(Filter.isBlocked("google.com"))
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
