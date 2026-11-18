package com.netsplit.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.netsplit.app.vpn.NetSplitVpnService

class MainActivity : Activity() {
    private lateinit var s: Settings
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var modeGroup: RadioGroup
    private lateinit var modeHelp: TextView
    private lateinit var rowA: View
    private lateinit var rowB: View
    private lateinit var speed: EditText
    private lateinit var speedStatus: TextView
    private lateinit var measure: Button
    private lateinit var limitLabel: TextView
    private lateinit var limitValue: TextView
    private lateinit var limitBar: SeekBar
    private lateinit var limitHint: TextView
    private lateinit var quic: CheckBox
    private lateinit var start: Button
    private lateinit var live: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var autoInfo: TextView
    private lateinit var manualRow: View
    private lateinit var protectGroup: RadioGroup
    private lateinit var boost: CheckBox
    private var binding = false

    private companion object {
        const val REQ_VPN = 1
        const val REQ_NOTIF = 2
        // SeekBar steps: 5%..90% in 5% steps
        fun stepToPct(step: Int) = 5 + step * 5
        fun pctToStep(pct: Int) = ((pct - 5) / 5).coerceIn(0, 17)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        s = Settings(this)
        if (!s.initialized) {
            s.appA = Settings.firstInstalled(packageManager, Settings.GAME_DEFAULTS)
            s.appB = Settings.firstInstalled(packageManager, Settings.VIDEO_DEFAULTS)
            s.initialized = true
        }

        modeGroup = findViewById(R.id.modeGroup)
        modeHelp = findViewById(R.id.modeHelp)
        rowA = findViewById(R.id.rowA)
        rowB = findViewById(R.id.rowB)
        speed = findViewById(R.id.speed)
        speedStatus = findViewById(R.id.speedStatus)
        measure = findViewById(R.id.measure)
        limitLabel = findViewById(R.id.limitLabel)
        limitValue = findViewById(R.id.limitValue)
        limitBar = findViewById(R.id.limitBar)
        limitHint = findViewById(R.id.limitHint)
        quic = findViewById(R.id.quic)
        start = findViewById(R.id.start)
        live = findViewById(R.id.live)
        autoSwitch = findViewById(R.id.autoSwitch)
        autoInfo = findViewById(R.id.autoInfo)
        manualRow = findViewById(R.id.manualRow)
        protectGroup = findViewById(R.id.protectGroup)
        boost = findViewById(R.id.boost)

        binding = true
        modeGroup.check(if (s.priorityMode) R.id.modePriority else R.id.modeSplit)
        speed.setText(fmtNum(s.totalMbps))
        quic.isChecked = s.blockQuic
        autoSwitch.isChecked = s.autoMode
        boost.isChecked = s.gameBoost
        protectGroup.check(listOf(R.id.protect0, R.id.protect1, R.id.protect2)[s.protection.coerceIn(0, 2)])
        binding = false

        autoSwitch.setOnCheckedChangeListener { _, c ->
            if (binding) return@setOnCheckedChangeListener
            s.autoMode = c; refresh(); settingsChanged()
        }
        protectGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            s.protection = when (id) { R.id.protect0 -> 0; R.id.protect2 -> 2; else -> 1 }
            refresh(); settingsChanged()
        }

        modeGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            s.priorityMode = id == R.id.modePriority
            refresh(); settingsChanged()
        }
        rowA.findViewById<Button>(R.id.pick).setOnClickListener {
            AppPicker.show(this, "Choose your game") { app ->
                if (app.pkg == s.appB) s.appB = null
                s.appA = app.pkg; refresh(); settingsChanged()
            }
        }
        rowB.findViewById<Button>(R.id.pick).setOnClickListener {
            AppPicker.show(this, "Choose the background app") { app ->
                if (app.pkg == s.appA) s.appA = null
                s.appB = app.pkg; refresh(); settingsChanged()
            }
        }
        speed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (binding) return
                val v = e.toString().toFloatOrNull() ?: return
                if (v in 0.1f..2000f) { s.totalMbps = v; refresh(); settingsChanged() }
            }
        })
        measure.setOnClickListener { runSpeedTest() }
        limitBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, step: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (s.priorityMode) s.bgPercent = stepToPct(step) else s.splitA = stepToPct(step)
                refresh(); settingsChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        boost.setOnCheckedChangeListener { _, c -> if (!binding) { s.gameBoost = c; refresh(); settingsChanged() } }
        quic.setOnCheckedChangeListener { _, c -> if (!binding) { s.blockQuic = c; settingsChanged() } }
        start.setOnClickListener { onStartClicked() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        ui.post(liveTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(liveTick)
    }

    // ------------------------------------------------------------------ UI state

    private fun appLabel(pkg: String?): String? = pkg?.let {
        try { packageManager.getApplicationInfo(it, 0).loadLabel(packageManager).toString() } catch (_: PackageManager.NameNotFoundException) { null }
    }

    private fun bindRow(row: View, role: String, pkg: String?, cap: String) {
        row.findViewById<TextView>(R.id.role).text = role
        val label = appLabel(pkg)
        row.findViewById<TextView>(R.id.name).text = label ?: "Not selected"
        row.findViewById<TextView>(R.id.cap).text = if (label != null) cap else ""
        val icon = row.findViewById<ImageView>(R.id.icon)
        if (label != null) icon.setImageDrawable(packageManager.getApplicationIcon(pkg!!))
        else icon.setImageResource(android.R.drawable.sym_def_app_icon)
    }

    private fun mbpsOf(pct: Int) = s.totalMbps * pct / 100f

    private fun refresh() {
        val total = s.totalMbps
        val nameA = appLabel(s.appA) ?: "Game"
        val nameB = appLabel(s.appB) ?: "Background app"
        val auto = s.autoMode
        manualRow.visibility = if (auto) View.GONE else View.VISIBLE
        speedStatus.visibility = if (auto) View.GONE else View.VISIBLE
        if (!(auto && NetSplitVpnService.running && NetSplitVpnService.liveMbps > 0f)) {
            autoInfo.text = if (auto) {
                "While running, NetSplit pings your connection twice a second. When the ping starts rising " +
                    "(YouTube is filling the line) it cuts YouTube at once, and gives it more again when the line is clear."
            } else "Uses the fixed speed below."
        }
        val autoPriority = auto && s.priorityMode
        protectGroup.visibility = if (autoPriority) View.VISIBLE else View.GONE
        boost.visibility = if (autoPriority) View.VISIBLE else View.GONE
        limitBar.visibility = if (autoPriority) View.GONE else View.VISIBLE
        if (s.priorityMode) {
            modeHelp.text = "Your game is never touched (no VPN, zero extra lag). Only the background app is held to a small share so it can't flood your connection."
            bindRow(rowA, "PRIORITY APP (GAME)", s.appA, "Full speed · not routed")
            bindRow(rowB, "BACKGROUND APP", s.appB, "Max ${fmtNum(mbpsOf(s.bgPercent))} Mbps")
            limitLabel.text = "Background app limit"
            limitBar.progress = pctToStep(s.bgPercent)
            limitValue.text = "$nameB: ${s.bgPercent}% = ${fmtNum(mbpsOf(s.bgPercent))} Mbps"
            limitHint.text = videoHint(mbpsOf(s.bgPercent)) +
                "\n$nameA keeps the remaining ${fmtNum(total - mbpsOf(s.bgPercent))} Mbps of headroom."
            if (autoPriority) {
                bindRow(rowB, "BACKGROUND APP", s.appB, "Auto – adjusts live")
                if (s.gameBoost) bindRow(rowA, "PRIORITY APP (GAME)", s.appA, "Never limited · gets more the moment it asks")
                limitLabel.text = "Game protection"
                limitValue.text = "$nameB gets as much speed as possible without raising your game's ping."
                limitHint.text = "Stronger protection = lower ping in $nameA, but $nameB may play in lower quality."
            }
        } else {
            val a = s.splitA
            modeHelp.text = "Both apps are capped at their share of your speed. Good when neither app should starve the other."
            bindRow(rowA, "APP 1", s.appA, "Max ${fmtNum(mbpsOf(a))} Mbps")
            bindRow(rowB, "APP 2", s.appB, "Max ${fmtNum(mbpsOf(100 - a))} Mbps")
            limitLabel.text = "Split"
            limitBar.progress = pctToStep(a)
            limitValue.text = "$nameA $a%  ·  $nameB ${100 - a}%"
            limitHint.text = "$nameA: up to ${fmtNum(mbpsOf(a))} Mbps\n$nameB: up to ${fmtNum(mbpsOf(100 - a))} Mbps — " +
                videoHint(mbpsOf(100 - a)).replaceFirstChar { it.lowercase() }
            if (auto) {
                bindRow(rowA, "APP 1", s.appA, "$a% of live speed")
                bindRow(rowB, "APP 2", s.appB, "${100 - a}% of live speed")
                limitHint.text = "Shares of your live speed, re-checked twice a second (last known about ${fmtNum(total)} Mbps)."
            }
        }
        updateStartButton()
    }

    private fun videoHint(mbps: Float) = when {
        mbps < 0.5f -> "Enough for audio / 144p video."
        mbps < 0.9f -> "Enough for 240p–360p video."
        mbps < 1.6f -> "Enough for 480p video."
        mbps < 3.5f -> "Enough for 720p video."
        mbps < 7f -> "Enough for 1080p video."
        else -> "Enough for 1440p+ video."
    }

    private fun updateStartButton() {
        val on = NetSplitVpnService.running
        start.text = if (on) "Stop" else "Start"
        start.setBackgroundResource(if (on) R.drawable.btn_stop else R.drawable.btn_primary)
    }

    private val liveTick = object : Runnable {
        override fun run() {
            updateStartButton()
            val lines = NetSplitVpnService.liveLines
            val err = NetSplitVpnService.lastError
            if (s.autoMode && NetSplitVpnService.running && NetSplitVpnService.liveMbps > 0f) {
                autoInfo.text = "Live speed ≈ ${fmtNum(NetSplitVpnService.liveMbps)} Mbps – limits follow it automatically."
            }
            live.text = when {
                NetSplitVpnService.running && lines.isNotEmpty() -> lines.joinToString("\n")
                NetSplitVpnService.running -> "Starting…"
                err != null -> err
                else -> "Not running"
            }
            ui.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun settingsChanged() {
        if (!NetSplitVpnService.running) return
        // Apply live (limits change instantly; changing apps restarts the VPN).
        ui.removeCallbacks(applyLive)
        ui.postDelayed(applyLive, 500)
    }

    private val applyLive = Runnable {
        if (NetSplitVpnService.running && validate(quiet = true)) {
            startService(Intent(this, NetSplitVpnService::class.java).setAction(NetSplitVpnService.ACTION_START))
        }
    }

    private fun validate(quiet: Boolean = false): Boolean {
        val msg = when {
            s.appB == null && s.priorityMode -> "Choose the background app (e.g. YouTube) first"
            !s.priorityMode && (s.appA == null || s.appB == null) -> "Choose both apps first"
            else -> null
        }
        if (msg != null && !quiet) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        return msg == null
    }

    private fun onStartClicked() {
        if (NetSplitVpnService.running) {
            startService(Intent(this, NetSplitVpnService::class.java).setAction(NetSplitVpnService.ACTION_STOP))
            ui.postDelayed({ updateStartButton() }, 300)
            return
        }
        if (!validate()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return // continues in onRequestPermissionsResult
        }
        askVpnThenStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) askVpnThenStart()
    }

    private fun askVpnThenStart() {
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN) else startVpn()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn()
            else Toast.makeText(this, "NetSplit needs the VPN permission to limit apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVpn() {
        NetSplitVpnService.lastError = null
        startForegroundService(Intent(this, NetSplitVpnService::class.java).setAction(NetSplitVpnService.ACTION_START))
        ui.postDelayed({ updateStartButton() }, 400)
    }

    private fun runSpeedTest() {
        if (NetSplitVpnService.running) {
            speedStatus.text = "Stop NetSplit first so the test measures your full speed."
            return
        }
        measure.isEnabled = false
        speedStatus.text = "Measuring… (about 10 seconds)"
        SpeedTest.run(
            onProgress = { m -> runOnUiThread { speedStatus.text = "Measuring… ${fmtNum(m)} Mbps" } },
            onDone = { m ->
                runOnUiThread {
                    measure.isEnabled = true
                    if (m == null) {
                        speedStatus.text = "Speed test failed. Type your usual speed instead."
                    } else {
                        val v = (m * 10).toInt() / 10f
                        s.totalMbps = v.coerceAtLeast(0.1f)
                        binding = true; speed.setText(fmtNum(s.totalMbps)); binding = false
                        speedStatus.text = "Measured ${fmtNum(v)} Mbps. Mobile speed changes a lot – re-measure if limits feel off."
                        refresh()
                    }
                }
            },
        )
    }

    private fun fmtNum(v: Float): String =
        if (v >= 10f || v == v.toInt().toFloat()) "%.0f".format(v) else "%.1f".format(v)
}
