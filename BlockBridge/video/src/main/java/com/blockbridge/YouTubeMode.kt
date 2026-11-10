package com.blockbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Firefox's engine (GeckoView) for the whole app, with the real uBlock Origin and SponsorBlock
 * installed from addons.mozilla.org (Mozilla-signed) and kept updated, plus BlockBridge's own
 * built-in "YouTube Plus" helper (background play, PiP view). Used by YouTube mode.
 */
object Gecko {
    const val UBO_ID = "uBlock0@raymondhill.net"
    const val SPONSOR_ID = "sponsorBlocker@ajay.app"
    const val PLUS_ID = "ytplus@blockbridge"
    val TRUSTED_ADDONS = setOf(UBO_ID, SPONSOR_ID, PLUS_ID)
    private const val UBO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val SPONSOR_URL = "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi"
    private const val PLUS_URL = "resource://android/assets/ytplus/"

    /** Firefox's engine ships for 64-bit phones only (see build.gradle.kts). */
    val supported: Boolean get() = android.os.Process.is64Bit()

    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile var ublock: WebExtension? = null
        private set
    @Volatile var sponsor: WebExtension? = null
        private set
    @Volatile var plus: WebExtension? = null
        private set
    @Volatile var status = "not opened yet"
        private set
    /** Called (on any thread) when an add-on finishes installing, so open pages can hook it. */
    @Volatile var onReady: (() -> Unit)? = null

    fun runtime(ctx: Context): GeckoRuntime = runtime ?: synchronized(this) {
        runtime ?: GeckoRuntime.create(ctx.applicationContext, GeckoRuntimeSettings.Builder()
            // "Detailed engine log": web page console messages go to the system log too.
            .consoleOutput(YtPrefs.debug(ctx))
            .aboutConfigEnabled(false)
            .crashHandler(GeckoCrashService::class.java)
            .build()).also { rt ->
            runtime = rt
            status = "Starting…"
            DiagLog.add("youtube", "Firefox engine started${if (YtPrefs.debug(ctx)) " (detailed log on)" else ""}")
            rt.setDelegate { DiagLog.add("youtube", "ERROR Firefox engine shut down") }
            // uBlock Origin asks for broad permissions; we installed it on purpose, so allow.
            // The default prompt answers "no", so approve explicitly (permissions yes, private
            // browsing no, no technical/interaction data sharing).
            rt.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
                // Only BlockBridge's own add-ons are approved. A web page that links to some other
                // add-on (.xpi) gets refused — otherwise any site could install extensions.
                override fun onInstallPromptRequest(
                    extension: WebExtension, permissions: Array<String>, origins: Array<String>, data: Array<String>,
                ): GeckoResult<WebExtension.PermissionPromptResponse> {
                    val ours = extension.id in TRUSTED_ADDONS
                    if (!ours) DiagLog.add("youtube", "refused to install add-on ${extension.id} (not one of BlockBridge's)")
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(ours, false, false))
                }
                override fun onUpdatePrompt(
                    extension: WebExtension, permissions: Array<String>, origins: Array<String>, data: Array<String>,
                ): GeckoResult<AllowOrDeny> =
                    GeckoResult.fromValue(if (extension.id in TRUSTED_ADDONS) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            rt.webExtensionController.ensureBuiltIn(PLUS_URL, PLUS_ID).accept(
                { e -> plus = e; DiagLog.add("youtube", "YouTube Plus helper ${e?.metaData?.version} loaded"); onReady?.invoke() },
                { e -> DiagLog.add("youtube", "YouTube Plus helper failed: ${e?.message}") })
            ensureAddons(ctx.applicationContext, rt)
        }
    }

    /** Installs uBlock Origin and SponsorBlock on first use, then checks for updates about once a day. */
    private fun ensureAddons(ctx: Context, rt: GeckoRuntime) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val checkUpdates = System.currentTimeMillis() - prefs.getLong("ubo_update", 0) > 6 * 3_600_000L
        if (checkUpdates) prefs.edit().putLong("ubo_update", System.currentTimeMillis()).apply()
        rt.webExtensionController.list().accept({ list ->
            fun ensure(id: String, url: String, name: String, set: (WebExtension) -> Unit, main: Boolean) {
                val existing = list?.firstOrNull { it.id == id }
                if (existing != null) {
                    set(existing)
                    if (main) status = "$name ${existing.metaData.version} active"
                    if (checkUpdates) rt.webExtensionController.update(existing).accept({ u ->
                        if (u != null) { set(u); if (main) status = "$name ${u.metaData.version} active" }
                    }, { })
                    return
                }
                if (main) status = "Installing $name…"
                DiagLog.add("youtube", "installing $name from addons.mozilla.org")
                rt.webExtensionController.install(url, null).accept({ ext ->
                    if (ext != null) set(ext)
                    if (main) status = "$name ${ext?.metaData?.version ?: ""} active"
                    DiagLog.add("youtube", "$name ${ext?.metaData?.version ?: ""} installed")
                    onReady?.invoke()
                }, { e ->
                    if (main) status = "Couldn't install $name: ${e?.message} — check your connection and reopen"
                    DiagLog.add("youtube", "couldn't install $name: ${e?.message}")
                })
            }
            ensure(UBO_ID, UBO_URL, "uBlock Origin", { ublock = it }, main = true)
            ensure(SPONSOR_ID, SPONSOR_URL, "SponsorBlock", { sponsor = it; applySponsor(ctx, rt) }, main = false)
        }, { e -> status = "Extension error: ${e?.message}" })
    }

    /**
     * Checks addons.mozilla.org for new uBlock Origin / SponsorBlock versions (every 6 hours
     * when YouTube mode opens, or now if [force]). [done] gets a summary on the main thread.
     */
    fun updateAddons(ctx: Context, force: Boolean, done: (String) -> Unit) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("ubo_update", 0) < 6 * 3_600_000L) return
        val c = runtime(ctx).webExtensionController
        val exts = listOfNotNull(ublock?.let { "uBlock Origin" to it }, sponsor?.let { "SponsorBlock" to it })
        if (exts.isEmpty()) { done("Add-ons: still installing — try again in a minute"); return }
        prefs.edit().putLong("ubo_update", System.currentTimeMillis()).apply()
        val lines = java.util.concurrent.ConcurrentHashMap<String, String>()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun finish(name: String, line: String) {
            lines[name] = line
            if (lines.size == exts.size) main.post { done(exts.joinToString("\n") { lines[it.first] ?: "" }) }
        }
        for ((name, ext) in exts) {
            val before = ext.metaData.version
            c.update(ext).accept({ u ->
                if (u != null) { if (ext.id == UBO_ID) { ublock = u; status = "uBlock Origin ${u.metaData.version} active" } else sponsor = u }
                val now = u?.metaData?.version ?: before
                if (now != before) DiagLog.add("youtube", "$name updated $before → $now")
                finish(name, if (now != before) "$name: updated to $now" else "$name: $now (up to date)")
            }, { e ->
                DiagLog.add("youtube", "ERROR $name update check failed: ${e?.message}")
                finish(name, "$name: couldn't check (${e?.message})")
            })
        }
    }

    /** Turns SponsorBlock on or off to match the YouTube Plus setting. */
    fun applySponsor(ctx: Context, rt: GeckoRuntime = runtime(ctx)) {
        val ext = sponsor ?: return
        val want = YtPrefs.sponsorBlock(ctx)
        if (ext.metaData.enabled == want) return
        val c = rt.webExtensionController
        (if (want) c.enable(ext, WebExtensionController.EnableSource.USER)
            else c.disable(ext, WebExtensionController.EnableSource.USER)).accept({ e ->
                if (e != null) sponsor = e
                DiagLog.add("youtube", "SponsorBlock ${if (want) "on" else "off"}")
            }, { e -> DiagLog.add("youtube", "ERROR SponsorBlock on/off failed: ${e?.message}") })
    }
}

/**
 * BlockBridge Video: YouTube's mobile site in Firefox's engine with uBlock Origin, full screen
 * like the YouTube app (the site's own top bar and tabs). You stay logged in. YouTube links
 * from other apps open here. BlockBridge's menu: the shield button added to YouTube's top bar,
 * or long-press the app icon.
 *
 * Mini player: a playing video can shrink into a small floating player (Back while a video
 * plays) while you keep browsing YouTube behind it. That's two pages: the one
 * playing (moved into the small view) and a second one for browsing.
 */
class YouTubeActivity : Activity() {
    /** Per-page state. */
    private class Page {
        var port: WebExtension.Port? = null
        var url = "https://m.youtube.com/"
        var title = "YouTube"
        var canGoBack = false
    }

    private enum class Player { NONE, FULL, MINI }

    private val pages = HashMap<GeckoSession, Page>()
    /** The YouTube page you browse (home, search, subscriptions, channels…). */
    private lateinit var browse: GeckoSession
    /** The watch page, in the player layer above (created on first video). */
    private var player: GeckoSession? = null
    private var state = Player.NONE
    /** The page in front: the player when it's open full size, otherwise the browsing page. */
    private val session: GeckoSession get() = player?.takeIf { state == Player.FULL } ?: browse
    /** The page whose video the notification/PiP/lock-screen buttons control. */
    private var mediaOwner: GeckoSession? = null

    private lateinit var root: Root
    private lateinit var browseView: GeckoView
    private lateinit var playerView: GeckoView
    private lateinit var playerBox: PlayerBox
    private lateinit var miniCard: LinearLayout
    private lateinit var miniVideoSpace: View
    /** Dims the browsing page behind the player; fades away as the player shrinks. */
    private lateinit var scrim: View
    /** Covers the rest of the watch page (below the video) as it fades out while shrinking. */
    private lateinit var belowFade: View
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: TextView
    private lateinit var progress: ProgressBar
    private var backPressedAt = 0L
    private var fullScreen = false
    private val lowEnd by lazy { Perf.lowEnd(this) }

    /** Where the video sits on the watch page, in pixels (reported by the helper). */
    private var vTop = 0f
    private var vHeight = 0f
    /** 0 = player full size, 1 = mini bar. */
    private var pos = 0f
    private var anim: android.animation.ValueAnimator? = null
    private var restoreMiniAfterPip = false

    private fun page(s: GeckoSession) = pages.getOrPut(s) { Page() }
    private val plusPort get() = pages[browse]?.port
    private val isNight get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val pageColor get() = if (isNight) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()
    private val textColor get() = if (isNight) 0xFFF1F1F1.toInt() else 0xFF0F0F0F.toInt()
    private val subColor get() = if (isNight) 0xFFAAAAAA.toInt() else 0xFF606060.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Gecko.supported) {
            Toast.makeText(this, "BlockBridge Video needs a 64-bit phone.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        Perf.preferHighRefreshRate(this)
        Gecko.runtime(this)

        browse = newSession()
        browseView = GeckoView(this).apply { setSession(browse) }
        // The player draws into a TextureView so it can shrink and move smoothly (GPU transform,
        // no surface rebuilt) when it turns into the mini bar.
        playerView = GeckoView(this).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
        belowFade = View(this).apply { setBackgroundColor(pageColor); alpha = 0f }
        playerBox = PlayerBox().apply {
            visibility = View.GONE
            setBackgroundColor(pageColor)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(belowFade, FrameLayout.LayoutParams(-1, -1))
        }
        scrim = View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f; visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Palette.CYAN)
            visibility = View.GONE
        }
        miniCard = miniCard()
        root = Root().apply {
            addView(browseView, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(miniCard, FrameLayout.LayoutParams(dp(200), dp(160), Gravity.TOP or Gravity.START))
            addView(playerBox, FrameLayout.LayoutParams(-1, -1))
            addView(progress, FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP))
        }
        setContentView(root)

        val start = startUrl(intent)
        if (isWatchUrl(start)) { browse.loadUri("https://m.youtube.com/"); openVideo(start, "link") } else browse.loadUri(start)
        handleShortcut(intent)
        Gecko.onReady = { runOnUiThread { if (!isDestroyed) { pages.keys.forEach { attachAddons(it) }; checkHelper() } } }
        checkHelper()
        YtMedia.onChange = { runOnUiThread { if (!isDestroyed) { updatePip(); updateMini() } } }
        YtMedia.userControl = { play ->
            runOnUiThread { if (!isDestroyed) userPlay(play) }
            true
        }
        if (Gecko.ublock == null) Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
        DiagLog.add("youtube", "BlockBridge Video opened · ${Perf.refreshInfo(this)}${if (lowEnd) " · low-end phone" else ""}")
        Gecko.updateAddons(this, force = false) { }
        Updates.checkDaily(this, Updates.VIDEO) { u -> runOnUiThread { if (!isDestroyed) offerAppUpdate(u) } }
        CrashGuard.takeReport(this)?.let { r ->
            AlertDialog.Builder(this)
                .setTitle("BlockBridge Video stopped unexpectedly last time")
                .setMessage("Please share this report so it can be fixed.\n\n" + r.text.take(1500))
                .setPositiveButton("Share report") { _, _ -> CrashGuard.share(this, "BlockBridge Video crash report", r.text) }
                .setNeutralButton("Copy") { _, _ -> CrashGuard.copy(this, r.text); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Close", null).show()
        }
    }

    /** A new YouTube page with all of the app's handlers. */
    private fun newSession(): GeckoSession = GeckoSession().apply {
        val me = this
        page(me)
        navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(s: GeckoSession, value: Boolean) { page(s).canGoBack = value }
            override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url == null) return
                page(s).url = url
                // host and page type only (no video IDs or searches) — enough to follow what happened
                runCatching { Uri.parse(url) }.getOrNull()?.let { u ->
                    DiagLog.add("youtube", "page ${u.host}${u.path?.take(40) ?: ""}${if (s === player) " (player)" else ""}")
                }
                runOnUiThread { if (!isDestroyed) routeNavigation(s, url) }
            }
            override fun onLoadError(s: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String>? {
                DiagLog.add("youtube", "ERROR loading ${uri?.let { runCatching { Uri.parse(it).host }.getOrNull() }}: " +
                    "category ${error.category}, code 0x${Integer.toHexString(error.code)}")
                return null
            }
            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                s.loadUri(uri) // open "new window" links in place
                return null
            }
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val uri = Uri.parse(request.uri)
                if (uri.scheme == null || uri.scheme in setOf("http", "https", "about", "data", "blob", "moz-extension"))
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                // "Open in the YouTube app" links (intent://…, vnd.youtube:…) stay here instead.
                youTubeAppLink(request.uri)?.let { web ->
                    DiagLog.add("youtube", "kept an 'open in YouTube app' link inside the app")
                    if (web.isNotEmpty()) runOnUiThread { if (isWatchUrl(web)) openVideo(web, "app link") else s.loadUri(web) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                // mailto:, tel: and other apps' links still open those apps.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }
        progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(s: GeckoSession, p: Int) {
                if (s !== session) return
                progress.progress = p
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
        }
        contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, t: String?) {
                page(s).title = t?.removeSuffix(" - YouTube") ?: "YouTube"
                if (s === player) updateMini()
            }
            override fun onFullScreen(s: GeckoSession, full: Boolean) { if (s === session) setFullScreen(full) }
            override fun onCrash(s: GeckoSession) = recover(s, "crashed")
            override fun onKill(s: GeckoSession) = recover(s, "was stopped by Android (low memory)")
        }
        promptDelegate = YouTubePrompts(this@YouTubeActivity)
        mediaSessionDelegate = mediaDelegate()
        permissionDelegate = object : GeckoSession.PermissionDelegate {
            // Without an answer GeckoView treats autoplay as "ask", i.e. not allowed — so a
            // video started from the PiP / notification buttons (no tap on the page) was
            // stopped at once. YouTube may play; other sites' requests are refused.
            override fun onContentPermissionRequest(
                s: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val host = runCatching { Uri.parse(perm.uri).host }.getOrNull() ?: ""
                val youtube = host == "youtube.com" || host.endsWith(".youtube.com")
                val autoplay = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                val allow = autoplay && youtube
                if (permLogs++ < 10) DiagLog.add("youtube", "permission ${perm.permission} for $host: ${if (allow) "allowed" else "refused"}")
                return GeckoResult.fromValue(
                    if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        open(Gecko.runtime(this@YouTubeActivity))
        attachAddons(me)
    }

    /**
     * Like the YouTube app: videos play in the player layer; everything else (channels, search,
     * Shorts…) opens in the browsing page, and the player shrinks to the mini bar.
     */
    private fun routeNavigation(s: GeckoSession, url: String) {
        if (s === browse && isWatchUrl(url)) {
            // A video opened in the browsing page (the helper usually catches the tap before this).
            if (page(browse).canGoBack) browse.goBack()
            openVideo(url, "tap (fallback)")
        } else if (s === player && state != Player.NONE && !isWatchUrl(url) && url.startsWith("http")) {
            player?.goBack()
            browse.loadUri(url)
            if (isShortsUrl(url)) closePlayer("Shorts opened") else if (state == Player.FULL) animateTo(1f, "a page opened from the video")
        }
    }

    private fun attachAddons(s: GeckoSession) { attachUBlock(s); attachPlus(s) }

    /** A page's engine process died: log it, reopen it and reload where it was. */
    private fun recover(s: GeckoSession, what: String) {
        DiagLog.add("youtube", "ERROR page $what${if (s === player) " (player)" else ""} — reloading")
        if (mediaOwner === s) YtMedia.clear(applicationContext)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            runCatching {
                if (!s.isOpen) s.open(Gecko.runtime(this))
                val view = if (s === player) playerView else browseView
                view.releaseSession(); view.setSession(s)
            }.onFailure { DiagLog.error("youtube", "couldn't reopen the page", it) }
            s.loadUri(page(s).url)
            Toast.makeText(this, "YouTube page $what — reloaded", Toast.LENGTH_SHORT).show()
        }
    }

    /** What the app is doing, for problem reports. */
    private fun summary() = buildString {
        appendLine("BlockBridge Video ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}: ${Gecko.status}")
        appendLine("SponsorBlock: ${Gecko.sponsor?.metaData?.let { "${it.version} ${if (it.enabled) "on" else "off"}" } ?: "not installed"}")
        appendLine("Helper: ${if (Gecko.plus != null) "loaded" else "not loaded"} · connected to page: ${plusPort != null}")
        appendLine("Settings: background ${YtPrefs.background(this@YouTubeActivity)} · PiP ${YtPrefs.pip(this@YouTubeActivity)} · " +
            "SponsorBlock ${YtPrefs.sponsorBlock(this@YouTubeActivity)} · smooth ${YtPrefs.smooth(this@YouTubeActivity)} · " +
            "detailed log ${YtPrefs.debug(this@YouTubeActivity)}")
        appendLine("Phone: ${Perf.describe(this@YouTubeActivity)} · ${Perf.refreshInfo(this@YouTubeActivity)}")
        appendLine("Media: ${if (YtMedia.session == null) "none" else if (YtMedia.playing) "playing" else "paused"} · notification ${YtMediaService.instance != null}")
        appendLine("Player: $state · pages open: ${pages.size}")
        appendLine("PiP supported: ${packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)} · in PiP: $isInPictureInPictureMode")
        append("A VPN (e.g. BlockBridge) is active: ${vpnActive()}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
        intent.data?.let {
            val url = startUrl(intent)
            if (isWatchUrl(url)) openVideo(url, "link") else { if (state == Player.FULL) animateTo(1f, "link"); browse.loadUri(url) }
        }
    }

    private fun startUrl(i: Intent?): String = youTubeStartUrl(i?.data)

    /** BlockBridge's menu: opened from the shield button the helper adds to YouTube's top bar. */
    private fun menu() {
        val items = arrayOf("Settings (background play, PiP, SponsorBlock…)", "uBlock Origin", "uBlock Origin dashboard",
            "SponsorBlock settings", "YouTube Music", "Report a problem (share logs)", "Check for updates",
            "Clear YouTube login & data")
        AlertDialog.Builder(this).setTitle(getString(com.blockbridge.video.R.string.app_name)).setItems(items) { _, which ->
            when (which) {
                0 -> plusSettings()
                1 -> showUBlock()
                2 -> Gecko.ublock?.metaData?.optionsPageUrl?.let { openInBrowse(it) }
                    ?: Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
                3 -> Gecko.sponsor?.metaData?.optionsPageUrl?.let { openInBrowse(it) }
                    ?: Toast.makeText(this, "SponsorBlock is still installing — try again in a moment", Toast.LENGTH_LONG).show()
                4 -> openInBrowse("https://music.youtube.com/")
                5 -> CrashGuard.shareFullReport(this, "BlockBridge Video report", summary(), setOf("youtube", "yt-page"))
                6 -> checkUpdates()
                7 -> AlertDialog.Builder(this)
                    .setMessage("Sign out of YouTube and delete its cookies and site data in this app?")
                    .setPositiveButton("Clear") { _, _ ->
                        closePlayer("signed out")
                        Gecko.runtime(this).storageController.clearData(
                            org.mozilla.geckoview.StorageController.ClearFlags.ALL)
                        browse.loadUri("https://m.youtube.com/")
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun openInBrowse(url: String) {
        if (state == Player.FULL) animateTo(1f, "menu")
        browse.loadUri(url)
    }

    /** Launcher shortcuts (long-press the app icon): Settings, Report a problem. */
    private fun handleShortcut(i: Intent?) {
        when (i?.action) {
            "com.blockbridge.video.SETTINGS" -> plusSettings()
            "com.blockbridge.video.REPORT" -> CrashGuard.shareFullReport(this, "BlockBridge Video report", summary(), setOf("youtube", "yt-page"))
        }
    }

    private fun vpnActive(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    // ---- Player layer and mini bar ----

    private fun isWatchUrl(url: String) = runCatching { Uri.parse(url) }.getOrNull()?.let { u ->
        val h = u.host ?: ""
        (h == "youtube.com" || h.endsWith(".youtube.com")) && h != "music.youtube.com" &&
            (u.path == "/watch" || u.path?.startsWith("/live/") == true)
    } == true

    private fun isShortsUrl(url: String) = runCatching { Uri.parse(url).path?.startsWith("/shorts") }.getOrNull() == true

    /** Opens [url] in the player layer: it slides up over the browsing page, like the YouTube app. */
    private fun openVideo(url: String, how: String) {
        val p = player ?: newSession().also { s -> player = s; playerView.setSession(s); tellPlus() }
        p.setActive(true)
        p.loadUri(url)
        vTop = 0f; vHeight = 0f
        // A video loaded into the player wasn't tapped on its own page, so YouTube's mobile site
        // would wait for another tap: the helper starts it as soon as it's ready.
        autoplayId = runCatching { Uri.parse(url).getQueryParameter("v") }.getOrNull()
            ?: runCatching { Uri.parse(url).lastPathSegment }.getOrNull() ?: ""
        autoplayPending = true
        sendAutoplay()
        DiagLog.add("youtube", "video opened in the player ($how)")
        when (state) {
            Player.FULL -> {}
            Player.MINI -> animateTo(0f, "another video")
            Player.NONE -> {
                state = Player.FULL
                pos = 0f
                playerBox.visibility = View.VISIBLE
                playerBox.alpha = 1f
                applyPos(0f)
                // slide up from the bottom, over a dim that fades in (like the YouTube app)
                val hgt = root.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
                playerBox.translationY = hgt
                scrim.visibility = View.VISIBLE; scrim.alpha = 0f
                scrim.animate().alpha(0.55f).setDuration(if (lowEnd) 220 else 300).start()
                playerBox.animate().translationY(0f).setDuration(if (lowEnd) 260 else 340)
                    .setInterpolator(EASE).withEndAction { scrim.visibility = View.GONE; settle() }.start()
            }
        }
        tellPlus()
    }

    /** Closes the player (✕, swipe away): stops the video. */
    private fun closePlayer(why: String) {
        val p = player ?: return
        if (state == Player.NONE) return
        anim?.cancel()
        state = Player.NONE
        playerBox.visibility = View.GONE
        playerBox.animate().cancel()
        miniCard.visibility = View.GONE
        scrim.visibility = View.GONE
        belowFade.alpha = 0f
        if (mediaOwner === p) { YtMedia.clear(applicationContext); mediaOwner = null }
        if (lowEnd) {
            // free the memory on low-end phones
            playerView.releaseSession(); pages.remove(p); runCatching { p.close() }; player = null
        } else {
            p.loadUri("about:blank"); p.setActive(false)
        }
        browse.setActive(true)
        tellPlus()
        DiagLog.add("youtube", "player closed ($why)")
    }

    /**
     * Positions the player for [p] (0 = full size, 1 = floating mini player) with GPU transforms
     * only: the page keeps its size (no relayout, no reload); it's scaled, moved and cropped to
     * the video with rounded corners. Like the YouTube app, the rest of the watch page fades out
     * as it shrinks and the page behind comes up from a dim.
     */
    private fun applyPos(p: Float) {
        pos = p
        val w = playerBox.width.toFloat(); val h = playerBox.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val vh = (if (vHeight > 0f) vHeight else w * 9f / 16f).coerceIn(1f, h)
        val vt = vTop.coerceIn(0f, h - vh)
        val cw = cardWidth().toFloat()
        val target = cw / w                           // scale of the floating video
        val (cx, cy) = cardXY()
        val s = 1f + (target - 1f) * p
        val left = cx * p
        val top = vt + (cy - vt) * p
        playerBox.pivotX = 0f; playerBox.pivotY = 0f
        playerBox.scaleX = s; playerBox.scaleY = s
        playerBox.translationX = left
        playerBox.translationY = top - vt * s
        // Crop toward the video (bottom goes first, a bit faster), rounded corners growing in.
        val cropP = (p * 1.35f).coerceAtMost(1f)
        cropTop = vt * p
        cropBottom = h + (vt + vh - h) * cropP
        cornerPx = dp(12) * p / s
        playerBox.invalidateOutline()
        // The rest of the watch page fades into the page colour; the browsing page un-dims.
        belowFade.alpha = (p * 2.2f).coerceAtMost(1f)
        scrim.alpha = (1f - p) * 0.55f
        scrim.visibility = if (p in 0.001f..0.999f) View.VISIBLE else View.GONE
        // The card (title, buttons) appears under the video near the end.
        miniCard.translationX = cx
        miniCard.translationY = cy
        val cardAlpha = ((p - 0.7f) / 0.3f).coerceIn(0f, 1f)
        miniCard.alpha = cardAlpha
        miniCard.visibility = if (p > 0.69f) View.VISIBLE else View.GONE
        playerBox.elevation = dp(10) * p
    }

    private var cropTop = 0f
    private var cropBottom = 0f
    private var cornerPx = 0f
    /** Where the floating player sits (top-left, in px), remembered between minimizes. */
    private var cardCorner = 3 // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right

    private fun cardWidth() = (root.width * (if (lowEnd) 0.5f else 0.56f)).toInt().coerceAtLeast(dp(180))
    private fun cardVideoHeight(): Int {
        val w = playerBox.width.toFloat().coerceAtLeast(1f)
        val vh = if (vHeight > 0f) vHeight else w * 9f / 16f
        return (cardWidth() * vh / w).toInt()
    }
    private fun cardHeight() = cardVideoHeight() + dp(CARD_BAR_H)

    /** Top-left of the floating player for its corner (above YouTube's tabs, below its top bar). */
    private fun cardXY(): Pair<Float, Float> {
        val m = dp(12).toFloat()
        val right = cardCorner % 2 == 1
        val bottom = cardCorner >= 2
        val x = if (right) root.width - cardWidth() - m else m
        val y = if (bottom) root.height - dp(NAV_H) - cardHeight() - m else dp(56) + m
        return (x + dragX) to (y + dragY)
    }
    /** While you drag the floating player, how far it's moved from its corner. */
    private var dragX = 0f
    private var dragY = 0f

    /**
     * Animates to full (0) or mini (1) with the screen's refresh rate (Choreographer), on an
     * emphasized-decelerate curve like Android/YouTube motion.
     */
    private fun animateTo(target: Float, how: String, velocity: Float = 0f) {
        if (state == Player.NONE) return
        anim?.cancel()
        browse.setActive(true) // shown behind the shrinking player
        val from = pos
        val dist = kotlin.math.abs(target - from)
        val base = if (lowEnd) 260L else 340L
        // a fast fling finishes faster
        val dur = (base * dist * (if (kotlin.math.abs(velocity) > dp(1500)) 0.7f else 1f)).toLong().coerceIn(120L, base)
        anim = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = dur
            interpolator = EASE
            addUpdateListener { applyPos(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (anim === a) { anim = null; settle() } }
            })
            start()
        }
        if (target >= 1f && state == Player.FULL) DiagLog.add("youtube", "mini player ($how)")
        if (target <= 0f && state == Player.MINI) DiagLog.add("youtube", "player expanded ($how)")
    }

    /** After an animation: record the state, pause what's hidden (saves battery and GPU). */
    private fun settle() {
        if (state == Player.NONE) return
        state = if (pos >= 0.99f) Player.MINI else Player.FULL
        applyPos(if (state == Player.MINI) 1f else 0f)
        // The browsing page is fully covered by the full-size player: stop drawing it.
        browse.setActive(state == Player.MINI)
        updateMini(); tellPlus(); updatePip()
    }

    /** The floating player's card: the video goes on top (drawn by the player layer), then title and buttons. */
    private fun miniCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = roundRect(pageColor, dp(12).toFloat())
        elevation = dp(10).toFloat()
        clipToOutline = true
        miniVideoSpace = View(context)
        addView(miniVideoSpace, LinearLayout.LayoutParams(-1, dp(90)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(2), 0)
            miniTitle = TextView(context).apply {
                textSize = 12f; setTextColor(textColor); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(miniTitle, LinearLayout.LayoutParams(0, -2, 1f))
            fun btn(t: String, desc: String, on: () -> Unit) = TextView(context).apply {
                text = t; textSize = 17f; setTextColor(textColor); gravity = Gravity.CENTER
                contentDescription = desc
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val ripple = android.util.TypedValue().also { tv -> context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true) }
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { on() }
            }
            miniPlay = btn("❚❚", "Play/pause") { YtMedia.request(!YtMedia.playing) }
            addView(miniPlay)
            addView(btn("✕", "Close") { dismissMini(0f, dp(80).toFloat()) })
        }, LinearLayout.LayoutParams(-1, dp(CARD_BAR_H)))
        setOnTouchListener(MiniGesture())
    }

    /** Sizes the card to the video (called when the video's shape is known or the screen turns). */
    private fun sizeCard() {
        val lp = miniCard.layoutParams ?: return
        val w = cardWidth(); val h = cardHeight()
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; miniCard.layoutParams = lp }
        val vlp = miniVideoSpace.layoutParams
        if (vlp.height != cardVideoHeight()) { vlp.height = cardVideoHeight(); miniVideoSpace.layoutParams = vlp }
    }

    /** Fling the floating player away: it slides off and fades, then the player closes. */
    private fun dismissMini(dx: Float, dy: Float) {
        val dur = if (lowEnd) 160L else 220L
        val ex = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) kotlin.math.sign(dx) * root.width * 0.6f else 0f
        val ey = if (ex == 0f) dp(120).toFloat() else 0f
        for (v in listOf(miniCard, playerBox)) {
            v.animate().translationXBy(ex).translationYBy(ey).alpha(0f).setDuration(dur).setInterpolator(EASE).start()
        }
        root.postDelayed({
            listOf(miniCard, playerBox).forEach { it.animate().cancel(); it.alpha = 1f }
            dragX = 0f; dragY = 0f
            closePlayer("swiped away")
        }, dur + 10)
    }

    /** Floating player: tap to expand; drag it anywhere (snaps to a corner); fling it off the side or down to close. */
    private inner class MiniGesture : View.OnTouchListener {
        private var x0 = 0f; private var y0 = 0f
        private var moved = false
        private var vt: android.view.VelocityTracker? = null
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            if (state != Player.MINI || anim != null) return false
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = android.view.VelocityTracker.obtain() }
            vt?.addMovement(e)
            val slop = android.view.ViewConfiguration.get(this@YouTubeActivity).scaledTouchSlop
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; moved = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!moved && kotlin.math.hypot(dx, dy) > slop) moved = true
                    if (moved) { dragX = dx; dragY = dy; applyPos(1f) }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f; val vy = vt?.yVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (!moved) { if (e.actionMasked == android.view.MotionEvent.ACTION_UP) animateTo(0f, "tap"); return true }
                    val (cx, _) = cardXY()
                    val offSide = cx < -cardWidth() * 0.35f || cx + cardWidth() > root.width + cardWidth() * 0.35f
                    val flung = kotlin.math.abs(vx) > dp(1800) && kotlin.math.abs(vx) > kotlin.math.abs(vy)
                    if (offSide || flung) { dismissMini(if (vx != 0f) vx else dragX, 0f); return true }
                    snapToCorner(vx, vy)
                }
            }
            return true
        }
    }

    /** Lets go of a dragged floating player: it glides to the nearest corner (fling direction counts). */
    private fun snapToCorner(vx: Float, vy: Float) {
        val (cx, cy) = cardXY()
        // where it's heading, a little ahead along the fling
        val px = cx + cardWidth() / 2f + vx * 0.12f
        val py = cy + cardHeight() / 2f + vy * 0.12f
        val newCorner = (if (py > root.height / 2f) 2 else 0) + (if (px > root.width / 2f) 1 else 0)
        // keep the on-screen position while switching the base corner, then animate the offset to 0
        cardCorner = newCorner
        dragX = 0f; dragY = 0f
        val (tx, ty) = cardXY()
        val startX = cx - tx; val startY = cy - ty
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = if (lowEnd) 200L else 260L
            interpolator = EASE
            addUpdateListener { a -> val f = a.animatedValue as Float; dragX = startX * f; dragY = startY * f; applyPos(1f) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (anim === a) { anim = null; dragX = 0f; dragY = 0f; applyPos(1f) } }
            })
            start()
        }
    }

    /** The full-size player: hands touches to the page, except while it's floating (the card takes them). */
    private inner class PlayerBox : FrameLayout(this@YouTubeActivity) {
        init {
            // crop to the video with rounded corners while shrinking/floating
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (pos <= 0f) outline.setRect(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, cropTop.toInt(), view.width, cropBottom.toInt(), cornerPx)
                }
            }
            clipToOutline = true
        }
        override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean =
            if (state == Player.FULL && anim == null) super.dispatchTouchEvent(e) else false
    }

    /**
     * Swipe the video down to shrink it, like the YouTube app: watches every touch before the
     * page gets it, and takes over once it's clearly a downward drag that started on the video.
     */
    private inner class Root : FrameLayout(this@YouTubeActivity) {
        private var x0 = 0f; private var y0 = 0f
        private var onVideo = false
        private var dragging = false
        private var vt: android.view.VelocityTracker? = null

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            post { sizeCard(); if (state != Player.NONE && anim == null) applyPos(if (state == Player.MINI) 1f else 0f) }
        }

        override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
            val canDrag = state == Player.FULL && !fullScreen && anim == null && !isInPictureInPictureMode
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    x0 = e.x; y0 = e.y
                    val vh = if (vHeight > 0f) vHeight else width * 9f / 16f
                    onVideo = canDrag && e.y >= vTop - dp(8) && e.y <= vTop + vh
                    vt?.recycle(); vt = if (onVideo) android.view.VelocityTracker.obtain() else null
                }
            }
            vt?.addMovement(e)
            if (!dragging && onVideo && canDrag && e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                val dx = e.x - x0; val dy = e.y - y0
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2
                if (dy > slop && dy > kotlin.math.abs(dx) * 1.5f) {
                    dragging = true
                    // the page stops handling this gesture
                    val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    browse.setActive(true)
                    y0 = e.y - 0.5f
                }
            }
            if (dragging) {
                when (e.actionMasked) {
                    // the video follows the finger
                    android.view.MotionEvent.ACTION_MOVE -> applyPos(((e.y - y0) / (height * DRAG_SPAN)).coerceIn(0f, 1f))
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f
                        vt?.recycle(); vt = null
                        dragging = false
                        animateTo(if (pos > 0.3f || vy > dp(900)) 1f else 0f, "swipe down", vy)
                    }
                }
                return true
            }
            return super.dispatchTouchEvent(e)
        }
    }

    private fun updateMini() {
        val p = player ?: return
        miniTitle.text = page(p).title
        miniPlay.text = if (YtMedia.playing && mediaOwner === p) "❚❚" else "▶"
    }

    // ---- Background play, lock-screen controls, picture-in-picture ----

    /** Follows what YouTube is playing (title, artwork, play/pause) for the notification and PiP. */
    private fun mediaDelegate() = object : MediaSession.Delegate {
        val app = applicationContext
        override fun onActivated(s: GeckoSession, m: MediaSession) {
            DiagLog.add("youtube", "media started"); mediaOwner = s; YtMedia.session = m; YtMedia.changed(app)
        }
        override fun onDeactivated(s: GeckoSession, m: MediaSession) {
            DiagLog.add("youtube", "media ended")
            if (YtMedia.session === m) { YtMedia.clear(app); if (mediaOwner === s) mediaOwner = null }
        }
        override fun onMetadata(s: GeckoSession, m: MediaSession, meta: MediaSession.Metadata) {
            mediaOwner = s
            YtMedia.session = m
            YtMedia.title = meta.title ?: ""
            YtMedia.artist = meta.artist ?: ""
            YtMedia.changed(app)
            // Smaller artwork on low-end phones: it's kept in memory for the notification.
            meta.artwork?.getBitmap(if (lowEnd) 256 else 512)?.accept({ b -> if (b != null && YtMedia.session === m) { YtMedia.artwork = b; YtMedia.changed(app) } }, { })
        }
        override fun onPlay(s: GeckoSession, m: MediaSession) {
            // Another page started playing (e.g. a Short while the player has a video): pause the other.
            val other = mediaOwner
            if (other != null && other !== s) runCatching { YtMedia.session?.pause() }
            mediaOwner = s
            if (!holding()) YtMedia.wantPlay = true
            DiagLog.add("youtube", "play${where(s)}")
            YtMedia.session = m; YtMedia.playing = true; YtMedia.changed(app)
        }
        override fun onPause(s: GeckoSession, m: MediaSession) {
            if (YtMedia.session !== m) return // an old video in another page
            DiagLog.add("youtube", "pause${where(s)}")
            val wasPlaying = YtMedia.playing
            YtMedia.playing = false; YtMedia.changed(app)
            if (ourPause) return
            // You just pressed pause on the PiP / notification / lock-screen / mini-bar buttons.
            if (!YtMedia.wantPlay && android.os.SystemClock.elapsedRealtime() - YtMedia.requestedAt < 5_000) return
            // Leaving the screen or entering PiP: Android may report the switch a moment after
            // YouTube already reacted to it, so a pause right after it counts as "not looking".
            val justMoved = android.os.SystemClock.elapsedRealtime() - transitionAt < 2_500 &&
                (YtPrefs.pip(app) || YtPrefs.background(app))
            val notLooking = holding() || (justMoved && wasPlaying)
            if (!notLooking) { YtMedia.wantPlay = false; return } // you paused on the page
            // While you're not looking you can only pause with the app's buttons (they set
            // wantPlay = false first), so this pause is YouTube's own: play again — at most
            // 3 times in 10 s, so we never fight YouTube forever.
            if (!YtMedia.wantPlay && !(justMoved && wasPlaying)) return
            YtMedia.wantPlay = true
            val now = android.os.SystemClock.elapsedRealtime()
            while (resumes.isNotEmpty() && now - resumes.first() > 10_000) resumes.removeFirst()
            if (resumes.size < 3) {
                resumes.addLast(now)
                DiagLog.add("youtube", "YouTube paused on its own — playing again")
                tellPlus()
                m.play()
            } else DiagLog.add("youtube", "ERROR YouTube keeps pausing — gave up for now (send a report)")
        }
        override fun onStop(s: GeckoSession, m: MediaSession) {
            if (YtMedia.session !== m) return
            YtMedia.playing = false; YtMedia.changed(app)
        }
        override fun onPositionState(s: GeckoSession, m: MediaSession, st: MediaSession.PositionState) {
            if (YtMedia.session !== m) return
            // Frequent: only the lock-screen progress is updated (no notification rebuild).
            YtMedia.durationMs = (st.duration * 1000).toLong().coerceAtLeast(0)
            YtMedia.positionMs = (st.position * 1000).toLong().coerceAtLeast(0)
            YtMedia.rate = st.playbackRate.toFloat()
            YtMedia.positionAt = android.os.SystemClock.elapsedRealtime()
            YtMedia.positionChanged()
        }
    }

    private fun where(s: GeckoSession) = when {
        isInPictureInPictureMode -> " (PiP)"
        stopped -> " (background)"
        s === player && state == Player.MINI -> " (mini player)"
        else -> ""
    }

    /** Play/pause from the app's buttons (PiP, notification, lock screen, mini bar). */
    private fun userPlay(play: Boolean) {
        val owner = mediaOwner
        val port = owner?.let { pages[it]?.port }
        DiagLog.add("youtube", "you pressed ${if (play) "play" else "pause"}${owner?.let { where(it) } ?: ""}")
        if (port != null) runCatching {
            // pause goes straight to the video (past the pause guard); play also via the engine
            port.postMessage(org.json.JSONObject().put("hold", play && holding()).put(if (play) "play" else "pause", true))
        }
        if (play) YtMedia.session?.play() else if (port == null) YtMedia.session?.pause()
        updateMini()
    }

    private var videoW = 16
    private var videoH = 9
    /** When we last left the screen or went into PiP; YouTube pausing right after that gets undone. */
    private var transitionAt = 0L
    private var ourPause = false
    private var reloadedForHelper = false
    private var pageLogs = 0
    private var permLogs = 0
    private val resumes = ArrayDeque<Long>()
    private var stopped = false

    /** Connects to the built-in helper on a page (background play, PiP view, video position, menu). */
    private fun attachPlus(s: GeckoSession) {
        val ext = Gecko.plus ?: return
        s.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                page(s).port = port
                DiagLog.add("youtube", "helper connected to the page${if (s === player) " (player)" else ""}")
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(msg: Any, p: WebExtension.Port) {
                        val j = msg as? org.json.JSONObject ?: return
                        j.optString("log").takeIf { it.isNotEmpty() }?.let {
                            if (pageLogs++ < 300) DiagLog.add("yt-page", it.take(300))
                        }
                        if (j.optBoolean("menu")) runOnUiThread { if (!isDestroyed) menu() }
                        // A video tapped on the browsing page: play it in the player layer.
                        j.optString("open").takeIf { it.isNotEmpty() && s === browse }?.let { url ->
                            runOnUiThread { if (!isDestroyed && isWatchUrl(url)) openVideo(url, "tap") }
                        }
                        // Where the video is on the watch page (for swipe-down and the shrink).
                        j.optJSONArray("vrect")?.let { a ->
                            if (s === player) runOnUiThread {
                                vTop = a.optDouble(0).toFloat(); vHeight = a.optDouble(1).toFloat()
                                // the rest of the watch page (below the video) fades while shrinking
                                belowFade.layoutParams = (belowFade.layoutParams as FrameLayout.LayoutParams).apply {
                                    topMargin = (vTop + vHeight).toInt().coerceAtLeast(0)
                                }
                                sizeCard()
                                if (anim == null && state == Player.MINI) applyPos(1f)
                            }
                        }
                        if (j.optBoolean("autoplayed") && s === player) autoplayPending = false
                        j.optJSONArray("video")?.let { a ->
                            val w = a.optInt(0); val h = a.optInt(1)
                            if (w > 0 && h > 0 && (s === mediaOwner || mediaOwner == null)) runOnUiThread { videoW = w; videoH = h; updatePip() }
                        }
                    }
                    override fun onDisconnect(p: WebExtension.Port) { if (pages[s]?.port === p) pages[s]?.port = null }
                })
                tellPlus()
            }
        }, "blockbridge")
    }

    /**
     * If the page loaded before the helper was ready, it never got the helper's script; reload
     * once so background play and the PiP view work.
     */
    private fun checkHelper() {
        browseView.postDelayed({
            if (isDestroyed || plusPort != null || reloadedForHelper || Gecko.plus == null) return@postDelayed
            reloadedForHelper = true
            DiagLog.add("youtube", "helper not connected to the page — reloading once")
            browse.reload()
        }, 6_000)
    }

    /** Tells every page's helper its role and state: browsing page or player, PiP, held. */
    private fun tellPlus() {
        for ((s, p) in pages) {
            val port = p.port ?: continue
            runCatching {
                port.postMessage(org.json.JSONObject()
                    .put("role", if (s === player) "player" else "browse")
                    .put("background", YtPrefs.background(this))
                    .put("smooth", YtPrefs.smooth(this))
                    // video-only view in the floating window
                    .put("pip", isInPictureInPictureMode && s === session)
                    .put("hold", holding() && YtMedia.wantPlay && s === mediaOwner))
            }
        }
        if (autoplayPending) sendAutoplay()
    }

    private var autoplayPending = false
    /** The video to autoplay (only that page acts on it, not the previous one still unloading). */
    private var autoplayId = ""

    private fun sendAutoplay() {
        val port = player?.let { pages[it]?.port } ?: return // sent again when the page's helper connects
        runCatching { port.postMessage(org.json.JSONObject().put("autoplay", autoplayId)) }
    }

    /** Not looking at the page: only the app's buttons can pause now. */
    private fun holding() = isInPictureInPictureMode || (stopped && YtPrefs.background(this))

    private fun pipParams(): PictureInPictureParams {
        val playing = YtMedia.playing
        val toggle = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", if (playing) "Pause" else "Play",
            YtMediaService.action(this, YtMediaService.TOGGLE, 2))
        val next = RemoteAction(Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", "Next",
            YtMediaService.action(this, YtMediaService.NEXT, 3))
        val prev = RemoteAction(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Previous", "Previous",
            YtMediaService.action(this, YtMediaService.PREV, 1))
        return PictureInPictureParams.Builder()
            .setAspectRatio(pipAspect(videoW, videoH))
            .setActions(listOf(prev, toggle, next).takeLast(maxPipActions()))
            .apply {
                // Android 12+: slide into PiP smoothly when you swipe home while a video plays.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(YtPrefs.pip(this@YouTubeActivity) && playing)
                    setSeamlessResizeEnabled(false) // video: no cross-fade while resizing
                }
            }
            .build()
    }

    private fun maxPipActions(): Int = runCatching { maxNumPictureInPictureActions }.getOrDefault(3).coerceAtLeast(1)

    private var lastPip = ""
    private fun updatePip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // Only when something PiP shows actually changed (it's a call into the system).
        val key = "${YtMedia.playing}/$videoW/$videoH/${YtPrefs.pip(this)}"
        if (key == lastPip) return
        lastPip = key
        runCatching { setPictureInPictureParams(pipParams()) }
            .onFailure { DiagLog.error("youtube", "picture-in-picture settings refused", it) }
    }

    /** Leaving the app while a video plays: shrink it into a floating window (Android 8–11). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        transitionAt = android.os.SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && YtPrefs.pip(this) && YtMedia.playing &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            prepareForPip()
            runCatching { enterPictureInPictureMode(pipParams()) }
                .onFailure { DiagLog.error("youtube", "couldn't start picture-in-picture", it) }
        }
    }

    /** The floating window shows the player full size (video only). */
    private fun prepareForPip() {
        if (state == Player.MINI && mediaOwner === player) {
            restoreMiniAfterPip = true
            anim?.cancel()
            state = Player.FULL
            applyPos(0f)
        }
    }

    private var leftPip = false

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) prepareForPip()
        progress.visibility = View.GONE
        miniCard.visibility = if (!inPip && state == Player.MINI) View.VISIBLE else View.GONE
        if (!inPip && restoreMiniAfterPip) {
            restoreMiniAfterPip = false
            root.post { if (state == Player.FULL) { pos = 1f; state = Player.MINI; applyPos(1f) } }
        }
        leftPip = !inPip
        if (inPip && YtMedia.playing) YtMedia.wantPlay = true
        if (inPip) transitionAt = android.os.SystemClock.elapsedRealtime()
        DiagLog.add("youtube", if (inPip) "picture-in-picture on" else "picture-in-picture off")
        tellPlus()
    }

    override fun onPause() {
        super.onPause()
        transitionAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        leftPip = false
        ourPause = false
        Perf.preferHighRefreshRate(this)
        tellPlus()
        updatePip()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        // Closing the floating window (×) or turning background play off stops the video.
        if (leftPip || !YtPrefs.background(this)) {
            if (YtMedia.playing) DiagLog.add("youtube", if (leftPip) "floating window closed — pausing" else "left the app, background play off — pausing")
            ourPause = true
            YtMedia.session?.pause()
        } else if (YtMedia.playing) {
            YtMedia.wantPlay = true
            DiagLog.add("youtube", "left the app — background play")
        }
        tellPlus()
        leftPip = false
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android is short of memory: drop the idle player page.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && state == Player.NONE) {
            player?.let { p ->
                playerView.releaseSession(); pages.remove(p); runCatching { p.close() }; player = null
                DiagLog.add("youtube", "low memory — closed the idle player page")
            }
        }
    }

    private var uboAction: WebExtension.Action? = null
    private var uboActionFor: GeckoSession? = null

    /** Hooks uBlock Origin's toolbar button for a page, so its popup knows which page it's for. */
    private fun attachUBlock(s: GeckoSession) {
        val ext = Gecko.ublock ?: return
        s.webExtensionController.setActionDelegate(ext, object : WebExtension.ActionDelegate {
            override fun onBrowserAction(e: WebExtension, sess: GeckoSession?, action: WebExtension.Action) {
                if (s !== session) return
                uboAction = action; uboActionFor = s
            }
            override fun onTogglePopup(e: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> = popup()
            override fun onOpenPopup(e: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> = popup()
        })
    }

    /** Shows uBlock Origin's own popup (blocked count, power button) in a dialog. */
    private fun popup(): GeckoResult<GeckoSession> {
        val popup = GeckoSession()
        runOnUiThread {
            val view = GeckoView(this).apply { setSession(popup) }
            AlertDialog.Builder(this)
                .setView(FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, dp(500))) })
                .setNegativeButton("Close", null)
                .setOnDismissListener { popup.close() }
                .show()
        }
        return GeckoResult.fromValue(popup)
    }

    private fun showUBlock() {
        if (Gecko.ublock == null) { Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show(); return }
        attachUBlock(session)
        uboAction?.click() ?: Toast.makeText(this, "uBlock Origin is starting — try again in a moment", Toast.LENGTH_SHORT).show()
    }

    /** Updates everything YouTube mode uses: uBlock Origin, SponsorBlock and BlockBridge itself. */
    private fun checkUpdates() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Gecko.updateAddons(this, force = true) { addons ->
            kotlin.concurrent.thread(name = "update-check", isDaemon = true) {
                val app = runCatching { Updates.check(this, Updates.VIDEO) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val u = app.getOrNull()
                    val appLine = when {
                        u != null -> "BlockBridge Video: new version available (build ${u.versionCode})"
                        app.isFailure -> "BlockBridge Video: couldn't check (${app.exceptionOrNull()?.message})"
                        else -> "BlockBridge Video: up to date"
                    }
                    val b = AlertDialog.Builder(this).setTitle("Updates")
                        .setMessage("$addons\n$appLine\n\nThe YouTube page itself always loads the newest version from YouTube.")
                        .setNegativeButton("Close", null)
                    if (u != null) b.setPositiveButton("Download") { _, _ -> offerAppUpdate(u) }
                    b.show()
                }
            }
        }
    }

    private fun offerAppUpdate(u: Updates.Available) {
        AlertDialog.Builder(this)
            .setTitle("BlockBridge Video update available")
            .setMessage("Build ${u.versionCode} is ready (with the newest Firefox engine and YouTube Plus helper). Download and open it to install.")
            .setPositiveButton("Download") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) } }
            .setNegativeButton("Later", null).show()
    }

    /** The YouTube Plus switches. */
    private fun plusSettings() {
        val labels = arrayOf(
            "Background play — keeps playing with the screen off or in other apps",
            "Picture-in-picture — floating video when you leave the app",
            "SponsorBlock — skip sponsor segments, intros and reminders",
            "Smooth mode — lighter video format your phone decodes in hardware (best for low-end phones; reload videos after changing)",
            "Detailed engine log — adds web page messages to reports (after restarting BlockBridge)")
        val keys = arrayOf("yt_background", "yt_pip", "yt_sponsor", "yt_smooth", "yt_debug")
        val on = booleanArrayOf(YtPrefs.background(this), YtPrefs.pip(this), YtPrefs.sponsorBlock(this),
            YtPrefs.smooth(this), YtPrefs.debug(this))
        AlertDialog.Builder(this).setTitle("YouTube Plus")
            .setMultiChoiceItems(labels, on) { _, i, checked -> on[i] = checked }
            .setPositiveButton("Save") { _, _ ->
                keys.indices.forEach { YtPrefs.set(this, keys[it], on[it]) }
                DiagLog.add("youtube", "settings: " + keys.indices.joinToString { "${keys[it]}=${on[it]}" })
                tellPlus(); updatePip(); Gecko.applySponsor(this)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setFullScreen(full: Boolean) {
        fullScreen = full
        window.insetsController?.let { c ->
            if (full) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        }
        requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullScreen -> session.exitFullScreen()
            anim != null -> {}
            // Like the YouTube app: Back on the full-size player shrinks it to the mini bar.
            state == Player.FULL -> animateTo(1f, "back")
            page(browse).canGoBack -> browse.goBack()
            // Mini player still playing: go to the home screen and keep playing in the background.
            state == Player.MINI && YtMedia.playing -> moveTaskToBack(true)
            // Like the YouTube app: press Back twice to leave.
            android.os.SystemClock.elapsedRealtime() - backPressedAt > 2_000 -> {
                backPressedAt = android.os.SystemClock.elapsedRealtime()
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (!Gecko.supported) { super.onDestroy(); return }
        anim?.cancel()
        Gecko.onReady = null
        YtMedia.onChange = null
        YtMedia.userControl = null
        YtMedia.clear(applicationContext)
        runCatching { browseView.releaseSession() }
        runCatching { playerView.releaseSession() }
        pages.keys.toList().forEach { runCatching { it.close() } }
        pages.clear()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Height of the floating player's title/buttons row and of YouTube's bottom tabs (dp). */
        const val CARD_BAR_H = 40
        const val NAV_H = 48
        /** How far (share of the screen height) a drag goes from full size to the mini bar. */
        const val DRAG_SPAN = 0.6f
        /** "Emphasized decelerate" (Material motion): moves off quickly, settles softly. */
        val EASE = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}

/**
 * The page to open for a link another app sent us: youtu.be/ID, youtube.com/watch?v=ID … go to
 * the mobile site. Anything else (other sites, file://, about:, javascript:…) opens YouTube's
 * home page instead — an app on the phone must not be able to load arbitrary pages here.
 */
internal fun youTubeStartUrl(u: Uri?): String {
    val home = "https://m.youtube.com/"
    if (u == null || (u.scheme != "https" && u.scheme != "http")) return home
    val host = u.host?.lowercase() ?: return home
    return when {
        host == "youtu.be" -> u.path?.trim('/')?.takeIf { Regex("^[A-Za-z0-9_-]{6,20}$").matches(it) }
            ?.let { "https://m.youtube.com/watch?v=$it" } ?: home
        host == "music.youtube.com" -> u.buildUpon().scheme("https").build().toString()
        host == "youtube.com" || host.endsWith(".youtube.com") ->
            u.buildUpon().scheme("https").authority("m.youtube.com").build().toString()
        else -> home
    }
}

/**
 * If [uri] is an "open in the YouTube app" link (intent://… for YouTube, vnd.youtube:…), returns
 * the web address to load here instead ("" if there isn't one); null for other apps' links.
 */
internal fun youTubeAppLink(uri: String): String? {
    if (uri.startsWith("vnd.youtube:")) {
        val id = uri.removePrefix("vnd.youtube:").trimStart('/').substringBefore('?').substringAfterLast('/')
        return if (id.isNotEmpty()) "https://m.youtube.com/watch?v=$id" else ""
    }
    if (!uri.startsWith("intent:")) return null
    val i = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    val pkg = i.`package` ?: ""
    val data = i.data
    val isYouTube = pkg.startsWith("com.google.android.youtube") || pkg.startsWith("com.google.android.apps.youtube") ||
        data?.scheme == "vnd.youtube" || data?.host?.let { it == "youtu.be" || it == "youtube.com" || it.endsWith(".youtube.com") } == true
    if (!isYouTube) return null
    val fallback = i.getStringExtra("browser_fallback_url")
    return when {
        data != null && (data.scheme == "http" || data.scheme == "https") -> data.toString()
        data?.scheme == "vnd.youtube" -> youTubeAppLink(data.toString()) ?: ""
        fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://")) -> fallback
        else -> ""
    }
}

/** Basic dialogs web pages need: alert, confirm, text input and drop-down lists (<select>). */
private class YouTubePrompts(private val a: Activity) : GeckoSession.PromptDelegate {
    override fun onAlertPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        AlertDialog.Builder(a).setTitle(p.title).setMessage(p.message)
            .setPositiveButton("OK") { _, _ -> r.complete(p.dismiss()) }
            .setOnCancelListener { r.complete(p.dismiss()) }.show()
        return r
    }

    override fun onButtonPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        AlertDialog.Builder(a).setTitle(p.title).setMessage(p.message)
            .setPositiveButton("OK") { _, _ -> r.complete(p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
            .setNegativeButton("Cancel") { _, _ -> r.complete(p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
            .setOnCancelListener { r.complete(p.dismiss()) }.show()
        return r
    }

    override fun onTextPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val input = android.widget.EditText(a).apply { setText(p.defaultValue) }
        AlertDialog.Builder(a).setTitle(p.title).setMessage(p.message).setView(input)
            .setPositiveButton("OK") { _, _ -> r.complete(p.confirm(input.text.toString())) }
            .setNegativeButton("Cancel") { _, _ -> r.complete(p.dismiss()) }
            .setOnCancelListener { r.complete(p.dismiss()) }.show()
        return r
    }

    override fun onChoicePrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val flat = p.choices.flatMap { c -> if (c.items != null) c.items!!.toList() else listOf(c) }.filter { !it.disabled && !it.separator }
        AlertDialog.Builder(a).setTitle(p.title)
            .setItems(flat.map { it.label }.toTypedArray()) { _, i -> r.complete(p.confirm(flat[i])) }
            .setOnCancelListener { r.complete(p.dismiss()) }.show()
        return r
    }
}

/** Picture-in-picture window shape for a video of [w]×[h], within what Android allows (2.39:1 … 1:2.39). */
internal fun pipAspect(w: Int, h: Int): Rational {
    if (w <= 0 || h <= 0) return Rational(16, 9)
    val r = w.toDouble() / h
    return when {
        r > 2.39 -> Rational(239, 100)
        r < 1 / 2.39 -> Rational(100, 239)
        else -> Rational(w, h)
    }
}
