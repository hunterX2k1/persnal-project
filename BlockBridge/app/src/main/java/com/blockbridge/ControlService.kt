package com.blockbridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.RemoteViews
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * The control-center notification: protection, ad-block and VPN switches plus the
 * emergency kill button, available from anywhere. Runs as a small foreground service
 * from the moment the app is opened (and whenever protection is on).
 */
class ControlService : Service() {
    companion object {
        private const val CHANNEL = "control"
        private const val ID = 7
        const val ACTION_PROTECTION = "com.blockbridge.ctl.PROTECTION"
        const val ACTION_ADBLOCK = "com.blockbridge.ctl.ADBLOCK"
        const val ACTION_VPN = "com.blockbridge.ctl.VPN"
        const val ACTION_KILL = "com.blockbridge.ctl.KILL"
        const val ACTION_REFRESH = "com.blockbridge.ctl.REFRESH"

        fun show(ctx: Context) {
            if (!Settings(ctx).controlNotification) return
            runCatching { ctx.startForegroundService(Intent(ctx, ControlService::class.java)) }
        }

        fun refresh(ctx: Context) {
            if (running) runCatching { ctx.startService(Intent(ctx, ControlService::class.java).setAction(ACTION_REFRESH)) }
        }

        fun hide(ctx: Context) = ctx.stopService(Intent(ctx, ControlService::class.java))

        @Volatile private var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            post()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Control center", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quick switches and the emergency kill button"
                setShowBadge(false)
            }
        )
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        handler.post(ticker)
        StateBus.listen(onState)
    }

    private val onState: () -> Unit = { post() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), even when
        // the service is already running — otherwise Android silently kills the whole app.
        runCatching {
            val n = build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.onFailure { DiagLog.error("control", "startForeground failed", it) }
        val s = Settings(this)
        when (intent?.action) {
            ACTION_KILL -> { Emergency.killEverything(this); return START_NOT_STICKY }
            ACTION_PROTECTION -> {
                if (ShieldVpnService.isRunning) {
                    startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
                } else if (VpnService.prepare(this) == null) {
                    startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
                } else {
                    // First start needs the system VPN consent dialog, which only an activity can show.
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_START, MainActivity.startToken))
                }
            }
            ACTION_ADBLOCK -> {
                s.adBlockEnabled = !s.adBlockEnabled
                Filter.enabled = s.adBlockEnabled
                StateBus.changed()
            }
            ACTION_VPN -> {
                if (!s.vpnEnabled && VpnProfiles.selected(this) == null) {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_VPN))
                } else {
                    s.vpnEnabled = !s.vpnEnabled
                    RouteConfig.reload(this)
                    StateBus.changed()
                    if (ShieldVpnService.isRunning) {
                        startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_APPLY_VPN))
                    }
                }
            }
        }
        handler.postDelayed({ post() }, 400) // reflect the new state quickly
        post()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        StateBus.remove(onState)
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun post() {
        if (!running) return
        runCatching { LifetimeStats.flush(this) }
        runCatching { ControlWidget.update(this) }
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, ProtectionTile::class.java))
            android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, VpnTile::class.java))
        }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun action(a: String) = PendingIntent.getService(
        this, a.hashCode(), Intent(this, ControlService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(): Notification {
        val s = Settings(this)
        val on = ShieldVpnService.isRunning
        val ad = s.adBlockEnabled
        val vpn = s.vpnEnabled
        val vs = VpnStatus.of(this)

        val title = when {
            !on -> "Not protected"
            vs.link == VpnStatus.Link.CONNECTING -> "Protected · connecting VPN…"
            vs.link == VpnStatus.Link.FAILED -> "VPN not connected"
            vs.link == VpnStatus.Link.CONNECTED -> "Protected · ${vs.flag} VPN"
            else -> "Protected"
        }
        val detail = "${"%,d".format(Stats.blocked.get())} blocked · " +
            if (vpn) vs.text else "VPN off"

        fun views(layout: Int) = RemoteViews(packageName, layout).apply {
            setTextViewText(R.id.n_title, title)
            setTextViewText(R.id.n_detail, detail)
            setTextColor(R.id.n_title, if (on) Palette.GOOD else Palette.BAD)
            pill(R.id.n_power, if (on) "●  ON" else "○  OFF", on, ACTION_PROTECTION)
            pill(R.id.n_adblock, if (ad) "🛡  Ad block" else "🛡  Ads allowed", ad, ACTION_ADBLOCK)
            vpnPill(vs)
            setOnClickPendingIntent(R.id.n_kill, action(ACTION_KILL))
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("BlockBridge · $title")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notif_small))
            .setCustomBigContentView(views(R.layout.notif_big))
            .build()
    }

    private fun RemoteViews.vpnPill(vs: VpnStatus.State) {
        val (text, bg, fg) = when (vs.link) {
            VpnStatus.Link.CONNECTED -> Triple("${vs.flag}  VPN", R.drawable.pill_on, Palette.BG)
            VpnStatus.Link.CONNECTING, VpnStatus.Link.PROTECTION_OFF -> Triple("🌐  VPN…", R.drawable.pill_warn, Palette.WARN)
            VpnStatus.Link.FAILED, VpnStatus.Link.UNAVAILABLE, VpnStatus.Link.NO_SERVER -> Triple("⚠  VPN", R.drawable.pill_kill, Palette.TEXT)
            VpnStatus.Link.OFF -> Triple("🌐  VPN off", R.drawable.pill_off, Palette.TEXT)
        }
        setTextViewText(R.id.n_vpn, text)
        setInt(R.id.n_vpn, "setBackgroundResource", bg)
        setTextColor(R.id.n_vpn, fg)
        setOnClickPendingIntent(R.id.n_vpn, action(ACTION_VPN))
    }

    private fun RemoteViews.pill(id: Int, text: String, active: Boolean, action: String) {
        if (id == 0) return
        setTextViewText(id, text)
        setInt(id, "setBackgroundResource", if (active) R.drawable.pill_on else R.drawable.pill_off)
        setTextColor(id, if (active) Palette.BG else Palette.TEXT)
        setOnClickPendingIntent(id, action(action))
    }
}

/** The emergency kill switch: stops every part of BlockBridge and closes the app. */
object Emergency {
    @Volatile var activity: WeakReference<Activity>? = null

    fun killEverything(ctx: Context) {
        Settings(ctx).wantRunning = false
        runCatching { ShieldVpnService.instance?.emergencyStop() } // closes the tunnel, hev and the relay
        runCatching { VpnClient.stop() }                           // ends the WireGuard helper process
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancelAll() }
        runCatching { ControlService.hide(ctx) }
        runCatching { activity?.get()?.finishAndRemoveTask() }
        // Anything still alive in this process goes with it.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 250)
    }
}

/** "NL-FREE-12" → 🇳🇱, "Netherlands" → 🇳🇱. */
object Flags {
    private val codes = Locale.getISOCountries().toSet()
    private val byName by lazy {
        codes.associateBy { Locale("", it).getDisplayCountry(Locale.ENGLISH).lowercase() }
    }

    fun code(name: String): String? {
        val first = name.trim().split(Regex("[^A-Za-z]+")).firstOrNull { it.isNotEmpty() } ?: return null
        if (first.length == 2 && first == first.uppercase() && first in codes) return first
        val lower = name.lowercase()
        return byName.entries.firstOrNull { lower.contains(it.key) }?.value
            ?: if (lower.contains("usa") || lower.startsWith("us")) "US" else if (lower.startsWith("uk")) "GB" else null
    }

    fun of(name: String): String {
        val c = code(name) ?: return "🌐"
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }
}
