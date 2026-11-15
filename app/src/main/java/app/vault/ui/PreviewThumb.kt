package app.vault.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.vault.data.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One muted player shared by every thumbnail; only one preview plays at a time. */
val LocalPreviewPlayer = staticCompositionLocalOf<ExoPlayer?> { null }

object PreviewState {
    var activeId by mutableStateOf<String?>(null)
    /** True once the active preview has shown its first frame. */
    var rendered by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    /** Seek to apply as soon as the video's length is known. */
    var pendingFraction = -1f
}

/**
 * Thumbnail with a video preview:
 *  - the cover always shows; the video fades in over it once its first frame is ready,
 *  - swipe sideways to scrub, press and hold to play, tap to open,
 *  - vertical swipes still scroll the list.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PreviewThumb(id: String, images: List<String?>, preview: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val player = LocalPreviewPlayer.current
    val active = PreviewState.activeId == id && preview != null && player != null
    var fraction by remember { mutableFloatStateOf(-1f) }
    var width by remember { mutableIntStateOf(1) }
    val slop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()

    // Warm the first part of the preview while the card is on screen (Wi-Fi only).
    LaunchedEffect(preview) { delay(400); PreviewEngine.prefetch(ctx, preview) }

    fun load() {
        if (preview == null || player == null || PreviewState.activeId == id) return
        PreviewState.activeId = id
        PreviewState.rendered = false
        PreviewState.pendingFraction = -1f
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(MediaItem.fromUri(preview))
        player.prepare()
    }
    fun play() { load(); player?.play() }
    fun seek(f: Float) {
        val p = player ?: return
        if (p.duration != C.TIME_UNSET && p.duration > 0) p.seekTo((p.duration * f).toLong()) else PreviewState.pendingFraction = f
    }

    Box(
        modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }.pointerInput(id, preview) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                load() // start fetching the moment the finger lands
                var scrubbing = false
                var held = false
                var cancelled = false
                val holdJob: Job = scope.launch { delay(300); held = true; play() }
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val dx = c.position.x - down.position.x
                    val dy = c.position.y - down.position.y
                    if (!scrubbing && !held && abs(dy) > slop && abs(dy) > abs(dx)) { cancelled = true; break } // let the list scroll
                    if (!scrubbing && abs(dx) > slop && abs(dx) > abs(dy) && preview != null) { scrubbing = true; holdJob.cancel(); play() }
                    if (scrubbing) {
                        fraction = (c.position.x / width).coerceIn(0f, 1f)
                        seek(fraction)
                        c.consume()
                    }
                }
                holdJob.cancel()
                if (!scrubbing && !held && !cancelled) onClick()
                if (scrubbing || held) {
                    // keep playing briefly from where the finger stopped, then back to the cover
                    scope.launch { delay(3000); if (PreviewState.activeId == id) { player?.pause(); PreviewState.activeId = null } }
                } else if (PreviewState.activeId == id) {
                    player?.pause(); PreviewState.activeId = null
                }
                fraction = -1f
            }
        },
    ) {
        VImage(images, Modifier.fillMaxSize(), forceReveal = active)
        if (active) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) // keep the cover visible until the first frame
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (!PreviewState.rendered || PreviewState.buffering) CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(26.dp), strokeWidth = 2.dp, color = Color.White,
            )
            if (fraction >= 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp).background(Accent))
        } else if (preview != null) {
            Icon(Icons.Default.SwipeRight, "Swipe to preview", tint = Color(0xCCFFFFFF),
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp))
        }
    }
}

/** Keeps [PreviewState] in sync with the shared player (first frame, buffering, pending seek). */
fun ExoPlayer.trackPreviewState(): ExoPlayer = apply {
    addListener(object : Player.Listener {
        override fun onRenderedFirstFrame() { PreviewState.rendered = true }
        override fun onPlaybackStateChanged(state: Int) {
            PreviewState.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY && PreviewState.pendingFraction >= 0f && duration > 0) {
                seekTo((duration * PreviewState.pendingFraction).toLong()); PreviewState.pendingFraction = -1f
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.w("Preview", "Preview failed: ${error.errorCodeName}")
            PreviewState.activeId = null
        }
    })
}
