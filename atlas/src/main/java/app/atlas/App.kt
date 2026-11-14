package app.atlas

import android.app.Application
import android.content.Context
import app.atlas.data.AdBlock
import app.atlas.data.AppLog
import app.atlas.data.Store
import app.atlas.data.USER_AGENT
import app.atlas.data.WikiRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application(), coil.ImageLoaderFactory {
    lateinit var repo: WikiRepo
    lateinit var store: Store
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        repo = WikiRepo(File(filesDir, "wiki"))
        store = Store(this)
        scope.launch { AdBlock.load(File(filesDir, "blocklists")) }
    }

    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { c -> c.proceed(c.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                .build()
        }
        .crossfade(true)
        .build()
}

val Context.app: App get() = applicationContext as App
