package com.blockbridge

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory debug log (last 400 lines) for the Diagnostics screen and "Copy debug log". */
object DiagLog {
    class Line(val time: Long, val tag: String, val msg: String)

    private val lines = ArrayDeque<Line>()
    @Volatile private var file: java.io.File? = null

    /** Also append every line to [f] (kept under ~200 KB) so it survives crashes. */
    fun persistTo(f: java.io.File) {
        file = f
        runCatching { if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000)) }
    }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var repeats = 0

    fun add(tag: String, msg: String) {
        synchronized(lines) {
            val m = msg.take(500)
            val last = lines.peekLast()
            // The same line again (e.g. a retry loop): count it instead of flooding the log.
            if (last != null && last.tag == tag && last.msg == m && System.currentTimeMillis() - last.time < 600_000) {
                repeats++
                return
            }
            flushRepeats()
            val l = Line(System.currentTimeMillis(), tag, m)
            lines.addLast(l)
            while (lines.size > 400) lines.removeFirst()
            write(l)
        }
    }

    private fun flushRepeats() {
        if (repeats == 0) return
        val last = lines.peekLast()
        val l = Line(System.currentTimeMillis(), last?.tag ?: "log", "(previous line repeated $repeats more times)")
        repeats = 0
        lines.addLast(l)
        write(l)
    }

    /** File writes happen on one background thread, in order (never on the screen thread). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-log").apply { isDaemon = true }
    }

    private fun write(l: Line) {
        val f = file ?: return
        val text = "${fmt.format(Date(l.time))} [${l.tag}] ${l.msg}\n"
        runCatching { writer.execute { runCatching { f.appendText(text) } } }
    }

    fun error(tag: String, msg: String, e: Throwable? = null) =
        add(tag, "ERROR $msg" + (e?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: ""))

    fun all(): List<Line> = synchronized(lines) { lines.toList() }

    fun text(): String = synchronized(lines) { flushRepeats(); lines.toList() }.joinToString("\n") { "${fmt.format(Date(it.time))} [${it.tag}] ${it.msg}" }

    fun clear() = synchronized(lines) { lines.clear() }
}

/**
 * "Something changed" signal shared by the app screen, the notification, the widget and the
 * Quick Settings tiles, so every switch shows the same state at the same moment.
 */
object StateBus {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pending = false

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }

    /** Coalesces bursts of changes into one refresh ~100 ms later, on the main thread. */
    fun changed() {
        if (pending) return
        pending = true
        main.postDelayed({
            pending = false
            listeners.forEach { runCatching { it() } }
        }, 100)
    }
}
