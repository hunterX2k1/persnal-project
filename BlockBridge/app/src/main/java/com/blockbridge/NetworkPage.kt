package com.blockbridge

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Network analyzer (Wireshark-style, connection level): every connection with its app,
 * host, port, protocol, route, bytes, duration and status; per-app totals; the DNS log;
 * filtering and CSV export.
 */
class NetworkPage(private val a: MainActivity) {
    private var sub = 0
    private var statusFilter = 0 // 0 all, 1 open, 2 blocked
    private var query = ""
    private var shown = -1L
    private lateinit var holder: FrameLayout
    private lateinit var listBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var summary: TextView
    private var dnsPage: View? = null

    fun build(): View = with(a) {
        val page = vbox()
        page.addView(pageTitle("Network", "Live connections from every app"))
        summary = small("").apply { setPadding(0, 0, 0, dp(8)) }
        page.addView(summary)
        val seg = segmented(listOf("Connections", "Apps", "DNS")) { i -> sub = i; showSub() }
        page.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        holder = FrameLayout(a)
        page.addView(holder)
        showSub()
        page
    }

    private fun showSub() = with(a) {
        holder.removeAllViews()
        shown = -1
        when (sub) {
            0 -> holder.addView(connectionsView())
            1 -> holder.addView(vbox().also { appsBox = it })
            else -> holder.addView(dnsPage ?: buildLoggerPage().also { dnsPage = it })
        }
        refresh()
    }

    private fun connectionsView(): View = with(a) {
        val v = vbox()
        val search = singleLine("", "Filter by app, host, IP or port")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString()?.trim() ?: ""; shown = -1; refresh() }
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        v.addView(search)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All", "Open", "Blocked").forEachIndexed { i, l ->
            row.addView(button(l) { statusFilter = i; shown = -1; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row.addView(button("Export") { export() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Clear") { ConnTracker.clear(); shown = -1; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        v.addView(row)
        listBox = vbox()
        v.addView(listBox)
        v
    }

    fun refresh() {
        if (!::summary.isInitialized) return
        summary.text = if (!ShieldVpnService.fullTunnel)
            "Turn on \"Send all app traffic through BlockBridge\" (Settings) and protection to see connections."
        else "${ConnTracker.liveCount()} open · ${a.mb(TrafficStats.bytesDown.get())} ↓ · ${a.mb(TrafficStats.bytesUp.get())} ↑ · " +
            "${TrafficStats.blockedConnections.get()} refused"
        if (ConnTracker.version == shown && sub != 2) return
        shown = ConnTracker.version
        when (sub) {
            0 -> if (::listBox.isInitialized) renderConnections()
            1 -> if (::appsBox.isInitialized) renderApps()
        }
    }

    private fun matches(c: Conn): Boolean {
        when (statusFilter) {
            1 -> if (c.status != Conn.Status.OPEN) return false
            2 -> if (c.status != Conn.Status.BLOCKED) return false
        }
        if (query.isEmpty()) return true
        val q = query.lowercase()
        return listOfNotNull(c.host, c.dst.address.hostAddress, c.dst.port.toString(), c.app?.label, c.app?.pkg, c.route)
            .any { it.lowercase().contains(q) }
    }

    private fun renderConnections() = with(a) {
        listBox.removeAllViews()
        val items = ConnTracker.snapshot().filter(::matches).take(150)
        if (items.isEmpty()) { listBox.addView(small("No connections yet.").apply { setPadding(0, dp(10), 0, 0) }); return@with }
        val now = System.currentTimeMillis()
        for (c in items) {
            val color = when (c.status) {
                Conn.Status.OPEN -> Palette.GOOD
                Conn.Status.BLOCKED -> Palette.BAD
                Conn.Status.FAILED -> Palette.WARN
                Conn.Status.CLOSED -> Palette.MUTED
            }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { details(c) }
            }
            row.addView(View(a).apply { background = roundRect(color, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(8), dp(8)))
            val text = vbox().apply { setPadding(dp(10), 0, 0, 0) }
            text.addView(TextView(a).apply {
                this.text = "${c.label}:${c.dst.port}"
                textSize = 14f; setTextColor(Palette.TEXT); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                typeface = Typeface.MONOSPACE
            })
            val dur = ((if (c.end > 0) c.end else now) - c.start) / 1000
            text.addView(small(listOfNotNull(
                c.app?.label ?: "?", c.proto, c.route,
                "↑${mb(c.bytesUp.get())} ↓${mb(c.bytesDown.get())}", "${dur}s",
                c.note,
            ).joinToString(" · ")).apply { textSize = 11f; maxLines = 2 })
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            listBox.addView(row)
        }
    }

    private fun renderApps() = with(a) {
        appsBox.removeAllViews()
        val totals = ConnTracker.perApp.values.sortedByDescending { it.up.get() + it.down.get() }
        if (totals.isEmpty()) { appsBox.addView(small("No traffic yet.")); return@with }
        val max = totals.maxOf { it.up.get() + it.down.get() }.coerceAtLeast(1)
        for (t in totals.take(60)) {
            val c = card().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            c.addView(TextView(a).apply { text = t.name; textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Palette.TEXT) })
            c.addView(small("↓ ${mb(t.down.get())}   ↑ ${mb(t.up.get())}   ·   ${t.conns.get()} connections" +
                if (t.blocked.get() > 0) "   ·   ${t.blocked.get()} blocked" else ""))
            val bar = FrameLayout(a).apply { background = roundRect(Palette.SURFACE2, dp(4).toFloat()) }
            val frac = (t.up.get() + t.down.get()).toFloat() / max
            bar.addView(View(a).apply { background = gradientRect(dp(4).toFloat(), Palette.CYAN, Palette.VIOLET) },
                FrameLayout.LayoutParams(0, -1).also { lp -> bar.post { lp.width = (bar.width * frac).toInt(); bar.requestLayout() } })
            c.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
            appsBox.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun details(c: Conn) {
        val info = buildString {
            appendLine("App: ${c.app?.label ?: "unknown"}${c.app?.let { " (${it.pkg}, uid ${it.uid})" } ?: ""}")
            appendLine("Host: ${c.host ?: "—"}")
            appendLine("Address: ${c.dst.address.hostAddress} port ${c.dst.port} (${c.proto})")
            appendLine("Route: ${c.route}")
            appendLine("Status: ${c.status.name.lowercase()}${c.note?.let { " — $it" } ?: ""}")
            appendLine("Started: ${DateFormat.format("HH:mm:ss", c.start)}" + if (c.end > 0) "  ended ${DateFormat.format("HH:mm:ss", c.end)}" else "")
            append("Data: ↑ ${a.mb(c.bytesUp.get())}  ↓ ${a.mb(c.bytesDown.get())}")
        }
        val b = AlertDialog.Builder(a).setTitle(c.label).setMessage(info).setNegativeButton("Close", null)
        c.host?.let { h ->
            b.setPositiveButton("Block $h") { _, _ ->
                a.settings.myFilters = (a.settings.myFilters.trimEnd() + "\n||$h^").trim()
                thread { Blocklists.loadUserRules(a.settings) }
                a.toast("Blocked $h")
            }
            b.setNeutralButton("Trust") { _, _ ->
                a.settings.trustedSites = (a.settings.trustedSites.trimEnd() + "\n$h").trim()
                thread { Blocklists.loadUserRules(a.settings) }
                a.toast("$h trusted")
            }
        }
        b.show()
    }

    /** Shares all tracked connections as CSV (opens in any spreadsheet app). */
    private fun export() {
        val csv = buildString {
            appendLine("start,end,app,package,uid,protocol,host,ip,port,route,status,note,bytes_up,bytes_down")
            for (c in ConnTracker.snapshot()) {
                fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
                appendLine(listOf(c.start, c.end, q(c.app?.label), q(c.app?.pkg), c.app?.uid ?: "", c.proto, q(c.host),
                    c.dst.address.hostAddress, c.dst.port, q(c.route), c.status, q(c.note), c.bytesUp.get(), c.bytesDown.get())
                    .joinToString(","))
            }
        }
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "BlockBridge connections")
            putExtra(Intent.EXTRA_TEXT, csv)
        }, "Export connections"))
    }

    private fun segmented(labels: List<String>, onSelect: (Int) -> Unit): View = with(a) {
        val seg = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(Palette.SURFACE, dp(99).toFloat(), Palette.STROKE, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun paint(sel: Int) {
            for (k in 0 until seg.childCount) {
                val t = seg.getChildAt(k) as TextView
                t.background = if (k == sel) gradientRect(dp(99).toFloat(), Palette.CYAN, Palette.VIOLET) else null
                t.setTextColor(if (k == sel) Palette.BG else Palette.MUTED)
            }
        }
        labels.forEachIndexed { i, l ->
            seg.addView(TextView(a).apply {
                text = l; textSize = 13f; setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { paint(i); onSelect(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint(sub)
        seg
    }
}
