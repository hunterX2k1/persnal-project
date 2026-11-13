package app.vault.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only diagnostics: an event log (rotated, ~256 KB) plus crash files.
 * Nothing leaves the phone unless the user shares a report from Settings → Diagnostics.
 */
object AppLog {
    private const val MAX_BYTES = 256 * 1024
    private lateinit var dir: File
    private lateinit var appContext: Context
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val logFile get() = File(dir, "app.log")
    private val oldLogFile get() = File(dir, "app.log.1")
    private val crashFile get() = File(dir, "last_crash.txt")
    private val crashSeenFile get() = File(dir, "last_crash.seen")

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                e("CRASH", "Uncaught exception on thread ${thread.name}", e)
                crashFile.writeText(buildString {
                    appendLine("Crash at ${Date()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine(deviceInfo())
                    appendLine()
                    appendLine(stackTrace(e))
                })
                crashSeenFile.delete()
            }
            previous?.uncaughtException(thread, e)
        }
        i("App", "Started · ${deviceInfo().replace("\n", " · ")}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val clean = redact(msg)
        when (level) {
            "E" -> Log.e("Vault/$tag", clean, t)
            "W" -> Log.w("Vault/$tag", clean, t)
            else -> Log.i("Vault/$tag", clean)
        }
        if (!::dir.isInitialized) return
        runCatching {
            if (logFile.length() > MAX_BYTES) { oldLogFile.delete(); logFile.renameTo(oldLogFile) }
            logFile.appendText(buildString {
                append(time.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(clean).append('\n')
                if (t != null) append(stackTrace(t).lines().take(25).joinToString("\n") { "    $it" }).append('\n')
            })
        }
    }

    /** Last [maxLines] lines across the rotated log files. */
    fun recent(maxLines: Int = 400): String {
        if (!::dir.isInitialized) return ""
        val lines = listOf(oldLogFile, logFile).filter { it.exists() }.flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
        return lines.takeLast(maxLines).joinToString("\n")
    }

    fun lastCrash(): String? = runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()

    /** True once per crash, so the app can offer the report on the next launch. */
    fun hasUnseenCrash(): Boolean = crashFile.exists() && !crashSeenFile.exists()
    fun markCrashSeen() { runCatching { crashSeenFile.writeText("1") } }

    fun clear() {
        listOf(logFile, oldLogFile, crashFile, crashSeenFile).forEach { it.delete() }
        i("App", "Logs cleared")
    }

    fun report(extra: String): String = buildString {
        appendLine("===== VAULT DEBUG REPORT =====")
        appendLine("Generated: ${Date()}")
        appendLine(deviceInfo())
        appendLine(extra)
        appendLine()
        appendLine("===== LAST CRASH =====")
        appendLine(lastCrash() ?: "None recorded")
        appendLine("===== RECENT LOG =====")
        appendLine(recent())
    }

    fun deviceInfo(): String {
        val version = runCatching {
            val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        return "App $version\nDevice ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}"
    }

    private fun stackTrace(t: Throwable) = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** Keep secrets and search terms out of logs. */
    private fun redact(s: String) = s
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-|]+"), "$1***")
        .replace(Regex("(?i)([?&](q|query|search|s)=)[^&\\s]*"), "$1***")
}
