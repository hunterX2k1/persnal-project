package com.blockbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LoadBalancerTest {
    private fun tunnel(name: String, latency: Long = -1, up: Boolean = true, active: Int = 0) =
        Tunnel(VpnProfile(name, name, "tor:")).apply {
            if (up) upstream = SocksUpstream(1, "u", "p")
            lastHandshake = System.currentTimeMillis()
            latencyMs = latency
            this.active.set(active)
        }

    @After fun reset() { LoadBalancer.mode = LbMode.OFF; LoadBalancer.reset() }

    @Test fun failoverUsesFirstWorkingServer() {
        LoadBalancer.mode = LbMode.FAILOVER
        val a = tunnel("A", up = false); val b = tunnel("B"); val c = tunnel("C")
        assertSame(b, LoadBalancer.pick("x.com", listOf(a, b, c)))
        b.probeFailures = 2 // failing health checks
        assertSame(c, LoadBalancer.pick("x.com", listOf(a, b, c)))
        c.stop()
        assertNull(LoadBalancer.pick("x.com", listOf(a, b, c))) // kill switch territory
    }

    @Test fun fastestPicksLowestLatency() {
        LoadBalancer.mode = LbMode.FASTEST
        val a = tunnel("A", 120); val b = tunnel("B", 35); val c = tunnel("C", -1)
        assertSame(b, LoadBalancer.pick(null, listOf(a, b, c)))
    }

    @Test fun balancedSpreadsAndSticks() {
        LoadBalancer.mode = LbMode.BALANCED
        val a = tunnel("A", active = 5); val b = tunnel("B", active = 1); val c = tunnel("C", active = 1)
        val first = LoadBalancer.pick("site.com", listOf(a, b, c))!!
        assertEquals(1, first.active.get()) // one of the least-loaded
        // same site sticks to the same server even if load changes
        first.active.set(50)
        assertSame(first, LoadBalancer.pick("site.com", listOf(a, b, c)))
        // a different site goes to the least-loaded remaining server
        val other = LoadBalancer.pick("other.com", listOf(a, b, c))!!
        assertEquals(1, other.active.get())
        // if the sticky server breaks, the site moves
        first.stop()
        val moved = LoadBalancer.pick("site.com", listOf(a, b, c))!!
        assert(moved !== first)
    }
}
