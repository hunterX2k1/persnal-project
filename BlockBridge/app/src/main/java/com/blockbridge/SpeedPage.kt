package com.blockbridge

import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Speed test screen: gauge, ping/jitter/download/upload tiles, history, VPN-vs-direct compare. */
class SpeedPage(private val a: MainActivity) {
    private lateinit var gauge: SpeedGauge
    private lateinit var phase: TextView
    private lateinit var routeLine: TextView
    private lateinit var serverLine: TextView
    private lateinit var ping: TextView
    private lateinit var jitter: TextView
    private lateinit var down: TextView
    private lateinit var up: TextView
    private lateinit var startBtn: Button
    private lateinit var compareBtn: Button
    private lateinit var history: LinearLayout
    @Volatile private var test: SpeedTest? = null

    fun build(): View = with(a) {
        val page = vbox()
        page.addView(pageTitle("Speed test", "Ping, download and upload through the route your traffic takes"))
        routeLine = small("").apply { setPadding(0, 0, 0, dp(8)) }
        page.addView(routeLine)

        val g = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        gauge = SpeedGauge(a).apply { caption = "Tap Start" }
        g.addView(gauge, LinearLayout.LayoutParams(-1, -2))
        phase = TextView(a).apply { textSize = 14f; setTextColor(Palette.MUTED); gravity = Gravity.CENTER }
        g.addView(phase)
        serverLine = small("").apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        g.addView(serverLine)
        page.addView(g, cardParams())

        val tiles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(label: String, color: Int, gap: Boolean): TextView {
            val t = card().apply { setPadding(dp(10), dp(12), dp(10), dp(12)); gravity = Gravity.CENTER_HORIZONTAL }
            val v = TextView(a).apply { text = "—"; textSize = 17f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(color); maxLines = 1 }
            t.addView(v)
            t.addView(TextView(a).apply { text = label; textSize = 10f; setTextColor(Palette.MUTED); letterSpacing = 0.06f })
            tiles.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (gap) marginStart = dp(8) })
            return v
        }
        ping = tile("PING ms", Palette.WARN, false)
        jitter = tile("JITTER ms", Palette.WARN, true)
        down = tile("DOWN Mbps", Palette.CYAN, true)
        up = tile("UP Mbps", Palette.VIOLET, true)
        page.addView(tiles, cardParams())

        startBtn = button("Start test") { toggle(compare = false) }.apply {
            background = ripple(gradientRect(dp(16).toFloat(), Palette.CYAN, Palette.VIOLET), dp(16).toFloat())
            setTextColor(Palette.BG); textSize = 16f
        }
        page.addView(startBtn, LinearLayout.LayoutParams(-1, dp(56)))
        compareBtn = button("Compare VPN vs direct") { toggle(compare = true) }
        page.addView(compareBtn)

        val hist = card()
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(cardTitle("History"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(small("Clear").apply {
            setTextColor(Palette.CYAN); setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener { SpeedHistory.clear(a); renderHistory() }
        })
        hist.addView(head)
        history = vbox()
        hist.addView(history)
        page.addView(hist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        renderHistory()
        updateRoute()
        page
    }

    /** The route the test will use: the active VPN server, or direct. */
    private fun currentRoute(): Pair<Dialer, String> {
        val t = if (a.settings.vpnEnabled && ShieldVpnService.isRunning) VpnClient.primary else null
        val upstream = t?.upstream
        return if (t != null && upstream != null) Dialers.via(upstream) to t.display
        else Dialers.direct to "Direct (no VPN)"
    }

    private fun updateRoute() {
        val (_, label) = currentRoute()
        routeLine.text = "Route: $label"
    }

    private fun toggle(compare: Boolean) {
        test?.let { it.cancel(); return }
        val (dialer, label) = currentRoute()
        val runs = mutableListOf(dialer to label)
        if (compare) {
            if (label.startsWith("Direct")) { a.toast("Turn the VPN on to compare"); return }
            runs += Dialers.direct to "Direct (no VPN)"
        }
        startBtn.text = "Stop"
        compareBtn.isEnabled = false
        thread(name = "speed-test") {
            val results = mutableListOf<SpeedResult>()
            for ((d, l) in runs) {
                val t = SpeedTest(d)
                test = t
                a.runOnUiThread { routeLine.text = "Route: $l"; reset() }
                t.onProgress = { p, v, _ -> a.runOnUiThread { progress(p, v) } }
                val r = runCatching { t.run(l) }
                if (r.exceptionOrNull()?.message == "cancelled") break
                r.onSuccess { res ->
                    results += res
                    SpeedHistory.add(a, res)
                    a.runOnUiThread { show(res) }
                }.onFailure { e ->
                    DiagLog.error("speed", "test over $l failed", e)
                    a.runOnUiThread { phase.text = "Test failed: ${e.message ?: e.javaClass.simpleName}" }
                    break
                }
            }
            test = null
            a.runOnUiThread {
                startBtn.text = "Start test"
                compareBtn.isEnabled = true
                renderHistory()
                if (results.size == 2) {
                    val (v, d) = results
                    phase.text = "VPN %.1f↓ / %.1f↑ Mbps vs direct %.1f↓ / %.1f↑ · ping +%.0f ms".format(
                        v.downMbps, v.upMbps, d.downMbps, d.upMbps, v.pingMs - d.pingMs)
                }
                updateRoute()
            }
        }
    }

    private fun reset() {
        listOf(ping, jitter, down, up).forEach { it.text = "—" }
        serverLine.text = ""
        gauge.set(0.0)
    }

    private fun progress(p: SpeedTest.Phase, v: Double) {
        when (p) {
            SpeedTest.Phase.PING -> { gauge.unit = "ms"; gauge.caption = "Ping"; gauge.set(v); phase.text = "Measuring latency…"; ping.text = "%.0f".format(v) }
            SpeedTest.Phase.DOWNLOAD -> { gauge.unit = "Mbps"; gauge.caption = "Download"; gauge.set(v); phase.text = "Downloading…"; down.text = "%.1f".format(v) }
            SpeedTest.Phase.UPLOAD -> { gauge.unit = "Mbps"; gauge.caption = "Upload"; gauge.set(v); phase.text = "Uploading…"; up.text = "%.1f".format(v) }
            SpeedTest.Phase.DONE -> Unit
        }
    }

    private fun show(r: SpeedResult) {
        ping.text = "%.0f".format(r.pingMs)
        jitter.text = "%.0f".format(r.jitterMs)
        down.text = "%.1f".format(r.downMbps)
        up.text = "%.1f".format(r.upMbps)
        gauge.unit = "Mbps"; gauge.caption = "Download"; gauge.set(r.downMbps)
        phase.text = "Done"
        serverLine.text = "${r.server} · your IP there: ${r.ip}"
    }

    private fun renderHistory() = with(a) {
        history.removeAllViews()
        val items = SpeedHistory.all(a).take(10)
        if (items.isEmpty()) { history.addView(small("No tests yet.").apply { setPadding(0, dp(8), 0, 0) }); return@with }
        for (r in items) {
            history.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(TextView(a).apply {
                    text = "↓ %.1f   ↑ %.1f Mbps   ·   %.0f ms".format(r.downMbps, r.upMbps, r.pingMs)
                    textSize = 14f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Palette.TEXT)
                })
                addView(small("${DateFormat.format("d MMM HH:mm", r.time)} · ${r.route} · ${r.server}").apply { textSize = 11f })
            })
        }
    }
}
