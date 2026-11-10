package com.blockbridge

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// ---------------- Quick Settings tiles ----------------

/** Pull-down tile: protection on/off. */
class ProtectionTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        if (ShieldVpnService.isRunning) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
        } else if (VpnService.prepare(this) == null) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
        } else {
            openApp(this, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, MainActivity.startToken))
        }
        qsTile?.let { it.state = if (ShieldVpnService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; it.updateTile() }
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (ShieldVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (ShieldVpnService.isRunning) "%,d blocked".format(Stats.blocked.get()) else "Off"
        t.updateTile()
    }
}

/** Pull-down tile: VPN server on/off. */
class VpnTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val s = Settings(this)
        if (!s.vpnEnabled && VpnProfiles.selected(this) == null) {
            openApp(this, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_VPN))
            return
        }
        s.vpnEnabled = !s.vpnEnabled
        RouteConfig.reload(this)
        StateBus.changed()
        if (ShieldVpnService.isRunning) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_APPLY_VPN))
        }
        ControlService.refresh(this)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val s = Settings(this)
        t.state = if (s.vpnEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = VpnProfiles.selected(this)?.let { "${Flags.of(it.name)} ${it.name}" } ?: "No server"
        }
        t.updateTile()
    }
}

@Suppress("DEPRECATION")
private fun TileService.openApp(ctx: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        startActivityAndCollapse(intent)
    }
}

// ---------------- Home-screen widget ----------------

class ControlWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx)

    companion object {
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ControlWidget::class.java))
            if (ids.isEmpty()) return
            val s = Settings(ctx)
            val on = ShieldVpnService.isRunning
            val v = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.w_title, if (on) "Protected" else "Not protected")
                setTextColor(R.id.w_title, if (on) Palette.GOOD else Palette.BAD)
                setTextViewText(R.id.w_count, "%,d blocked today".format(LifetimeStats.today(ctx).first))
                val server = VpnProfiles.selected(ctx)
                pill(ctx, R.id.w_power, if (on) "●  ON" else "○  OFF", on, ControlService.ACTION_PROTECTION)
                pill(ctx, R.id.w_vpn, if (s.vpnEnabled) "${server?.let { Flags.of(it.name) } ?: "🌐"}  VPN" else "🌐  VPN off",
                    s.vpnEnabled, ControlService.ACTION_VPN)
                setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0,
                    Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            }
            mgr.updateAppWidget(ids, v)
        }

        private fun RemoteViews.pill(ctx: Context, id: Int, text: String, active: Boolean, action: String) {
            setTextViewText(id, text)
            setInt(id, "setBackgroundResource", if (active) R.drawable.pill_on else R.drawable.pill_off)
            setTextColor(id, if (active) Palette.BG else Palette.TEXT)
            setOnClickPendingIntent(id, PendingIntent.getForegroundService(ctx, action.hashCode(),
                Intent(ctx, ControlService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}

// ---------------- Background filter-list updates ----------------

/** Refreshes expired filter lists every ~12 hours on any network, even with the app closed. */
class ListUpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "list-update-job") {
            val ok = runCatching {
                Blocklists.loadAll(this)
                if (Blocklists.needsUpdate(this)) DiagLog.add("lists", Blocklists.update(this, force = false))
            }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(42) != null) return
            js.schedule(JobInfo.Builder(42, ComponentName(ctx, ListUpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(12 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build())
        }
    }
}

// ---------------- Lifetime statistics ----------------

/** Blocked/lookup counts since install and per day (last 30 days), kept across restarts. */
object LifetimeStats {
    private var flushedBlocked = 0L
    private var flushedTotal = 0L
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** Adds what the live counters gained since the last flush. Cheap; call often. */
    @Synchronized
    fun flush(ctx: Context) {
        val b = Stats.blocked.get(); val t = Stats.total.get()
        val db = b - flushedBlocked; val dt = t - flushedTotal
        if (db <= 0 && dt <= 0) return
        flushedBlocked = b; flushedTotal = t
        val p = prefs(ctx)
        val key = day.format(Date())
        val e = p.edit()
            .putLong("life_blocked", p.getLong("life_blocked", 0) + db)
            .putLong("life_total", p.getLong("life_total", 0) + dt)
            .putLong("day_b_$key", p.getLong("day_b_$key", 0) + db)
            .putLong("day_t_$key", p.getLong("day_t_$key", 0) + dt)
        // forget days older than 30
        val cutoff = day.format(Date(System.currentTimeMillis() - 31L * 86_400_000))
        p.all.keys.filter { (it.startsWith("day_b_") || it.startsWith("day_t_")) && it.takeLast(8) < cutoff }.forEach { e.remove(it) }
        e.apply()
    }

    fun lifetime(ctx: Context) = prefs(ctx).getLong("life_blocked", 0) to prefs(ctx).getLong("life_total", 0)

    fun today(ctx: Context): Pair<Long, Long> {
        val key = day.format(Date())
        return prefs(ctx).getLong("day_b_$key", 0) to prefs(ctx).getLong("day_t_$key", 0)
    }

    /** Last [n] days, oldest first: (label, blocked, lookups). */
    fun lastDays(ctx: Context, n: Int = 7): List<Triple<String, Long, Long>> {
        val p = prefs(ctx)
        val label = SimpleDateFormat("EEE", Locale.getDefault())
        return (n - 1 downTo 0).map { back ->
            val d = Date(System.currentTimeMillis() - back * 86_400_000L)
            val key = day.format(d)
            Triple(label.format(d), p.getLong("day_b_$key", 0), p.getLong("day_t_$key", 0))
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lifetime", Context.MODE_PRIVATE)
}
