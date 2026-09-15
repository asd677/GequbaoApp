package com.dsh.gequbao.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.dsh.gequbao.MainActivity
import com.dsh.gequbao.R
import com.dsh.gequbao.core.NowPlaying
import com.dsh.gequbao.core.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务 + 通知栏媒体控制。
 *
 * 播放器本体是网页里的 <audio>，这个服务不碰音频，它负责：
 *  - 让进程在后台不被杀（WebView 里的音乐才能继续响）；
 *  - 把系统的播放/暂停/上一首/下一首按钮转成对网页的 JS 调用；
 *  - 在通知栏显示当前歌名、歌手、封面。
 */
class PlaybackService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL = "gb_playing"
        const val ACTION_PLAY = "com.dsh.gequbao.PLAY"
        const val ACTION_PAUSE = "com.dsh.gequbao.PAUSE"
        const val ACTION_TOGGLE = "com.dsh.gequbao.TOGGLE"
        const val ACTION_NEXT = "com.dsh.gequbao.NEXT"
        const val ACTION_PREV = "com.dsh.gequbao.PREV"

        fun ensureRunning(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
            }
        }

        fun shutdown(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var native: NativePlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastSongKey: String? = null
    private var lastPlaying: Boolean? = null

    private var coverKey: String = ""
    private var cover: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // 原生播放器：网页被用户翻走之后，接着响的就是它
        native = NativePlayer(this).apply {
            onProgress = { playing, pos, dur -> PlayerHub.onNativeProgress(playing, pos, dur) }
            onEnded = { PlayerHub.onNativeEnded() }
            onError = { msg -> PlayerHub.onNativeError(msg) }
        }
        PlayerHub.nativeAudio = object : PlayerHub.NativeAudio {
            override fun play(url: String, startSec: Int) = native.play(url, startSec)
            override fun resume() = native.resume()
            override fun pause() = native.pause()
            override fun stop() = native.stop()
            override fun seekTo(sec: Int) = native.seekTo(sec)
            override val positionSec: Int get() = native.positionSec
        }

        session = MediaSessionCompat(this, "gequbao").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = PlayerHub.play()
                override fun onPause() = PlayerHub.pause()
                override fun onSkipToNext() = PlayerHub.nextSong()
                override fun onSkipToPrevious() = PlayerHub.prevSong()
                override fun onSeekTo(pos: Long) = PlayerHub.seek((pos / 1000).toInt())
                override fun onStop() {
                    PlayerHub.pause()
                    stopSelf()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }

        val initial = PlayerHub.state.value
        startForegroundInternal(buildNotification(initial))

        scope.launch {
            PlayerHub.state.collect { st ->
                updateSession(st)
                val songChanged = st.song?.key != lastSongKey
                if (songChanged || st.playing != lastPlaying) {
                    lastSongKey = st.song?.key
                    lastPlaying = st.playing
                    if (songChanged) loadCover(st)
                    notify(buildNotification(st))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            ACTION_PLAY -> PlayerHub.play()
            ACTION_PAUSE -> PlayerHub.pause()
            ACTION_TOGGLE -> PlayerHub.toggle()
            ACTION_NEXT -> PlayerHub.nextSong()
            ACTION_PREV -> PlayerHub.prevSong()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务：还在放就继续放，没在放就收摊
        if (!PlayerHub.state.value.playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        PlayerHub.nativeAudio = null
        runCatching { native.release() }
        runCatching { session.isActive = false; session.release() }
        stopForegroundCompat()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 内部

    private fun startForegroundInternal(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
    }

    private fun notify(n: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(
            CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(ch)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun updateSession(st: NowPlaying) {
        val song = st.song
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song?.title ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song?.artist ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, st.duration * 1000L)
                .build()
        )
        val state = if (st.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, st.position * 1000L, if (st.playing) 1f else 0f)
                .build()
        )
    }

    private fun buildNotification(st: NowPlaying): Notification {
        val song = st.song
        val playPause = if (st.playing) {
            NotificationCompat.Action(
                R.drawable.ic_pause, "暂停",
                actionIntent(ACTION_PAUSE, 2)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play, "播放",
                actionIntent(ACTION_PLAY, 3)
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song?.title?.ifBlank { "歌曲宝" } ?: "歌曲宝")
            .setContentText(song?.artist.orEmpty())
            .setContentIntent(openAppIntent())
            .setDeleteIntent(actionIntent(ACTION_PAUSE, 4))
            .addAction(NotificationCompat.Action(R.drawable.ic_prev, "上一首", actionIntent(ACTION_PREV, 5)))
            .addAction(playPause)
            .addAction(NotificationCompat.Action(R.drawable.ic_next, "下一首", actionIntent(ACTION_NEXT, 6)))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(st.playing)

        cover?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun loadCover(st: NowPlaying) {
        val url = st.song?.cover.orEmpty()
        if (url.isBlank() || url == coverKey) return
        coverKey = url
        cover = null
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { downloadBitmap(url) }
            if (bmp != null && coverKey == url) {
                cover = bmp
                notify(buildNotification(PlayerHub.state.value))
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://www.gequbao.com/")
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
