package com.blockbridge

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Makes silent crashes visible: Java crashes are written to a file, native crashes and
 * ANRs come from Android's exit history (API 30+), and the debug log is mirrored to disk
 * so it survives the crash. The next app start shows a report with a Share button.
 */
object CrashGuard {
    private lateinit var dir: File
    private val crashFile get() = File(dir, "last_crash.txt")
    private val historyFile get() = File(dir, "crash_history.txt")
    val logFile: File get() = File(dir, "diag.log")

    fun install(ctx: Context) {
        dir = ctx.filesDir
        DiagLog.persistTo(logFile)
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else ""
        val part = when {
            proc.endsWith(":tor") -> "Tor process"
            proc.endsWith(":crash") -> "crash recorder"
            proc.contains(":") -> "process ${proc.substringAfter(':')}"
            else -> "app"
        }
        DiagLog.add("app", "$part started · BlockBridge $version (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile.writeText("Crash in thread \"${t.name}\" at ${java.util.Date()}\n\n$sw\n\n" +
                    "Recent log:\n${DiagLog.text().takeLast(6000)}\n\nSystem log:\n${systemLog(300, 2_000)}")
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
                    sb.append("\n[Android] $kind in ${e.processName} at ${java.util.Date(e.timestamp)}: ${e.description ?: "no description"}\n")
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
        val report = Report("BlockBridge ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n$sb", starting)
        addHistory(report.text)
        return report
    }

    /** Keeps the last few crash reports so they can be shared later (Settings › Diagnostics). */
    internal fun addHistory(text: String) = runCatching {
        val old = if (historyFile.exists()) historyFile.readText() else ""
        historyFile.writeText(("=".repeat(40) + "\n" + text + "\n\n" + old).take(150_000))
    }

    fun history(): String? = runCatching { historyFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clearHistory() { runCatching { historyFile.delete() } }

    /**
     * BlockBridge's own lines from Android's system log (logcat). Apps can only read their own,
     * so this has the Firefox engine's, Tor's and Android's messages about BlockBridge.
     * Blocking; give up after [timeoutMs].
     */
    fun systemLog(lines: Int = 1500, timeoutMs: Long = 5_000): String {
        var out = "(system log unavailable)"
        val t = Thread {
            out = runCatching {
                val p = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "-v", "threadtime")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { it.readText() }.ifBlank { "(empty)" }
            }.getOrElse { "(system log unavailable: ${it.message})" }
        }
        t.isDaemon = true
        t.start(); t.join(timeoutMs)
        return out
    }

    fun header(ctx: Context): String {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return "BlockBridge $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${java.util.Date()}"
    }

    /**
     * A full problem report: [summary], the saved debug log, recent crashes and the system log.
     * Built off the main thread, then shared (text) with any app — email, WhatsApp, Drive…
     */
    fun shareFullReport(a: android.app.Activity, subject: String, summary: String, focusTags: Set<String> = emptySet()) {
        android.widget.Toast.makeText(a, "Collecting logs…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val text = buildReport(header(a), summary,
                runCatching { logFile.readLines() }.getOrDefault(DiagLog.text().lines()), history(), systemLog(), focusTags)
            a.runOnUiThread { if (!a.isFinishing) share(a, subject, text) }
        }.start()
    }

    /**
     * A report short enough to paste into a chat in full: the summary, the lines about the
     * problem area ([focusTags]) first, then the most recent log, the last crash, and only the
     * warnings/errors from the system log.
     */
    internal fun buildReport(header: String, summary: String, log: List<String>, crashes: String?,
                             systemLog: String, focusTags: Set<String>): String = buildString {
        appendLine(header); appendLine()
        appendLine(summary.trim()); appendLine()
        if (focusTags.isNotEmpty()) {
            val focus = log.filter { l -> focusTags.any { "[$it]" in l } }.takeLast(150)
            appendLine("---- ${focusTags.joinToString("/")} log (latest ${focus.size} lines) ----")
            focus.forEach(::appendLine); appendLine()
        }
        val recent = log.filter { it.isNotBlank() }.takeLast(200)
        appendLine("---- Debug log (latest ${recent.size} lines) ----")
        recent.forEach(::appendLine)
        crashes?.let { c ->
            val last = c.split("=".repeat(40)).map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (last != null) { appendLine(); appendLine("---- Last crash ----"); appendLine(last.take(8_000)) }
        }
        // Warnings, errors and fatal lines only (threadtime format: "date time pid tid LEVEL tag: msg").
        val important = systemLog.lines().filter { Regex("^\\S+ \\S+\\s+\\d+\\s+\\d+ [WEF] ").containsMatchIn(it) }.takeLast(150)
        appendLine(); appendLine("---- System log: warnings & errors (latest ${important.size}) ----")
        important.forEach(::appendLine)
    }

    fun share(a: android.app.Activity, subject: String, text: String) {
        runCatching {
            a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }, subject))
        }.onFailure {
            copy(a, text)
            android.widget.Toast.makeText(a, "Couldn't open share — report copied instead", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun copy(a: Context, text: String) = a.getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText("BlockBridge report", text))
}
