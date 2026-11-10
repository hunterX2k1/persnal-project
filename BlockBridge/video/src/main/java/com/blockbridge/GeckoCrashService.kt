package com.blockbridge

import android.os.Build
import com.blockbridge.video.R
import java.io.File

/**
 * Firefox-engine (GeckoView) crash reports. GeckoView starts this in its own ":crash" process
 * when any of its processes crashes; we record what crashed in the debug log and crash history.
 * (The minidump itself can't be read without Mozilla's symbols, so it's deleted.)
 */
class GeckoCrashService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // GeckoView starts us with startForegroundService, so go foreground at once.
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("engine_crash", "Engine crash reports",
            android.app.NotificationManager.IMPORTANCE_MIN))
        val n = android.app.Notification.Builder(this, "engine_crash")
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("Saving crash report…").build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(4243, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(4243, n)
        }
        runCatching { record(intent) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun record(i: android.content.Intent?) {
        if (i == null) return
        val type = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE) ?: "?"
        val remote = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE) ?: ""
        val vis = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY) ?: ""
        val dump = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_MINIDUMP_PATH)?.let(::File)
        val extras = i.getStringExtra(org.mozilla.geckoview.GeckoRuntime.EXTRA_EXTRAS_PATH)?.let(::File)
        // The .extra file is small JSON with Firefox's own crash reason ("MozCrashReason" etc.).
        val reason = runCatching {
            val j = org.json.JSONObject(extras!!.readText())
            listOf("MozCrashReason", "AbortMessage", "ipc_channel_error", "OOMAllocationSize", "TotalPhysicalMemory")
                .filter { j.has(it) }.joinToString("\n") { "$it: ${j.optString(it).take(500)}" }
        }.getOrDefault("")
        val text = "${CrashGuard.header(this)}\n\nFirefox engine crash — process: $type${if (remote.isNotEmpty()) " ($remote)" else ""}" +
            "${if (vis.isNotEmpty()) " · $vis" else ""}\n${reason.ifEmpty { "no reason recorded" }}\n" +
            "minidump: ${dump?.length() ?: 0} bytes\n\nSystem log:\n${CrashGuard.systemLog(400, 3_000)}"
        DiagLog.add("youtube", "ERROR Firefox engine crash in $type process ${remote}: ${reason.lineSequence().firstOrNull() ?: ""}")
        CrashGuard.addHistory(text)
        runCatching { dump?.delete(); extras?.delete() }
    }
}
