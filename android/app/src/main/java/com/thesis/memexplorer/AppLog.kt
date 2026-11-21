package com.thesis.memexplorer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent in-app log + crash capture, so a build problem or a runtime error
 * can be exported and sent back for diagnosis ("tell you the error"). Everything
 * the app prints is also appended to files/session.log; uncaught exceptions are
 * written to files/crash.log before the process dies.
 */
object AppLog {
    private const val SESSION = "session.log"
    private const val CRASH = "crash.log"
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var dir: File? = null
    private val listeners = ArrayList<(String) -> Unit>()

    fun init(ctx: Context) {
        dir = ctx.filesDir
        // Fresh session marker.
        write("session.log", "\n==== session ${Date()} (pid ${android.os.Process.myPid()}) ====\n", append = true)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val text = "\n==== CRASH ${Date()} thread=${t.name} ====\n$sw\n"
                write(CRASH, text, append = true)
                write(SESSION, text, append = true)
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun log(msg: String) {
        val line = "${ts.format(Date())}  $msg"
        write(SESSION, line + "\n", append = true)
        synchronized(listeners) { listeners.forEach { it(line) } }
    }

    fun addListener(l: (String) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun clearListeners() { synchronized(listeners) { listeners.clear() } }

    fun sessionFile(): File = File(dir, SESSION)
    fun crashFile(): File = File(dir, CRASH)
    fun hadCrash(): Boolean = crashFile().let { it.exists() && it.length() > 0 }

    fun readAll(): String = try {
        buildString {
            if (hadCrash()) append("---- crash.log ----\n").append(crashFile().readText()).append("\n")
            append("---- session.log ----\n").append(sessionFile().readText())
        }
    } catch (e: Exception) { "could not read logs: ${e.message}" }

    fun clearCrash() { try { crashFile().writeText("") } catch (_: Exception) {} }

    private fun write(name: String, text: String, append: Boolean) {
        val d = dir ?: return
        try { File(d, name).appendText(text) } catch (_: Exception) { }
    }
}
