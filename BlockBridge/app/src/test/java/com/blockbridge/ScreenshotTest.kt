package com.blockbridge

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the real screens to PNGs (set -Dbb.shots=<dir>). Skipped unless asked for,
 * so normal test runs stay fast.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
class ScreenshotTest {
    private val out = System.getProperty("bb.shots")?.takeIf { it.isNotEmpty() }?.let { File(it) }

    private fun save(v: View, name: String, w: Int = v.width, h: Int = v.height) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setRunning(on: Boolean) {
        ShieldVpnService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(null, on)
    }

    @Test fun screens() {
        assumeTrue(out != null)
        out!!.mkdirs()
        // demo data
        val c = UboCompiler.Compiler()
        c.addList("easylist", sequenceOf("||doubleclick.net^", "||googlesyndication.com^"))
        c.addList("easyprivacy", sequenceOf("||google-analytics.com^", "||app-measurement.com^"))
        Filter.setLists(c.build())
        for (d in listOf("www.google.com", "securepubads.g.doubleclick.net", "i.ytimg.com", "www.google-analytics.com",
            "pagead2.googlesyndication.com", "github.com", "app-measurement.com")) Stats.record(d, Filter.match(d))
        setRunning(true)
        VpnClient.previewEngines = true

        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctl.get()
        VpnProfiles.add(act, "NL-FREE-12", "[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y\nEndpoint=1.2.3.4:51820")
        val root = act.window.decorView
        repeat(40) { i ->
            Stats.total.addAndGet((3 + (i * 7) % 11).toLong())
            Stats.blocked.addAndGet(((i * 5) % 4).toLong())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        }

        fun shoot(name: String) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2700, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1200, 2700)
            save(root, name)
        }
        fun tab(i: Int) {
            MainActivity::class.java.getDeclaredMethod("showTab", Int::class.java).apply { isAccessible = true }.invoke(act, i)
        }
        shoot("1-home")
        // whole scrolling home page
        val scroll = MainActivity::class.java.getDeclaredField("content").apply { isAccessible = true }.get(act) as View
        scroll.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        scroll.layout(0, 0, 1200, scroll.measuredHeight)
        save(scroll, "1b-home-full")
        tab(MainActivity.TAB_FILTERS); shoot("2-filters")
        tab(MainActivity.TAB_VPN); shoot("3-vpn")
        tab(MainActivity.TAB_LOGGER); shoot("4-logger")
        tab(MainActivity.TAB_SETTINGS); shoot("5-settings")
        setRunning(false); tab(MainActivity.TAB_HOME); shoot("6-home-off")

        // notification (expanded) as Android would inflate it
        setRunning(true)
        val svc = Robolectric.buildService(ControlService::class.java).create().get()
        val n = ControlService::class.java.getDeclaredMethod("build").apply { isAccessible = true }.invoke(svc) as Notification
        val holder = FrameLayout(act)
        val big = n.bigContentView.apply(act, holder)
        holder.addView(big)
        holder.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        holder.layout(0, 0, 1100, holder.measuredHeight)
        save(holder, "7-notification")
        setRunning(false)
    }
}
