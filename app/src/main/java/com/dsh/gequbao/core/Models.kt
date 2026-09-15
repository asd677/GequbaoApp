package com.dsh.gequbao.core

/** 站点根地址。所有页面/直链都基于它拼。 */
const val SITE = "https://www.gequbao.com"

/** 伪装成手机 Chrome：站点对桌面 UA 会走另一套排版（设置里的桌面版就是切到下面那个）。 */
const val UA_MOBILE =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

const val UA_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/**
 * 一首歌。字段全部来自网页注入脚本抓到的信息（window.appData / <audio>）或本地列表。
 *
 * 注意 [key] 只用于去重，不是字段，Gson 不会序列化它。
 */
data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
    /** 秒，0 表示未知 */
    val duration: Int = 0,
    /** 歌曲详情页，如 https://www.gequbao.com/music/4188 */
    val pageUrl: String = "",
    /** 最近一次解析到的音频直链，可能带签名会过期，只用来做「边听边存」和下载 */
    val audioUrl: String = "",
    val updatedAt: Long = 0L
) {
    val key: String get() = if (id.isNotEmpty()) "id:$id" else "n:$title|$artist"

    val display: String get() = if (artist.isBlank()) title else "$title - $artist"

    fun page(): String = when {
        pageUrl.isNotBlank() -> pageUrl
        id.isNotBlank() -> "$SITE/music/$id"
        else -> SITE
    }

    companion object {
        fun fromUrl(url: String): String? =
            Regex("""/music/(\d+)""").find(url)?.groupValues?.get(1)
    }
}

data class Playlist(
    val id: String = "",
    val name: String = "",
    val songs: List<Song> = emptyList(),
    val createdAt: Long = 0L
)

data class DownloadRec(
    /** DownloadManager 的 id，查询进度用 */
    val dmId: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val fileName: String = "",
    val url: String = "",
    val ts: Long = 0L
) {
    val display: String get() = if (artist.isBlank()) title else "$title - $artist"
}

data class AppSettings(
    val dark: Boolean = false,
    val hideHeader: Boolean = true,
    val hideFooter: Boolean = true,
    val desktopUa: Boolean = false,
    val dataSaver: Boolean = false,
    val zoom: Float = 1f,
    val keepScreenOn: Boolean = false,
    /** 打开歌曲详情页自动开播（默认开，播放器该有的手感） */
    val autoPlayOnSong: Boolean = true
)

enum class QueueMode(val label: String) {
    SEQ("顺序"), ONE("单曲"), SHUFFLE("随机");
}

/** 声音到底是谁在放：网页里的 <audio>，还是交接给原生 ExoPlayer 之后。 */
enum class Source { WEB, NATIVE }

/** 当前播放状态，UI 与通知栏共用。 */
data class NowPlaying(
    val song: Song? = null,
    val playing: Boolean = false,
    val position: Int = 0,
    val duration: Int = 0,
    val source: Source = Source.WEB,
    val queueName: String = "",
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val queueMode: QueueMode = QueueMode.SEQ
)
