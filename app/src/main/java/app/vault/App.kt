package app.vault

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.vault.data.AppLog
import app.vault.data.Prefs
import app.vault.data.USER_AGENT
import app.vault.data.Repo
import app.vault.sync.SyncWorker

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
    }

    /** Image loader that identifies the app (Wikimedia rejects anonymous image requests). */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                .build()
        }
        .crossfade(true)
        .build()
}

object AppLock {
    var locked by mutableStateOf(true)
}

val Context.app: App get() = applicationContext as App
