package app.vault.sync

import android.content.Context
import androidx.work.*
import app.vault.app
import app.vault.data.AppLog
import java.util.concurrent.TimeUnit

/** Background auto-update of the performer database. Runs silently (no notifications). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (app.prefs.apiKey.isBlank()) return Result.success()
        return runCatching { app.repo.syncStep(pages = 10) }
            .onFailure { AppLog.e("Sync", "Background sync failed (attempt $runAttemptCount)", it) }
            .fold({ Result.success() }, { if (runAttemptCount < 3) Result.retry() else Result.failure() })
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("catalog_sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
