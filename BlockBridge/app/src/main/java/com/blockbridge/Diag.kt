package com.blockbridge

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory debug log (last 400 lines) for the Diagnostics screen and "Copy debug log". */
object DiagLog {
    class Line(val time: Long, val tag: String, val msg: String)

    private val lines = ArrayDeque<Line>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, msg: String) {
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), tag, msg.take(500)))
            while (lines.size > 400) lines.removeFirst()
        }
    }

    fun error(tag: String, msg: String, e: Throwable? = null) =
        add(tag, "ERROR $msg" + (e?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: ""))

    fun all(): List<Line> = synchronized(lines) { lines.toList() }

    fun text(): String = all().joinToString("\n") { "${fmt.format(Date(it.time))} [${it.tag}] ${it.msg}" }

    fun clear() = synchronized(lines) { lines.clear() }
}
