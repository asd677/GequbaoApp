package com.dsh.gequbao.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.dsh.gequbao.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 播放中枢：网页里的 <audio> 是唯一播放器，这里只做三件事
 *  1. 把网页上报的状态（歌名/歌手/进度/是否在播）翻译成 [NowPlaying] 给 UI 和通知栏；
 *  2. 反向把「播放/暂停/上一首/下一首/跳转」翻译成注入脚本能懂的 JS；
 *  3. 维护原生播放队列（收藏 / 歌单顺序连播就是这么实现的）。
 *
 * 为什么队列要放原生：站点自己不知道我们的收藏和歌单。
 * 一首放完后由网页上报 ended，这里决定下一首是谁、然后让 WebView 跳到那首歌的详情页，
 * 并让注入脚本自动点播放 —— 于是「连续播放自己的歌单」就成立了。
 */
object PlayerHub {

    private const val TAG = "PlayerHub"

    private lateinit var app: Context
    private val gson = Gson()
    private val main = Handler(Looper.getMainLooper())

    /** WebView 执行 JS 的入口，由 MainActivity 装配 */
    @Volatile
    private var jsRunner: ((String) -> Unit)? = null

    /** 让 WebView 跳页的入口 */
    @Volatile
    private var navigator: ((String) -> Unit)? = null

    /** 当前 WebView 地址，用来判断「是不是已经在这首歌的页面上了」 */
    @Volatile
    var currentWebUrl: String = ""
        private set

    private val pendingAutoplay = AtomicBoolean(false)
    private var lastHistoryKey = ""
    private var lastHistoryAt = 0L

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val queue = MutableStateFlow<List<Song>>(emptyList())
    private val queueIndex = MutableStateFlow(-1)
    private val queueMode = MutableStateFlow(QueueMode.SEQ)
    private val queueName = MutableStateFlow("")

    val queueList: StateFlow<List<Song>> = queue.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun attach(runner: (String) -> Unit, nav: (String) -> Unit) {
        jsRunner = runner
        navigator = nav
    }

    fun detach() {
        jsRunner = null
        navigator = null
    }

    fun setWebUrl(url: String) {
        currentWebUrl = url
    }

    // ------------------------------------------------------------ 状态刷新

    private fun refresh() {
        _state.value = NowPlaying(
            song = _state.value.song,
            playing = _state.value.playing,
            position = _state.value.position,
            duration = _state.value.duration,
            queueName = queueName.value,
            queueSize = queue.value.size,
            queueIndex = queueIndex.value,
            queueMode = queueMode.value
        )
    }

    private fun run(js: String) {
        val r = jsRunner
        if (r == null) Log.w(TAG, "jsRunner 未装配，丢弃: $js") else main.post { r(js) }
    }

    private fun navigate(url: String) {
        val n = navigator
        if (n == null) Log.w(TAG, "navigator 未装配，丢弃: $url") else main.post { n(url) }
    }

    // ------------------------------------------------------------ 网页 -> 原生

    /** 注入脚本按固定频率上报的播放状态（可能是 JS 线程调用） */
    fun onJsState(json: String) {
        val s = runCatching { gson.fromJson(json, JsState::class.java) }.getOrNull() ?: return
        main.post {
            val prev = _state.value.song
            // 首页/榜单/搜索页也会上报，那些页面拿不到歌曲身份（没有 songId 也没有音频地址），
            // 这时只能当「播放状态」看，绝不能把 document.title 当成新歌写进去
            val identified = s.songId.isNotBlank() || s.url.isNotBlank()
            if (!identified) {
                if (prev != null) _state.value = _state.value.copy(playing = s.playing)
                return@post
            }
            if (s.title.isBlank()) return@post

            val wasPlaying = _state.value.playing
            val song = Song(
                id = s.songId.ifBlank { prev?.id.orEmpty() },
                title = s.title,
                artist = s.artist,
                cover = s.cover.ifBlank { prev?.cover.orEmpty() },
                duration = if (s.duration > 0) s.duration else prev?.duration ?: 0,
                pageUrl = s.pageUrl,
                audioUrl = s.url.ifBlank { prev?.audioUrl.orEmpty() },
                updatedAt = System.currentTimeMillis()
            )
            _state.value = _state.value.copy(
                song = song,
                playing = s.playing,
                position = s.position,
                duration = if (s.duration > 0) s.duration else _state.value.duration
            )
            if (s.playing) {
                markHistory(song)
                // 只在「从没播 -> 在播」这一刻拉起前台服务，避免后台反复 startForegroundService
                if (!wasPlaying) PlaybackService.ensureRunning(app)
            }
        }
    }

    /** 打开歌曲详情页时先拿到的元信息（还没点播放） */
    fun onSongMeta(json: String) {
        val m = runCatching { gson.fromJson(json, JsMeta::class.java) }.getOrNull() ?: return
        main.post {
            if (m.title.isBlank()) return@post
            val prev = _state.value.song
            if (prev?.id == m.id && prev.title == m.title && prev.cover.isNotBlank()) return@post
            _state.value = _state.value.copy(
                song = Song(
                    id = m.id,
                    title = m.title,
                    artist = m.artist,
                    cover = m.cover,
                    duration = parseDuration(m.duration),
                    pageUrl = m.url,
                    audioUrl = "",
                    updatedAt = System.currentTimeMillis()
                ),
                position = 0,
                duration = parseDuration(m.duration)
            )
        }
    }

    private fun markHistory(song: Song) {
        if (song.id.isBlank() || song.title.isBlank()) return
        val now = System.currentTimeMillis()
        if (song.key == lastHistoryKey && now - lastHistoryAt < 60_000) return
        lastHistoryKey = song.key
        lastHistoryAt = now
        LocalStore.get(app).addHistory(song)
    }

    /** 注入脚本问：这次跳页要自动播吗？（JS 线程同步调用，必须无副作用） */
    fun consumeAutoplay(): Boolean {
        val forced = pendingAutoplay.getAndSet(false)
        val auto = runCatching { LocalStore.get(app).settings.value.autoPlayOnSong }.getOrDefault(false)
        return forced || auto
    }

    /** 网页里的 <audio> 播完了 */
    fun onEnded() {
        main.post {
            val list = queue.value
            if (list.isEmpty()) {
                _state.value = _state.value.copy(playing = false)
                return@post
            }
            when (queueMode.value) {
                QueueMode.ONE -> run("window.__gb && __gb.replay()")
                QueueMode.SHUFFLE -> playIndex(Random.nextInt(list.size))
                QueueMode.SEQ -> playIndex(queueIndex.value + 1)
            }
        }
    }

    fun toast(msg: String) {
        if (msg.isBlank()) return
        main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
    }

    // ------------------------------------------------------------ 原生 -> 网页

    fun toggle() = run("window.__gb && __gb.toggle()")
    fun play() = run("window.__gb && __gb.play()")
    fun pause() = run("window.__gb && __gb.pause()")
    fun seek(sec: Int) = run("window.__gb && __gb.seek($sec)")

    fun nextSong() {
        val list = queue.value
        if (list.isEmpty()) {
            toast("还没有播放队列，去「音乐库」点一首吧")
            return
        }
        when (queueMode.value) {
            QueueMode.SHUFFLE -> playIndex(Random.nextInt(list.size))
            else -> playIndex(queueIndex.value + 1)
        }
    }

    fun prevSong() {
        val list = queue.value
        if (list.isEmpty()) {
            toast("还没有播放队列")
            return
        }
        if (queueMode.value == QueueMode.SHUFFLE) {
            playIndex(Random.nextInt(list.size))
            return
        }
        // 播了 5 秒以上先回到本曲开头，符合大多数音乐 App 的习惯
        if (_state.value.position > 5) {
            seek(0)
            return
        }
        playIndex(queueIndex.value - 1)
    }

    fun cycleQueueMode(): QueueMode {
        val next = when (queueMode.value) {
            QueueMode.SEQ -> QueueMode.ONE
            QueueMode.ONE -> QueueMode.SHUFFLE
            QueueMode.SHUFFLE -> QueueMode.SEQ
        }
        queueMode.value = next
        refresh()
        return next
    }

    // ------------------------------------------------------------ 队列

    /** 播单曲：清空队列，只放这一首 */
    fun playSingle(song: Song) {
        queue.value = listOf(song)
        queueIndex.value = 0
        queueName.value = ""
        refresh()
        goTo(song, forcePlay = true)
    }

    /** 从列表某一首开始连播 */
    fun playAll(songs: List<Song>, startIndex: Int = 0, name: String = "") {
        if (songs.isEmpty()) return
        val idx = startIndex.coerceIn(0, songs.size - 1)
        queue.value = songs
        queueIndex.value = idx
        queueName.value = name
        refresh()
        goTo(songs[idx], forcePlay = true)
    }

    fun playQueueAt(index: Int) {
        val list = queue.value
        if (index !in list.indices) return
        queueIndex.value = index
        refresh()
        goTo(list[index], forcePlay = true)
    }

    private fun playIndex(i: Int) {
        val list = queue.value
        if (list.isEmpty()) return
        val idx = ((i % list.size) + list.size) % list.size
        queueIndex.value = idx
        refresh()
        goTo(list[idx], forcePlay = true)
    }

    private fun goTo(song: Song, forcePlay: Boolean) {
        val onPage = Song.fromUrl(currentWebUrl)
        if (onPage != null && song.id.isNotBlank() && onPage == song.id) {
            // 已经站在这一页，直接播
            if (forcePlay) run("window.__gb && __gb.play()")
            return
        }
        if (forcePlay) pendingAutoplay.set(true)
        navigate(song.page())
    }

    /** 歌曲页加载完成后，若队列正指向这首歌则自动开播（配合 consumeAutoplay 使用，此处仅兜底刷新 UI） */
    fun onPageLoaded(url: String) {
        setWebUrl(url)
        main.post { refresh() }
    }

    fun toggleFavoriteCurrent(): Boolean {
        val song = _state.value.song ?: return false
        val fav = LocalStore.get(app).toggleFavorite(song)
        toast(if (fav) "已收藏《${song.title}》" else "已取消收藏")
        return fav
    }

    fun currentSong(): Song? = _state.value.song

    fun clearQueue() {
        queue.value = emptyList()
        queueIndex.value = -1
        queueName.value = ""
        refresh()
        toast("已清空播放队列")
    }

    // ------------------------------------------------------------ JSON 载体

    internal data class JsState(
        val title: String = "",
        val artist: String = "",
        val cover: String = "",
        val url: String = "",
        val songId: String = "",
        val pageUrl: String = "",
        val position: Int = 0,
        val duration: Int = 0,
        val playing: Boolean = false
    )

    internal data class JsMeta(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val cover: String = "",
        val duration: String = "",
        val url: String = ""
    )

    private fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(":")
        return when (parts.size) {
            2 -> parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: 0
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> text.toIntOrNull() ?: 0
        }
    }
}
