package com.blockbridge

import android.app.Application
import android.os.Build

/** Installs crash capture before anything else runs. */
class BlockBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firefox's engine runs its own processes (tabs, GPU…) with their own crash handling;
        // they may also be sandboxed without file access. Only BlockBridge's processes here.
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        val ours = proc == packageName || proc.endsWith(":tor") || proc.endsWith(":crash")
        if (!ours) return
        runCatching { CrashGuard.install(this) }
        if (proc == packageName) Perf.prewarm(this)
    }
}
