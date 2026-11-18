package com.netsplit.app.vpn

import java.util.concurrent.atomic.AtomicLong

/** Classic token bucket. rate <= 0 means unlimited. Only touched by the engine thread (rate is volatile). */
class TokenBucket(rateBytesPerSec: Long) {
    @Volatile
    var rate: Long = rateBytesPerSec

    private var tokens = 0.0
    private var last = System.nanoTime()

    val unlimited get() = rate <= 0

    fun refill(now: Long = System.nanoTime()) {
        val r = rate
        if (r <= 0) return
        // ~100 ms of burst keeps the flow smooth without letting big spikes through.
        val cap = maxOf(r / 10.0, 16_000.0)
        tokens = minOf(cap, tokens + (now - last) / 1e9 * r)
        last = now
    }

    /** How many bytes may be sent right now. */
    fun available(): Int {
        if (unlimited) return Int.MAX_VALUE
        refill()
        return tokens.toInt().coerceAtLeast(0)
    }

    fun consume(n: Int) {
        if (!unlimited) tokens -= n
    }

    fun tryConsume(n: Int): Boolean {
        if (unlimited) return true
        refill()
        if (tokens >= n) { tokens -= n; return true }
        return false
    }
}

/** Per-app limit + live counters. */
class AppLimiter(
    val packageName: String,
    val label: String,
    val uid: Int,
    rateBytesPerSec: Long,
) {
    val down = TokenBucket(rateBytesPerSec)
    val up = TokenBucket(rateBytesPerSec)
    /** Priority app: routed only so we can watch its traffic; never throttled. */
    val monitorOnly: Boolean get() = down.unlimited
    val bytesDown = AtomicLong()
    val bytesUp = AtomicLong()

    var rateBytesPerSec: Long
        get() = down.rate
        set(v) { down.rate = v; up.rate = v }
}
