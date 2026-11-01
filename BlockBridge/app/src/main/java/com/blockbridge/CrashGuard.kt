package com.blockbridge

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Installs crash capture before anything else runs. */
class BlockBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}

/**
 * Makes silent crashes visible: Java crashes are written to a file, native crashes and
 * ANRs come from Android's exit history (API 30+), and the debug log is mirrored to disk
 * so it survives the crash. The next app start shows a report with a Share button.
 */
object CrashGuard {
    private lateinit var dir: File
    private val crashFile get() = File(dir, "last_crash.txt")
    val logFile: File get() = File(dir, "diag.log")

    fun install(ctx: Context) {
        dir = ctx.filesDir
        DiagLog.persistTo(logFile)
        DiagLog.add("app", "started (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile.writeText("Crash in thread \"${t.name}\" at ${java.util.Date()}\n\n$sw\n\nRecent log:\n${DiagLog.text().takeLast(6000)}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Marks the risky start of the full-traffic engine; cleared once it has run for a while. */
    fun markStarting(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("engine_starting", on).commit()

    private fun wasStarting(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("engine_starting", false)

    class Report(val text: String, val duringEngineStart: Boolean)

    /** A crash that happened since the last time we looked, or null. Consumes it. */
    fun takeReport(ctx: Context): Report? {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        if (crashFile.exists()) {
            sb.append(crashFile.readText()); crashFile.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val seen = prefs.getLong("exit_seen", 0)
            val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 5) }.getOrDefault(emptyList())
            val bad = exits.filter {
                it.timestamp > seen && it.reason in setOf(
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                )
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("exit_seen", it).apply() }
            for (e in bad) {
                val kind = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    ApplicationExitInfo.REASON_ANR -> "app not responding"
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    else -> "start-up failure"
                }
                if (sb.isEmpty() || e.reason != ApplicationExitInfo.REASON_CRASH) {
                    sb.append("\n[Android] $kind at ${java.util.Date(e.timestamp)}: ${e.description ?: "no description"}\n")
                    if (e.reason == ApplicationExitInfo.REASON_ANR) {
                        runCatching { e.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readText().take(4000)) } }
                    }
                }
            }
        }
        val starting = wasStarting(ctx)
        if (sb.isEmpty()) {
            if (starting) markStarting(ctx, false)
            return null
        }
        markStarting(ctx, false)
        // Last persisted log lines from the crashed run
        runCatching { if (logFile.exists()) sb.append("\n\nLog before the crash:\n").append(logFile.readText().takeLast(6000)) }
        return Report("BlockBridge ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n$sb", starting)
    }
}
