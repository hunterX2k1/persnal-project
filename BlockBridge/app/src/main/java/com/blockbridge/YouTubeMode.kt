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
 * YouTube mode: YouTube's mobile site in Firefox's engine with uBlock Origin, full-screen like
 * an app. You stay logged in. YouTube links from other apps open here.
 *
 * Mini player: a playing video can shrink into a small floating player (⌄ button, swipe the top
 * bar down, or Back) while you keep browsing YouTube behind it. That's two pages: the one
 * playing (moved into the small view) and a second one for browsing.
 */
class YouTubeActivity : Activity() {
    /** Per-page state: each YouTube page (main or mini player) has its own. */
    private class Page {
        var port: WebExtension.Port? = null
        var url = "https://m.youtube.com/"
        var title = "YouTube"
        var canGoBack = false
        var lastBrowseUrl = "https://m.youtube.com/"
    }

    private val pages = HashMap<GeckoSession, Page>()
    /** The page shown full size. */
    private lateinit var session: GeckoSession
    /** The page in the mini player, if any. */
    private var mini: GeckoSession? = null
    /** A browsing page kept from the last mini-player round, reused next time (not on low-end phones). */
    private var spare: GeckoSession? = null
    /** The page whose video the notification/PiP/lock-screen buttons control. */
    private var mediaOwner: GeckoSession? = null

    private lateinit var geckoView: GeckoView
    private lateinit var miniView: GeckoView
    private lateinit var miniCard: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: TextView
    private lateinit var minimizeBtn: TextView
    private lateinit var bar: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private var uboBadge: TextView? = null
    private var fullScreen = false
    private val lowEnd by lazy { Perf.lowEnd(this) }

    private fun page(s: GeckoSession) = pages.getOrPut(s) { Page() }
    private val plusPort get() = pages[session]?.port

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Gecko.supported) {
            Toast.makeText(this, "YouTube mode needs a 64-bit phone. Ad blocking, VPN and Tor still work.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        Gecko.runtime(this)
        window.statusBarColor = Palette.BG

        session = newSession()
        geckoView = GeckoView(this).apply { setSession(this@YouTubeActivity.session) }
        miniView = GeckoView(this)
        bar = topBar()
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Palette.CYAN)
            visibility = View.GONE
        }
        miniCard = miniPlayer()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar)
                addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
                addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(miniCard, FrameLayout.LayoutParams(miniWidth(), -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
        })

        session.loadUri(startUrl(intent))
        Gecko.onReady = { runOnUiThread { if (!isDestroyed) { pages.keys.forEach { attachAddons(it) }; checkHelper() } } }
        checkHelper()
        YtMedia.onChange = { runOnUiThread { if (!isDestroyed) { updatePip(); updateMini() } } }
        YtMedia.userControl = { play ->
            runOnUiThread { if (!isDestroyed) userPlay(play) }
            true
        }
        if (Gecko.ublock == null) Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
        DiagLog.add("youtube", "YouTube mode opened${if (lowEnd) " (low-end phone: smooth mode)" else ""}")
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

    /** A new YouTube page with all of YouTube mode's handlers. */
    private fun newSession(): GeckoSession = GeckoSession().apply {
        val me = this
        page(me)
        navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(s: GeckoSession, value: Boolean) { page(s).canGoBack = value }
            override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url == null) return
                val p = page(s)
                p.url = url
                if (!isVideoUrl(url)) p.lastBrowseUrl = url
                // host and page type only (no video IDs or searches) — enough to follow what happened
                runCatching { Uri.parse(url) }.getOrNull()?.let { u ->
                    DiagLog.add("youtube", "page ${u.host}${u.path?.take(40) ?: ""}${if (s === mini) " (mini player)" else ""}")
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // You opened another video while one plays in the mini player: it replaces it.
                    if (s === session && mini != null && isVideoUrl(url)) closeMini("another video opened")
                    updateBarButtons()
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
                if (uri.scheme == null || uri.scheme in setOf("http", "https", "about", "data", "blob", "moz-extension"))
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                // "Open in the YouTube app" links (intent://…, vnd.youtube:…) stay here instead.
                youTubeAppLink(request.uri)?.let { web ->
                    DiagLog.add("youtube", "kept an 'open in YouTube app' link inside BlockBridge")
                    if (web.isNotEmpty()) s.loadUri(web)
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
                if (s === session) title.text = page(s).title
                if (s === mini) updateMini()
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

    private fun attachAddons(s: GeckoSession) { attachUBlock(s); attachPlus(s) }

    /** A page's engine process died: log it, reopen it and reload where it was. */
    private fun recover(s: GeckoSession, what: String) {
        DiagLog.add("youtube", "ERROR page $what${if (s === mini) " (mini player)" else ""} — reloading")
        if (mediaOwner === s) YtMedia.clear(applicationContext)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (s === mini) { closeMini("its page $what"); return@runOnUiThread }
            if (s === spare) { spare = null; pages.remove(s); runCatching { s.close() }; return@runOnUiThread }
            runCatching {
                if (!s.isOpen) s.open(Gecko.runtime(this))
                geckoView.releaseSession(); geckoView.setSession(s)
            }.onFailure { DiagLog.error("youtube", "couldn't reopen the page", it) }
            s.loadUri(page(s).url)
            Toast.makeText(this, "YouTube page $what — reloaded", Toast.LENGTH_SHORT).show()
        }
    }

    /** What YouTube mode is doing, for problem reports. */
    private fun summary() = buildString {
        appendLine("YouTube mode: ${Gecko.status}")
        appendLine("SponsorBlock: ${Gecko.sponsor?.metaData?.let { "${it.version} ${if (it.enabled) "on" else "off"}" } ?: "not installed"}")
        appendLine("Helper: ${if (Gecko.plus != null) "loaded" else "not loaded"} · connected to page: ${plusPort != null}")
        appendLine("Settings: background ${YtPrefs.background(this@YouTubeActivity)} · PiP ${YtPrefs.pip(this@YouTubeActivity)} · " +
            "SponsorBlock ${YtPrefs.sponsorBlock(this@YouTubeActivity)} · smooth ${YtPrefs.smooth(this@YouTubeActivity)} · " +
            "detailed log ${YtPrefs.debug(this@YouTubeActivity)}")
        appendLine("Phone: ${Perf.describe(this@YouTubeActivity)}")
        appendLine("Media: ${if (YtMedia.session == null) "none" else if (YtMedia.playing) "playing" else "paused"} · notification ${YtMediaService.instance != null}")
        appendLine("Mini player: ${if (mini != null) "open" else "closed"} · pages open: ${pages.size}")
        appendLine("PiP supported: ${packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)} · in PiP: $isInPictureInPictureMode")
        append("Protection: ${if (ShieldVpnService.isRunning) "on" else "off"}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let {
            if (mini != null) closeMini("a link was opened")
            session.loadUri(startUrl(intent))
        }
    }

    private fun startUrl(i: Intent?): String = youTubeStartUrl(i?.data)

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
        minimizeBtn = icon("⌄", "Mini player") { minimize("button") }.apply { visibility = View.GONE }
        addView(minimizeBtn)
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
        // Swipe the bar down while a video plays: mini player.
        var downY = 0f
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (e.rawY - downY > dp(70) && canMinimize()) { minimize("swipe"); downY = Float.MAX_VALUE; true } else false
                }
                else -> false
            }
        }
    }

    private fun updateBarButtons() {
        minimizeBtn.visibility = if (canMinimize()) View.VISIBLE else View.GONE
    }

    // ---- Mini player ----

    private fun isVideoUrl(url: String) = runCatching { Uri.parse(url) }.getOrNull()?.let { u ->
        u.host?.endsWith("youtube.com") == true && (u.path == "/watch" || u.path?.startsWith("/shorts/") == true || u.path?.startsWith("/live/") == true)
    } == true

    private fun canMinimize() = mini == null && !fullScreen && isVideoUrl(page(session).url) &&
        !page(session).url.contains("/shorts/") // Shorts are a scrolling feed, not one video

    private fun miniWidth() = (resources.displayMetrics.widthPixels * (if (lowEnd) 0.5 else 0.55)).toInt().coerceAtLeast(dp(200))

    private fun miniPlayer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        elevation = dp(12).toFloat()
        background = roundRect(Palette.SURFACE, dp(14).toFloat(), Palette.STROKE, dp(1))
        clipToOutline = true
        val w = miniWidth()
        // The video, with a transparent layer on top that takes taps and drags (the page
        // underneath doesn't need touches in the mini player).
        addView(FrameLayout(context).apply {
            addView(miniView, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                contentDescription = "Expand video"
                setOnTouchListener(MiniDrag())
            }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(w, w * 9 / 16))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(4), dp(4))
            miniTitle = TextView(context).apply {
                textSize = 12f; setTextColor(Palette.TEXT); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { expand("tap") }
            }
            addView(miniTitle, LinearLayout.LayoutParams(0, -2, 1f))
            fun btn(t: String, desc: String, on: () -> Unit) = TextView(context).apply {
                text = t; textSize = 18f; setTextColor(Palette.TEXT); gravity = Gravity.CENTER
                contentDescription = desc
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { on() }
            }
            miniPlay = btn("❚❚", "Play/pause") { YtMedia.request(!YtMedia.playing) }
            addView(miniPlay)
            addView(btn("✕", "Close mini player") { closeMini("closed") })
        })
    }

    /** Drag the mini player around; let go and it snaps to the nearest corner. Fling it off the side to close. Tap to expand. */
    private inner class MiniDrag : View.OnTouchListener {
        private var startX = 0f; private var startY = 0f
        private var baseX = 0f; private var baseY = 0f
        private var moved = false
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; baseX = miniCard.translationX; baseY = miniCard.translationY; moved = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX; val dy = e.rawY - startY
                    if (!moved && kotlin.math.hypot(dx, dy) > dp(8)) moved = true
                    if (moved) {
                        miniCard.translationX = baseX + dx; miniCard.translationY = baseY + dy
                        // fade as it's dragged off the side
                        val parentW = (miniCard.parent as View).width
                        val off = offscreen(parentW)
                        miniCard.alpha = (1f - off * 1.5f).coerceIn(0.3f, 1f)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!moved) { if (e.actionMasked == android.view.MotionEvent.ACTION_UP) expand("tap"); return true }
                    val parent = miniCard.parent as View
                    if (offscreen(parent.width) > 0.4f) { closeMini("swiped away"); return true }
                    snap(parent)
                }
            }
            return true
        }

        /** How far (0…1 of its width) the card hangs off the left or right edge. */
        private fun offscreen(parentW: Int): Float {
            val left = miniCard.left + miniCard.translationX
            val right = left + miniCard.width
            val over = maxOf(0f, -left, right - parentW)
            return over / miniCard.width
        }

        private fun snap(parent: View) {
            val m = dp(12).toFloat()
            // Card is laid out at the bottom-right; translation moves it from there.
            val minX = -(parent.width - miniCard.width - 2 * m)
            val minY = -(parent.height - miniCard.height - 2 * m - bar.height)
            val tx = if (miniCard.translationX < minX / 2) minX else 0f
            val ty = if (miniCard.translationY < minY / 2) minY else 0f
            miniCard.animate().translationX(tx).translationY(ty).alpha(1f).setDuration(if (lowEnd) 120 else 200).start()
        }
    }

    private fun updateMini() {
        val m = mini ?: return
        miniTitle.text = page(m).title
        miniPlay.text = if (YtMedia.playing && mediaOwner === m) "❚❚" else "▶"
    }

    /** Shrinks the playing video into the mini player and shows YouTube behind it. */
    private fun minimize(how: String) {
        if (!canMinimize()) return
        val playing = session
        val browse = spare ?: newSession().also { it.loadUri(page(playing).lastBrowseUrl.takeIf { u -> !isVideoUrl(u) } ?: "https://m.youtube.com/") }
        spare = null
        browse.setActive(true)
        geckoView.releaseSession()
        miniView.setSession(playing)
        geckoView.setSession(browse)
        session = browse
        mini = playing
        title.text = page(browse).title
        miniCard.translationX = 0f; miniCard.translationY = 0f; miniCard.alpha = 1f
        miniCard.visibility = View.VISIBLE
        updateMini(); updateBarButtons(); tellPlus()
        DiagLog.add("youtube", "mini player on ($how)")
    }

    /** Back to the full-size video. */
    private fun expand(how: String) {
        val m = mini ?: return
        val browse = session
        miniView.releaseSession()
        geckoView.releaseSession()
        geckoView.setSession(m)
        session = m
        mini = null
        miniCard.visibility = View.GONE
        title.text = page(m).title
        keepOrCloseSpare(browse)
        updateBarButtons(); tellPlus()
        DiagLog.add("youtube", "mini player expanded ($how)")
    }

    /** Stops the video in the mini player and closes it. */
    private fun closeMini(why: String) {
        val m = mini ?: return
        mini = null
        miniCard.visibility = View.GONE
        miniView.releaseSession()
        if (mediaOwner === m) { YtMedia.clear(applicationContext); mediaOwner = null }
        pages.remove(m)
        runCatching { m.close() }
        updateBarButtons()
        DiagLog.add("youtube", "mini player closed ($why)")
    }

    /** Keeps the browsing page for the next mini-player round — except on low-end phones (memory). */
    private fun keepOrCloseSpare(s: GeckoSession) {
        if (lowEnd) { pages.remove(s); runCatching { s.close() }; return }
        spare?.let { old -> pages.remove(old); runCatching { old.close() } }
        s.setActive(false) // not shown: no rendering, timers slowed
        spare = s
    }

    // ---- YouTube Plus: background play, lock-screen controls, picture-in-picture ----

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
            // Another page started playing (e.g. a Short while the mini player plays): pause the other.
            val other = mediaOwner
            if (other != null && other !== s && other === mini) runCatching { YtMedia.session?.pause() }
            mediaOwner = s
            if (!holding()) YtMedia.wantPlay = true
            DiagLog.add("youtube", "play${if (isInPictureInPictureMode) " (PiP)" else if (stopped) " (background)" else if (s === mini) " (mini player)" else ""}")
            YtMedia.session = m; YtMedia.playing = true; YtMedia.changed(app)
        }
        override fun onPause(s: GeckoSession, m: MediaSession) {
            if (YtMedia.session !== m) return // an old video in another page
            DiagLog.add("youtube", "pause${if (isInPictureInPictureMode) " (PiP)" else if (stopped) " (background)" else if (s === mini) " (mini player)" else ""}")
            val wasPlaying = YtMedia.playing
            YtMedia.playing = false; YtMedia.changed(app)
            if (ourPause) return
            // You just pressed pause on the PiP / notification / lock-screen / mini-player buttons.
            if (!YtMedia.wantPlay && android.os.SystemClock.elapsedRealtime() - YtMedia.requestedAt < 5_000) return
            // Leaving the screen or entering PiP: Android may report the switch a moment after
            // YouTube already reacted to it, so a pause right after it counts as "not looking".
            val justMoved = android.os.SystemClock.elapsedRealtime() - transitionAt < 2_500 &&
                (YtPrefs.pip(app) || YtPrefs.background(app))
            val notLooking = holding() || (justMoved && wasPlaying) || s === mini
            if (!notLooking) { YtMedia.wantPlay = false; return } // you paused on the page
            // While you're not looking you can only pause with BlockBridge's buttons (they set
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
            YtMedia.durationMs = (st.duration * 1000).toLong().coerceAtLeast(0)
            YtMedia.positionMs = (st.position * 1000).toLong().coerceAtLeast(0)
            YtMedia.rate = st.playbackRate.toFloat()
            YtMedia.positionAt = android.os.SystemClock.elapsedRealtime()
            YtMedia.changed(app)
        }
    }

    /** Play/pause from BlockBridge's buttons (PiP, notification, lock screen, mini player). */
    private fun userPlay(play: Boolean) {
        val owner = mediaOwner
        val port = owner?.let { pages[it]?.port }
        DiagLog.add("youtube", "you pressed ${if (play) "play" else "pause"}${if (isInPictureInPictureMode) " (PiP)" else if (stopped) " (background)" else if (owner != null && owner === mini) " (mini player)" else ""}")
        if (port != null) runCatching {
            // pause goes straight to the video (past the pause guard); play also via the engine
            port.postMessage(org.json.JSONObject().put("hold", play && (holding() || owner === mini)).put(if (play) "play" else "pause", true))
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

    /** Connects to BlockBridge's built-in helper on a page (background play, PiP/mini view, no "open app"). */
    private fun attachPlus(s: GeckoSession) {
        val ext = Gecko.plus ?: return
        s.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                page(s).port = port
                DiagLog.add("youtube", "helper connected to the page${if (s === mini) " (mini player)" else ""}")
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(msg: Any, p: WebExtension.Port) {
                        val j = msg as? org.json.JSONObject ?: return
                        j.optString("log").takeIf { it.isNotEmpty() }?.let {
                            if (pageLogs++ < 300) DiagLog.add("yt-page", it.take(300))
                        }
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
        geckoView.postDelayed({
            if (isDestroyed || plusPort != null || reloadedForHelper || Gecko.plus == null) return@postDelayed
            reloadedForHelper = true
            DiagLog.add("youtube", "helper not connected to the page — reloading once")
            session.reload()
        }, 6_000)
    }

    /** Tells every page's helper its state: shown full size, in PiP, in the mini player, or held. */
    private fun tellPlus() {
        for ((s, p) in pages) {
            val port = p.port ?: continue
            val inMini = s === mini
            runCatching {
                port.postMessage(org.json.JSONObject()
                    .put("background", YtPrefs.background(this))
                    .put("smooth", YtPrefs.smooth(this))
                    // video-only view: the floating window, or the mini player
                    .put("pip", (isInPictureInPictureMode && s === session) || inMini)
                    .put("hold", (holding() || inMini) && YtMedia.wantPlay && s === mediaOwner))
            }
        }
    }

    /** Not looking at the page: only BlockBridge's buttons can pause now. */
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
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (mini != null && mediaOwner === mini) expand("leaving the app")
            runCatching { enterPictureInPictureMode(pipParams()) }
                .onFailure { DiagLog.error("youtube", "couldn't start picture-in-picture", it) }
        }
    }

    private var leftPip = false

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        // The floating window shows the full-size page: bring the mini player's video there.
        if (inPip && mini != null && mediaOwner === mini) expand("picture-in-picture")
        bar.visibility = if (inPip || fullScreen) View.GONE else View.VISIBLE
        progress.visibility = View.GONE
        if (inPip) miniCard.visibility = View.GONE else if (mini != null) miniCard.visibility = View.VISIBLE
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
        // Android is short of memory: drop the kept browsing page first.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            spare?.let { s ->
                spare = null; pages.remove(s); runCatching { s.close() }
                DiagLog.add("youtube", "low memory — closed the kept browsing page")
            }
        }
    }

    private var uboAction: WebExtension.Action? = null

    /** Hooks uBlock Origin's toolbar button for a page, so its popup knows which page it's for. */
    private fun attachUBlock(s: GeckoSession) {
        val ext = Gecko.ublock ?: return
        s.webExtensionController.setActionDelegate(ext, object : WebExtension.ActionDelegate {
            override fun onBrowserAction(e: WebExtension, sess: GeckoSession?, action: WebExtension.Action) {
                if (s !== session) return
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
        attachUBlock(session)
        uboAction?.click() ?: Toast.makeText(this, "uBlock Origin is starting — try again in a moment", Toast.LENGTH_SHORT).show()
    }

    private fun menu() {
        val items = arrayOf("YouTube home", "YouTube Music", "YouTube Plus settings…", "Open uBlock Origin dashboard",
            "SponsorBlock settings", "Clear YouTube login & data", "Report a problem (share logs)", "Check for updates")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> session.loadUri("https://m.youtube.com/")
                1 -> session.loadUri("https://music.youtube.com/")
                2 -> plusSettings()
                3 -> Gecko.ublock?.metaData?.optionsPageUrl?.let { session.loadUri(it) }
                    ?: Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
                4 -> Gecko.sponsor?.metaData?.optionsPageUrl?.let { session.loadUri(it) }
                    ?: Toast.makeText(this, "SponsorBlock is still installing — try again in a moment", Toast.LENGTH_LONG).show()
                5 -> AlertDialog.Builder(this)
                    .setMessage("Sign out of YouTube and delete its cookies and site data in BlockBridge?")
                    .setPositiveButton("Clear") { _, _ ->
                        closeMini("signed out")
                        Gecko.runtime(this).storageController.clearData(
                            org.mozilla.geckoview.StorageController.ClearFlags.ALL)
                        session.loadUri("https://m.youtube.com/")
                    }
                    .setNegativeButton("Cancel", null).show()
                6 -> CrashGuard.shareFullReport(this, "BlockBridge YouTube mode report", summary(), setOf("youtube", "yt-page"))
                7 -> checkUpdates()
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
        bar.visibility = if (full) View.GONE else View.VISIBLE
        if (full) miniCard.visibility = View.GONE else if (mini != null) miniCard.visibility = View.VISIBLE
        window.insetsController?.let { c ->
            if (full) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        }
        requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updateBarButtons()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullScreen -> session.exitFullScreen()
            // Like the YouTube app: Back on a playing video shrinks it to the mini player.
            canMinimize() && YtMedia.playing && mediaOwner === session -> minimize("back")
            page(session).canGoBack -> session.goBack()
            // Mini player still playing: go to the home screen and keep playing in the background.
            mini != null && YtMedia.playing -> moveTaskToBack(true)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (!Gecko.supported) { super.onDestroy(); return }
        Gecko.onReady = null
        YtMedia.onChange = null
        YtMedia.userControl = null
        YtMedia.clear(applicationContext)
        runCatching { geckoView.releaseSession() }
        runCatching { miniView.releaseSession() }
        pages.keys.toList().forEach { runCatching { it.close() } }
        pages.clear()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
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
