package com.dsh.gequbao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.dsh.gequbao.R
import com.dsh.gequbao.core.LocalStore
import com.dsh.gequbao.core.Playlist
import com.dsh.gequbao.core.Song

/** 封面：加载失败/没有地址时退化成一块带音符图标的占位。 */
@Composable
fun Cover(url: String, size: Dp, radius: Dp = 8.dp) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(radius)
    if (url.isBlank()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f)
            )
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
        contentDescription = null,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .size(size)
            .clip(shape)
    )
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song.cover, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artist.ifBlank { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.duration > 0) {
                    Text(
                        "  ·  ${formatTime(song.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        trailing?.invoke()
        if (menu != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu { menuOpen = false }
                }
            }
        }
    }
}

@Composable
fun MenuAction(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
fun EmptyHint(text: String, sub: String = "") {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 「加入歌单」选择器 */
@Composable
fun PlaylistPickerDialog(
    store: LocalStore,
    song: Song,
    onDismiss: () -> Unit
) {
    val playlists by store.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    fun addTo(pl: Playlist) {
        val ok = store.addToPlaylist(pl.id, song)
        com.dsh.gequbao.core.PlayerHub.toast(if (ok) "已加入《${pl.name}》" else "《${pl.name}》里已经有了")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "新建歌单" else "加入歌单") },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("歌单名称") }
                    )
                } else {
                    if (playlists.isEmpty()) {
                        Text("还没有歌单，先建一个吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    playlists.forEach { pl ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { addTo(pl) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pl.name, Modifier.weight(1f))
                            Text(
                                "${pl.songs.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    val pl = store.createPlaylist(name)
                    name = ""
                    creating = false
                    addTo(pl)
                }) { Text("创建并加入") }
            } else {
                TextButton(onClick = { creating = true }) { Text("新建歌单") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (creating) creating = false else onDismiss() }) { Text("关闭") }
        }
    )
}

fun formatTime(sec: Int): String {
    if (sec <= 0) return "00:00"
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}
