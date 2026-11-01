package com.blockbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * BlockBridge's dashboard: Home (power, ad-block and VPN switches, live stats), Filters
 * (uBO lists, My filters, Trusted sites), VPN, Logger and Settings.
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_FILTERS = 1
        const val TAB_VPN = 2
        const val TAB_NETWORK = 3
        const val TAB_LOGGER = TAB_NETWORK
        const val TAB_SPEED = 4
        const val TAB_SETTINGS = 5
        private const val REQ_VPN = 1
        private const val REQ_IMPORT = 2
        private const val REQ_NOTIF = 3
        private val RED = Palette.BAD
        private val GREEN = Palette.GOOD
        private val AMBER = Palette.WARN
        private val NAV = listOf(
            "Home" to R.drawable.ic_home, "Filters" to R.drawable.ic_filter, "VPN" to R.drawable.ic_globe,
            "Network" to R.drawable.ic_network, "Speed" to R.drawable.ic_speed, "Settings" to R.drawable.ic_tune,
        )
    }

    internal lateinit var settings: Settings
    internal val networkPage by lazy { NetworkPage(this) }
    internal val speedPage by lazy { SpeedPage(this) }
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var navBar: LinearLayout
    private val pages = HashMap<Int, View>()
    private var currentTab = 0

    // Home
    private lateinit var power: PowerButton
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var liveChip: TextView
    private lateinit var adSwitch: Switch
    private lateinit var adSub: TextView
    private lateinit var vpnSwitch: Switch
    private lateinit var vpnSub: TextView
    private lateinit var tileBlocked: TextView
    private lateinit var tileLookups: TextView
    private lateinit var tileData: TextView
    private lateinit var tileDataLabel: TextView
    private lateinit var spark: Sparkline
    private lateinit var weekBars: WeekBars
    private lateinit var lifetimeText: TextView
    private lateinit var recentBlocked: LinearLayout
    private lateinit var warnText: TextView
    private var lastTotal = -1L
    private var lastBlocked = -1L
    private var syncing = false

    // Filters
    private var filterSub = 0
    private val filterPages = HashMap<Int, View>()

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
        Emergency.activity = WeakReference(this)
        Filter.enabled = settings.adBlockEnabled

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0E1426.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(8))
            elevation = dp(8).toFloat()
        }
        NAV.forEachIndexed { i, (label, icon) ->
            navBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(2))
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    setPadding(dp(14), dp(4), dp(14), dp(4))
                }, LinearLayout.LayoutParams(-2, dp(30)))
                addView(TextView(context).apply { text = label; textSize = 10.5f; gravity = Gravity.CENTER; maxLines = 1 })
                setOnClickListener { showTab(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(navBar, LinearLayout.LayoutParams(-1, -2))
        })

        showTab(savedInstanceState?.getInt("tab") ?: TAB_HOME)
        thread { Blocklists.loadAll(applicationContext) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ControlService.show(this)
        ListUpdateJob.schedule(this)
        CrashGuard.takeReport(this)?.let { showCrashReport(it) }
        Updates.checkDaily(this) { u -> runOnUiThread { offerUpdate(u) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_TAB)) showTab(i.getIntExtra(EXTRA_TAB, TAB_HOME))
        if (i.getBooleanExtra(EXTRA_START, false) && !ShieldVpnService.isRunning) toggle()
        i.removeExtra(EXTRA_TAB); i.removeExtra(EXTRA_START)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
    }

    private val onState: () -> Unit = { refresh() }

    override fun onResume() {
        super.onResume()
        ticks = 0
        handler.post(ticker)
        StateBus.listen(onState)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        StateBus.remove(onState)
    }

    private fun showTab(i: Int) {
        currentTab = i
        for (t in 0 until navBar.childCount) {
            val item = navBar.getChildAt(t) as LinearLayout
            val sel = t == i
            val icon = item.getChildAt(0) as ImageView
            icon.imageTintList = ColorStateList.valueOf(if (sel) Palette.CYAN else Palette.MUTED)
            icon.background = if (sel) roundRect(0x3322D3EE, dp(16).toFloat()) else null
            (item.getChildAt(1) as TextView).setTextColor(if (sel) Palette.CYAN else Palette.MUTED)
        }
        content.removeAllViews()
        content.addView(pages.getOrPut(i) {
            when (i) {
                TAB_HOME -> buildHomePage()
                TAB_FILTERS -> buildFiltersPage()
                TAB_VPN -> buildVpnPage()
                TAB_NETWORK -> networkPage.build()
                TAB_SPEED -> speedPage.build()
                else -> buildSettingsPage()
            }
        })
        scroll.scrollTo(0, 0)
        if (i == TAB_LOGGER) shownVersion = -1
        refresh()
    }

    // ---------------- Home ----------------

    private fun buildHomePage(): View {
        val page = vbox()

        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "BlockBridge"; textSize = 26f; setTypeface(Typeface.DEFAULT_BOLD)
            letterSpacing = -0.02f; brandGradient()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        liveChip = TextView(this).apply {
            textSize = 11f; setTypeface(Typeface.DEFAULT_BOLD); letterSpacing = 0.08f
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        top.addView(liveChip)
        page.addView(top)
        page.addView(small("uBlock Origin filters · WireGuard VPN · all apps").apply { setPadding(0, 0, 0, dp(12)) })

        // hero
        val hero = card().apply {
            background = gradientRect(dp(24).toFloat(), 0xFF141B33.toInt(), 0xFF1A1440.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        power = PowerButton(this).apply { setOnClickListener { toggle() } }
        hero.addView(power)
        heroTitle = TextView(this).apply { textSize = 24f; setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.CENTER }
        heroSub = TextView(this).apply { textSize = 13f; setTextColor(Palette.MUTED); gravity = Gravity.CENTER }
        hero.addView(heroTitle)
        hero.addView(heroSub)
        page.addView(hero, cardParams())

        // switches
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (adCard, aSw, aSub) = toggleCard(R.drawable.ic_shield, "Ad block", Palette.GOOD) { on ->
            settings.adBlockEnabled = on
            Filter.enabled = on
            StateBus.changed()
        }
        adSwitch = aSw; adSub = aSub
        val (vpnCard, vSw, vSub) = toggleCard(R.drawable.ic_globe, "VPN", Palette.VIOLET) { on ->
            if (on && VpnProfiles.selected(this) == null) {
                syncing = true; vpnSwitch.isChecked = false; syncing = false
                toast("Add a VPN server first")
                showTab(TAB_VPN)
                return@toggleCard
            }
            settings.vpnEnabled = on
            RouteConfig.reload(this)
            if (::vpnToggle.isInitialized) { syncing = true; vpnToggle.isChecked = on; syncing = false }
            applyVpn()
            StateBus.changed()
        }
        vpnSwitch = vSw; vpnSub = vSub
        row.addView(adCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(vpnCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        page.addView(row, cardParams())

        // stat tiles
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(label: String, color: Int, gap: Boolean): Pair<TextView, TextView> {
            val t = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
            val v = TextView(this).apply { textSize = 20f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(color); maxLines = 1 }
            val l = TextView(this).apply { text = label; textSize = 11f; setTextColor(Palette.MUTED); letterSpacing = 0.06f }
            t.addView(v); t.addView(l)
            tiles.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (gap) marginStart = dp(10) })
            return v to l
        }
        tileBlocked = tile("BLOCKED", Palette.BAD, false).first
        tileLookups = tile("LOOKUPS", Palette.CYAN, true).first
        tile("DATA", Palette.VIOLET, true).let { tileData = it.first; tileDataLabel = it.second }
        page.addView(tiles, cardParams())

        // lifetime + last 7 days
        val week = card()
        week.addView(cardTitle("Last 7 days"))
        lifetimeText = small("")
        week.addView(lifetimeText)
        weekBars = WeekBars(this)
        week.addView(weekBars, LinearLayout.LayoutParams(-1, dp(90)).apply { topMargin = dp(8) })
        page.addView(week, cardParams())

        // live chart
        val chart = card()
        val chartHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chartHead.addView(cardTitle("Live activity"), LinearLayout.LayoutParams(0, -2, 1f))
        chartHead.addView(small("● lookups").apply { setTextColor(Palette.CYAN) })
        chartHead.addView(small("   ● blocked").apply { setTextColor(Palette.BAD) })
        chart.addView(chartHead)
        spark = Sparkline(this)
        chart.addView(spark, LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(10) })
        page.addView(chart, cardParams())

        // recently blocked
        val recent = card()
        recent.addView(cardTitle("Recently blocked"))
        recentBlocked = vbox()
        recent.addView(recentBlocked)
        page.addView(recent, cardParams())

        warnText = body("").apply {
            setTextColor(Palette.WARN)
            background = roundRect(0x22FBBF24, dp(16).toFloat(), 0x55FBBF24, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        page.addView(warnText, cardParams())

        // emergency kill
        val kill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(roundRect(0x22EF4444, dp(18).toFloat(), Palette.DANGER, dp(1)), dp(18).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Emergency kill?")
                    .setMessage("Stops protection and the VPN, removes the notification and closes BlockBridge completely.")
                    .setPositiveButton("Kill now") { _, _ -> Emergency.killEverything(this@MainActivity) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        kill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_bolt); imageTintList = ColorStateList.valueOf(Palette.DANGER)
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val killText = vbox().apply { setPadding(dp(12), 0, 0, 0) }
        killText.addView(TextView(this).apply {
            text = "Emergency kill"; textSize = 16f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Palette.DANGER)
        })
        killText.addView(small("Stop everything and close the app instantly"))
        kill.addView(killText)
        page.addView(kill, cardParams())
        return page
    }

    /** A card with an icon, a title, a status line and a switch. Tapping the card flips it. */
    private fun toggleCard(icon: Int, title: String, color: Int, onChange: (Boolean) -> Unit): Triple<View, Switch, TextView> {
        val c = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(color)
            background = roundRect((color and 0x00FFFFFF) or 0x26000000, dp(12).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val sw = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(color, Palette.MUTED))
            trackTintList = ColorStateList(states, intArrayOf((color and 0x00FFFFFF) or 0x66000000, Palette.STROKE))
            setOnCheckedChangeListener { _, on -> if (!syncing) onChange(on) }
        }
        head.addView(sw)
        c.addView(head)
        c.addView(TextView(this).apply {
            text = title; textSize = 16f; setTypeface(Typeface.DEFAULT_BOLD); setPadding(0, dp(10), 0, 0)
        })
        val sub = small("")
        sub.maxLines = 2
        c.addView(sub)
        c.isClickable = true
        c.setOnClickListener { sw.toggle() }
        return Triple(c, sw, sub)
    }

    private fun refreshHome(on: Boolean) {
        if (!::power.isInitialized) return
        val vpnWanted = settings.vpnEnabled
        val vs = VpnStatus.of(this)
        val vpnUp = vs.link == VpnStatus.Link.CONNECTED
        power.on = on
        power.busy = on && vs.link == VpnStatus.Link.CONNECTING
        heroTitle.text = when {
            !on -> "Not protected"
            vs.link == VpnStatus.Link.CONNECTING -> "Connecting VPN…"
            vs.link == VpnStatus.Link.FAILED -> "VPN not connected"
            else -> "Protected"
        }
        heroTitle.setTextColor(if (on) Palette.TEXT else Palette.MUTED)
        heroSub.text = ShieldVpnService.lastError ?: when {
            !on -> "Tap the button to turn BlockBridge on"
            else -> listOfNotNull(
                if (ShieldVpnService.fullTunnel) "All app traffic" else "DNS filtering",
                if (settings.adBlockEnabled) "ads blocked" else "ads allowed",
                if (vpnWanted) VpnProfiles.selected(this)?.let { "${Flags.of(it.name)} ${it.name}" } else null,
            ).joinToString(" · ")
        }
        liveChip.text = if (on) "● LIVE" else "● OFF"
        liveChip.setTextColor(if (on) Palette.GOOD else Palette.MUTED)
        liveChip.background = roundRect(if (on) 0x2234D399 else 0x228A94B3, dp(99).toFloat())

        syncing = true
        adSwitch.isChecked = settings.adBlockEnabled
        vpnSwitch.isChecked = vpnWanted
        syncing = false
        val excepted = settings.exceptedApps.size
        adSub.text = (if (settings.adBlockEnabled) "%,d hostnames".format(Filter.blockCount) else "Paused — ads allowed") +
            if (excepted > 0) " · $excepted app${if (excepted == 1) "" else "s"} excepted" else ""
        val server = VpnProfiles.selected(this)
        vpnSub.text = when (vs.link) {
            VpnStatus.Link.OFF -> server?.let { "${Flags.of(it.name)} ${it.name} · off" } ?: "No server yet"
            VpnStatus.Link.CONNECTED -> "${vs.flag} ${vs.text.removePrefix("Connected · ")}"
            else -> vs.text
        }
        vpnSub.setTextColor(when (vs.link) {
            VpnStatus.Link.CONNECTED -> Palette.GOOD
            VpnStatus.Link.CONNECTING, VpnStatus.Link.PROTECTION_OFF -> Palette.WARN
            VpnStatus.Link.OFF -> Palette.MUTED
            else -> Palette.BAD
        })

        val total = Stats.total.get()
        val blocked = Stats.blocked.get()
        tileBlocked.text = compact(blocked)
        tileLookups.text = compact(total)
        if (ShieldVpnService.fullTunnel) {
            tileData.text = mb(TrafficStats.bytesUp.get() + TrafficStats.bytesDown.get())
            tileDataLabel.text = "DATA · ${TrafficStats.active.get()} OPEN"
        } else {
            tileData.text = "—"; tileDataLabel.text = "DATA"
        }
        if (lastTotal >= 0) spark.push((total - lastTotal).toFloat(), (blocked - lastBlocked).toFloat())
        if (ticks % 5 == 0) {
            LifetimeStats.flush(this)
            val (lb, lt) = LifetimeStats.lifetime(this)
            val (tb, _) = LifetimeStats.today(this)
            lifetimeText.text = "Today ${"%,d".format(tb)} blocked · all time ${"%,d".format(lb)} of ${"%,d".format(lt)} lookups"
            weekBars.set(LifetimeStats.lastDays(this))
        }
        lastTotal = total; lastBlocked = blocked

        if (Stats.version != shownVersion || recentBlocked.childCount == 0) {
            if (currentTab == TAB_HOME) shownVersion = Stats.version
            recentBlocked.removeAllViews()
            val items = Stats.recent().filter { it.blocked }.take(5)
            if (items.isEmpty()) recentBlocked.addView(small("Nothing blocked yet.").apply { setPadding(0, dp(8), 0, 0) })
            for (e in items) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                r.addView(View(this).apply { background = roundRect(Palette.BAD, dp(4).toFloat()) },
                    LinearLayout.LayoutParams(dp(8), dp(8)))
                r.addView(TextView(this).apply {
                    text = e.domain; textSize = 13f; maxLines = 1; setPadding(dp(10), 0, dp(8), 0)
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(small(Catalog.title(this, e.by)).apply { maxLines = 1; textSize = 11f })
                recentBlocked.addView(r)
            }
        }
    }

    internal fun compact(n: Long) = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 10_000 -> "%.1fk".format(n / 1e3)
        else -> "%,d".format(n)
    }

    // ---------------- App exceptions ----------------

    private class AppRow(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)
    private var restartPending = false

    private fun buildAppsPage(): View {
        val page = vbox()
        page.addView(body("Tap an app to choose how BlockBridge treats it:"))
        page.addView(small("• No VPN — still ad-blocked, but connects from your real location (split tunneling).\n" +
            "• Skip all — no ad blocking and no VPN. Use it for games whose reward ads need ads to load."))
        val search = singleLine("", "Search apps")
        page.addView(search)
        val list = vbox().apply { setPadding(0, dp(8), 0, 0) }
        list.addView(small("Loading apps…"))
        page.addView(list)

        thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString(), runCatching { pm.getApplicationIcon(it) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                fun render(q: String) {
                    list.removeAllViews()
                    val excepted = settings.exceptedApps
                    val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                        .sortedByDescending { it.pkg in excepted || it.pkg in settings.vpnBypassApps }
                    for (a in shown.take(150)) list.addView(appRow(a, a.pkg in excepted))
                    if (shown.isEmpty()) list.addView(small("No apps match."))
                }
                render("")
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) = render(e?.toString() ?: "")
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
        }
        return page
    }

    private enum class AppMode(val label: String, val color: Int) {
        NORMAL("Normal", Palette.MUTED), NO_VPN("No VPN", Palette.VIOLET), SKIP("Skip all", Palette.WARN)
    }

    private fun modeOf(pkg: String) = when (pkg) {
        in settings.exceptedApps -> AppMode.SKIP
        in settings.vpnBypassApps -> AppMode.NO_VPN
        else -> AppMode.NORMAL
    }

    private fun appRow(a: AppRow, @Suppress("UNUSED_PARAMETER") checked: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, dp(8))
        }
        row.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val text = vbox().apply { setPadding(dp(12), 0, dp(8), 0) }
        text.addView(TextView(this).apply { this.text = a.label; textSize = 15f; setTextColor(Palette.TEXT); maxLines = 1 })
        text.addView(small(a.pkg).apply { textSize = 11f; maxLines = 1 })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val chip = TextView(this).apply {
            textSize = 12f; setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        fun paint() {
            val m = modeOf(a.pkg)
            chip.text = m.label
            chip.setTextColor(m.color)
            chip.background = roundRect((m.color and 0x00FFFFFF) or 0x22000000, dp(99).toFloat(), (m.color and 0x00FFFFFF) or 0x66000000, dp(1))
        }
        paint()
        row.addView(chip)
        row.setOnClickListener {
            val modes = AppMode.values()
            val names = arrayOf(
                "Normal — ad blocking + VPN",
                "No VPN — ad blocking only (split tunneling)",
                "Skip BlockBridge — no ad blocking, no VPN (reward ads work)",
            )
            AlertDialog.Builder(this)
                .setTitle(a.label)
                .setSingleChoiceItems(names, modes.indexOf(modeOf(a.pkg))) { d, which ->
                    val skip = settings.exceptedApps.toMutableSet()
                    val noVpn = settings.vpnBypassApps.toMutableSet()
                    val wasSkip = a.pkg in skip
                    skip.remove(a.pkg); noVpn.remove(a.pkg)
                    when (modes[which]) {
                        AppMode.SKIP -> skip.add(a.pkg)
                        AppMode.NO_VPN -> noVpn.add(a.pkg)
                        AppMode.NORMAL -> Unit
                    }
                    settings.exceptedApps = skip
                    settings.vpnBypassApps = noVpn
                    RouteConfig.reload(this)
                    // Skipping apps needs the tunnel rebuilt; split tunneling applies to new connections.
                    if (wasSkip != (modes[which] == AppMode.SKIP)) scheduleRestart()
                    paint()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        return row
    }

    /** The tunnel has to be rebuilt for app exceptions to apply; batch quick changes. */
    private fun scheduleRestart() {
        if (!ShieldVpnService.isRunning || restartPending) return
        restartPending = true
        handler.postDelayed({
            restartPending = false
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_RESTART))
            toast("App exceptions applied")
        }, 1500)
    }

    // ---------------- Filters (lists / my filters / trusted) ----------------

    private fun buildFiltersPage(): View {
        val page = vbox()
        page.addView(pageTitle("Filters", "uBlock Origin's lists and your own rules"))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(Palette.SURFACE, dp(99).toFloat(), Palette.STROKE, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val holder = FrameLayout(this)
        val labels = listOf("Lists", "My filters", "Trusted", "Apps")
        fun select(i: Int) {
            filterSub = i
            for (k in 0 until seg.childCount) {
                val t = seg.getChildAt(k) as TextView
                val on = k == i
                t.background = if (on) gradientRect(dp(99).toFloat(), Palette.CYAN, Palette.VIOLET) else null
                t.setTextColor(if (on) Palette.BG else Palette.MUTED)
            }
            holder.removeAllViews()
            holder.addView(filterPages.getOrPut(i) {
                when (i) { 0 -> buildListsPage(); 1 -> buildMyFiltersPage(); 2 -> buildTrustedPage(); else -> buildAppsPage() }
            })
        }
        labels.forEachIndexed { i, l ->
            seg.addView(TextView(this).apply {
                text = l; textSize = 13f; setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { select(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        page.addView(holder)
        select(filterSub)
        return page
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

    internal fun buildLoggerPage(): View {
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
        page.addView(pageTitle("Settings", "DNS, traffic mode, notification, backup"))
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

        page.addView(CheckBox(this).apply {
            text = "Block encrypted-DNS bypass"
            isChecked = settings.dohGuard
            setOnCheckedChangeListener { _, on -> settings.dohGuard = on; DohGuard.enabled = on }
        })
        page.addView(small("Apps and browsers with their own DNS-over-HTTPS (e.g. Chrome \"Secure DNS\" set " +
            "to Google) would skip the filters. This refuses those servers so they fall back to " +
            "BlockBridge. Needs all-traffic mode."))

        page.addView(CheckBox(this).apply {
            text = "Safe mode (DNS-only, no traffic engine)"
            isChecked = settings.safeMode
            setOnCheckedChangeListener { _, on ->
                settings.safeMode = on
                if (ShieldVpnService.isRunning) {
                    startService(Intent(this@MainActivity, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_RESTART))
                }
            }
        })
        page.addView(small("Turned on automatically if starting protection crashed. Ad blocking still " +
            "works; VPN servers, the network analyzer and split tunneling need it off."))

        page.addView(header("Control center"))
        page.addView(CheckBox(this).apply {
            text = "Show control-center notification"
            isChecked = settings.controlNotification
            setOnCheckedChangeListener { _, on ->
                settings.controlNotification = on
                if (on) ControlService.show(this@MainActivity) else ControlService.hide(this@MainActivity)
            }
        })
        page.addView(small("Quick switches for protection, ad block and VPN, plus the emergency kill button, " +
            "right in your notification shade."))

        page.addView(header("Backup"))
        page.addView(small("Selected lists, My filters and Trusted sites. Restore also accepts the " +
            "\"userFilters\" / \"whitelist\" parts of a uBlock Origin backup file."))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Back up") { backupDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Restore") { restoreDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row)

        page.addView(header("Updates"))
        page.addView(button("Check for updates") {
            toast("Checking…")
            thread {
                val r = runCatching { Updates.check(this) }
                runOnUiThread {
                    r.onSuccess { u -> if (u != null) offerUpdate(u) else toast("You have the latest version") }
                        .onFailure { toast("Couldn't check: ${it.message}") }
                }
            }
        })
        page.addView(small("Looks at the \"BlockBridge (latest build)\" release on github.com/${Updates.REPO}."))

        page.addView(header("Diagnostics"))
        val diag = body("").apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        fun renderDiag() { diag.text = diagnostics() }
        renderDiag()
        page.addView(diag)
        val dRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dRow.addView(button("Refresh") { renderDiag() }, LinearLayout.LayoutParams(0, -2, 1f))
        dRow.addView(button("Copy log") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("BlockBridge log", diagnostics() + "\n\n" + DiagLog.text()))
            toast("Debug log copied")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        dRow.addView(button("Share log") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BlockBridge debug log")
                putExtra(Intent.EXTRA_TEXT, diagnostics() + "\n\n" + runCatching { CrashGuard.logFile.readText().takeLast(60_000) }.getOrDefault(DiagLog.text()))
            }, "Share debug log"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(dRow)
        page.addView(button("Keep BlockBridge running (battery)") { askBatteryExemption(force = true) })
        page.addView(small("Phones from Samsung, Xiaomi, Oppo and others stop background apps. Allowing " +
            "\"unrestricted\" battery keeps protection and the VPN alive."))

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

    private fun backupDialog() {
        val pw = singleLine("", "Password (optional)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(small("Set a password to include your VPN servers (they contain private keys). " +
            "They're encrypted with AES-256; without the password they can't be restored."))
        box.addView(pw)
        AlertDialog.Builder(this)
            .setTitle("Back up")
            .setView(box)
            .setPositiveButton("Back up") { _, _ ->
                val password = pw.text.toString()
                thread {
                    val text = settings.backup(this, password.ifEmpty { null })
                    runOnUiThread {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "BlockBridge backup")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "Save backup to…"))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreDialog() {
        val input = multiline("", "Paste backup text here")
        val pw = singleLine("", "Password (if the backup has VPN servers)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0); addView(input); addView(pw) }
        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setView(box)
            .setPositiveButton("Restore") { _, _ ->
                val text = input.text.toString()
                val password = pw.text.toString()
                thread {
                    val msg = try {
                        val added = settings.restore(this, text, password.ifEmpty { null })
                        Upstream.reload(settings)
                        Blocklists.loadUserRules(settings)
                        when {
                            added > 0 -> "Restored, including $added VPN server(s)"
                            settings.hasEncryptedServers(text) && password.isEmpty() ->
                                "Restored. Enter the password to also restore VPN servers."
                            else -> "Restored"
                        }
                    } catch (e: javax.crypto.AEADBadTagException) {
                        "Wrong password — nothing restored from the VPN part"
                    } catch (e: Exception) {
                        "That doesn't look like a backup: ${e.message}"
                    }
                    runOnUiThread {
                        toast(msg)
                        pages.clear(); filterPages.clear()
                        showTab(currentTab)
                        runUpdate(force = false)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- VPN ----------------

    private fun buildVpnPage(): View {
        val page = vbox()
        page.addView(pageTitle("VPN", "Browse from another country — ads stay blocked"))
        vpnStatus = body("").apply { setTypeface(typeface, Typeface.BOLD) }
        page.addView(vpnStatus)
        if (!VpnClient.available(this) || !(HevTunnel.available || VpnClient.previewEngines)) {
            page.addView(body("This build doesn't include the VPN engine."))
            return page
        }
        vpnToggle = CheckBox(this).apply {
            text = "Use VPN server (show the server's country)"
            textSize = 16f
            isChecked = settings.vpnEnabled
            setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                settings.vpnEnabled = on
                RouteConfig.reload(this@MainActivity)
                applyVpn()
                StateBus.changed()
            }
        }
        page.addView(vpnToggle)
        page.addView(small(
            "While on, websites see the server's IP and country, and lookups go through it too. " +
                "Ads stay blocked. If the server can't be reached, internet is paused rather than " +
                "falling back to your real location (kill switch)."
        ))

        page.addView(header("Load balancer"))
        page.addView(small("Use several servers at once. Failover: next server takes over if one dies. " +
            "Balanced: spreads connections (each website sticks to one server). Fastest: lowest latency."))
        val lb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(Palette.SURFACE, dp(99).toFloat(), Palette.STROKE, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun paintLb() {
            for (k in 0 until lb.childCount) {
                val t = lb.getChildAt(k) as TextView
                val on = LbMode.values()[k] == settings.lbMode
                t.background = if (on) gradientRect(dp(99).toFloat(), Palette.CYAN, Palette.VIOLET) else null
                t.setTextColor(if (on) Palette.BG else Palette.MUTED)
            }
        }
        LbMode.values().forEach { m ->
            lb.addView(TextView(this).apply {
                text = m.label; textSize = 12f; setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    settings.lbMode = m
                    if (m != LbMode.OFF && settings.lbMembers.isEmpty()) {
                        settings.lbMembers = VpnProfiles.all(this@MainActivity).map { it.id }.toSet()
                    }
                    paintLb(); renderVpnList()
                    if (settings.vpnEnabled) applyVpn()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paintLb()
        page.addView(lb, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

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
        page.addView(button("Add Tor (free, pick a country)") { torDialog() })
        page.addView(button("Check my location") {
            VpnClient.exitInfo = "Checking…"
            thread { VpnClient.checkLocation() }
        })

        page.addView(header("Free: Tor"))
        page.addView(body(
            "No account needed. Traffic hops through 3 volunteer relays and leaves from the country " +
                "you pick. Slower than a VPN, carries TCP only (calls/QUIC fall back), and Google " +
                "or some sites may show CAPTCHAs or block Tor."
        ))
        page.addView(header("Tor bridges"))
        page.addView(small("If Tor is blocked where you are (it won't connect), use a bridge. Snowflake works " +
            "almost everywhere; obfs4 is faster. Custom: paste bridge lines from bridges.torproject.org."))
        val bridgeBtn = button("Bridges: ${settings.torBridges.label}") {}
        bridgeBtn.setOnClickListener {
            val modes = TorClient.Bridges.values()
            AlertDialog.Builder(this)
                .setTitle("Tor bridges")
                .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(settings.torBridges)) { d, i ->
                    d.dismiss()
                    if (modes[i] == TorClient.Bridges.CUSTOM) {
                        val lines = multiline(settings.torBridgeLines, "obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0")
                        AlertDialog.Builder(this)
                            .setTitle("Paste bridge lines")
                            .setView(vbox().apply { setPadding(dp(16), dp(8), dp(16), 0); addView(lines) })
                            .setPositiveButton("Save") { _, _ ->
                                settings.torBridgeLines = lines.text.toString()
                                settings.torBridges = TorClient.Bridges.CUSTOM
                                bridgeBtn.text = "Bridges: Custom"
                                if (settings.vpnEnabled && VpnClient.tunnels.any { it.isTor }) applyVpn()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        settings.torBridges = modes[i]
                        bridgeBtn.text = "Bridges: ${modes[i].label}"
                        if (settings.vpnEnabled && VpnClient.tunnels.any { it.isTor }) applyVpn()
                    }
                }
                .show()
        }
        page.addView(bridgeBtn)

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
        val multi = settings.lbMode != LbMode.OFF
        val selected = VpnProfiles.selected(this)?.id
        val members = settings.lbMembers
        val live = VpnClient.tunnels.associateBy { it.profile.id }
        for (p in profiles) {
            val on = if (multi) p.id in members else p.id == selected
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            info.addView(TextView(this).apply {
                text = "${if (multi) (if (on) "☑" else "☐") else (if (on) "◉" else "○")}   ${Flags.of(p.name)}  ${p.name}"
                textSize = 16f
                setTextColor(if (on) Palette.CYAN else Palette.TEXT)
            })
            live[p.id]?.let { t ->
                val st = when {
                    t.usable && t.latencyMs >= 0 -> "● working · ${t.latencyMs} ms · ${t.active.get()} open"
                    t.usable -> "● working · ${t.active.get()} open"
                    else -> "● ${t.error ?: t.state}"
                }
                info.addView(small(st).apply { setTextColor(if (t.usable) Palette.GOOD else Palette.BAD); setPadding(dp(30), 0, 0, 0) })
            }
            info.setOnClickListener {
                if (multi) {
                    settings.lbMembers = if (p.id in members) members - p.id else members + p.id
                } else {
                    VpnProfiles.select(this@MainActivity, p.id)
                }
                renderVpnList()
                if (settings.vpnEnabled) applyVpn()
            }
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Delete") {
                AlertDialog.Builder(this)
                    .setMessage("Delete ${p.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        val wasUsed = p.id == VpnProfiles.selected(this)?.id || p.id in settings.lbMembers
                        VpnProfiles.remove(this, p.id)
                        settings.lbMembers = settings.lbMembers - p.id
                        renderVpnList()
                        if (wasUsed && settings.vpnEnabled) applyVpn()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            vpnList.addView(row)
        }
        val torMembers = profiles.count { TorClient.isTor(it) && (if (multi) it.id in members else it.id == selected) }
        if (multi && torMembers > 1) {
            vpnList.addView(small("⚠ Only one Tor exit can run at a time — the load balancer uses the first " +
                "ticked Tor server. For a specific country, set Load balancer to Off and pick it.").apply {
                setTextColor(Palette.WARN); setPadding(0, dp(6), 0, dp(6))
            })
        }
    }

    private fun torDialog() {
        val codes = TorClient.COUNTRIES.keys.toList()
        val labels = codes.map { c -> (if (c.isEmpty()) "🌐" else Flags.of(c)) + "  " + TorClient.COUNTRIES[c] }
        AlertDialog.Builder(this)
            .setTitle("Tor exit country")
            .setItems(labels.toTypedArray()) { _, which ->
                val code = codes[which]
                val name = TorClient.profileName(code)
                val existing = VpnProfiles.all(this).firstOrNull { it.conf == "tor:$code" }
                val p = existing ?: VpnProfiles.add(this, name, "tor:$code")
                VpnProfiles.select(this, p.id)
                if (::vpnList.isInitialized) renderVpnList()
                toast("$name selected")
                if (settings.vpnEnabled) applyVpn()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        handler.postDelayed({ askBatteryExemption(force = false) }, 1500)
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
        refreshHome(on)

        if (currentTab == TAB_VPN && ::vpnList.isInitialized && ticks % 3 == 0 && VpnClient.tunnels.isNotEmpty()) renderVpnList()

        if (::vpnStatus.isInitialized) {
            vpnStatus.text = buildString {
                append(VpnStatus.of(this@MainActivity).text)
                val hs = VpnClient.lastHandshake
                if (settings.vpnEnabled && on && hs > 0) {
                    append("  (last contact ${(System.currentTimeMillis() - hs) / 1000}s ago)")
                }
                VpnClient.error?.let { if (settings.vpnEnabled) append("\n⚠ $it") }
                VpnClient.exitInfo?.let { append("\n\n$it") }
            }
            vpnStatus.setTextColor(
                if (settings.vpnEnabled && on && VpnClient.lastHandshake > 0) GREEN
                else if (settings.vpnEnabled && VpnClient.error != null) RED else Palette.TEXT
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

        if (ticks++ % 5 == 0 && ::warnText.isInitialized) {
            val w = privateDnsWarning()
            warnText.text = w ?: ""
            warnText.visibility = if (w == null) View.GONE else View.VISIBLE
        }

        if (currentTab == TAB_NETWORK) networkPage.refresh()
        if (currentTab == TAB_NETWORK && ::loggerBox.isInitialized && Stats.version != shownVersion) {
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

    // ---------------- crash reports ----------------

    private fun showCrashReport(r: CrashGuard.Report) {
        if (r.duringEngineStart && !settings.safeMode) {
            settings.safeMode = true // next start uses DNS-only filtering
        }
        AlertDialog.Builder(this)
            .setTitle("BlockBridge stopped unexpectedly")
            .setMessage(
                (if (r.duringEngineStart) "It happened while starting the all-traffic engine, so Safe mode " +
                    "(DNS-only ad blocking) is now on — protection will start normally. " else "") +
                    "Please share this report so it can be fixed.\n\n" + r.text.take(1500)
            )
            .setPositiveButton("Share report") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "BlockBridge crash report")
                    putExtra(Intent.EXTRA_TEXT, r.text)
                }, "Share crash report"))
            }
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("BlockBridge crash", r.text))
                toast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- updates ----------------

    private fun offerUpdate(u: Updates.Available) {
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("A newer BlockBridge (build ${u.versionCode}) is on GitHub. Download and open it to install.")
            .setPositiveButton("Download") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u.url)))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ---------------- diagnostics ----------------

    private fun ignoringBattery() = getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(packageName) == true

    internal fun diagnostics(): String = buildString {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        appendLine("BlockBridge ${info?.versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Protection: ${if (ShieldVpnService.isRunning) "on" else "off"} · mode: ${if (ShieldVpnService.fullTunnel) "all traffic" else "DNS only"}${if (settings.safeMode) " (safe mode)" else ""}")
        ShieldVpnService.lastError?.let { appendLine("Last error: $it") }
        appendLine("Engines: tunnel ${if (HevTunnel.available) "ok" else "missing"} · WireGuard ${if (VpnClient.available(this@MainActivity)) "ok" else "missing"}")
        appendLine("Filters: ${Filter.blockCount} hostnames · ad block ${if (Filter.enabled) "on" else "off"} · DoH guard ${if (DohGuard.enabled) "on" else "off"}")
        appendLine("DNS: ${Upstream.servers.joinToString { it.hostAddress ?: "?" }}${if (DnsHandler.via != null) " (through VPN)" else ""}")
        appendLine("VPN: ${if (settings.vpnEnabled) VpnClient.state else "off"} · balancer ${settings.lbMode.label}")
        for (t in VpnClient.tunnels) {
            val hs = if (t.lastHandshake > 0) "${(System.currentTimeMillis() - t.lastHandshake) / 1000}s ago" else "none"
            appendLine("  ${t.name}: ${if (t.usable) "working" else "down"} · handshake $hs · ${t.latencyMs} ms · ${t.active.get()} open${t.error?.let { " · $it" } ?: ""}")
        }
        appendLine("Connections: ${ConnTracker.liveCount()} open · ${TrafficStats.connections.get()} total · ${TrafficStats.blockedConnections.get()} refused")
        appendLine("App exceptions: ${settings.exceptedApps.size} skip · ${settings.vpnBypassApps.size} no-VPN")
        append("Battery optimization: ${if (ignoringBattery()) "off (good)" else "on — Android may stop BlockBridge"}")
        TorClient.tailLog(this@MainActivity, 25)?.let { append("\n\nTor log (last lines):\n$it") }
    }

    /** Asks Android to let BlockBridge run unrestricted (asked once automatically). */
    internal fun askBatteryExemption(force: Boolean) {
        if (ignoringBattery()) { if (force) toast("Already unrestricted"); return }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!force && prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        runCatching {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$packageName"))
            startActivity(i)
        }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ---------------- small UI helpers ----------------

    internal fun mb(bytes: Long) = if (bytes < 1_000_000) "${bytes / 1000} KB" else "%.1f MB".format(bytes / 1e6)
    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    internal fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    internal fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(Palette.SURFACE, dp(20).toFloat(), Palette.STROKE, dp(1))
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    internal fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    internal fun cardTitle(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Palette.TEXT)
    }

    internal fun pageTitle(t: String, sub: String) = vbox().apply {
        setPadding(0, dp(4), 0, dp(14))
        addView(TextView(context).apply { text = t; textSize = 26f; setTypeface(Typeface.DEFAULT_BOLD); brandGradient() })
        addView(small(sub))
    }

    internal fun header(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Palette.TEXT)
        setPadding(0, dp(20), 0, dp(6))
    }

    internal fun body(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Palette.TEXT); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(6), 0, dp(6))
    }

    internal fun small(t: String) = TextView(this).apply {
        text = t; textSize = 12.5f; setTextColor(Palette.MUTED); setLineSpacing(0f, 1.1f)
    }

    internal fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Palette.TEXT)
        stateListAnimator = null
        // inset keeps side-by-side buttons from touching
        background = android.graphics.drawable.InsetDrawable(
            ripple(roundRect(Palette.SURFACE2, dp(14).toFloat(), Palette.STROKE, dp(1)), dp(14).toFloat()),
            dp(4), dp(4), dp(4), dp(4),
        )
        minHeight = dp(52); minimumHeight = dp(52)
        setOnClickListener { onClick() }
    }

    internal fun field() = roundRect(Palette.SURFACE2, dp(14).toFloat(), Palette.STROKE, dp(1))

    internal fun multiline(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 6; gravity = Gravity.TOP
        typeface = Typeface.MONOSPACE; textSize = 13f
        setTextColor(Palette.TEXT); setHintTextColor(Palette.MUTED)
        background = field(); setPadding(dp(12), dp(12), dp(12), dp(12))
        setHorizontallyScrolling(false)
    }

    internal fun singleLine(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        setTextColor(Palette.TEXT); setHintTextColor(Palette.MUTED)
        background = field(); setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
    }
}
