package com.dsh.gequbao.core

import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 原生解析器：**不经过 WebView**，自己把歌曲页抓下来、调站点自己的播放接口拿直链。
 *
 * 为什么要这个东西：网页里的 `<audio>` 是页面的子资源，用户一翻页它就跟着页面一起没了 ——
 * 这就是「返回首页歌就停了」的根因。有了它，原生播放器可以自己去取下一首的直链，
 * 于是「边听边逛」和「队列连播」就不需要把用户的页面拽走。
 *
 * 走的完全是站点自己的公开流程（实测可用，不需要登录）：
 *   1. GET  /music/{id}                      → 页面里的 window.appData.play_id
 *   2. POST /member/common-play-url  id=xxx  → { code:1, data:{ url } }（酷我 CDN 的 mp3 直链）
 *
 * 注意：这个接口对免费用户有**解析次数限制**，超了会返回
 * `code=4 / parse_quota_exhausted`；有时还会要求人机验证（appData.should_verify）。
 * 这两种情况都当成正常失败抛出去，由调用方退回「把网页翻到那首歌」的老办法。
 */
object GbResolver {

    private val gson = Gson()

    data class Resolved(
        val url: String,
        val title: String = "",
        val artist: String = "",
        val cover: String = "",
        val duration: Int = 0
    )

    class ResolveException(message: String) : Exception(message)

    /** 只想要元信息（不想消耗解析次数）时用 */
    data class Meta(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val cover: String = "",
        val duration: Int = 0,
        val playId: String = ""
    )

    suspend fun resolve(songId: String): Result<Resolved> = withContext(Dispatchers.IO) {
        runCatching {
            if (songId.isBlank()) throw ResolveException("没有歌曲 id，解析不了")
            val meta = fetchMeta(songId)
            if (meta.playId.isBlank()) {
                throw ResolveException(
                    if (meta.title.isBlank()) "歌曲页没拿到数据（可能被风控挡了）" else "这首歌没有播放地址"
                )
            }
            val url = requestPlayUrl(meta.playId)
            Resolved(
                url = url,
                title = meta.title,
                artist = meta.artist,
                cover = meta.cover,
                duration = meta.duration
            )
        }
    }

    /** 抓歌曲页并解析 window.appData */
    suspend fun fetchMeta(songId: String): Meta = withContext(Dispatchers.IO) {
        val page = "$SITE/music/$songId"
        val html = httpGet(page)
        val raw = extractAppData(html) ?: throw ResolveException("页面里找不到播放信息")
        val obj = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
            ?: throw ResolveException("播放信息解析失败")

        val shouldVerify = obj.get("should_verify")?.asBoolean == true
        if (shouldVerify) throw ResolveException("这首歌需要人机验证，请到网页里播一次")

        Meta(
            id = obj.get("mp3_id")?.asString.orEmpty(),
            title = obj.get("mp3_title")?.asString.orEmpty(),
            artist = obj.get("mp3_author")?.asString.orEmpty(),
            cover = obj.get("mp3_cover")?.asString.orEmpty(),
            duration = parseDuration(obj.get("mp3_duration")?.asString.orEmpty()),
            playId = obj.get("play_id")?.asString.orEmpty()
        )
    }

    private fun requestPlayUrl(playId: String): String {
        val body = "id=" + URLEncoder.encode(playId, "UTF-8") + "&purpose=play"
        val text = httpPost("$SITE/member/common-play-url", body, "$SITE/music/0")
        val obj = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
            ?: throw ResolveException("接口返回看不懂")

        val code = obj.get("code")?.asInt ?: -1
        if (code != 1) {
            val reason = obj.getAsJsonObject("data")?.get("reason")?.asString.orEmpty()
            val msg = obj.get("msg")?.asString.orEmpty()
            throw ResolveException(
                when {
                    reason.contains("quota") -> "今天的免费解析次数用完了（网页里也一样，等明天或开会员）"
                    msg.isNotBlank() -> msg
                    else -> "解析失败（code=$code）"
                }
            )
        }
        val url = obj.getAsJsonObject("data")?.get("url")?.asString.orEmpty()
        if (url.isBlank()) throw ResolveException("接口没返回播放地址")
        return url
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String): String {
        val conn = open(url)
        conn.requestMethod = "GET"
        return read(conn)
    }

    private fun httpPost(url: String, form: String, referer: String): String {
        val conn = open(url, referer)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        conn.outputStream.use { it.write(form.toByteArray()) }
        return read(conn)
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw ResolveException(
                when (code) {
                    403, 429 -> "被站点挡了（HTTP $code），到网页里播一次吧"
                    else -> "站点返回 HTTP $code"
                }
            )
        }
        return text
    }

    private fun open(url: String, referer: String = SITE): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA_MOBILE)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            // 站点是登录态的，Cookie 必须带上（WebView 和这里共用同一个 CookieManager）
            CookieManager.getInstance().getCookie(SITE)?.let { setRequestProperty("Cookie", it) }
        }
        return conn
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 页面里是 `window.appData = JSON.parse('{\u0022mp3_id\u0022:1,...}');`
     * 也就是「JSON 又被 JS 字符串转义了一遍」，这里先把转义还原成真 JSON。
     */
    internal fun extractAppData(html: String): String? {
        val marker = "window.appData = JSON.parse('"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = html.indexOf("');", from)
        if (end < 0) return null
        return unescapeJs(html.substring(from, end))
    }

    internal fun unescapeJs(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c != '\\' || i == src.length - 1) {
                sb.append(c); i++; continue
            }
            when (val n = src[i + 1]) {
                'u' -> {
                    val hex = src.substring(i + 2, minOf(i + 6, src.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar()); i += 6
                    } else {
                        sb.append(n); i += 2
                    }
                }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                else -> { sb.append(n); i += 2 }   // \" \/ \\ \' 这些都还原成它本身
            }
        }
        return sb.toString()
    }

    private fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        val p = text.split(":")
        return when (p.size) {
            2 -> (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
            3 -> (p[0].toIntOrNull() ?: 0) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 + (p[2].toIntOrNull() ?: 0)
            else -> text.toIntOrNull() ?: 0
        }
    }
}
