package com.blockbridge

import android.app.ActivityManager
import android.content.Context

/**
 * Low-end phone detection (e.g. Nokia G42-class: 4 GB RAM or less, or Android's own
 * "low RAM" flag). On these BlockBridge animates less and YouTube mode uses lighter video.
 */
object Perf {
    @Volatile private var cached: Boolean? = null

    fun lowEnd(ctx: Context): Boolean = cached ?: run {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        isLowEnd(am?.isLowRamDevice == true, mem.totalMem, Runtime.getRuntime().availableProcessors())
            .also { cached = it }
    }

    /** ≤ 4.5 GB of RAM (phones sold as "4 GB" report a bit less) or ≤ 4 CPU cores. */
    internal fun isLowEnd(lowRamFlag: Boolean, totalMem: Long, cores: Int): Boolean =
        lowRamFlag || (totalMem in 1..4_500_000_000L) || cores in 1..4

    fun describe(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return "%.1f GB RAM · %d cores · %s".format(mem.totalMem / 1e9, Runtime.getRuntime().availableProcessors(),
            if (lowEnd(ctx)) "low-end mode" else "normal")
    }
}
