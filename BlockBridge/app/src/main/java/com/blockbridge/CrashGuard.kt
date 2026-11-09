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
    private val historyFile get() = File(dir, "crash_history.txt")
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
    fun shareFullReport(a: android.app.Activity, subject: String, summary: String) {
        android.widget.Toast.makeText(a, "Collecting logs…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val text = buildString {
                appendLine(header(a)); appendLine()
                appendLine(summary); appendLine()
                appendLine("---- Debug log ----")
                appendLine(runCatching { logFile.readText().takeLast(80_000) }.getOrDefault(DiagLog.text()))
                history()?.let { appendLine(); appendLine("---- Recent crashes ----"); appendLine(it.take(30_000)) }
                appendLine(); appendLine("---- System log (logcat) ----")
                append(systemLog().takeLast(120_000))
            }
            a.runOnUiThread { if (!a.isFinishing) share(a, subject, text) }
        }.start()
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

/**
 * Firefox-engine (GeckoView) crash reports. GeckoView starts this in its own ":crash" process
 * when any of its processes crashes; we record what crashed in the debug log and crash history.
 * (The minidump itself can't be read without Mozilla's symbols, so it's deleted.)
 */
class GeckoCrashService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // GeckoView starts us with startForegroundService, so go foreground at once.
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("engine_crash", "Engine crash reports",
            android.app.NotificationManager.IMPORTANCE_MIN))
        val n = android.app.Notification.Builder(this, "engine_crash")
            .setSmallIcon(R.drawable.ic_shield).setContentTitle("Saving crash report…").build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(4243, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(4243, n)
        }
        runCatching { record(intent) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun record(i: android.content.Intent?) {
        if (i == null) return
        val type = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE) ?: "?"
        val remote = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE) ?: ""
        val vis = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY) ?: ""
        val dump = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_MINIDUMP_PATH)?.let(::File)
        val extras = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_EXTRAS_PATH)?.let(::File)
        // The .extra file is small JSON with Firefox's own crash reason ("MozCrashReason" etc.).
        val reason = runCatching {
            val j = org.json.JSONObject(extras!!.readText())
            listOf("MozCrashReason", "AbortMessage", "ipc_channel_error", "OOMAllocationSize", "TotalPhysicalMemory")
                .filter { j.has(it) }.joinToString("\n") { "$it: ${j.optString(it).take(500)}" }
        }.getOrDefault("")
        val text = "${CrashGuard.header(this)}\n\nFirefox engine crash — process: $type${if (remote.isNotEmpty()) " ($remote)" else ""}" +
            "${if (vis.isNotEmpty()) " · $vis" else ""}\n${reason.ifEmpty { "no reason recorded" }}\n" +
            "minidump: ${dump?.length() ?: 0} bytes\n\nSystem log:\n${CrashGuard.systemLog(400, 3_000)}"
        DiagLog.add("youtube", "ERROR Firefox engine crash in $type process ${remote}: ${reason.lineSequence().firstOrNull() ?: ""}")
        CrashGuard.addHistory(text)
        runCatching { dump?.delete(); extras?.delete() }
    }
}
