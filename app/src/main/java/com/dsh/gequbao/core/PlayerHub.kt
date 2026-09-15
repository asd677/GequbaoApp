package com.dsh.gequbao.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.dsh.gequbao.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 播放中枢。声音有两个可能的出处，由 [Source] 标明：
 *
 *  - **[Source.WEB]**：网页里的 `<audio>` 在响（用户在歌曲页上，能看到歌词和进度）；
 *  - **[Source.NATIVE]**：用户一边听一边去翻别的页面，页面一换 `<audio>` 就随页面一起没了，
 *    所以这时把直链和进度交接给原生 ExoPlayer（[NativeAudio]），声音接着响，页面随便翻。
 *
 * 交接的触发点是 [onPageStarted]：只要「正在放 + 新页面不是这首歌」就接管；
 * 反过来，网页一旦要出声（注入脚本会先喊 [onWillPlay]），原生立刻让位，
 * 并把进度交给网页，做到「接着听」而不是「从头再来」。
 *
 * 队列本身也在这里：站点不知道我们的收藏和歌单，所以放完一首由这里决定下一首是谁 ——
 * 原生模式直接用站点自己的播放接口解析直链（[GbResolver]），不用把用户的页面拽走。
 */
object PlayerHub {

    private const val TAG = "PlayerHub"

    private lateinit var app: Context
    private val gson = Gson()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 原生播放器的控制口子，由 PlaybackService 装配 */
    interface NativeAudio {
        fun play(url: String, startSec: Int)
        fun resume()
        fun pause()
        fun stop()
        fun seekTo(sec: Int)
        val positionSec: Int
    }

    @Volatile
    var nativeAudio: NativeAudio? = null

    /** 当前声音是谁在放（只在主线程读写） */
    private var source = Source.WEB

    /** 我们自己发起的跳页（切歌）：这种跳页不做交接，旧的直接停 */
    private var suppressHandoff = false

    /** 原生让位给网页时，要把播放进度交给网页，别让用户从头听一遍 */
    private var resumeAt = 0
    private var resumeKey = ""
    private var nativeErrorStreak = 0
    private var handoffAttempts = 0

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

    /** 新页面开始加载 */
    fun onPageStarted(url: String) {
        setWebUrl(url)
        main.post {
            val st = _state.value
            val song = st.song ?: return@post
            val newId = Song.fromUrl(url)

            // 还在同一首歌的页面（刷新、锚点）什么都不动
            if (newId != null && newId == song.id) return@post

            val deliberate = suppressHandoff
            suppressHandoff = false

            // 我们自己要切歌：旧的停掉，接下来的交给新页面去放
            if (deliberate) {
                if (source == Source.NATIVE) {
                    nativeAudio?.stop()
                    setSource(Source.WEB)
                }
                if (st.playing) _state.value = _state.value.copy(playing = false)
                return@post
            }

            // 本来就交给原生在放了：用户继续翻就好，什么都不用做
            if (source == Source.NATIVE) return@post

            // 网页没在放：没有交接的余地
            if (!st.playing) return@post

            // 没拿到直链：接不了
            if (song.audioUrl.isBlank()) {
                _state.value = _state.value.copy(playing = false)
                return@post
            }

            handoffAttempts = 0
            handoff()
        }
    }

    /** 把当前正在放的那首交给原生播放器，从当前进度接着放 */
    private fun handoff() {
        val st = _state.value
        val song = st.song ?: return
        val audio = nativeAudio
        if (audio == null) {
            if (handoffAttempts++ < 4) {
                // 前台服务可能刚被拉起、ExoPlayer 还没就绪，拉一下再试
                PlaybackService.ensureRunning(app)
                main.postDelayed({ if (source == Source.WEB && _state.value.playing) handoff() }, 400)
            } else {
                _state.value = _state.value.copy(playing = false)
            }
            return
        }
        if (song.audioUrl.isBlank()) {
            _state.value = _state.value.copy(playing = false)
            return
        }
        audio.play(song.audioUrl, st.position)
        nativeErrorStreak = 0
        setSource(Source.NATIVE)
        _state.value = _state.value.copy(playing = true)
        toast("已交给后台继续放《${song.title}》，你可以接着翻")
    }

    // ------------------------------------------------------------ 状态刷新

    private fun refresh() {
        _state.value = NowPlaying(
            song = _state.value.song,
            playing = _state.value.playing,
            position = _state.value.position,
            duration = _state.value.duration,
            source = source,
            queueName = queueName.value,
            queueSize = queue.value.size,
            queueIndex = queueIndex.value,
            queueMode = queueMode.value
        )
    }

    private fun setSource(s: Source) {
        source = s
        _state.value = _state.value.copy(source = s)
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
            // 原生正在放的时候，网页那边的「没在放」全是噪音（原页面早随跳转销毁了），
            // 状态一律以原生播放器为准；只有「网页真的出声了」才需要处理
            if (source == Source.NATIVE && !s.playing) return@post
            val prev = _state.value.song
            // 旧页面在跳转过程中还可能补发 emptied/pause 之类的事件，
            // 带着「上一首」的身份和不播状态跑过来；这种恬恬地丢掉，否则迷你条会回跳一下
            val webSongId = Song.fromUrl(currentWebUrl)
            if (!s.playing && s.songId.isNotBlank() && webSongId != null && s.songId != webSongId) return@post
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
                // 网页开始出声了：原生那一份立刻让位（onWillPlay 一般已经先做过，这里兜底）
                if (source == Source.NATIVE) {
                    nativeAudio?.stop()
                    setSource(Source.WEB)
                }
                markHistory(song)
                // 只在「从没播 -> 在播」这一刻拉起前台服务，避免后台反复 startForegroundService
                if (!wasPlaying) PlaybackService.ensureRunning(app)
                // 从原生交回网页时，把进度接上（仅当还是同一首歌）
                val resume = resumeAt
                if (resume > 3 && song.key == resumeKey) {
                    resumeAt = 0
                    resumeKey = ""
                    if (s.position <= 1) run("window.__gb && __gb.seek($resume)")
                } else if (resumeKey.isNotEmpty() && song.key != resumeKey) {
                    resumeAt = 0
                    resumeKey = ""
                }
            }
        }
    }

    /** 打开歌曲详情页时先拿到的元信息（还没点播放） */
    fun onSongMeta(json: String) {
        val m = runCatching { gson.fromJson(json, JsMeta::class.java) }.getOrNull() ?: return
        main.post {
            if (m.title.isBlank()) return@post
            // 原生正在放A、用户只是翻到B的页面看看：迷你条不能改成B
            if (source == Source.NATIVE && _state.value.playing) return@post
            val prev = _state.value.song
            if (prev != null && prev.id == m.id && prev.title == m.title && prev.cover == m.cover) return@post
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

    /**
     * 注入脚本说：网页马上要出声了（用户点了播放，或自动播放要开始）。
     * 原生的那一份立刻让位，并把进度交给网页，做到「接着听」。
     */
    fun onWillPlay() {
        main.post {
            if (source != Source.NATIVE) return@post
            resumeAt = nativeAudio?.positionSec ?: _state.value.position
            // 进度只能交给「同一首歌」：用户点了另一首就必须从头放
            resumeKey = _state.value.song?.key.orEmpty()
            nativeAudio?.stop()
            setSource(Source.WEB)
        }
    }

    fun toggle() {
        if (source == Source.NATIVE) {
            if (_state.value.playing) nativeAudio?.pause() else nativeAudio?.resume()
        } else {
            run("window.__gb && __gb.toggle()")
        }
    }

    fun play() {
        if (source == Source.NATIVE) nativeAudio?.resume() else run("window.__gb && __gb.play()")
    }

    fun pause() {
        if (source == Source.NATIVE) nativeAudio?.pause() else run("window.__gb && __gb.pause()")
    }

    fun seek(sec: Int) {
        if (source == Source.NATIVE) nativeAudio?.seekTo(sec) else run("window.__gb && __gb.seek($sec)")
    }

    fun nextSong() {
        val list = queue.value
        if (list.isEmpty()) {
            toast("还没有播放队列，去「音乐库」点一首吧")
            return
        }
        if (source == Source.NATIVE) {
            playNativeIndex(
                if (queueMode.value == QueueMode.SHUFFLE) Random.nextInt(list.size)
                else queueIndex.value + 1
            )
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
        // 播了 5 秒以上先回到本曲开头，符合大多数音乐 App 的习惯
        if (_state.value.position > 5) {
            seek(0)
            return
        }
        if (source == Source.NATIVE) {
            playNativeIndex(
                if (queueMode.value == QueueMode.SHUFFLE) Random.nextInt(list.size)
                else queueIndex.value - 1
            )
            return
        }
        if (queueMode.value == QueueMode.SHUFFLE) {
            playIndex(Random.nextInt(list.size))
            return
        }
        playIndex(queueIndex.value - 1)
    }

    // ------------------------------------------------------------ 原生播放器的回调

    fun onNativeProgress(playing: Boolean, position: Int, duration: Int) {
        main.post {
            if (source != Source.NATIVE) return@post
            if (playing) nativeErrorStreak = 0
            _state.value = _state.value.copy(
                playing = playing,
                position = position,
                duration = if (duration > 0) duration else _state.value.duration
            )
        }
    }

    fun onNativeEnded() {
        main.post {
            if (source != Source.NATIVE) return@post
            val list = queue.value
            if (list.isEmpty()) {
                _state.value = _state.value.copy(playing = false)
                return@post
            }
            when (queueMode.value) {
                QueueMode.ONE -> {
                    nativeAudio?.seekTo(0)
                    nativeAudio?.resume()
                }
                QueueMode.SHUFFLE -> playNativeIndex(Random.nextInt(list.size))
                QueueMode.SEQ -> playNativeIndex(queueIndex.value + 1)
            }
        }
    }

    fun onNativeError(msg: String) {
        main.post {
            if (source != Source.NATIVE) return@post
            val list = queue.value
            if (nativeErrorStreak++ >= 2 || list.size <= 1) {
                toast("播放失败：$msg")
                _state.value = _state.value.copy(playing = false)
                return@post
            }
            // 直链过期之类：直接试下一首，别卡死在这里
            playNativeIndex(queueIndex.value + 1)
        }
    }

    /** 原生模式下切歌：自己解析直链，不去动用户的页面 */
    private fun playNativeIndex(index: Int) {
        val list = queue.value
        if (list.isEmpty()) return
        val idx = ((index % list.size) + list.size) % list.size
        queueIndex.value = idx
        refresh()
        val song = list[idx]
        if (nativeAudio == null) {
            toast("原生播放器还没就绪")
            return
        }
        if (song.id.isBlank()) {
            toast("《${song.title}》没有歌曲 id，解析不了")
            return
        }
        toast("正在解析《${song.title}》…")
        scope.launch {
            GbResolver.resolve(song.id)
                .onSuccess { res ->
                    val playable = song.copy(
                        title = res.title.ifBlank { song.title },
                        artist = res.artist.ifBlank { song.artist },
                        cover = res.cover.ifBlank { song.cover },
                        duration = if (res.duration > 0) res.duration else song.duration,
                        audioUrl = res.url,
                        updatedAt = System.currentTimeMillis()
                    )
                    // 回写队列：下次再轮到它就不用再解析一次（站点对免费用户有解析次数限制）
                    queue.value = queue.value.map { if (it.key == playable.key) playable else it }
                    _state.value = _state.value.copy(
                        song = playable,
                        playing = true,
                        position = 0,
                        duration = playable.duration
                    )
                    nativeErrorStreak = 0
                    setSource(Source.NATIVE)
                    markHistory(playable)
                    PlaybackService.ensureRunning(app)
                    nativeAudio?.play(playable.audioUrl, 0)
                }
                .onFailure { e ->
                    toast("解析失败：${e.message}")
                    _state.value = _state.value.copy(playing = false)
                }
        }
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
        // 主动切歌：原生那一份先让位，别和马上要开嗓的网页一起响
        if (source == Source.NATIVE) {
            nativeAudio?.stop()
            setSource(Source.WEB)
        }
        suppressHandoff = true
        if (forcePlay) pendingAutoplay.set(true)
        navigate(song.page())
    }

    /** 歌曲页加载完成后，若队列正指向这首歌则自动开播（配合 consumeAutoplay 使用，此处仅兜底刷新 UI） */
    fun onPageLoaded(url: String) {
        setWebUrl(url)
        main.post {
            suppressHandoff = false   // 兜底：万一这次跳转没走 onPageStarted，别把标记漏到下一次
            refresh()
        }
    }

    fun toggleFavoriteCurrent(): Boolean {
        val song = _state.value.song ?: return false
        val fav = LocalStore.get(app).toggleFavorite(song)
        toast(if (fav) "已收藏《${song.title}》" else "已取消收藏")
        return fav
    }

    fun currentSong(): Song? = _state.value.song

    /** 用站点自己的接口现解析一个直链（下载、原生切歌都用得上），结果回主线程 */
    fun resolveUrl(song: Song, onResult: (Result<GbResolver.Resolved>) -> Unit) {
        if (song.id.isBlank()) {
            main.post { onResult(Result.failure(GbResolver.ResolveException("这首歌没有 id，解析不了"))) }
            return
        }
        scope.launch { onResult(GbResolver.resolve(song.id)) }
    }

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
