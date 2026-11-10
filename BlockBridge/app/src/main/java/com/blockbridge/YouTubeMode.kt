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
    private const val UBO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val SPONSOR_URL = "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi"
    private const val PLUS_URL = "resource://android/assets/ytplus/"

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
                override fun onInstallPromptRequest(
                    extension: WebExtension, permissions: Array<String>, origins: Array<String>, data: Array<String>,
                ): GeckoResult<WebExtension.PermissionPromptResponse> =
                    GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, false, false))
                override fun onUpdatePrompt(
                    extension: WebExtension, permissions: Array<String>, origins: Array<String>, data: Array<String>,
                ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
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
 * YouTube mode: YouTube's mobile site in Firefox's engine with uBlock Origin, full-screen like
 * an app. You stay logged in. YouTube links from other apps can open here.
 */
class YouTubeActivity : Activity() {
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var bar: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private var canGoBack = false
    private var uboBadge: TextView? = null
    private var fullScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = Gecko.runtime(this)
        window.statusBarColor = Palette.BG

        session = GeckoSession().apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(s: GeckoSession, value: Boolean) { canGoBack = value }
                override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                    if (url != null) {
                        lastUrl = url
                        // host and page type only (no video IDs or searches) — enough to follow what happened
                        runCatching { Uri.parse(url) }.getOrNull()?.let { u -> DiagLog.add("youtube", "page ${u.host}${u.path?.take(40) ?: ""}") }
                    }
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
                    // intent://, mailto: and the like go to other apps
                    if (uri.scheme != null && uri.scheme !in setOf("http", "https", "about", "data", "blob", "moz-extension")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onProgressChange(s: GeckoSession, p: Int) {
                    progress.progress = p
                    progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
            }
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(s: GeckoSession, t: String?) { title.text = t?.removeSuffix(" - YouTube") ?: "YouTube" }
                override fun onFullScreen(s: GeckoSession, full: Boolean) = setFullScreen(full)
                override fun onCrash(s: GeckoSession) = recover("crashed")
                override fun onKill(s: GeckoSession) = recover("was stopped by Android (low memory)")
            }
            promptDelegate = YouTubePrompts(this@YouTubeActivity)
            mediaSessionDelegate = mediaDelegate()
            open(runtime)
        }

        geckoView = GeckoView(this).apply { setSession(this@YouTubeActivity.session) }
        bar = topBar()
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Palette.CYAN)
            visibility = View.GONE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(bar)
            addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
            addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        session.loadUri(startUrl(intent))
        attachUBlock()
        attachPlus()
        Gecko.onReady = { runOnUiThread { if (!isDestroyed) { attachUBlock(); attachPlus(); checkHelper() } } }
        checkHelper()
        YtMedia.onChange = { runOnUiThread { if (!isDestroyed) updatePip() } }
        if (Gecko.ublock == null) Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
        DiagLog.add("youtube", "YouTube mode opened")
        Gecko.updateAddons(this, force = false) { }
        Updates.checkDaily(this) { u -> runOnUiThread { if (!isDestroyed) offerAppUpdate(u) } }
        CrashGuard.takeReport(this)?.let { r ->
            AlertDialog.Builder(this)
                .setTitle("BlockBridge stopped unexpectedly last time")
                .setMessage("Please share this report so it can be fixed.\n\n" + r.text.take(1500))
                .setPositiveButton("Share report") { _, _ -> CrashGuard.share(this, "BlockBridge crash report", r.text) }
                .setNeutralButton("Copy") { _, _ -> CrashGuard.copy(this, r.text); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Close", null).show()
        }
    }

    /** The page's engine process died: log it, reopen the session and reload where we were. */
    private fun recover(what: String) {
        DiagLog.add("youtube", "ERROR page $what — reloading")
        YtMedia.clear(applicationContext)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            runCatching {
                if (!session.isOpen) session.open(Gecko.runtime(this))
                geckoView.setSession(session)
            }.onFailure { DiagLog.error("youtube", "couldn't reopen the page", it) }
            session.loadUri(lastUrl)
            Toast.makeText(this, "YouTube page $what — reloaded", Toast.LENGTH_SHORT).show()
        }
    }

    /** What YouTube mode is doing, for problem reports. */
    private fun summary() = buildString {
        appendLine("YouTube mode: ${Gecko.status}")
        appendLine("SponsorBlock: ${Gecko.sponsor?.metaData?.let { "${it.version} ${if (it.enabled) "on" else "off"}" } ?: "not installed"}")
        appendLine("Helper: ${if (Gecko.plus != null) "loaded" else "not loaded"} · connected to page: ${plusPort != null}")
        appendLine("Settings: background ${YtPrefs.background(this@YouTubeActivity)} · PiP ${YtPrefs.pip(this@YouTubeActivity)} · " +
            "SponsorBlock ${YtPrefs.sponsorBlock(this@YouTubeActivity)} · detailed log ${YtPrefs.debug(this@YouTubeActivity)}")
        appendLine("Media: ${if (YtMedia.session == null) "none" else if (YtMedia.playing) "playing" else "paused"} · notification ${YtMediaService.instance != null}")
        appendLine("PiP supported: ${packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)} · in PiP: $isInPictureInPictureMode")
        append("Protection: ${if (ShieldVpnService.isRunning) "on" else "off"}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { session.loadUri(startUrl(intent)) }
    }

    /** youtu.be/ID, youtube.com/watch?v=ID … → the mobile site; otherwise the home page. */
    private fun startUrl(i: Intent?): String {
        val u = i?.data ?: return "https://m.youtube.com/"
        return when {
            u.host == "youtu.be" -> "https://m.youtube.com/watch?v=${u.path?.trim('/')}"
            u.host == "music.youtube.com" -> u.buildUpon().scheme("https").build().toString()
            u.host?.endsWith("youtube.com") == true -> u.buildUpon().authority("m.youtube.com").scheme("https").build().toString()
            else -> u.toString()
        }
    }

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF0E1426.toInt())
        setPadding(dp(4), dp(4), dp(8), dp(4))
        fun icon(text: String, desc: String, onClick: () -> Unit) = TextView(context).apply {
            this.text = text; textSize = 20f; setTextColor(Palette.TEXT); gravity = Gravity.CENTER
            contentDescription = desc
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
        addView(icon("←", "Back") { onBackPressed() })
        title = TextView(context).apply {
            text = "YouTube"; textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Palette.TEXT)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon("⟳", "Reload") { session.reload() })
        uboBadge = TextView(context).apply {
            text = "uBO"; textSize = 12f; setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Palette.BG); gravity = Gravity.CENTER
            background = gradientRect(dp(8).toFloat(), Palette.CYAN, Palette.VIOLET)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = "uBlock Origin"
            setOnClickListener { showUBlock() }
        }
        addView(uboBadge)
        addView(icon("⋮", "Menu") { menu() })
    }

    // ---- YouTube Plus: background play, lock-screen controls, picture-in-picture ----

    /** Follows what YouTube is playing (title, artwork, play/pause) for the notification and PiP. */
    private fun mediaDelegate() = object : MediaSession.Delegate {
        val app = applicationContext
        override fun onActivated(s: GeckoSession, m: MediaSession) {
            DiagLog.add("youtube", "media started"); YtMedia.session = m; YtMedia.changed(app)
        }
        override fun onDeactivated(s: GeckoSession, m: MediaSession) {
            DiagLog.add("youtube", "media ended")
            if (YtMedia.session === m) YtMedia.clear(app)
        }
        override fun onMetadata(s: GeckoSession, m: MediaSession, meta: MediaSession.Metadata) {
            YtMedia.session = m
            YtMedia.title = meta.title ?: ""
            YtMedia.artist = meta.artist ?: ""
            YtMedia.changed(app)
            meta.artwork?.getBitmap(512)?.accept({ b -> if (b != null && YtMedia.session === m) { YtMedia.artwork = b; YtMedia.changed(app) } }, { })
        }
        override fun onPlay(s: GeckoSession, m: MediaSession) {
            DiagLog.add("youtube", "play${if (isInPictureInPictureMode) " (PiP)" else if (stopped) " (background)" else ""}")
            YtMedia.session = m; YtMedia.playing = true; YtMedia.changed(app)
        }
        override fun onPause(s: GeckoSession, m: MediaSession) {
            DiagLog.add("youtube", "pause${if (isInPictureInPictureMode) " (PiP)" else if (stopped) " (background)" else ""}")
            val wasPlaying = YtMedia.playing
            YtMedia.playing = false; YtMedia.changed(app)
            // YouTube pauses itself when the page shrinks or goes to the background; if that
            // happens right as we go into PiP / background play, start it again.
            val justMoved = android.os.SystemClock.elapsedRealtime() - transitionAt < 2_500
            val keepPlaying = isInPictureInPictureMode || (YtPrefs.background(app) && (stopped || !hasWindowFocus()))
            if (wasPlaying && justMoved && keepPlaying && !ourPause) {
                DiagLog.add("youtube", "YouTube paused itself during the switch — resuming")
                m.play()
            }
        }
        override fun onStop(s: GeckoSession, m: MediaSession) { YtMedia.playing = false; YtMedia.changed(app) }
        override fun onPositionState(s: GeckoSession, m: MediaSession, st: MediaSession.PositionState) {
            YtMedia.durationMs = (st.duration * 1000).toLong().coerceAtLeast(0)
            YtMedia.positionMs = (st.position * 1000).toLong().coerceAtLeast(0)
            YtMedia.rate = st.playbackRate.toFloat()
            YtMedia.positionAt = android.os.SystemClock.elapsedRealtime()
            YtMedia.changed(app)
        }
    }

    private var plusPort: WebExtension.Port? = null
    private var videoW = 16
    private var videoH = 9
    /** When we last left the screen or went into PiP; YouTube pausing right after that gets undone. */
    private var transitionAt = 0L
    private var ourPause = false
    private var reloadedForHelper = false
    private var pageLogs = 0
    private var stopped = false

    /** Connects to BlockBridge's built-in helper on the page (it keeps playback going in the background). */
    private fun attachPlus() {
        val ext = Gecko.plus ?: return
        session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                plusPort = port
                DiagLog.add("youtube", "helper connected to the page")
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(msg: Any, p: WebExtension.Port) {
                        val j = msg as? org.json.JSONObject ?: return
                        j.optString("log").takeIf { it.isNotEmpty() }?.let {
                            if (pageLogs++ < 300) DiagLog.add("yt-page", it.take(300))
                        }
                        j.optJSONArray("video")?.let { a ->
                            val w = a.optInt(0); val h = a.optInt(1)
                            if (w > 0 && h > 0) runOnUiThread { videoW = w; videoH = h; updatePip() }
                        }
                    }
                    override fun onDisconnect(p: WebExtension.Port) { if (plusPort === p) plusPort = null }
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
        geckoView.postDelayed({
            if (isDestroyed || plusPort != null || reloadedForHelper || Gecko.plus == null) return@postDelayed
            reloadedForHelper = true
            DiagLog.add("youtube", "helper not connected to the page — reloading once")
            session.reload()
        }, 6_000)
    }

    private fun tellPlus() = runCatching {
        plusPort?.postMessage(org.json.JSONObject()
            .put("background", YtPrefs.background(this))
            .put("pip", isInPictureInPictureMode))
    }

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

    private fun updatePip() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            runCatching { setPictureInPictureParams(pipParams()) }
                .onFailure { DiagLog.error("youtube", "picture-in-picture settings refused", it) }
    }

    /** Leaving the app while a video plays: shrink it into a floating window (Android 8–11). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        transitionAt = android.os.SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && YtPrefs.pip(this) && YtMedia.playing &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            runCatching { enterPictureInPictureMode(pipParams()) }
                .onFailure { DiagLog.error("youtube", "couldn't start picture-in-picture", it) }
    }

    private var leftPip = false

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        bar.visibility = if (inPip || fullScreen) View.GONE else View.VISIBLE
        progress.visibility = View.GONE
        leftPip = !inPip
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
        } else if (YtMedia.playing) DiagLog.add("youtube", "left the app — background play")
        leftPip = false
    }

    private var uboAction: WebExtension.Action? = null

    /** Hooks uBlock Origin's toolbar button for this tab, so its popup knows which page it's for. */
    private fun attachUBlock() {
        val ext = Gecko.ublock ?: return
        session.webExtensionController.setActionDelegate(ext, object : WebExtension.ActionDelegate {
            override fun onBrowserAction(e: WebExtension, s: GeckoSession?, action: WebExtension.Action) {
                uboAction = action
                action.badgeText?.let { b -> runOnUiThread { uboBadge?.text = if (b.isBlank()) "uBO" else "uBO $b" } }
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
        attachUBlock()
        uboAction?.click() ?: Toast.makeText(this, "uBlock Origin is starting — try again in a moment", Toast.LENGTH_SHORT).show()
    }

    private fun menu() {
        val items = arrayOf("YouTube home", "YouTube Music", "YouTube Plus settings…", "Open uBlock Origin dashboard",
            "SponsorBlock settings", "Open in another app", "Clear YouTube login & data", "Report a problem (share logs)", "Check for updates")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> session.loadUri("https://m.youtube.com/")
                1 -> session.loadUri("https://music.youtube.com/")
                2 -> plusSettings()
                3 -> Gecko.ublock?.metaData?.optionsPageUrl?.let { session.loadUri(it) }
                    ?: Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
                4 -> Gecko.sponsor?.metaData?.optionsPageUrl?.let { session.loadUri(it) }
                    ?: Toast.makeText(this, "SponsorBlock is still installing — try again in a moment", Toast.LENGTH_LONG).show()
                5 -> runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl())), "Open with"))
                }
                8 -> checkUpdates()
                7 -> CrashGuard.shareFullReport(this, "BlockBridge YouTube mode report", summary())
                6 -> AlertDialog.Builder(this)
                    .setMessage("Sign out of YouTube and delete its cookies and site data in BlockBridge?")
                    .setPositiveButton("Clear") { _, _ ->
                        Gecko.runtime(this).storageController.clearData(
                            org.mozilla.geckoview.StorageController.ClearFlags.ALL)
                        session.loadUri("https://m.youtube.com/")
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    /** Updates everything YouTube mode uses: uBlock Origin, SponsorBlock and BlockBridge itself. */
    private fun checkUpdates() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Gecko.updateAddons(this, force = true) { addons ->
            kotlin.concurrent.thread(name = "update-check", isDaemon = true) {
                val app = runCatching { Updates.check(this) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val u = app.getOrNull()
                    val appLine = when {
                        u != null -> "BlockBridge: new version available (build ${u.versionCode})"
                        app.isFailure -> "BlockBridge: couldn't check (${app.exceptionOrNull()?.message})"
                        else -> "BlockBridge: up to date"
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
            .setTitle("BlockBridge update available")
            .setMessage("Build ${u.versionCode} is ready. It also updates YouTube mode (the Firefox engine and the YouTube Plus helper). Download and open it to install.")
            .setPositiveButton("Download") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) } }
            .setNegativeButton("Later", null).show()
    }

    /** The YouTube Plus switches. */
    private fun plusSettings() {
        val labels = arrayOf(
            "Background play — keeps playing with the screen off or in other apps",
            "Picture-in-picture — floating video when you leave the app",
            "SponsorBlock — skip sponsor segments, intros and reminders",
            "Detailed engine log — adds web page messages to reports (after restarting BlockBridge)")
        val keys = arrayOf("yt_background", "yt_pip", "yt_sponsor", "yt_debug")
        val on = booleanArrayOf(YtPrefs.background(this), YtPrefs.pip(this), YtPrefs.sponsorBlock(this), YtPrefs.debug(this))
        AlertDialog.Builder(this).setTitle("YouTube Plus")
            .setMultiChoiceItems(labels, on) { _, i, checked -> on[i] = checked }
            .setPositiveButton("Save") { _, _ ->
                keys.indices.forEach { YtPrefs.set(this, keys[it], on[it]) }
                DiagLog.add("youtube", "settings: " + keys.indices.joinToString { "${keys[it]}=${on[it]}" })
                tellPlus(); updatePip(); Gecko.applySponsor(this)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private var lastUrl = "https://m.youtube.com/"
    private fun currentUrl() = lastUrl

    private fun setFullScreen(full: Boolean) {
        fullScreen = full
        bar.visibility = if (full) View.GONE else View.VISIBLE
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
            canGoBack -> session.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        Gecko.onReady = null
        YtMedia.onChange = null
        YtMedia.clear(applicationContext)
        session.close()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
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
