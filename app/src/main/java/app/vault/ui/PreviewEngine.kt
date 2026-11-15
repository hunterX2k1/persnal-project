package app.vault.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.vault.data.AppLog
import app.vault.data.SiteSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * Makes thumbnail previews start fast:
 *  - one 250 MB on-disk cache for preview videos (watched once = instant next time),
 *  - small start-up buffers (plays after ~0.25 s of data instead of several seconds),
 *  - on Wi-Fi, the first ~600 KB of each visible card's preview is fetched in the background.
 */
@UnstableApi
object PreviewEngine {
    private const val PREFETCH_BYTES = 600_000L
    @Volatile private var cache: SimpleCache? = null
    private lateinit var dataSource: CacheDataSource.Factory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val prefetched = Collections.synchronizedSet(HashSet<String>())

    @Synchronized
    private fun init(ctx: Context) {
        if (cache != null) return
        val app = ctx.applicationContext
        cache = SimpleCache(app.cacheDir.resolve("previews"), LeastRecentlyUsedCacheEvictor(250L * 1024 * 1024), StandaloneDatabaseProvider(app))
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(SiteSearch.BROWSER_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(10_000)
        dataSource = CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun newPlayer(ctx: Context): ExoPlayer {
        init(ctx)
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(/* min */ 2_000, /* max */ 10_000, /* to start */ 250, /* after stall */ 600)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .apply { volume = 0f }
    }

    /** Warm the start of a preview so it plays instantly when touched (Wi-Fi only, two at a time). */
    fun prefetch(ctx: Context, url: String?) {
        url ?: return
        init(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm?.isActiveNetworkMetered != false) return
        if (!prefetched.add(url)) return
        scope.launch {
            gate.withPermit {
                runCatching {
                    CacheWriter(dataSource.createDataSource(), DataSpec.Builder().setUri(Uri.parse(url)).setLength(PREFETCH_BYTES).build(), null, null).cache()
                }.onFailure { prefetched.remove(url) }
            }
        }
    }
}
