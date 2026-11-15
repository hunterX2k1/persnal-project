package app.vault.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.vault.AppLock
import app.vault.app
import app.vault.data.AdBlock
import app.vault.data.AppLog
import java.io.ByteArrayInputStream

/**
 * In-app browser tuned for video sites: ad/tracker blocking, pop-up and redirect blocking,
 * unsafe-site warnings, full-screen landscape video, picture-in-picture, downloads, desktop mode.
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val prefs by lazy { app.prefs }

    private var pageTitle by mutableStateOf("")
    private var pageUrl by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)
    private var canBack by mutableStateOf(false)
    private var canForward by mutableStateOf(false)
    private var blocked by mutableIntStateOf(0)
    private var desktop by mutableStateOf(false)
    private var unsafeUrl by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<Pair<String, String?>?>(null) // message, url to open anyway
    private var noticeId by mutableIntStateOf(0)

    @Volatile private var currentSite = ""
    @Volatile private var adblockOn = true
    private var pageLoaded = false
    private val allowedUnsafe = HashSet<String>()
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var defaultUa: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPrivacyFlags()
        desktop = false
        web = WebView(this).apply { configure(this) }

        val start = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        AppLog.i("Browser", "Open ${Uri.parse(start).host}")
        pageUrl = start
        if (needsUnsafeWarning(start)) unsafeUrl = start else web.loadUrl(start)

        setContent { VaultTheme { BrowserUi() } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            offscreenPreRaster = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        defaultUa = w.settings.userAgentString
        applyDesktop(w)
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (adblockOn && !req.isForMainFrame && AdBlock.isAd(req.url.host)) {
                    runOnUiThread { blocked++ }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                val scheme = req.url.scheme
                if (scheme != "http" && scheme != "https") {
                    if (req.hasGesture()) show("Blocked opening another app", null)
                    return true
                }
                if (!req.isForMainFrame) return false
                val host = req.url.host
                if (needsUnsafeWarning(url)) { unsafeUrl = url; return true }
                if (adblockOn && AdBlock.isAd(host)) { blocked++; show("Blocked an ad page ($host)", null); return true }
                val crossSite = AdBlock.site(host) != currentSite
                if (prefs.blockRedirects && pageLoaded && crossSite && !req.hasGesture() && !req.isRedirect) {
                    AppLog.i("Browser", "Blocked redirect to $host")
                    show("Blocked a redirect to $host", url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url; pageLoaded = false
                currentSite = AdBlock.site(Uri.parse(url).host)
                adblockOn = prefs.adblock && currentSite !in prefs.allowAds
                refreshNav()
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageUrl = url; pageTitle = view.title.orEmpty(); pageLoaded = true; refreshNav()
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) AppLog.w("Browser", "Page error ${err.errorCode} (${err.description}) on ${req.url.host}")
            }

            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                if (req.isForMainFrame) AppLog.w("Browser", "HTTP ${res.statusCode} on ${req.url.host}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppLog.e("Browser", "Web page renderer died (crashed=${detail.didCrash()}) on $currentSite")
                Toast.makeText(this@BrowserActivity, "The page crashed and was closed", Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { loadProgress = p }
            override fun onReceivedTitle(view: WebView, t: String?) { pageTitle = t.orEmpty() }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()

            /** Pop-ups: capture the target URL in a throw-away WebView, then decide. */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture && prefs.blockRedirects) { blocked++; show("Blocked a pop-up", null); return false }
                var handled = false
                val temp = WebView(this@BrowserActivity)
                fun take(url: String) {
                    if (handled) return
                    handled = true
                    temp.post { temp.stopLoading(); temp.destroy() }
                    handlePopup(url)
                }
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(r.url.toString()); return true }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { if (url != "about:blank") take(url) }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun handlePopup(url: String) {
        val host = Uri.parse(url).host
        when {
            adblockOn && AdBlock.isAd(host) -> { blocked++; show("Blocked a pop-up ad", null) }
            needsUnsafeWarning(url) -> unsafeUrl = url
            !prefs.blockRedirects || AdBlock.site(host) == currentSite -> web.loadUrl(url)
            else -> show("Pop-up blocked: $host", url)
        }
    }

    private fun needsUnsafeWarning(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        return prefs.warnUnsafe && AdBlock.site(host) !in allowedUnsafe && AdBlock.isUnsafe(host)
    }

    private fun show(msg: String, openUrl: String?) { notice = msg to openUrl; noticeId++ }

    private fun refreshNav() { canBack = web.canGoBack(); canForward = web.canGoForward() }

    private fun applyDesktop(w: WebView = web) {
        w.settings.userAgentString = if (desktop) DESKTOP_UA else defaultUa
    }

    private fun download(url: String, ua: String, cd: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, cd, mime)
        when {
            !url.startsWith("http") -> Toast.makeText(this, "This download only works in the phone browser", Toast.LENGTH_LONG).show()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> runCatching {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .apply { CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) } }
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                getSystemService(DownloadManager::class.java).enqueue(req)
                AppLog.i("Browser", "Download started from ${Uri.parse(url).host}")
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            }.onFailure { AppLog.e("Browser", "Download failed", it); openExternal(url) }
            else -> openExternal(url)
        }
    }

    // ---------- full-screen video ----------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view; customCallback = callback
        (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        customCallback?.onCustomViewHidden(); customCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customView != null) runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) web.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyPrivacyFlags()
        if (prefs.hasPin && AppLock.locked && !isInPictureInPictureMode) { finish(); return } // main screen asks for the PIN
        web.onResume()
    }

    override fun onDestroy() {
        if (prefs.clearBrowserOnExit) {
            web.clearHistory(); web.clearCache(true); web.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        web.destroy()
        super.onDestroy()
    }

    // ---------- UI ----------

    @Composable
    private fun BrowserUi() {
        val snack = remember { SnackbarHostState() }
        val allowAds = prefs.allowAds
        LaunchedEffect(noticeId) {
            val n = notice ?: return@LaunchedEffect
            val r = snack.showSnackbar(n.first, actionLabel = if (n.second != null) "Open" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) n.second?.let { web.loadUrl(it) }
        }
        BackHandler {
            when {
                customView != null -> exitFullscreen()
                web.canGoBack() -> web.goBack()
                else -> finish()
            }
        }
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            topBar = { TopBar(siteAllowed = currentSite in allowAds) },
            bottomBar = {
                Row(Modifier.navigationBarsPadding().fillMaxWidth().background(Surface1), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { web.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton(onClick = { web.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                    IconButton(onClick = { web.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(onClick = { shareText(pageUrl, pageTitle) }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close") }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        unsafeUrl?.let { u ->
            val host = Uri.parse(u).host.orEmpty()
            AlertDialog(
                onDismissRequest = { unsafeUrl = null; if (!pageLoaded && !web.canGoBack()) finish() },
                icon = { Icon(Icons.Default.GppBad, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Unsafe site") },
                text = { Text("FMHY lists $host as unsafe: it has been caught with malware, scams or fake downloads.") },
                confirmButton = { Button(onClick = { unsafeUrl = null; if (!pageLoaded && !web.canGoBack()) finish() }) { Text("Go back") } },
                dismissButton = {
                    TextButton(onClick = { allowedUnsafe += AdBlock.site(host); unsafeUrl = null; web.loadUrl(u) }) { Text("Open anyway", color = MaterialTheme.colorScheme.error) }
                },
            )
        }
    }

    @Composable
    private fun TopBar(siteAllowed: Boolean) {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pageTitle.ifBlank { "Loading…" }, maxLines = 1, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pageUrl.startsWith("https")) Icon(Icons.Default.Lock, null, tint = Muted, modifier = Modifier.size(12.dp))
                    Text(Uri.parse(pageUrl).host.orEmpty(), maxLines = 1, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(start = 3.dp))
                }
            }
            if (prefs.adblock) Text(
                if (siteAllowed) "🛡 off" else "🛡 $blocked",
                fontSize = 12.sp, color = if (siteAllowed) Muted else Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Surface2).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Desktop site") },
                        leadingIcon = { Icon(Icons.Default.Computer, null) },
                        trailingIcon = { Checkbox(desktop, null) },
                        onClick = { desktop = !desktop; applyDesktop(); web.reload(); menu = false },
                    )
                    if (prefs.adblock) DropdownMenuItem(
                        text = { Text(if (siteAllowed) "Turn blocking back on here" else "Allow ads on this site") },
                        leadingIcon = { Icon(Icons.Default.Shield, null) },
                        onClick = { prefs.toggleAllowAds(currentSite); web.reload(); menu = false },
                    )
                    DropdownMenuItem(text = { Text("Copy link") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { copy(pageUrl, "Link copied"); menu = false })
                    DropdownMenuItem(text = { Text("Open in phone browser") }, leadingIcon = { Icon(Icons.Default.Public, null) },
                        onClick = { openExternal(pageUrl); menu = false })
                    DropdownMenuItem(text = { Text("Play video from copied link") }, leadingIcon = { Icon(Icons.Default.PlayCircle, null) },
                        onClick = {
                            val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                            playVideo(clip?.takeIf { it.startsWith("http") } ?: pageUrl, pageTitle); menu = false
                        })
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
