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
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, msg: String) {
        synchronized(lines) {
            val l = Line(System.currentTimeMillis(), tag, msg.take(500))
            lines.addLast(l)
            while (lines.size > 400) lines.removeFirst()
            file?.let { f -> runCatching { f.appendText("${fmt.format(Date(l.time))} [${l.tag}] ${l.msg}\n") } }
        }
    }

    fun error(tag: String, msg: String, e: Throwable? = null) =
        add(tag, "ERROR $msg" + (e?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: ""))

    fun all(): List<Line> = synchronized(lines) { lines.toList() }

    fun text(): String = all().joinToString("\n") { "${fmt.format(Date(it.time))} [${it.tag}] ${it.msg}" }

    fun clear() = synchronized(lines) { lines.clear() }
}
