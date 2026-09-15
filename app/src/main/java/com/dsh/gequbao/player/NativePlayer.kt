package com.dsh.gequbao.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dsh.gequbao.core.SITE
import com.dsh.gequbao.core.UA_MOBILE

/**
 * 原生播放器（ExoPlayer）。
 *
 * 它只在一种情况下出场：**用户一边听歌一边去翻别的页面**。
 * 网页里的 <audio> 是页面的子资源，页面一换就没了；这时把正在放的那条直链
 * 连同播放进度交接给这里，声音就能接着响，用户想怎么逛就怎么逛。
 *
 * 两个坑，实测踩过：
 *  1. 直链（酷我 CDN）**不能带 Referer**，带了直接 403，所以这里只设 UA；
 *  2. 音频焦点 / 拔耳机自动暂停交给 ExoPlayer 自己处理（setAudioAttributes 第二参 true）。
 */
class NativePlayer(context: Context) {

    private val context: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** (isPlaying, positionSec, durationSec) */
    var onProgress: ((Boolean, Int, Int) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val player: ExoPlayer = run {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private val ticker = object : Runnable {
        override fun run() {
            emit()
            if (player.isPlaying) handler.postDelayed(this, 500)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                emit()
                if (isPlaying) startTicker() else stopTicker()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        stopTicker()
                        emit()
                        onEnded?.invoke()
                    }
                    Player.STATE_READY -> emit()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                stopTicker()
                onError?.invoke(error.errorCodeName + (error.message?.let { ": $it" } ?: ""))
            }
        })
    }

    val isPlaying: Boolean get() = player.isPlaying

    val positionSec: Int get() = (player.currentPosition / 1000L).toInt().coerceAtLeast(0)

    fun play(url: String, startSec: Int = 0) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA_MOBILE)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)

        // 头按域名给：酷我 CDN 带了 Referer 反而 403（实测），
        // 而站点自己的地址又需要 Referer/Cookie，所以按 host 分开处理
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.endsWith("gequbao.com")) {
            val headers = HashMap<String, String>()
            headers["Referer"] = SITE
            android.webkit.CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
            http.setDefaultRequestProperties(headers)
        }

        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        player.setMediaSource(sources.createMediaSource(MediaItem.fromUri(url)))
        player.prepare()
        if (startSec > 0) player.seekTo(startSec * 1000L)
        player.play()
        emit()
        startTicker()
    }

    fun resume() {
        player.play()
        startTicker()
    }

    fun pause() {
        player.pause()
        stopTicker()
        emit()
    }

    fun stop() {
        stopTicker()
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        emit()
    }

    fun seekTo(sec: Int) {
        player.seekTo(sec.coerceAtLeast(0) * 1000L)
        emit()
    }

    fun release() {
        stopTicker()
        runCatching { player.release() }
    }

    private fun emit() {
        val dur = player.duration
        onProgress?.invoke(
            player.isPlaying,
            positionSec,
            if (dur > 0) (dur / 1000L).toInt() else 0
        )
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 500)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }
}
