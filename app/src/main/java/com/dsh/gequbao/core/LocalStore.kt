package com.dsh.gequbao.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 本地资料库：收藏 / 歌单 / 历史 / 下载记录 / 搜索历史 / 设置。
 *
 * 为什么不用 Room：这里存的就是几个小列表，Room 要引注解处理器、编译链更脆；
 * 直接落 JSON 文件足够，好处是文件能被用户肉眼检查、直接备份、手改。
 *
 * 写入策略：内存里改 StateFlow（UI 立刻刷新），后台单线程串行落盘，
 * 先写 .tmp 再 rename，避免写一半被杀进程把文件写坏。
 */
class LocalStore private constructor(context: Context) {

    companion object {
        private const val MAX_HISTORY = 300

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) {
                instance ?: LocalStore(context.applicationContext).also { instance = it }
            }
    }

    private val gson = Gson()
    private val dir = File(context.filesDir, "store").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "gb-store") }

    private val fFav = File(dir, "favorites.json")
    private val fHist = File(dir, "history.json")
    private val fPlaylist = File(dir, "playlists.json")
    private val fDownload = File(dir, "downloads.json")
    private val fSearch = File(dir, "searches.json")
    private val fSettings = File(dir, "settings.json")

    private val _favorites = MutableStateFlow(readList<Song>(fFav))
    val favorites: StateFlow<List<Song>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow(readList<Song>(fHist))
    val history: StateFlow<List<Song>> = _history.asStateFlow()

    private val _playlists = MutableStateFlow(readList<Playlist>(fPlaylist))
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _downloads = MutableStateFlow(readList<DownloadRec>(fDownload))
    val downloads: StateFlow<List<DownloadRec>> = _downloads.asStateFlow()

    private val _searches = MutableStateFlow(readList<String>(fSearch))
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // ---------------------------------------------------------------- 读写

    private inline fun <reified T> readList(file: File): List<T> = runCatching {
        if (!file.exists()) return@runCatching emptyList<T>()
        val type = TypeToken.getParameterized(ArrayList::class.java, T::class.java).type
        val list: List<T>? = gson.fromJson(file.readText(), type)
        list ?: emptyList()
    }.getOrElse { emptyList() }

    private fun readSettings(): AppSettings = runCatching {
        if (!fSettings.exists()) return@runCatching AppSettings()
        gson.fromJson(fSettings.readText(), AppSettings::class.java) ?: AppSettings()
    }.getOrElse { AppSettings() }

    private fun persist(file: File, json: String) {
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun <T> save(file: File, list: List<T>) = persist(file, gson.toJson(list))

    // ---------------------------------------------------------------- 收藏

    fun isFavorite(song: Song): Boolean =
        _favorites.value.any { it.key == song.key }

    /** @return 操作后是否已收藏 */
    fun toggleFavorite(song: Song): Boolean {
        val cur = _favorites.value
        val hit = cur.any { it.key == song.key }
        val next = if (hit) cur.filterNot { it.key == song.key }
        else listOf(song.copy(updatedAt = System.currentTimeMillis())) + cur
        _favorites.value = next
        save(fFav, next)
        return !hit
    }

    fun removeFavorite(song: Song) {
        val next = _favorites.value.filterNot { it.key == song.key }
        _favorites.value = next
        save(fFav, next)
    }

    fun clearFavorites() {
        _favorites.value = emptyList()
        save(fFav, emptyList<Song>())
    }

    // ---------------------------------------------------------------- 历史

    /** 播放开始时调用；同一首只保留最近一次。 */
    fun addHistory(song: Song) {
        if (song.title.isBlank()) return
        val entry = song.copy(updatedAt = System.currentTimeMillis())
        val next = (listOf(entry) + _history.value.filterNot { it.key == song.key }).take(MAX_HISTORY)
        _history.value = next
        save(fHist, next)
    }

    fun removeHistory(song: Song) {
        val next = _history.value.filterNot { it.key == song.key }
        _history.value = next
        save(fHist, next)
    }

    fun clearHistory() {
        _history.value = emptyList()
        save(fHist, emptyList<Song>())
    }

    // ---------------------------------------------------------------- 歌单

    fun createPlaylist(name: String): Playlist {
        val pl = Playlist(UUID.randomUUID().toString(), name.ifBlank { "新歌单" }, emptyList(), System.currentTimeMillis())
        val next = _playlists.value + pl
        _playlists.value = next
        save(fPlaylist, next)
        return pl
    }

    fun renamePlaylist(id: String, name: String) {
        val next = _playlists.value.map { if (it.id == id) it.copy(name = name) else it }
        _playlists.value = next
        save(fPlaylist, next)
    }

    fun deletePlaylist(id: String) {
        val next = _playlists.value.filterNot { it.id == id }
        _playlists.value = next
        save(fPlaylist, next)
    }

    /** @return 是否新增成功（已存在返回 false） */
    fun addToPlaylist(playlistId: String, song: Song): Boolean {
        var added = false
        val next = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl
            else if (pl.songs.any { it.key == song.key }) pl
            else {
                added = true
                pl.copy(songs = pl.songs + song.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        if (added) {
            _playlists.value = next
            save(fPlaylist, next)
        }
        return added
    }

    fun removeFromPlaylist(playlistId: String, song: Song) {
        val next = _playlists.value.map { pl ->
            if (pl.id == playlistId) pl.copy(songs = pl.songs.filterNot { it.key == song.key }) else pl
        }
        _playlists.value = next
        save(fPlaylist, next)
    }

    // ---------------------------------------------------------------- 下载记录

    fun addDownload(rec: DownloadRec) {
        val next = (listOf(rec) + _downloads.value.filterNot { it.dmId == rec.dmId })
        _downloads.value = next
        save(fDownload, next)
    }

    fun removeDownload(dmId: Long) {
        val next = _downloads.value.filterNot { it.dmId == dmId }
        _downloads.value = next
        save(fDownload, next)
    }

    // ---------------------------------------------------------------- 搜索历史

    fun addSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val next = (listOf(kw) + _searches.value.filterNot { it.equals(kw, ignoreCase = true) }).take(30)
        _searches.value = next
        save(fSearch, next)
    }

    fun clearSearches() {
        _searches.value = emptyList()
        save(fSearch, emptyList<String>())
    }

    // ---------------------------------------------------------------- 设置

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(fSettings, gson.toJson(next))
    }
}
