package app.vault.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.vault.AppLock
import app.vault.app
import app.vault.data.AppLog

/** Private in-app browser: blocks pop-ups and jumps to other apps, supports full-screen video. */
class BrowserActivity : ComponentActivity() {
    private var web: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pageTitle by mutableStateOf("")
    private var pageUrl by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)
    private var canBack by mutableStateOf(false)
    private var canForward by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPrivacyFlags()
        val start = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        AppLog.i("Browser", "Open ${Uri.parse(start).host}")

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val scheme = req.url.scheme ?: return true
                    // Stay inside the browser; block intent://, market:// and other app jumps.
                    return scheme != "http" && scheme != "https"
                }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) AppLog.w("Browser", "Page error ${err.errorCode} (${err.description}) on ${req.url.host}")
                }
                override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                    if (req.isForMainFrame) AppLog.w("Browser", "HTTP ${res.statusCode} on ${req.url.host}")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Without this, a crashed web page takes the whole app down.
                    AppLog.e("Browser", "Web page renderer died (crashed=${detail.didCrash()}) on ${Uri.parse(pageUrl).host}")
                    web = null
                    finish()
                    return true
                }
                override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { pageUrl = u; refreshNav() }
                override fun onPageFinished(view: WebView, u: String) { pageUrl = u; pageTitle = view.title.orEmpty(); refreshNav() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) { loadProgress = p }
                override fun onReceivedTitle(view: WebView, t: String?) { pageTitle = t.orEmpty() }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
                override fun onHideCustomView() = exitFullscreen()
            }
            loadUrl(start)
        }
        web = wv
        pageUrl = start

        setContent {
            VaultTheme {
                BackHandler {
                    when {
                        customView != null -> exitFullscreen()
                        wv.canGoBack() -> wv.goBack()
                        else -> finish()
                    }
                }
                Scaffold(
                    containerColor = Bg,
                    topBar = { BrowserBar() },
                    bottomBar = { BrowserBottom() },
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        AndroidView(factory = { wv }, modifier = Modifier.fillMaxSize())
                        if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    private fun refreshNav() {
        canBack = web?.canGoBack() == true
        canForward = web?.canGoForward() == true
    }

    @Composable
    private fun BrowserBar() {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close") }
            Column(Modifier.weight(1f)) {
                Text(pageTitle.ifBlank { "Loading…" }, maxLines = 1, fontSize = 14.sp)
                Text(Uri.parse(pageUrl).host.orEmpty(), maxLines = 1, fontSize = 12.sp, color = Muted)
            }
            IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", pageUrl))
                        Toast.makeText(this@BrowserActivity, "Copied", Toast.LENGTH_SHORT).show(); menu = false
                    })
                    DropdownMenuItem(text = { Text("Open in phone browser") }, onClick = {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) }; menu = false
                    })
                    DropdownMenuItem(text = { Text("Play video from link") }, onClick = { promptVideo(); menu = false })
                }
            }
        }
    }

    @Composable
    private fun BrowserBottom() {
        Row(Modifier.navigationBarsPadding().fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { web?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton(onClick = { web?.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            IconButton(onClick = { finish() }) { Icon(Icons.Default.Home, "Back to app") }
        }
    }

    /** Lets the user paste a direct .mp4/.m3u8 link to watch it in the native player. */
    private fun promptVideo() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        val candidate = clip?.takeIf { it.startsWith("http") } ?: pageUrl
        playVideo(candidate, pageTitle)
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view; customCallback = callback
        (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        customCallback?.onCustomViewHidden(); customCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onResume() {
        super.onResume()
        applyPrivacyFlags()
        if (app.prefs.hasPin && AppLock.locked) finish() // main screen will ask for the PIN
        web?.onResume()
    }

    override fun onPause() { web?.onPause(); super.onPause() }

    override fun onDestroy() {
        web?.let { wv ->
            if (app.prefs.clearBrowserOnExit) {
                wv.clearHistory(); wv.clearCache(true); wv.clearFormData()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
            }
            wv.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "url" }
}
