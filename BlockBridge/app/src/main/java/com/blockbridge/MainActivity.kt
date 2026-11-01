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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Dashboard laid out like uBlock Origin's: Filter lists, My filters, Trusted sites, Logger, Settings. */
class MainActivity : Activity() {
    private companion object {
        const val REQ_VPN = 1
        const val REQ_IMPORT = 2
        val RED = Color.parseColor("#E53935")
        val GREEN = Color.parseColor("#43A047")
        val AMBER = Color.parseColor("#F9A825")
        val TABS = listOf("Filter lists", "My filters", "Trusted sites", "Logger", "Settings", "VPN")
    }

    private lateinit var settings: Settings
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var statsText: TextView
    private lateinit var warnText: TextView
    private lateinit var tabBar: LinearLayout
    private lateinit var content: LinearLayout
    private val pages = HashMap<Int, View>()
    private var currentTab = 0

    // Filter lists tab
    private lateinit var listInfo: TextView
    private lateinit var applyBtn: Button
    private lateinit var updateBtn: Button
    private val countViews = HashMap<String, TextView>()
    private var listsDirty = false

    // Editors
    private lateinit var myFiltersEdit: EditText
    private lateinit var trustedEdit: EditText
    private lateinit var up1Edit: EditText
    private lateinit var up2Edit: EditText

    // VPN
    private lateinit var vpnStatus: TextView
    private lateinit var vpnList: LinearLayout
    private lateinit var vpnToggle: CheckBox

    // Logger
    private lateinit var loggerBox: LinearLayout
    private var shownVersion = -1L
    private var loggerFilter = 0 // 0 all, 1 blocked, 2 allowed

    private val handler = Handler(Looper.getMainLooper())
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        statusText = TextView(this).apply {
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }
        header.addView(statusText)
        toggleBtn = Button(this).apply { textSize = 18f; setOnClickListener { toggle() } }
        header.addView(toggleBtn, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(6) })
        statsText = body("").apply { gravity = Gravity.CENTER }
        header.addView(statsText)
        warnText = body("").apply { setTextColor(AMBER); visibility = View.GONE }
        header.addView(warnText)

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TABS.forEachIndexed { i, name ->
            tabBar.addView(Button(this).apply {
                text = name; isAllCaps = false; textSize = 14f
                setOnClickListener { showTab(i) }
            })
        }
        header.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, pad * 2)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(content)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        showTab(savedInstanceState?.getInt("tab") ?: 0)
        thread { Blocklists.loadAll(applicationContext) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
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

    private fun showTab(i: Int) {
        currentTab = i
        for (t in 0 until tabBar.childCount) tabBar.getChildAt(t).alpha = if (t == i) 1f else 0.55f
        content.removeAllViews()
        content.addView(pages.getOrPut(i) {
            when (i) {
                0 -> buildListsPage()
                1 -> buildMyFiltersPage()
                2 -> buildTrustedPage()
                3 -> buildLoggerPage()
                4 -> buildSettingsPage()
                else -> buildVpnPage()
            }
        })
        if (i == 3) { shownVersion = -1; refresh() }
    }

    // ---------------- Filter lists ----------------

    private fun buildListsPage(): View {
        val page = vbox()
        listInfo = body("")
        page.addView(listInfo)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        applyBtn = button("Apply changes") { listsDirty = false; runUpdate(force = false) }
        updateBtn = button("Update now") { runUpdate(force = true) }
        buttons.addView(applyBtn, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(updateBtn, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(buttons)

        val lists = Catalog.all(this)
        for ((group, title) in Catalog.GROUP_TITLES) {
            val inGroup = lists.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            val box = vbox()
            val on = inGroup.count { settings.isEnabled(it) }
            val expanded = group in setOf("default", "ads", "privacy", "malware") || on > 0
            val head = header("${if (expanded) "▾" else "▸"} $title  ($on/${inGroup.size})")
            box.visibility = if (expanded) View.VISIBLE else View.GONE
            head.setOnClickListener {
                val show = box.visibility != View.VISIBLE
                box.visibility = if (show) View.VISIBLE else View.GONE
                val n = inGroup.count { settings.isEnabled(it) }
                head.text = "${if (show) "▾" else "▸"} $title  ($n/${inGroup.size})"
            }
            page.addView(head)
            for (l in inGroup) {
                box.addView(CheckBox(this).apply {
                    text = l.title
                    isChecked = settings.isEnabled(l)
                    setOnCheckedChangeListener { _, checked ->
                        settings.setEnabled(l, checked)
                        listsDirty = true
                    }
                })
                val count = small(settings.listCount(l.key) ?: "").apply {
                    setPadding(dp(32), 0, 0, dp(4))
                    visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                }
                countViews[l.key] = count
                box.addView(count)
            }
            page.addView(box)
        }
        page.addView(small(
            "\"used\" = rules BlockBridge can apply for the whole phone (hostname rules). " +
                "The rest are cosmetic/URL-path rules that only work inside a browser extension."
        ).apply { setPadding(0, dp(12), 0, 0) })
        return page
    }

    private fun refreshCounts() {
        for ((k, v) in countViews) {
            val c = settings.listCount(k) ?: ""
            v.text = c
            v.visibility = if (c.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---------------- My filters ----------------

    private fun buildMyFiltersPage(): View {
        val page = vbox()
        page.addView(body("Your own rules, in uBlock Origin syntax. One per line."))
        page.addView(small(
            "||ads.example.com^          block a hostname (and its subdomains)\n" +
                "@@||cdn.example.com^        never block it\n" +
                "||tracker.com^\$important    block even if a list allows it\n" +
                "||x.com^\$badfilter          cancel a rule from a list\n" +
                "0.0.0.0 bad.com / bad.com   hosts-file style also works\n" +
                "! comment"
        ).apply { typeface = Typeface.MONOSPACE; textSize = 11f })
        myFiltersEdit = multiline(settings.myFilters, "||ads.example.com^")
        page.addView(myFiltersEdit)
        page.addView(button("Apply changes") {
            settings.myFilters = myFiltersEdit.text.toString()
            thread { Blocklists.loadUserRules(settings) }
            toast("My filters applied")
        })
        return page
    }

    // ---------------- Trusted sites ----------------

    private fun buildTrustedPage(): View {
        val page = vbox()
        page.addView(body(
            "Hostnames that are never filtered — like uBO's trusted sites. Use this when a site " +
                "or app breaks. One per line; subdomains are included."
        ))
        trustedEdit = multiline(settings.trustedSites, "example.com")
        page.addView(trustedEdit)
        page.addView(button("Apply changes") {
            settings.trustedSites = trustedEdit.text.toString()
            thread { Blocklists.loadUserRules(settings) }
            toast("Trusted sites applied")
        })
        return page
    }

    // ---------------- Logger ----------------

    private fun buildLoggerPage(): View {
        val page = vbox()
        page.addView(small("Every hostname looked up on this phone. Tap one to allow, block or trust it."))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All", "Blocked", "Allowed").forEachIndexed { i, label ->
            filters.addView(button(label) { loggerFilter = i; shownVersion = -1; refresh() },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        filters.addView(button("Clear") { Stats.clear(); shownVersion = -1; refresh() },
            LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(filters)
        loggerBox = vbox()
        page.addView(loggerBox)
        return page
    }

    private fun renderLogger() {
        loggerBox.removeAllViews()
        val entries = Stats.recent().filter {
            when (loggerFilter) { 1 -> it.blocked; 2 -> !it.blocked; else -> true }
        }.take(100)
        if (entries.isEmpty()) {
            loggerBox.addView(small("Nothing yet. Turn protection on and browse in Chrome."))
            return
        }
        for (e in entries) {
            val by = Catalog.title(this, e.by)
            loggerBox.addView(TextView(this).apply {
                val time = DateFormat.format("HH:mm:ss", e.time)
                text = "$time  ${if (e.blocked) "✕" else "✓"}  ${e.domain}" +
                    if (by.isNotEmpty()) "\n            ${if (e.blocked) "blocked" else "allowed"} by $by" else ""
                textSize = 13f
                typeface = Typeface.MONOSPACE
                if (e.blocked) setTextColor(RED) else if (e.by != null) setTextColor(GREEN)
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { loggerAction(e) }
            })
        }
    }

    private fun loggerAction(e: Stats.Entry) {
        val options = if (e.blocked) arrayOf(
            "Allow ${e.domain}  (adds @@||${e.domain}^)",
            "Trust ${e.domain}  (never filter it)",
        ) else arrayOf("Block ${e.domain}  (adds ||${e.domain}^)")
        AlertDialog.Builder(this)
            .setTitle(e.domain)
            .setItems(options) { _, which ->
                when {
                    !e.blocked -> appendLine(isTrusted = false, "||${e.domain}^")
                    which == 0 -> appendLine(isTrusted = false, "@@||${e.domain}^")
                    else -> appendLine(isTrusted = true, e.domain)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appendLine(isTrusted: Boolean, line: String) {
        val cur = (if (isTrusted) settings.trustedSites else settings.myFilters).trimEnd()
        if (cur.lines().none { it.trim() == line }) {
            val next = if (cur.isEmpty()) line else "$cur\n$line"
            if (isTrusted) settings.trustedSites = next else settings.myFilters = next
        }
        if (::myFiltersEdit.isInitialized) myFiltersEdit.setText(settings.myFilters)
        if (::trustedEdit.isInitialized) trustedEdit.setText(settings.trustedSites)
        thread { Blocklists.loadUserRules(settings) }
        toast("Added: $line")
    }

    // ---------------- Settings ----------------

    private fun buildSettingsPage(): View {
        val page = vbox()
        page.addView(header("DNS server"))
        page.addView(small("Allowed lookups are sent here. 1.1.1.1 = Cloudflare, 8.8.8.8 = Google, 9.9.9.9 = Quad9."))
        up1Edit = singleLine(settings.upstream1, "Main")
        up2Edit = singleLine(settings.upstream2, "Backup")
        page.addView(up1Edit)
        page.addView(up2Edit)
        page.addView(button("Save DNS") {
            val u1 = up1Edit.text.toString().trim()
            val u2 = up2Edit.text.toString().trim()
            if (!Upstream.isValid(u1) || (u2.isNotEmpty() && !Upstream.isValid(u2))) {
                toast("DNS server must be an IP address, e.g. 1.1.1.1")
            } else {
                settings.upstream1 = u1; settings.upstream2 = u2
                Upstream.reload(settings)
                toast("Saved")
            }
        })

        page.addView(header("Traffic"))
        page.addView(CheckBox(this).apply {
            text = "Send all app traffic through BlockBridge"
            isChecked = settings.fullTunnel && HevTunnel.available
            isEnabled = HevTunnel.available
            setOnCheckedChangeListener { _, on ->
                settings.fullTunnel = on
                if (ShieldVpnService.isRunning) toast("Turn protection off and on again to switch mode")
            }
        })
        page.addView(small(
            if (HevTunnel.available)
                "On: every connection from every app passes through BlockBridge. DNS sent to any " +
                    "server (apps that skip Android's DNS) is filtered too, and connections to " +
                    "blocked hosts are refused. Uses a little more battery.\n" +
                    "Off: only DNS lookups pass through (lowest battery use)."
            else "This build doesn't include the full-traffic engine, so only DNS lookups are filtered."
        ))

        page.addView(header("Backup"))
        page.addView(small("Selected lists, My filters and Trusted sites. Restore also accepts the " +
            "\"userFilters\" / \"whitelist\" parts of a uBlock Origin backup file."))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Back up") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BlockBridge backup")
                putExtra(Intent.EXTRA_TEXT, settings.backup(this@MainActivity))
            }, "Save backup to…"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Restore") { restoreDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row)

        page.addView(header("Make sure Chrome uses it"))
        page.addView(body(
            "• Chrome › Settings › Privacy and security › Use secure DNS → \"Use your current " +
                "service provider\" (or off). A custom secure DNS in Chrome skips BlockBridge.\n" +
                "• Android Settings › Private DNS → Off or Automatic.\n" +
                "• Keep it on: Android Settings › Network › VPN › BlockBridge › ⚙ › Always-on VPN.\n" +
                "• Only one VPN app can run at a time."
        ))
        page.addView(header("What carries over from uBlock Origin"))
        page.addView(body(
            "✓ uBO's filter list catalogue with the same defaults\n" +
                "✓ Hostname rules, exceptions, \$important, \$badfilter, !#if directives\n" +
                "✓ My filters, Trusted sites, Logger, backup/restore, list auto-update\n" +
                "✓ Works for every app on the phone, not only the browser\n" +
                "✗ Cosmetic filters (hiding page elements) and scriptlets — e.g. YouTube video ads. " +
                "These need to run inside the web page, which only a browser extension can do."
        ))
        return page
    }

    private fun restoreDialog() {
        val input = multiline("", "Paste backup text here")
        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                try {
                    settings.restore(this, input.text.toString())
                    Upstream.reload(settings)
                    pages.clear()
                    showTab(currentTab)
                    runUpdate(force = false)
                    thread { Blocklists.loadUserRules(settings) }
                } catch (e: Exception) {
                    toast("That doesn't look like a backup: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- VPN ----------------

    private fun buildVpnPage(): View {
        val page = vbox()
        vpnStatus = body("").apply { setTypeface(typeface, Typeface.BOLD) }
        page.addView(vpnStatus)
        if (!VpnClient.available(this) || !HevTunnel.available) {
            page.addView(body("This build doesn't include the VPN engine."))
            return page
        }
        vpnToggle = CheckBox(this).apply {
            text = "Use VPN server (show the server's country)"
            textSize = 16f
            isChecked = settings.vpnEnabled
            setOnCheckedChangeListener { _, on ->
                settings.vpnEnabled = on
                applyVpn()
            }
        }
        page.addView(vpnToggle)
        page.addView(small(
            "While on, websites see the server's IP and country, and lookups go through it too. " +
                "Ads stay blocked. If the server can't be reached, internet is paused rather than " +
                "falling back to your real location (kill switch)."
        ))

        page.addView(header("Servers"))
        vpnList = vbox()
        page.addView(vpnList)
        renderVpnList()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Import .conf file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Paste config") { pasteConfigDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row)
        page.addView(button("Check my location") {
            VpnClient.exitInfo = "Checking…"
            thread { VpnClient.checkLocation() }
        })

        page.addView(header("Free servers: Proton VPN"))
        page.addView(body(
            "1. Make a free account at protonvpn.com (no card needed).\n" +
                "2. On a browser go to account.protonvpn.com › Downloads › WireGuard configuration.\n" +
                "3. Platform: Android. Pick a free server (Free plan: a few countries such as " +
                "Netherlands, USA, Japan…), press Create, then Download.\n" +
                "4. Here: Import .conf file. Repeat for each country you want."
        ))
        page.addView(header("Your own free server"))
        page.addView(body(
            "Oracle Cloud's Always Free tier gives you a server in the region you pick (sign-up " +
                "asks for a card to verify you). Run BlockBridge/server/setup-wireguard-server.sh " +
                "from this project on it, then paste the config it prints."
        ))
        page.addView(header("Making Google agree"))
        page.addView(body(
            "Google also uses your phone's location, not just the IP:\n" +
                "• Turn off Location (quick settings) while using the VPN.\n" +
                "• Search › Settings › Region can be set to the server's country.\n" +
                "• Your SIM country and Play Store country (set by your payments profile) don't " +
                "change with a VPN.\n" +
                "• Streaming apps may block VPN servers, and unlocking other regions can break " +
                "their terms of use."
        ))
        return page
    }

    private fun renderVpnList() {
        vpnList.removeAllViews()
        val profiles = VpnProfiles.all(this)
        if (profiles.isEmpty()) {
            vpnList.addView(small("No servers yet — import one below."))
            return
        }
        val selected = VpnProfiles.selected(this)?.id
        for (p in profiles) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "${if (p.id == selected) "◉" else "○"}  ${p.name}"
                textSize = 16f
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    VpnProfiles.select(this@MainActivity, p.id)
                    renderVpnList()
                    if (settings.vpnEnabled) applyVpn()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Delete") {
                AlertDialog.Builder(this)
                    .setMessage("Delete ${p.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        val wasSelected = p.id == VpnProfiles.selected(this)?.id
                        VpnProfiles.remove(this, p.id)
                        renderVpnList()
                        if (wasSelected && settings.vpnEnabled) applyVpn()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            vpnList.addView(row)
        }
    }

    private fun pasteConfigDialog() {
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val name = singleLine("", "Name, e.g. Netherlands")
        val conf = multiline("", "[Interface]\nPrivateKey = …\n\n[Peer]\n…")
        box.addView(name); box.addView(conf)
        AlertDialog.Builder(this)
            .setTitle("Paste WireGuard config")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                addProfile(name.text.toString().ifBlank { "VPN server" }, conf.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addProfile(name: String, conf: String) {
        val problem = VpnProfile.validate(conf)
        if (problem != null) { toast("Not a WireGuard config: $problem"); return }
        val first = VpnProfiles.all(this).isEmpty()
        VpnProfiles.add(this, name, conf)
        if (::vpnList.isInitialized) renderVpnList()
        toast("Added $name")
        if (first && settings.vpnEnabled) applyVpn()
    }

    private fun importFile(uri: android.net.Uri) {
        try {
            var name: String? = null
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().take(64_000).toByteArray() }
                ?.toString(Charsets.UTF_8) ?: throw java.io.IOException("can't read file")
            addProfile(VpnProfile.nameFrom(name), text)
        } catch (e: Exception) {
            toast("Couldn't import: ${e.message}")
        }
    }

    private fun applyVpn() {
        if (ShieldVpnService.isRunning) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_APPLY_VPN))
        } else {
            toast(if (settings.vpnEnabled) "Saved — turn protection on to connect" else "Saved")
        }
    }

    // ---------------- Power + status ----------------

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
        if (requestCode == REQ_IMPORT) {
            if (resultCode == RESULT_OK) data?.data?.let { importFile(it) }
            return
        }
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
            runOnUiThread { toast(msg); refreshCounts() }
        }
    }

    private fun refresh() {
        val on = ShieldVpnService.isRunning
        statusText.text = if (on) "🛡 Protection ON" else "Protection OFF"
        statusText.setTextColor(if (on) GREEN else RED)
        toggleBtn.text = if (on) "Turn off" else "Turn on"

        val total = Stats.total.get()
        val blocked = Stats.blocked.get()
        val pct = if (total > 0) blocked * 100 / total else 0
        statsText.text = ShieldVpnService.lastError
            ?: buildString {
                append("Lookups: ${"%,d".format(total)}   Blocked: ${"%,d".format(blocked)} ($pct%)")
                if (ShieldVpnService.fullTunnel) {
                    append("\nAll traffic • ${TrafficStats.active.get()} open connections • ")
                    append("↑ ${mb(TrafficStats.bytesUp.get())} ↓ ${mb(TrafficStats.bytesDown.get())}")
                    val refused = TrafficStats.blockedConnections.get()
                    if (refused > 0) append("\n${"%,d".format(refused)} connections to blocked hosts refused")
                } else if (on) append("\nDNS-only mode")
            }

        if (::vpnStatus.isInitialized) {
            vpnStatus.text = buildString {
                append(
                    when {
                        !settings.vpnEnabled -> "VPN off — websites see your real location"
                        !on -> "VPN on — turn protection on to connect"
                        else -> "VPN: ${VpnClient.state}"
                    }
                )
                val hs = VpnClient.lastHandshake
                if (settings.vpnEnabled && on && hs > 0) {
                    append("  (last contact ${(System.currentTimeMillis() - hs) / 1000}s ago)")
                }
                VpnClient.error?.let { if (settings.vpnEnabled) append("\n⚠ $it") }
                VpnClient.exitInfo?.let { append("\n\n$it") }
            }
            vpnStatus.setTextColor(
                if (settings.vpnEnabled && on && VpnClient.lastHandshake > 0) GREEN
                else if (settings.vpnEnabled && VpnClient.error != null) RED else statsText.currentTextColor
            )
        }

        if (::listInfo.isInitialized) {
            val busy = Blocklists.status
            updateBtn.isEnabled = busy.isEmpty()
            applyBtn.isEnabled = busy.isEmpty()
            applyBtn.text = if (listsDirty) "Apply changes •" else "Apply changes"
            val last = settings.lastUpdate
            listInfo.text = when {
                busy.isNotEmpty() -> busy
                Filter.blockCount == 0 -> "No filters yet — they download when you turn protection on."
                else -> "${"%,d".format(Filter.blockCount)} hostnames blocked. Last update: " +
                    (if (last > 0) DateFormat.format("d MMM, HH:mm", last) else "—") +
                    ". Lists refresh automatically when they expire."
            }
        }

        if (ticks++ % 5 == 0) {
            val w = privateDnsWarning()
            warnText.text = w ?: ""
            warnText.visibility = if (w == null) View.GONE else View.VISIBLE
        }

        if (currentTab == 3 && Stats.version != shownVersion) {
            shownVersion = Stats.version
            renderLogger()
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

    // ---------------- small UI helpers ----------------

    private fun mb(bytes: Long) = if (bytes < 1_000_000) "${bytes / 1000} KB" else "%.1f MB".format(bytes / 1e6)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setPadding(0, dp(6), 0, dp(6))
    }

    private fun small(t: String) = TextView(this).apply {
        text = t; textSize = 13f; alpha = 0.75f
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun multiline(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 6; gravity = Gravity.TOP
        typeface = Typeface.MONOSPACE; textSize = 13f
        setHorizontallyScrolling(false)
    }

    private fun singleLine(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
    }
}
