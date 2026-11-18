package com.netsplit.app.vpn

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Simulated mobile link: a bottleneck queue in front of a link whose speed changes over time.
 * YouTube always wants more (its arrival rate equals its limit); the game sends a little.
 * Checks that the game's extra ping stays low while YouTube still gets most of the spare speed.
 */
class AutoTunerTest {
    private class Sim(val thresholdMs: Double, seed: Int = 7) {
        val rnd = Random(seed)
        val tuner = AutoTuner(priorityMode = true, splitA = 50, thresholdMs = thresholdMs, initialBytesPerSec = 250_000.0)
        var queue = 0.0 // bytes waiting at the bottleneck
        val game = 15_000.0
        var t = 0L

        /** Runs [seconds] with link speed [cap]; returns (avg extra queue delay ms, avg youtube bytes/s) for the second half. */
        fun run(seconds: Int, cap: Double, baseRtt: Double = 40.0): Pair<Double, Double> {
            var delaySum = 0.0; var ytSum = 0.0; var n = 0
            val steps = seconds * 100
            var ytBytes = 0.0; var totBytes = 0.0
            for (i in 0 until steps) {
                val limit = tuner.ctl.value
                val arrive = (limit + game) * 0.01
                val served = minOf(queue + arrive, cap * 0.01)
                queue = queue + arrive - served
                // youtube's share of what was delivered
                ytBytes += served * limit / (limit + game); totBytes += served
                t += 10
                if (i >= steps / 2) { delaySum += queue / cap * 1000; ytSum += limit; n++ }
                if (t % 500 == 0L) {
                    val rtt = baseRtt + queue / cap * 1000 + rnd.nextDouble() * 12 // radio jitter
                    val lost = rnd.nextDouble() < 0.02 || rtt > 400 // the app's probe timeout
                    tuner.tick(t, if (lost) null else rtt, totBytes / 0.5, doubleArrayOf(minOf(ytBytes / 0.5, limit)))
                    ytBytes = 0.0; totBytes = 0.0
                }
            }
            return delaySum / n to ytSum / n
        }
    }

    @Test
    fun followsChangingSpeed() {
        val sim = Sim(thresholdMs = 35.0)
        // 20 Mbps link
        val (d1, yt1) = sim.run(60, 2_500_000.0)
        println("20 Mbps: extra delay %.1f ms, youtube %.1f Mbps".format(d1, yt1 * 8 / 1e6))
        assertTrue("delay $d1", d1 < 35)
        assertTrue("youtube should get most of the link, got $yt1", yt1 > 1_500_000)

        // Signal drops to 4 Mbps (moving / crowded tower)
        val (d2, yt2) = sim.run(30, 500_000.0)
        println("4 Mbps:  extra delay %.1f ms, youtube %.1f Mbps".format(d2, yt2 * 8 / 1e6))
        assertTrue("delay $d2", d2 < 35)
        assertTrue(yt2 < 500_000 && yt2 > 200_000)

        // Back to a fast 5G cell: 40 Mbps
        val (d3, yt3) = sim.run(90, 5_000_000.0)
        println("40 Mbps: extra delay %.1f ms, youtube %.1f Mbps".format(d3, yt3 * 8 / 1e6))
        assertTrue("delay $d3", d3 < 35)
        assertTrue("should grow back, got $yt3", yt3 > 3_000_000)
        println("live speed estimate: %.1f Mbps".format(sim.tuner.linkBytesPerSec * 8 / 1e6))
    }

    @Test
    fun strongerProtectionMeansLowerDelay() {
        val loose = Sim(60.0).run(90, 1_250_000.0)
        val strict = Sim(20.0).run(90, 1_250_000.0)
        println("balanced: %.1f ms, max: %.1f ms".format(loose.first, strict.first))
        assertTrue(strict.first <= loose.first)
        assertTrue(strict.first < 20)
    }

    @Test
    fun holdsWhenProbesBlockedAndRecovers() {
        val t = AutoTuner(true, 50, 35.0, 300_000.0)
        var now = 0L
        fun step(rtt: Double?) { now += 500; t.tick(now, rtt, t.ctl.value, doubleArrayOf(t.ctl.value)) }
        repeat(10) { step(40.0) }
        val before = t.ctl.value
        repeat(40) { step(null) }        // probes stop answering
        val after = t.ctl.value
        repeat(60) { step(null) }
        assertTrue("must hold once probes are gone", t.ctl.value == after)
        repeat(120) { step(40.0) }       // probes are back and the line is clear
        println("before %.0f, during outage %.0f, recovered %.0f".format(before, after, t.ctl.value))
        assertTrue(t.ctl.value > 0.8 * before)
    }

    /**
     * Game asks for 3 Mbps (match loading) for 20 s on a 10 Mbps line while YouTube is hungry.
     * With the game's traffic visible, YouTube must step aside quickly so the game gets what it asks for.
     */
    private fun surge(watchGame: Boolean): Triple<Double, Double, Double> {
        val cap = 1_250_000.0
        val tuner = AutoTuner(true, 50, 35.0, 600_000.0)
        val rnd = Random(3)
        var queue = 0.0; var t = 0L
        var ytB = 0.0; var gB = 0.0; var totB = 0.0
        var gameSurgeGot = 0.0; var gameSurgeN = 0; var delayMax = 0.0
        for (i in 0 until 60 * 100) {
            val sec = i / 100.0
            val demand = if (sec in 30.0..50.0) 375_000.0 else 12_000.0
            val yt = tuner.ctl.value
            val arrive = (yt + demand) * 0.01
            val served = minOf(queue + arrive, cap * 0.01)
            queue += arrive - served
            val gameServed = served * demand / (yt + demand)
            gB += gameServed; ytB += served - gameServed; totB += served
            if (sec in 33.0..50.0) { gameSurgeGot += gameServed / 0.01; gameSurgeN++ }
            if (sec >= 31.0) delayMax = maxOf(delayMax, queue / cap * 1000)
            t += 10
            if (t % 500 == 0L) {
                val rtt = 40 + queue / cap * 1000 + rnd.nextDouble() * 10
                tuner.tick(t, if (rtt > 400) null else rtt, totB / 0.5, doubleArrayOf(ytB / 0.5), if (watchGame) gB / 0.5 else null)
                ytB = 0.0; gB = 0.0; totB = 0.0
            }
        }
        return Triple(gameSurgeGot / gameSurgeN, delayMax, tuner.ctl.value)
    }

    @Test
    fun gameGetsMoreWhenItAsks() {
        val (got, delay, ytAfter) = surge(watchGame = true)
        val (gotBlind, delayBlind, _) = surge(watchGame = false)
        println("game wants 3.0 Mbps → gets %.2f Mbps (worst extra ping %.0f ms) with game boost".format(got * 8 / 1e6, delay))
        println("                    → gets %.2f Mbps (worst extra ping %.0f ms) without".format(gotBlind * 8 / 1e6, delayBlind))
        println("youtube 10 s after the surge: %.1f Mbps".format(ytAfter * 8 / 1e6))
        assertTrue("game got $got", got > 0.9 * 375_000)
        assertTrue(got >= gotBlind)
        assertTrue(delay <= delayBlind)
        assertTrue("youtube should recover after the game calms down", ytAfter > 600_000)
    }
}
