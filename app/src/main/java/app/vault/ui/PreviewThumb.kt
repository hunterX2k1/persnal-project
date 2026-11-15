package app.vault.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One muted player shared by every thumbnail; only one preview plays at a time. */
val LocalPreviewPlayer = staticCompositionLocalOf<ExoPlayer?> { null }

object PreviewState {
    var activeId by mutableStateOf<String?>(null)
}

/**
 * Thumbnail with a video preview:
 *  - swipe sideways across it to scrub through the preview,
 *  - press and hold to play it,
 *  - tap to open.
 * Vertical swipes still scroll the list.
 */
@Composable
fun PreviewThumb(id: String, image: String?, preview: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val player = LocalPreviewPlayer.current
    val active = PreviewState.activeId == id && preview != null && player != null
    var fraction by remember { mutableFloatStateOf(-1f) }
    var width by remember { mutableIntStateOf(1) }
    val slop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()

    fun start() {
        if (preview == null || player == null) return
        if (PreviewState.activeId != id) {
            PreviewState.activeId = id
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.fromUri(preview))
            player.prepare()
        }
        player.play()
    }

    LaunchedEffect(fraction, active) {
        if (active && fraction >= 0f) {
            val d = player!!.duration
            if (d > 0) player.seekTo((d * fraction).toLong())
        }
    }

    Box(
        modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }.pointerInput(id, preview) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var scrubbing = false
                var held = false
                var cancelled = false
                val holdJob: Job = scope.launch { delay(350); held = true; start() }
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val dx = c.position.x - down.position.x
                    val dy = c.position.y - down.position.y
                    if (!scrubbing && !held && abs(dy) > slop && abs(dy) > abs(dx)) { cancelled = true; break } // let the list scroll
                    if (!scrubbing && abs(dx) > slop && abs(dx) > abs(dy) && preview != null) { scrubbing = true; holdJob.cancel(); start() }
                    if (scrubbing) {
                        fraction = (c.position.x / width).coerceIn(0f, 1f)
                        c.consume()
                    }
                }
                holdJob.cancel()
                if (!scrubbing && !held && !cancelled) onClick()
                if (scrubbing || held) {
                    // keep playing briefly from where the finger stopped, then go back to the cover
                    scope.launch { delay(2500); if (PreviewState.activeId == id) { player?.pause(); PreviewState.activeId = null } }
                }
                fraction = -1f
            }
        },
    ) {
        VImage(image, Modifier.fillMaxSize(), forceReveal = active)
        if (active) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (fraction >= 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp).background(Accent))
        } else if (preview != null) {
            Icon(Icons.Default.SwipeRight, "Swipe to preview", tint = Color(0xCCFFFFFF),
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp))
        }
    }
}
