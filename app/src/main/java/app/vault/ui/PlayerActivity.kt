package app.vault.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.vault.AppLock
import app.vault.app

/**
 * Full-screen native player: double-tap left/right to skip 10s, built-in speed & quality menu,
 * picture-in-picture when you leave the app.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var seekHint: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacyFlags()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        view = PlayerView(this).apply {
            this.player = this@PlayerActivity.player
            setBackgroundColor(Color.BLACK)
            setShowNextButton(false); setShowPreviousButton(false)
            setShowFastForwardButton(true); setShowRewindButton(true)
            keepScreenOn = true
        }
        seekHint = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; alpha = 0f
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        val root = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(seekHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }
        setContentView(root)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (view.isControllerFullyVisible) view.hideController() else view.showController(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val forward = e.x > view.width / 2
                if (forward) player.seekForward() else player.seekBack()
                seekHint.text = if (forward) "» 10s" else "10s «"
                seekHint.animate().alpha(1f).setDuration(80).withEndAction { seekHint.animate().alpha(0f).setStartDelay(400).setDuration(200) }
                return true
            }
        })
        view.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.widget.Toast.makeText(this@PlayerActivity, "Can't play this link: ${error.errorCodeName}", android.widget.Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        view.useController = !isInPip
    }

    override fun onResume() {
        super.onResume()
        if (app.prefs.hasPin && AppLock.locked && !isInPictureInPictureMode) finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() { player.release(); super.onDestroy() }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }
}
