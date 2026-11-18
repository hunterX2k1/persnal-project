package com.netsplit.app.vpn

import kotlin.math.max
import kotlin.math.min

/**
 * Adjusts the limits live from what the connection is doing right now.
 *
 * Signal: round-trip time of tiny probes (sent outside the VPN, so they wait in the same mobile queue as
 * the game's packets). When the limited app pushes more than the link can carry *at this moment*, the
 * queue grows and the ping rises above its idle baseline. Then we cut fast; while the ping stays low and
 * the app wants more, we raise the limit — quickly at first, gently near the level that caused lag before.
 *
 * Pure logic (no Android, no clock) so it can be unit tested with a simulated link.
 */
class AutoTuner(
    var priorityMode: Boolean,
    /** Split mode: share (0-100) of limiter 0; limiter 1 gets the rest. */
    var splitA: Int,
    /** Extra ping (ms above baseline) we tolerate before cutting. */
    var thresholdMs: Double,
    initialBytesPerSec: Double,
) {
    /** Priority mode: limit of the background app. Split mode: total budget shared by the two apps. */
    val ctl = Aimd(initialBytesPerSec, min = if (priorityMode) 40_000.0 else 100_000.0, max = 125_000_000.0)

    // --- live state (read by the UI)
    var baselineMs: Double? = null; private set
    var pingMs: Double? = null; private set
    var extraMs = 0.0; private set
    /** Estimated current speed of the whole connection, bytes/s. */
    var linkBytesPerSec = initialBytesPerSec * 2; private set
    var probesOk = false; private set

    // --- game demand (priority mode, when the game's traffic is visible)
    /** Recent peak of the game's traffic (bytes/s), decays over a few seconds. */
    var gameNeed = 0.0; private set
    /** Bytes/s currently kept free for the game. 0 when the game is idle / not watched. */
    var gameReserve = 0.0; private set
    /** True while the game is asking for clearly more than usual (loading, downloading a match...). */
    var gameSurge = false; private set
    private var gameAvg = 0.0
    private var lastGameActive = Long.MIN_VALUE / 2

    private val history = ArrayDeque<Pair<Long, Double>>() // (time, rtt) for the rolling baseline
    private val recent = ArrayDeque<Double>()               // last few samples for a median
    private var lastGoodProbe = Long.MIN_VALUE
    private var smoothedTotal = 0.0

    /**
     * @param rttMs probe round trip, or null if it was lost
     * @param totalRx bytes/s received by the whole phone in the last interval (excluding VPN double counting)
     * @param usage bytes/s actually used by each limited app in the last interval
     * @return new limit (bytes/s) for each limited app, same order as [usage]
     */
    fun tick(now: Long, rttMs: Double?, totalRx: Double, usage: DoubleArray, gameRx: Double? = null): LongArray {
        smoothedTotal = if (smoothedTotal == 0.0) totalRx else 0.5 * smoothedTotal + 0.5 * totalRx

        if (rttMs != null) {
            lastGoodProbe = now
            history.addLast(now to rttMs)
            while (history.isNotEmpty() && now - history.first().first > 60_000) history.removeFirst()
            baselineMs = history.minOf { it.second }
        }
        // A lost probe counts as a very late one: one loss is filtered out by the median, repeated loss = congestion.
        val sample = rttMs ?: ((baselineMs ?: 100.0) + thresholdMs * 4)
        recent.addLast(sample)
        while (recent.size > 3) recent.removeFirst()
        pingMs = rttMs
        probesOk = now - lastGoodProbe < 10_000

        if (probesOk && baselineMs != null) {
            extraMs = max(0.0, recent.sorted()[recent.size / 2] - baselineMs!!)
            val congested = extraMs > thresholdMs
            val clear = extraMs < thresholdMs * 0.5

            // Live link-speed estimate: when the queue is building, the link is full, so what we receive ≈ its speed.
            linkBytesPerSec = when {
                congested -> max(50_000.0, 0.6 * linkBytesPerSec + 0.4 * smoothedTotal)
                smoothedTotal > linkBytesPerSec -> smoothedTotal
                else -> linkBytesPerSec
            }

            // Probe the estimate upwards while the line is clear and well used, so it can't get stuck low.
            if (clear && smoothedTotal >= 0.8 * linkBytesPerSec) linkBytesPerSec *= 1.03

            val used = if (priorityMode) usage.getOrElse(0) { 0.0 } else usage.sum()
            // When the game is active it has priority: the background app yields even if it's the smaller flow.
            val gameActive = gameRx != null && now - lastGameActive < 10_000
            ctl.update(now, congested, clear, used, max(smoothedTotal, totalRx), if (gameActive) 0.1 else 0.3)
        }

        if (priorityMode && gameRx != null) {
            // Fast attack, slow release (~5 s): react the moment the game asks for more.
            gameNeed = max(gameRx, gameNeed * 0.93)
            gameSurge = gameRx > 40_000 && gameRx > 1.5 * gameAvg + 10_000
            gameAvg = 0.9 * gameAvg + 0.1 * gameRx
            if (gameRx > 2_000) lastGameActive = now
            gameReserve = if (now - lastGameActive < 10_000) {
                // Keep 1.5x of what the game recently used plus a cushion for bursts; more during a surge.
                gameNeed * (if (gameSurge) 2.0 else 1.5) + 30_000
            } else 0.0
            if (gameReserve > 0) {
                val ceiling = max(ctl.min, linkBytesPerSec - gameReserve)
                ctl.capTo(ceiling)
            }
        }
        // If probes can't get through at all we just hold the current limits.

        return if (priorityMode) {
            LongArray(usage.size) { ctl.value.toLong() }
        } else {
            val a = splitA.coerceIn(5, 95) / 100.0
            LongArray(usage.size) { i -> (ctl.value * if (i == 0) a else 1 - a).toLong() }
        }
    }
}

/** Additive-increase / multiplicative-decrease with a memory of where congestion started. */
class Aimd(initial: Double, val min: Double, val max: Double) {
    var value = initial.coerceIn(min, max)
        private set
    private var lastBad = Double.MAX_VALUE
    private var lastCut = Long.MIN_VALUE / 2

    /** Immediately lowers the limit (e.g. to make room for the game) without treating it as congestion. */
    fun capTo(ceiling: Double) {
        if (ceiling < value) value = max(min, ceiling)
    }

    fun update(now: Long, congested: Boolean, clear: Boolean, used: Double, total: Double, share: Double = 0.3) {
        if (congested) {
            // Only cut if our app is a big part of the traffic (otherwise the lag isn't caused by it).
            // Compare with total traffic, not with our limit: when the signal suddenly drops the app
            // can't reach its old limit any more, yet it is still what's filling the queue.
            if (now - lastCut >= 1000 && (used > share * total || used > 0.3 * value)) {
                lastBad = used
                value = max(min, min(value, used) * 0.7)
                lastCut = now
            }
        } else if (clear && used >= 0.75 * value && now - lastCut >= 2000) {
            val factor = if (value < 0.8 * lastBad) 1.12 else 1.02
            value = min(max, value * factor + 6_000)
            if (value > lastBad * 1.2) lastBad = Double.MAX_VALUE // the connection got faster
        }
    }
}
