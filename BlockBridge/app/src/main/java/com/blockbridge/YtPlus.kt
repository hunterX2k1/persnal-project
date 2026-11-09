package com.blockbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import org.mozilla.geckoview.MediaSession

/** YouTube Plus settings (YouTube mode). All on by default. */
object YtPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun background(ctx: Context) = p(ctx).getBoolean("yt_background", true)
    fun pip(ctx: Context) = p(ctx).getBoolean("yt_pip", true)
    fun sponsorBlock(ctx: Context) = p(ctx).getBoolean("yt_sponsor", true)
    fun debug(ctx: Context) = p(ctx).getBoolean("yt_debug", false)
    fun set(ctx: Context, key: String, on: Boolean) = p(ctx).edit().putBoolean(key, on).apply()
}

/** What's playing in YouTube mode, shared by the page, the notification and picture-in-picture. */
object YtMedia {
    @Volatile var session: MediaSession? = null
    @Volatile var playing = false
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var artwork: Bitmap? = null
    @Volatile var durationMs = 0L
    @Volatile var positionMs = 0L
    @Volatile var rate = 1f
    @Volatile var positionAt = 0L
    /** Tells the activity to refresh its picture-in-picture buttons. */
    @Volatile var onChange: (() -> Unit)? = null

    fun changed(ctx: Context) {
        onChange?.invoke()
        YtMediaService.instance?.let { it.refresh(); return }
        if (session == null) return
        // Starting a foreground service from the background can be refused (Android 12+); the
        // notification is then shown the next time you open YouTube mode.
        DiagLog.add("youtube", "showing playback notification")
        runCatching { ctx.startForegroundService(Intent(ctx, YtMediaService::class.java)) }
            .onFailure { DiagLog.add("youtube", "media notification not started: ${it.message}") }
    }

    fun clear(ctx: Context) {
        session = null; playing = false; title = ""; artist = ""; artwork = null
        durationMs = 0; positionMs = 0
        changed(ctx)
    }

    fun toggle() { val s = session ?: return; if (playing) s.pause() else s.play() }
}

/**
 * Background playback: keeps YouTube mode alive while audio plays with the screen off or in
 * other apps, and shows play/pause/next controls in the notification shade and lock screen.
 */
class YtMediaService : Service() {
    companion object {
        const val CHANNEL = "yt_media"
        const val ID = 4242
        const val TOGGLE = "com.blockbridge.yt.TOGGLE"
        const val NEXT = "com.blockbridge.yt.NEXT"
        const val PREV = "com.blockbridge.yt.PREV"
        const val CLOSE = "com.blockbridge.yt.CLOSE"
        @Volatile var instance: YtMediaService? = null
            private set

        fun action(ctx: Context, action: String, req: Int): PendingIntent = PendingIntent.getService(
            ctx, req, Intent(ctx, YtMediaService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private lateinit var media: android.media.session.MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "YouTube playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Play/pause controls while YouTube mode plays in the background"
                setShowBadge(false)
            })
        media = android.media.session.MediaSession(this, "BlockBridge YouTube").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { YtMedia.session?.play() }
                override fun onPause() { YtMedia.session?.pause() }
                override fun onSkipToNext() { YtMedia.session?.nextTrack() }
                override fun onSkipToPrevious() { YtMedia.session?.previousTrack() }
                override fun onFastForward() { YtMedia.session?.seekForward() }
                override fun onRewind() { YtMedia.session?.seekBackward() }
                override fun onSeekTo(pos: Long) { YtMedia.session?.seekTo(pos / 1000.0, false) }
                override fun onStop() { YtMedia.session?.pause(); stopSelf() }
            })
            setSessionActivity(PendingIntent.getActivity(this@YtMediaService, 0,
                Intent(this@YtMediaService, YouTubeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> YtMedia.toggle()
            NEXT -> YtMedia.session?.nextTrack()
            PREV -> YtMedia.session?.previousTrack()
            CLOSE -> YtMedia.session?.pause()
        }
        // Always go foreground first (Android kills the app if a started foreground service doesn't).
        val n = render()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        if (intent?.action == CLOSE || YtMedia.session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Redraws the notification, or goes away when nothing is playing any more. */
    fun refresh() {
        if (YtMedia.session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else getSystemService(NotificationManager::class.java).notify(ID, render())
    }

    private fun render(): Notification {
        val m = YtMedia
        media.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, m.title.ifBlank { "YouTube" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, m.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, m.durationMs)
            .apply { m.artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
            .build())
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(if (m.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                m.positionMs, if (m.playing) m.rate else 0f, m.positionAt)
            .build())

        fun act(icon: Int, label: String, action: String, req: Int) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label,
                action(this, action, req)).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(m.title.ifBlank { "YouTube" })
            .setContentText(m.artist.ifBlank { "BlockBridge · no ads" })
            .apply { m.artwork?.let { setLargeIcon(it) } }
            .setContentIntent(media.controller.sessionActivity)
            .setDeleteIntent(action(this, CLOSE, 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(m.playing)
            .setShowWhen(false)
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", PREV, 1))
            .addAction(if (m.playing) act(android.R.drawable.ic_media_pause, "Pause", TOGGLE, 2)
                else act(android.R.drawable.ic_media_play, "Play", TOGGLE, 2))
            .addAction(act(android.R.drawable.ic_media_next, "Next", NEXT, 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Close", CLOSE, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        media.isActive = false
        media.release()
        super.onDestroy()
    }
}
