package com.blockbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Firefox's engine (GeckoView) for the whole app, with the real uBlock Origin installed from
 * addons.mozilla.org (Mozilla-signed) and kept updated. Used by YouTube mode.
 */
object Gecko {
    private const val UBO_ID = "uBlock0@raymondhill.net"
    private const val UBO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile var ublock: WebExtension? = null
        private set
    @Volatile var status = "Starting…"
        private set

    fun runtime(ctx: Context): GeckoRuntime = runtime ?: synchronized(this) {
        runtime ?: GeckoRuntime.create(ctx.applicationContext, GeckoRuntimeSettings.Builder()
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            .build()).also { rt ->
            runtime = rt
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
            ensureUBlock(ctx.applicationContext, rt)
        }
    }

    /** Installs uBlock Origin on first use, then checks for updates about once a day. */
    private fun ensureUBlock(ctx: Context, rt: GeckoRuntime) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        rt.webExtensionController.list().accept({ list ->
            val existing = list?.firstOrNull { it.id == UBO_ID }
            if (existing != null) {
                ublock = existing
                status = "uBlock Origin ${existing.metaData.version} active"
                if (System.currentTimeMillis() - prefs.getLong("ubo_update", 0) > 86_400_000L) {
                    prefs.edit().putLong("ubo_update", System.currentTimeMillis()).apply()
                    rt.webExtensionController.update(existing).accept({ u -> if (u != null) { ublock = u; status = "uBlock Origin ${u.metaData.version} active" } }, { })
                }
            } else {
                status = "Installing uBlock Origin…"
                DiagLog.add("youtube", "installing uBlock Origin from addons.mozilla.org")
                rt.webExtensionController.install(UBO_URL, null).accept({ ext ->
                    ublock = ext
                    status = "uBlock Origin ${ext?.metaData?.version ?: ""} active"
                    DiagLog.add("youtube", status)
                }, { e ->
                    status = "Couldn't install uBlock Origin: ${e?.message} — check your connection and reopen"
                    DiagLog.add("youtube", status)
                })
            }
        }, { e -> status = "Extension error: ${e?.message}" })
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
                    if (url != null) lastUrl = url
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
            }
            promptDelegate = YouTubePrompts(this@YouTubeActivity)
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
        if (Gecko.ublock == null) Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
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
        val items = arrayOf("YouTube home", "Open uBlock Origin dashboard", "Open in another app", "Clear YouTube login & data")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> session.loadUri("https://m.youtube.com/")
                1 -> Gecko.ublock?.metaData?.optionsPageUrl?.let { session.loadUri(it) }
                    ?: Toast.makeText(this, Gecko.status, Toast.LENGTH_LONG).show()
                2 -> runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl())), "Open with"))
                }
                3 -> AlertDialog.Builder(this)
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
