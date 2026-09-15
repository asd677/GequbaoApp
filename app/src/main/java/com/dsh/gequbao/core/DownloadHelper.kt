package com.dsh.gequbao.core

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 下载全部交给系统 DownloadManager：
 * 断点续传、通知栏进度、合入「下载」目录都是它现成的，
 * 自己写线程池下载只会更差。
 *
 * 文件落在 `Music/歌曲宝/` 下，Android 10+ 不需要任何存储权限。
 */
object DownloadHelper {

    const val DIR_NAME = "歌曲宝"

    data class Item(
        val id: Long,
        val status: Int,
        val downloaded: Long,
        val total: Long,
        val localUri: String,
        val reason: Int
    ) {
        val progress: Float
            get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f

        val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
        val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
    }

    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 老系统（API 26~28）写公共目录要权限 */
    fun permission(): String = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun enqueue(
        context: Context,
        url: String,
        title: String,
        artist: String,
        userAgent: String? = null,
        mimeType: String? = null,
        pageUrl: String? = null,
        cookie: String? = null
    ): Long? {
        if (url.isBlank()) return null
        val dm = context.getSystemService(DownloadManager::class.java) ?: return null
        val name = fileName(title, artist, url)

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title.ifBlank { name })
            .setDescription(artist.ifBlank { "歌曲宝" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "$DIR_NAME/$name")

        val ua = userAgent ?: WebSettings.getDefaultUserAgent(context)
        request.addRequestHeader("User-Agent", ua)
        // Referer 只给本站的地址：直链（酷我 CDN 之类）带了 Referer 会直接 403（实测）
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.endsWith("gequbao.com")) {
            request.addRequestHeader("Referer", pageUrl ?: SITE)
        }
        val ck = cookie ?: CookieManager.getInstance().getCookie(url)
        if (!ck.isNullOrBlank()) request.addRequestHeader("Cookie", ck)
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: guessMime(url)
        if (mime != null) request.setMimeType(mime)

        return runCatching { dm.enqueue(request) }.getOrNull()
    }

    suspend fun query(context: Context, ids: List<Long>): Map<Long, Item> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val dm = context.getSystemService(DownloadManager::class.java) ?: return@withContext emptyMap()
        val out = HashMap<Long, Item>()
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    out[id] = Item(
                        id = id,
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty(),
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    )
                }
            }
        }
        out
    }

    fun remove(context: Context, id: Long) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        runCatching { dm.remove(id) }
    }

    /** 入队 + 落一条本地记录，失败返回 false */
    fun enqueueAndRecord(
        context: Context,
        url: String,
        title: String,
        artist: String,
        userAgent: String? = null,
        mimeType: String? = null,
        pageUrl: String? = null
    ): Boolean {
        val id = enqueue(context, url, title, artist, userAgent, mimeType, pageUrl) ?: return false
        LocalStore.get(context).addDownload(
            DownloadRec(
                dmId = id,
                title = title,
                artist = artist,
                fileName = fileName(title, artist, url),
                url = url,
                ts = System.currentTimeMillis()
            )
        )
        return true
    }

    fun fileName(title: String, artist: String, url: String): String {
        val base = buildString {
            append(title.ifBlank { "未知歌曲" })
            if (artist.isNotBlank()) append("-").append(artist)
        }.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").take(80)

        val fromUrl = URLUtil.guessFileName(url, null, null)
        val ext = fromUrl.substringAfterLast('.', "").lowercase().let {
            if (it in listOf("mp3", "flac", "m4a", "aac", "wav", "ape", "ogg")) it else "mp3"
        }
        return "$base.$ext"
    }

    private fun guessMime(url: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            URLUtil.guessFileName(url, null, null).substringAfterLast('.', "").lowercase()
        )
}
