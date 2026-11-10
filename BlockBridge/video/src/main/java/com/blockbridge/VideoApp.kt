package com.blockbridge

import android.app.Application
import android.os.Build

/** BlockBridge Video: crash capture and the device check, in its own processes only. */
class VideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firefox's engine runs its own processes (tabs, GPU…) with their own crash handling.
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        if (proc != packageName && !proc.endsWith(":crash")) return
        runCatching { CrashGuard.install(this) }
        if (proc == packageName) Perf.prewarm(this)
    }
}
