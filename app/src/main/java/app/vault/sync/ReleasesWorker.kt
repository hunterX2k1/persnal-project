package app.vault.sync

import android.content.Context
import androidx.work.*
import app.vault.app
import app.vault.data.AppLog
import java.util.concurrent.TimeUnit

/** Daily check for new videos from followed performers. Silent: results appear as a badge inside the app. */
class ReleasesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching { applicationContext.app.repo.checkNewReleases() }
        .fold({ Result.success() }, { AppLog.e("NewReleases", "Background check failed", it); Result.retry() })

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ReleasesWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("new_releases", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
