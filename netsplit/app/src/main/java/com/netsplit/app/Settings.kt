package com.netsplit.app

import android.content.Context
import android.content.pm.PackageManager

/** Persistent user settings + the limit plan derived from them. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("netsplit", Context.MODE_PRIVATE)

    var priorityMode: Boolean
        get() = p.getBoolean("priority", true)
        set(v) = p.edit().putBoolean("priority", v).apply()

    /** App A: the game / priority app. */
    var appA: String?
        get() = p.getString("appA", null)
        set(v) = p.edit().putString("appA", v).apply()

    /** App B: the background app (YouTube...). */
    var appB: String?
        get() = p.getString("appB", null)
        set(v) = p.edit().putString("appB", v).apply()

    var totalMbps: Float
        get() = p.getFloat("mbps", 10f)
        set(v) = p.edit().putFloat("mbps", v).apply()

    /** Priority mode: share of the total speed the background app may use. */
    var bgPercent: Int
        get() = p.getInt("bgPct", 30)
        set(v) = p.edit().putInt("bgPct", v).apply()

    /** Split mode: share of app A (app B gets the rest). */
    var splitA: Int
        get() = p.getInt("splitA", 50)
        set(v) = p.edit().putInt("splitA", v).apply()

    var blockQuic: Boolean
        get() = p.getBoolean("quic", true)
        set(v) = p.edit().putBoolean("quic", v).apply()

    var initialized: Boolean
        get() = p.getBoolean("init", false)
        set(v) = p.edit().putBoolean("init", v).apply()

    data class Limit(val pkg: String, val percent: Int, val bytesPerSec: Long)

    /** Apps that go through the VPN and their caps. The priority app in priority mode is never routed (zero overhead). */
    fun plan(): List<Limit> {
        val total = totalMbps.coerceAtLeast(0.1f)
        fun lim(pkg: String, pct: Int) = Limit(pkg, pct, (total * pct / 100f * 125_000f).toLong())
        val a = appA
        val b = appB
        return if (priorityMode) {
            listOfNotNull(b?.let { lim(it, bgPercent) })
        } else {
            listOfNotNull(a?.let { lim(it, splitA) }, b?.let { lim(it, 100 - splitA) })
        }
    }

    companion object {
        val GAME_DEFAULTS = listOf("com.nekki.shadowfightarena", "com.nekki.shadowfight3", "com.nekki.shadowfight")
        val VIDEO_DEFAULTS = listOf("com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube")

        fun firstInstalled(pm: PackageManager, pkgs: List<String>) = pkgs.firstOrNull {
            try { pm.getApplicationInfo(it, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        }
    }
}
