package com.dsh.gequbao.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.dsh.gequbao.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.gequbao.MainActivity
import com.dsh.gequbao.core.DownloadHelper
import com.dsh.gequbao.core.LocalStore
import com.dsh.gequbao.core.NowPlaying
import com.dsh.gequbao.core.PlayerHub
import com.dsh.gequbao.core.SITE
import com.dsh.gequbao.core.Song
import com.dsh.gequbao.web.WebShell

/** 底部 4 个 tab：两个是网页，两个是原生。 */
private enum class MainTab(val label: String, val icon: ImageVector, val url: String?) {
    Discover("发现", Icons.Filled.Home, SITE),
    Rank("榜单", Icons.Filled.Star, "$SITE/top/week-search"),
    Library("音乐库", Icons.AutoMirrored.Filled.List, null),
    Mine("我的", Icons.Filled.Person, null);

    val isWeb: Boolean get() = url != null
}

@Composable
fun AppRoot(shell: WebShell, activity: MainActivity) {
    val ctx = LocalContext.current
    val store = remember { LocalStore.get(ctx) }
    val settings by store.settings.collectAsState()
    val now by PlayerHub.state.collectAsState()
    val queue by PlayerHub.queueList.collectAsState()
    val favorites by store.favorites.collectAsState()
    val progress by shell.progress.collectAsState()
    val canGoBack by shell.canGoBack.collectAsState()

    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var lastWebTab by rememberSaveable { mutableStateOf(0) }
    var queueSheet by remember { mutableStateOf(false) }
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    val tab = MainTab.entries[tabIndex]

    // 通知权限（后台播放的通知栏）+ 老系统的存储权限（下载到公共目录）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.POST_NOTIFICATIONS
        if (DownloadHelper.needsLegacyPermission() &&
            ctx.checkSelfPermission(DownloadHelper.permission()) != PackageManager.PERMISSION_GRANTED
        ) want += DownloadHelper.permission()
        if (want.isNotEmpty()) permLauncher.launch(want.toTypedArray())
    }

    LaunchedEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(settings.dark) {
        activity.window.statusBarColor = if (settings.dark) 0xFF101114.toInt() else 0xFFFFFFFF.toInt()
        activity.window.navigationBarColor = if (settings.dark) 0xFF101114.toInt() else 0xFFFFFFFF.toInt()
    }

    // 切换 tab：网页 tab 才动 WebView，避免无谓刷新
    LaunchedEffect(tabIndex) {
        if (tab.isWeb) {
            lastWebTab = tabIndex
            val url = tab.url!!
            if (shell.currentUrl() != url) shell.load(url)
        }
    }

    BackHandler(enabled = true) {
        when {
            !tab.isWeb -> tabIndex = lastWebTab
            canGoBack -> shell.back()
            // 正在放歌时把任务压后台而不是销毁页面，否则会打断播放
            PlayerHub.state.value.playing -> activity.moveTaskToBack(true)
            else -> activity.finish()
        }
    }

    AppTheme(dark = settings.dark) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Scaffold(
                bottomBar = {
                    Column {
                        MiniPlayerBar(
                            now = now,
                            favorite = now.song?.let { s -> favorites.any { it.key == s.key } } == true,
                            onOpen = {
                                now.song?.let { s ->
                                    tabIndex = lastWebTab
                                    shell.load(s.page())
                                }
                            },
                            onToggle = { PlayerHub.toggle() },
                            onPrev = { PlayerHub.prevSong() },
                            onNext = { PlayerHub.nextSong() },
                            onFavorite = { PlayerHub.toggleFavoriteCurrent() },
                            onDownload = {
                                now.song?.let { downloadSong(ctx, shell, it, switchToWeb = { tabIndex = lastWebTab }) }
                            },
                            onQueue = { queueSheet = true },
                            onAddToPlaylist = { now.song?.let { pickerSong = it } },
                            onShare = { now.song?.let { shareSong(ctx, it) } },
                            onSeek = { sec -> PlayerHub.seek(sec) }
                        )
                        NavigationBar {
                            MainTab.entries.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tabIndex == i,
                                    onClick = { tabIndex = i },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                    }
                }
            ) { pad ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                ) {
                    // 常驻的 WebView（切到原生页时只是不可见，页面和播放都不中断）
                    AndroidView(
                        factory = { _ ->
                            // 正常情况下 factory 只跑一次；万一组合被重建，先把 WebView 从旧父容器摘下来，
                            // 否则会直接 "child already has a parent" 崩掉
                            val wv = shell.webView
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv
                        },
                        update = { it.visibility = if (tab.isWeb) View.VISIBLE else View.INVISIBLE },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (!tab.isWeb) {
                        Surface(Modifier.fillMaxSize()) {
                            when (tab) {
                                MainTab.Library -> LibraryScreen(
                                    store = store,
                                    onPlayAll = { list, i, name -> PlayerHub.playAll(list, i, name) },
                                    onOpenInWeb = { song ->
                                        tabIndex = lastWebTab
                                        shell.load(song.page())
                                    },
                                    onDownload = { song -> downloadSong(ctx, shell, song, { tabIndex = lastWebTab }) },
                                    onShare = { shareSong(ctx, it) },
                                    onAddToPlaylist = { pickerSong = it }
                                )

                                MainTab.Mine -> MineScreen(
                                    store = store,
                                    shell = shell,
                                    onSearch = { kw ->
                                        store.addSearch(kw)
                                        tabIndex = lastWebTab
                                        shell.search(kw)
                                    }
                                )

                                else -> Unit
                            }
                        }
                    }

                    if (tab.isWeb && progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }

    if (queueSheet) {
        QueueSheet(
            queue = queue,
            now = now,
            onDismiss = { queueSheet = false },
            onPick = { PlayerHub.playQueueAt(it) },
            onMode = { PlayerHub.cycleQueueMode() },
            onClear = { PlayerHub.clearQueue() }
        )
    }

    pickerSong?.let { song ->
        PlaylistPickerDialog(store = store, song = song, onDismiss = { pickerSong = null })
    }
}

// ------------------------------------------------------------------ 迷你播放条

@Composable
private fun MiniPlayerBar(
    now: NowPlaying,
    favorite: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    onSeek: (Int) -> Unit
) {
    val song = now.song ?: return
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        if (now.duration > 0) {
            var barWidth by remember { mutableStateOf(1) }
            LinearProgressIndicator(
                progress = { (now.position.toFloat() / now.duration).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
                    .pointerInput(now.duration) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / barWidth * now.duration).toInt())
                        }
                    }
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(song.cover, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
            ) {
                Text(
                    song.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(song.artist.ifBlank { "未知歌手" })
                        if (now.queueSize > 0) append("  ·  ${now.queueName.ifBlank { "播放队列" }} ${now.queueIndex + 1}/${now.queueSize}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPrev, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_prev), contentDescription = "上一首", modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(42.dp)) {
                Icon(
                    painter = painterResource(if (now.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (now.playing) "暂停" else "播放",
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_next), contentDescription = "下一首", modifier = Modifier.size(24.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("播放队列（${now.queueSize}）") },
                        onClick = { menu = false; onQueue() }
                    )
                    DropdownMenuItem(
                        text = { Text("下载这首歌") },
                        onClick = { menu = false; onDownload() }
                    )
                    DropdownMenuItem(
                        text = { Text("加入歌单") },
                        onClick = { menu = false; onAddToPlaylist() }
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("回到开头") },
                        onClick = { menu = false; onSeek(0) }
                    )
                    DropdownMenuItem(
                        text = { Text("打开歌曲页") },
                        onClick = { menu = false; onOpen() }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 播放队列

@Composable
private fun QueueSheet(
    queue: List<Song>,
    now: NowPlaying,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onMode: () -> Unit,
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(R.drawable.ic_music_note), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now.queueName.ifBlank { "播放队列" },
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "共 ${queue.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onMode) { Text("模式：${now.queueMode.label}") }
                TextButton(onClick = onClear) { Text("清空") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            if (queue.isEmpty()) {
                EmptyHint("队列是空的", "从收藏或歌单里点一首开始")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(queue, key = { _, s -> s.key }) { i, song ->
                        val current = i == now.queueIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(i) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Cover(song.cover, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    song.title,
                                    color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (current) {
                                Icon(
                                    painter = painterResource(if (now.playing) R.drawable.ic_pause else R.drawable.ic_play),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 通用动作

/** 下载：当前正在播的那首直接拿网页里的直链；其它歌先跳过去，让网页自己解析 */
private fun downloadSong(
    ctx: android.content.Context,
    shell: WebShell,
    song: Song,
    switchToWeb: () -> Unit
) {
    val current = PlayerHub.currentSong()
    if (current != null && current.key == song.key) {
        shell.requestAudioUrls { src, dl ->
            val url = dl.ifBlank { src }.ifBlank { song.audioUrl }
            if (url.isBlank()) {
                PlayerHub.toast("还没拿到下载地址，先在网页里点一下播放")
                return@requestAudioUrls
            }
            val ok = DownloadHelper.enqueueAndRecord(
                context = ctx,
                url = url,
                title = song.title,
                artist = song.artist,
                pageUrl = song.page()
            )
            PlayerHub.toast(if (ok) "开始下载：${song.title}" else "下载启动失败")
        }
    } else {
        PlayerHub.toast("已打开歌曲页，点「下载歌曲」或先在网页里播放一次")
        switchToWeb()
        shell.load(song.page())
    }
}

private fun shareSong(ctx: android.content.Context, song: Song) {
    val text = buildString {
        append(song.title.ifBlank { "歌曲" })
        if (song.artist.isNotBlank()) append(" - ").append(song.artist)
        append("\n").append(song.page())
        append("\n\n分享自「歌曲宝」App")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, "分享歌曲")) }
}
