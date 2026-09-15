package com.dsh.gequbao.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.gequbao.core.DownloadHelper
import com.dsh.gequbao.core.LocalStore
import com.dsh.gequbao.core.Playlist
import com.dsh.gequbao.core.Song
import kotlinx.coroutines.delay

private val LIB_TABS = listOf("收藏", "歌单", "历史", "下载")

/**
 * 音乐库：完全原生的一页。收藏 / 歌单 / 历史 / 下载都不依赖网页，
 * 网页挂了这些也还在（下载记录只是记录，文件本身在系统下载目录里）。
 */
@Composable
fun LibraryScreen(
    store: LocalStore,
    onPlayAll: (List<Song>, Int, String) -> Unit,
    onOpenInWeb: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    val playlists by store.playlists.collectAsState()

    val opened = playlists.firstOrNull { it.id == openPlaylistId }

    Column(Modifier.fillMaxSize()) {
        if (opened != null) {
            PlaylistDetail(
                playlist = opened,
                onBack = { openPlaylistId = null },
                onPlayAll = onPlayAll,
                onOpenInWeb = onOpenInWeb,
                onDownload = onDownload,
                onShare = onShare,
                onRemove = { store.removeFromPlaylist(opened.id, it) }
            )
            return@Column
        }
        TabRow(selectedTabIndex = tab) {
            LIB_TABS.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title) }
                )
            }
        }

        when (tab) {
            0 -> FavoritesTab(store, onPlayAll, onOpenInWeb, onDownload, onShare, onAddToPlaylist)
            1 -> PlaylistsTab(store, onPlayAll) {
                openPlaylistId = it
            }
            2 -> HistoryTab(store, onPlayAll, onOpenInWeb, onDownload, onShare, onAddToPlaylist)
            3 -> DownloadsTab(store)
        }
    }
}

// ------------------------------------------------------------------ 收藏

@Composable
private fun ColumnScope.FavoritesTab(
    store: LocalStore,
    onPlayAll: (List<Song>, Int, String) -> Unit,
    onOpenInWeb: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    val songs by store.favorites.collectAsState()
    if (songs.isEmpty()) {
        EmptyHint("还没有收藏", "在播放条上点 ♥ 就收藏了")
        return
    }
    ListHeader("${songs.size} 首", onPlayAll = { onPlayAll(songs, 0, "收藏") })
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlayAll(songs, index, "收藏") },
                menu = { dismiss ->
                    MenuAction("立即播放") { dismiss(); onPlayAll(songs, index, "收藏") }
                    MenuAction("打开歌曲页") { dismiss(); onOpenInWeb(song) }
                    MenuAction("加入歌单") { dismiss(); onAddToPlaylist(song) }
                    MenuAction("下载") { dismiss(); onDownload(song) }
                    MenuAction("分享") { dismiss(); onShare(song) }
                    MenuAction("取消收藏") { dismiss(); store.removeFavorite(song) }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ 歌单

@Composable
private fun ColumnScope.PlaylistsTab(
    store: LocalStore,
    onPlayAll: (List<Song>, Int, String) -> Unit,
    onOpen: (String) -> Unit
) {
    val playlists by store.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { creating = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("新建歌单", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        if (playlists.isEmpty()) {
            item { EmptyHint("还没有歌单", "新建一个，把喜欢的歌攒起来") }
        }
        items(playlists, key = { it.id }) { pl ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(pl.id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${pl.songs.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onPlayAll(pl.songs, 0, pl.name) }) { Text("播放") }
                IconButton(onClick = { store.deletePlaylist(pl.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }

    if (creating) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建歌单") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("歌单名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) store.createPlaylist(newName)
                    newName = ""
                    creating = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    onBack: () -> Unit,
    onPlayAll: (List<Song>, Int, String) -> Unit,
    onOpenInWeb: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onRemove: (Song) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { onPlayAll(playlist.songs, 0, playlist.name) }) { Text("播放全部") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        if (playlist.songs.isEmpty()) {
            EmptyHint("歌单是空的", "在歌曲菜单里选「加入歌单」")
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(playlist.songs, key = { _, s -> s.key }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlayAll(playlist.songs, index, playlist.name) },
                    menu = { dismiss ->
                        MenuAction("打开歌曲页") { dismiss(); onOpenInWeb(song) }
                        MenuAction("下载") { dismiss(); onDownload(song) }
                        MenuAction("分享") { dismiss(); onShare(song) }
                        MenuAction("从歌单移除") { dismiss(); onRemove(song) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ 历史

@Composable
private fun ColumnScope.HistoryTab(
    store: LocalStore,
    onPlayAll: (List<Song>, Int, String) -> Unit,
    onOpenInWeb: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    val songs by store.history.collectAsState()
    if (songs.isEmpty()) {
        EmptyHint("还没有播放记录", "听过的歌会自动出现在这里")
        return
    }
    ListHeader("最近 ${songs.size} 首", onPlayAll = { onPlayAll(songs, 0, "播放历史") }, onClear = { store.clearHistory() })
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlayAll(songs, index, "播放历史") },
                menu = { dismiss ->
                    MenuAction("立即播放") { dismiss(); onPlayAll(songs, index, "播放历史") }
                    MenuAction("加入歌单") { dismiss(); onAddToPlaylist(song) }
                    MenuAction("下载") { dismiss(); onDownload(song) }
                    MenuAction("分享") { dismiss(); onShare(song) }
                    MenuAction("从历史中删除") { dismiss(); store.removeHistory(song) }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ 下载

@Composable
private fun ColumnScope.DownloadsTab(store: LocalStore) {
    val ctx = LocalContext.current
    val records by store.downloads.collectAsState()
    var status by remember { mutableStateOf<Map<Long, DownloadHelper.Item>>(emptyMap()) }

    LaunchedEffect(records.map { it.dmId }) {
        while (true) {
            status = DownloadHelper.query(ctx, records.map { it.dmId })
            delay(1200)
        }
    }

    if (records.isEmpty()) {
        EmptyHint("还没有下载", "歌曲菜单里选「下载」，文件存到 Music/歌曲宝/")
        return
    }

    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(records, key = { it.dmId }) { rec ->
            val st = status[rec.dmId]
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            rec.title.ifBlank { rec.fileName },
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            buildString {
                                append(rec.artist.ifBlank { "歌曲宝" })
                                when {
                                    st == null -> append("  ·  查询中…")
                                    st.done -> append("  ·  已完成")
                                    st.failed -> append("  ·  失败(${st.reason})")
                                    else -> append("  ·  ${(st.progress * 100).toInt()}%")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (st?.done == true) {
                        IconButton(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(st.localUri))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            }
                        }) { Icon(Icons.Filled.PlayArrow, contentDescription = "播放") }
                    }
                    IconButton(onClick = {
                        DownloadHelper.remove(ctx, rec.dmId)
                        store.removeDownload(rec.dmId)
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (st != null && !st.done && !st.failed) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ 小工具

@Composable
private fun ListHeader(text: String, onPlayAll: (() -> Unit)? = null, onClear: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                if (onPlayAll != null) {
                    TextButton(onClick = onPlayAll) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("播放全部")
                    }
                }
                if (onClear != null) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
        }
    }
}
