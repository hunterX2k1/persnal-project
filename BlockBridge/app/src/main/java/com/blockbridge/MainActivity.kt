package com.blockbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private companion object {
        const val REQ_VPN = 1
        val RED = Color.parseColor("#E53935")
        val GREEN = Color.parseColor("#43A047")
        val AMBER = Color.parseColor("#F9A825")
    }

    private lateinit var settings: Settings
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var statsText: TextView
    private lateinit var listInfo: TextView
    private lateinit var warnText: TextView
    private lateinit var updateBtn: Button
    private lateinit var recentBox: LinearLayout
    private lateinit var blockEdit: EditText
    private lateinit var allowEdit: EditText
    private lateinit var up1Edit: EditText
    private lateinit var up2Edit: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var shownVersion = -1L
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }

        // --- Status ---
        statusText = TextView(this).apply {
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }
        root.addView(statusText)
        toggleBtn = Button(this).apply { textSize = 18f; setOnClickListener { toggle() } }
        root.addView(toggleBtn, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
        statsText = body("").apply { gravity = Gravity.CENTER }
        root.addView(statsText)
        warnText = body("").apply { setTextColor(AMBER); visibility = View.GONE }
        root.addView(warnText)

        // --- Blocklists ---
        root.addView(header("Blocklists"))
        listInfo = body("")
        root.addView(listInfo)
        for (src in Sources.all) {
            val cb = CheckBox(this).apply {
                text = src.name
                isChecked = settings.isEnabled(src)
                setOnCheckedChangeListener { _, on ->
                    settings.setEnabled(src, on)
                    runUpdate(force = false)
                }
            }
            root.addView(cb)
            root.addView(small(src.description).apply { setPadding(dp(32), 0, 0, dp(6)) })
        }
        updateBtn = button("Update blocklists now") { runUpdate(force = true) }
        root.addView(updateBtn)

        // --- Recent activity ---
        root.addView(header("Recent requests"))
        root.addView(small("Tap a domain to allow or block it. Red = blocked."))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)

        // --- Custom rules ---
        root.addView(header("My rules"))
        root.addView(small("Always block (one domain per line, also covers its subdomains):"))
        blockEdit = multiline(settings.userBlock, "ads.example.com")
        root.addView(blockEdit)
        root.addView(small("Never block — use this if a site breaks:"))
        allowEdit = multiline(settings.userAllow, "example.com")
        root.addView(allowEdit)

        root.addView(header("Real DNS server"))
        root.addView(small("Allowed lookups are sent here. 1.1.1.1 = Cloudflare, 8.8.8.8 = Google, 9.9.9.9 = Quad9."))
        up1Edit = singleLine(settings.upstream1, "Main")
        up2Edit = singleLine(settings.upstream2, "Backup")
        root.addView(up1Edit)
        root.addView(up2Edit)
        root.addView(button("Save rules & DNS") { saveRules() })

        // --- Help ---
        root.addView(header("Make sure Chrome uses it"))
        root.addView(
            body(
                "• Chrome › Settings › Privacy and security › Use secure DNS → choose " +
                    "\"Use your current service provider\" (or turn it off). A custom secure DNS " +
                    "in Chrome skips BlockBridge.\n" +
                    "• Android Settings › Private DNS → Off or Automatic.\n" +
                    "• To keep it on all the time: Android Settings › Network › VPN › BlockBridge " +
                    "› ⚙ › Always-on VPN.\n" +
                    "• Only one VPN app can run at a time.\n" +
                    "• Changes may take a minute to apply because Chrome remembers lookups briefly."
            )
        )

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        thread { Blocklists.loadAll(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        ticks = 0
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun toggle() {
        if (ShieldVpnService.isRunning) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN) else startShield()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startShield()
            else toast("BlockBridge needs the VPN permission to filter DNS.")
        }
    }

    private fun startShield() {
        startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
    }

    private fun runUpdate(force: Boolean) {
        val ctx = applicationContext
        thread {
            val msg = try { Blocklists.update(ctx, force) } catch (e: Exception) { "Update failed: ${e.message}" }
            runOnUiThread { toast(msg) }
        }
    }

    private fun saveRules() {
        val u1 = up1Edit.text.toString().trim()
        val u2 = up2Edit.text.toString().trim()
        if (!Upstream.isValid(u1) || (u2.isNotEmpty() && !Upstream.isValid(u2))) {
            toast("DNS server must be an IP address, e.g. 1.1.1.1")
            return
        }
        settings.userBlock = blockEdit.text.toString()
        settings.userAllow = allowEdit.text.toString()
        settings.upstream1 = u1
        settings.upstream2 = u2
        thread {
            Blocklists.loadUserRules(settings)
            Upstream.reload(settings)
        }
        toast("Saved")
    }

    private fun addRule(domain: String, allow: Boolean) {
        val target = if (allow) allowEdit else blockEdit
        val other = if (allow) blockEdit else allowEdit
        // Remove from the opposite list so the new choice wins.
        other.setText(other.text.lines().filter { ListParser.userDomain(it) != domain }.joinToString("\n"))
        val cur = target.text.toString().trimEnd()
        if (cur.lines().none { ListParser.userDomain(it) == domain }) {
            target.setText(if (cur.isEmpty()) domain else "$cur\n$domain")
        }
        settings.userBlock = blockEdit.text.toString()
        settings.userAllow = allowEdit.text.toString()
        thread { Blocklists.loadUserRules(settings) }
        toast(if (allow) "$domain will no longer be blocked" else "$domain will be blocked")
    }

    private fun refresh() {
        val on = ShieldVpnService.isRunning
        statusText.text = if (on) "🛡 Protection ON" else "Protection OFF"
        statusText.setTextColor(if (on) GREEN else RED)
        toggleBtn.text = if (on) "Stop" else "Start"

        val total = Stats.total.get()
        val blocked = Stats.blocked.get()
        val pct = if (total > 0) blocked * 100 / total else 0
        statsText.text = ShieldVpnService.lastError
            ?: "Lookups: ${"%,d".format(total)}   Blocked: ${"%,d".format(blocked)} ($pct%)"

        val busy = Blocklists.status
        updateBtn.isEnabled = busy.isEmpty()
        val last = settings.lastUpdate
        listInfo.text = when {
            busy.isNotEmpty() -> busy
            Filter.blockCount == 0 -> "No blocklist yet — it downloads automatically when you tap Start."
            else -> "${"%,d".format(Filter.blockCount)} domains blocked. Updated " +
                (if (last > 0) DateFormat.format("d MMM, HH:mm", last) else "—") +
                ". Refreshes automatically every 3 days."
        }

        if (ticks++ % 5 == 0) {
            val w = privateDnsWarning()
            warnText.text = w ?: ""
            warnText.visibility = if (w == null) View.GONE else View.VISIBLE
        }

        if (Stats.version != shownVersion) {
            shownVersion = Stats.version
            renderRecent()
        }
    }

    private fun renderRecent() {
        recentBox.removeAllViews()
        val entries = Stats.recent().take(50)
        if (entries.isEmpty()) {
            recentBox.addView(small("Nothing yet. Start protection and browse in Chrome."))
            return
        }
        for (e in entries) {
            val row = TextView(this).apply {
                val time = DateFormat.format("HH:mm:ss", e.time)
                text = "$time  ${if (e.blocked) "✕" else "✓"}  ${e.domain}"
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(if (e.blocked) RED else currentTextColor)
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(e.domain)
                        .setMessage(if (e.blocked) "This domain is blocked." else "This domain is allowed.")
                        .setPositiveButton(if (e.blocked) "Allow it" else "Block it") { _, _ ->
                            addRule(e.domain, allow = e.blocked)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            recentBox.addView(row)
        }
    }

    /** Android "Private DNS" with a hostname sends lookups around BlockBridge. */
    @Suppress("DEPRECATION")
    private fun privateDnsWarning(): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val strict = cm.allNetworks.any { cm.getLinkProperties(it)?.privateDnsServerName != null }
        return if (strict) "⚠ Android Private DNS is set to a specific provider, so lookups skip " +
            "BlockBridge. Set Settings › Private DNS to Off or Automatic." else null
    }

    // --- small UI helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setPadding(0, dp(6), 0, dp(6))
    }

    private fun small(t: String) = TextView(this).apply {
        text = t; textSize = 13f; alpha = 0.75f
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
    }

    private fun multiline(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 3; gravity = Gravity.TOP
        typeface = Typeface.MONOSPACE; textSize = 14f
    }

    private fun singleLine(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
    }
}
