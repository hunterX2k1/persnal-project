package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnGateTest {
    private val csv = javaClass.classLoader!!.getResource("vpngate_sample.csv")!!.readText()

    @Test fun parsesTheRealList() {
        val servers = VpnGate.parse(csv)
        assertEquals(3, servers.size)
        val s = servers.first()
        assertEquals(2, s.code.length)
        assertTrue(s.speedMbps > 0 && s.pingMs >= 0)
        assertTrue(s.proto == "tcp" || s.proto == "udp")
        assertTrue(s.port > 0)
        assertTrue(Ovpn.looksLikeOvpn(s.config))
        assertTrue(servers.zipWithNext().all { (a, b) -> a.score >= b.score }) // best first
        assertTrue(s.profileName.startsWith(s.code)) // so the flag shows
    }

    @Test fun recognisesOpenVpnConfigs() {
        assertTrue(Ovpn.looksLikeOvpn("client\ndev tun\nproto udp\nremote vpn.example.com 1194\n<ca>\nX\n</ca>"))
        assertFalse(Ovpn.looksLikeOvpn("[Interface]\nPrivateKey = x\n[Peer]\nEndpoint = 1.2.3.4:51820"))
    }
}
