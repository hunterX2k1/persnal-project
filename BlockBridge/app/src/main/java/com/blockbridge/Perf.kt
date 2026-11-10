package com.blockbridge

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager

/**
 * What this phone can do, checked when BlockBridge runs, so one APK suits every phone:
 * - low-end (≤ 4.5 GB RAM, ≤ 4 cores, 32-bit only, or Android's "low RAM" flag): fewer
 *   animation frames, nothing extra kept in memory, smaller images;
 * - video chips: YouTube's VP9/AV1 video is used only if the phone decodes it in hardware,
 *   otherwise YouTube mode asks for H.264 (decoded in hardware on practically every phone);
 * - Battery Saver on: animate less while it's on.
 */
object Perf {
    class Profile(
        val ramBytes: Long, val cores: Int, val is64Bit: Boolean, val lowRamFlag: Boolean,
        val hwVp9: Boolean, val hwAv1: Boolean, val hwAvc: Boolean,
    ) {
        val lowEnd get() = isLowEnd(lowRamFlag, ramBytes, cores) || !is64Bit
    }

    @Volatile private var cached: Profile? = null

    fun profile(ctx: Context): Profile = cached ?: synchronized(this) {
        cached ?: run {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val hw = hardwareDecoders()
            Profile(
                ramBytes = mem.totalMem,
                cores = Runtime.getRuntime().availableProcessors(),
                is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
                lowRamFlag = am?.isLowRamDevice == true,
                hwVp9 = "video/x-vnd.on2.vp9" in hw,
                hwAv1 = "video/av01" in hw,
                hwAvc = "video/avc" in hw,
            ).also { cached = it }
        }
    }

    fun lowEnd(ctx: Context): Boolean = profile(ctx).lowEnd

    /** YouTube mode's smooth mode default: on for low-end phones and phones without a VP9 video chip. */
    fun smoothByDefault(ctx: Context): Boolean = profile(ctx).let { smoothByDefault(it.lowEnd, it.hwVp9) }

    internal fun smoothByDefault(lowEnd: Boolean, hwVp9: Boolean) = lowEnd || !hwVp9

    /** Animate less: low-end phone, or Battery Saver is on right now. */
    fun saving(ctx: Context): Boolean =
        lowEnd(ctx) || ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    /** ≤ 4.5 GB of RAM (phones sold as "4 GB" report a bit less) or ≤ 4 CPU cores. */
    internal fun isLowEnd(lowRamFlag: Boolean, totalMem: Long, cores: Int): Boolean =
        lowRamFlag || (totalMem in 1..4_500_000_000L) || cores in 1..4

    /** Video formats this phone decodes with a hardware chip (not the CPU). */
    private fun hardwareDecoders(): Set<String> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && isHardware(it) }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()
    }.getOrDefault(emptySet())

    private fun isHardware(c: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) c.isHardwareAccelerated
        else !c.name.startsWith("OMX.google.") && !c.name.startsWith("c2.android.") // Android's software codecs

    fun describe(ctx: Context): String = profile(ctx).let { p ->
        "%.1f GB RAM · %d cores · %s · video chip: %s · %s".format(
            p.ramBytes / 1e9, p.cores, if (p.is64Bit) "64-bit" else "32-bit",
            listOfNotNull("H.264".takeIf { p.hwAvc }, "VP9".takeIf { p.hwVp9 }, "AV1".takeIf { p.hwAv1 })
                .joinToString(", ").ifEmpty { "none found" },
            if (p.lowEnd) "low-end mode" else "full mode")
    }
}
