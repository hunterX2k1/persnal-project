package app.vault

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.vault.data.AdBlock
import app.vault.data.AppLog
import app.vault.data.SiteSearch
import app.vault.data.Prefs
import app.vault.data.USER_AGENT
import app.vault.data.Repo
import app.vault.sync.SyncWorker
import kotlinx.coroutines.launch

class App : Application(), coil.ImageLoaderFactory {
    lateinit var prefs: Prefs
    lateinit var repo: Repo

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        prefs = Prefs(this)
        repo = Repo(this, prefs)
        // Re-lock whenever the whole app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLock.locked = true
            }
        })
        SyncWorker.schedule(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            AdBlock.load(java.io.File(filesDir, "blocklists"))
            runCatching { repo.tidySites() }
            runCatching { repo.makeVideoSitesSearchable() }
        }
    }

    /**
     * Image loader tuned for long scrolling grids: 300 MB disk cache that ignores short server cache times,
     * a browser user agent for studio image servers, and the app's own name for Wikimedia (which requires one).
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val ua = if (req.url.host.endsWith("wikimedia.org") || req.url.host.endsWith("wikipedia.org")) USER_AGENT else SiteSearch.BROWSER_UA
                    chain.proceed(req.newBuilder().header("User-Agent", ua).build())
                }
                .build()
        }
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(120)
        .build()
}

object AppLock {
    var locked by mutableStateOf(true)
}

val Context.app: App get() = applicationContext as App
