package com.dsh.gequbao.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.gequbao.core.AppSettings
import com.dsh.gequbao.core.LocalStore
import com.dsh.gequbao.core.SITE
import com.dsh.gequbao.web.WebShell

/** 「我的」：设置、搜索历史、关于。 */
@Composable
fun MineScreen(
    store: LocalStore,
    shell: WebShell,
    onSearch: (String) -> Unit
) {
    val settings by store.settings.collectAsState()
    val searches by store.searches.collectAsState()
    var showClear by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    fun set(block: (AppSettings) -> AppSettings) {
        store.updateSettings(block)
        shell.applySettings()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---------------------------------------------------------- 搜索历史
        SectionTitle("搜索历史")
        if (searches.isEmpty()) {
            Text(
                "还没有搜索记录",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                searches.take(18).forEach { kw ->
                    FilterChip(
                        selected = false,
                        onClick = { onSearch(kw) },
                        label = { Text(kw) },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { store.clearSearches() }) { Text("清空搜索历史") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // ---------------------------------------------------------- 外观
        SectionTitle("外观")
        SwitchRow("夜间模式", "网页跟着一起变深色", settings.dark) { set { s -> s.copy(dark = it) } }
        SwitchRow("隐藏网页顶部导航", "原生底部导航已经够用了", settings.hideHeader) { set { s -> s.copy(hideHeader = it) } }
        SwitchRow("隐藏网页页脚", "去掉底部的备案/友链一堆东西", settings.hideFooter) { set { s -> s.copy(hideFooter = it) } }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("网页缩放")
                Text(
                    "字太小就放大一点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            listOf(0.9f, 1f, 1.1f, 1.25f).forEach { z ->
                FilterChip(
                    selected = kotlin.math.abs(settings.zoom - z) < 0.01f,
                    onClick = { set { s -> s.copy(zoom = z) } },
                    label = { Text("${(z * 100).toInt()}%") },
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // ---------------------------------------------------------- 播放
        SectionTitle("播放")
        SwitchRow("打开歌曲页自动播放", "从列表点进一首歌就直接放", settings.autoPlayOnSong) { set { s -> s.copy(autoPlayOnSong = it) } }
        SwitchRow("保持屏幕常亮", "看着歌词的时候别黑屏", settings.keepScreenOn) { set { s -> s.copy(keepScreenOn = it) } }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // ---------------------------------------------------------- 网页
        SectionTitle("网页")
        SwitchRow("省流模式", "不加载图片，流量紧张时用", settings.dataSaver) { set { s -> s.copy(dataSaver = it) } }
        SwitchRow("桌面版网页", "切到电脑版排版（会重新加载页面）", settings.desktopUa) { set { s -> s.copy(desktopUa = it) } }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showClear = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("清理网页缓存")
                Text(
                    if (cleared) "已清理（登录状态也会一起清掉）" else "清掉 WebView 缓存、Cookie、本地存储",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("清理", color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // ---------------------------------------------------------- 关于
        SectionTitle("关于")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { shell.load(SITE) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("打开歌曲宝官网")
                Text(
                    SITE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Icon(Icons.Filled.Share, contentDescription = null)
        }
        Text(
            buildString {
                append("版本 1.0\n\n")
                append("这个 App 是个 WebView 外壳：歌曲、歌词、音频全部来自 gequbao.com，")
                append("App 本身不提供任何音源、也不解析任何接口。\n\n")
                append("原生补上的部分是：收藏 / 自建歌单 / 播放历史 / 搜索历史 / 系统下载器下载管理 / ")
                append("通知栏与控制中心控制 / 后台播放 / 歌单连续播放 / 夜间模式。\n\n")
                append("所有原生数据都只存在本机（files/store/*.json），不联网、不上传。")
            },
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清理网页缓存？") },
            text = { Text("会清掉网页缓存、Cookie 和本地存储，网页上的登录状态也会一起消失。收藏/歌单/历史不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    shell.clearWebCache { }
                    cleared = true
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SwitchRow(
    title: String,
    sub: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Suppress("unused")
private val unusedIcon: ImageVector? = null
