package com.dsh.gequbao.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.dsh.gequbao.core.AppSettings
import com.dsh.gequbao.core.DownloadHelper
import com.dsh.gequbao.core.LocalStore
import com.dsh.gequbao.core.PlayerHub
import com.dsh.gequbao.core.SITE
import com.dsh.gequbao.core.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream

/**
 * WebView 外壳：设置项、URL 分流、注入脚本装配。
 *
 * 站点能力（长期不变的部分）都写在这里；页面本身长什么样与我们无关 ——
 * 所有和页面结构的耦合都收敛在 assets/inject.js 一个文件里，
 * 站点改版时只需要改那个脚本。
 */
class WebShell(private val context: Context) {

    companion object {
        private const val TAG = "WebShell"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "aac", "wav", "ape", "ogg", "wma")

        fun isAudioUrl(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            val ext = path.substringAfterLast('.', "")
            return ext in AUDIO_EXT
        }
    }

    val webView: WebView = WebView(context)
    val bridge = JsBridge()

    private val _progress = MutableStateFlow(100)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private var injectJs = ""
    private var lastAppliedUa = ""

    init {
        injectJs = runCatching {
            context.assets.open("inject.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "inject.js 读取失败", it)
            ""
        }
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            // 站点要靠点一下才给播放地址，但队列连播时我们必须能自动播
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = MOBILE_UA
        }
        lastAppliedUa = MOBILE_UA

        webView.addJavascriptInterface(bridge, "GB")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return route(url, request.hasGesture())
            }

            @Deprecated("旧机型走这里")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                route(url.orEmpty(), true)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                _progress.value = 5
                PlayerHub.onPageStarted(url.orEmpty())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _progress.value = 100
                PlayerHub.onPageLoaded(url.orEmpty())
                _canGoBack.value = view.canGoBack()
                _title.value = view.title.orEmpty()
                applyPageSettings()
                inject()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                PlayerHub.setWebUrl(url.orEmpty())
                _canGoBack.value = view.canGoBack()
                _title.value = view.title.orEmpty()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (LocalStore.get(context).settings.value.dataSaver && looksLikeImage(url)) {
                    // 省流模式：图片直接返回空，别走流量
                    return WebResourceResponse("image/png", null, ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _progress.value = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                _title.value = title.orEmpty()
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "console: ${msg.message()} @${msg.lineNumber()}")
                return true
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onWebDownload(url, userAgent, contentDisposition, mimeType)
        })
    }

    /** @return true 表示这次跳转已被我们接管 */
    private fun route(url: String, gesture: Boolean): Boolean {
        if (url.isBlank() || url.startsWith("javascript:") || url.startsWith("about:")) return false

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme != "http" && scheme != "https") {
            openExternal(url)
            return true
        }

        val host = uri.host.orEmpty().lowercase()
        val ours = host.endsWith("gequbao.com")

        // 音频直链：别在 WebView 里放（会白屏），交给系统下载
        if (isAudioUrl(url)) {
            onWebDownload(url, webView.settings.userAgentString, null, null)
            return true
        }

        // 站外链接（含淘宝/微信/网盘分享页）交给系统浏览器
        if (!ours && gesture) {
            openExternal(url)
            return true
        }
        return false
    }

    private fun openExternal(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { PlayerHub.toast("没有可以打开这个链接的应用") }
    }

    private fun looksLikeImage(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp")
    }

    private fun onWebDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        val song = PlayerHub.currentSong()
        val guess = android.webkit.URLUtil.guessFileName(url, disposition, mime)
        val title = song?.title?.takeIf { it.isNotBlank() }
            ?: guess.substringBeforeLast('.').substringBefore('-')
        val artist = song?.artist.orEmpty()

        val ok = DownloadHelper.enqueueAndRecord(
            context = context,
            url = url,
            title = title,
            artist = artist,
            userAgent = userAgent ?: webView.settings.userAgentString,
            mimeType = mime,
            pageUrl = PlayerHub.currentWebUrl.ifBlank { SITE }
        )
        if (!ok) {
            PlayerHub.toast("下载启动失败，试试长按链接「用浏览器打开」")
            return
        }
        PlayerHub.toast("开始下载：$title")
    }

    // ------------------------------------------------------------ 注入与设置

    private fun buildUiConfig(s: AppSettings): String {
        val nativeDark = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
        return """{"dark":${s.dark},"nativeDark":$nativeDark,"hideHeader":${s.hideHeader},""" +
            """"hideFooter":${s.hideFooter},"zoom":${s.zoom}}"""
    }

    private fun applyPageSettings() {
        val s = LocalStore.get(context).settings.value
        bridge.uiConfigJson = buildUiConfig(s)
        webView.setBackgroundColor(if (s.dark) 0xFF101014.toInt() else 0xFFFFFFFF.toInt())
        val ws = webView.settings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                ws, if (s.dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, s.dark)
        }
    }

    /** 设置页改了之后调用：立即生效，必要时重载 */
    fun applySettings(reloadIfNeeded: Boolean = true) {
        val s = LocalStore.get(context).settings.value
        val ua = if (s.desktopUa) DESKTOP_UA else MOBILE_UA
        applyPageSettings()
        if (ua != lastAppliedUa) {
            lastAppliedUa = ua
            webView.settings.userAgentString = ua
            if (reloadIfNeeded) webView.reload()
        } else if (reloadIfNeeded) {
            inject()
        }
    }

    fun inject() {
        if (injectJs.isBlank()) return
        bridge.uiConfigJson = buildUiConfig(LocalStore.get(context).settings.value)
        webView.evaluateJavascript(injectJs, null)
    }

    // ------------------------------------------------------------ 对外操作

    fun load(url: String) {
        webView.loadUrl(url)
    }

    fun currentUrl(): String = webView.url.orEmpty()

    fun back(): Boolean {
        return if (webView.canGoBack()) {
            webView.goBack(); true
        } else false
    }

    fun reload() = webView.reload()

    fun goHome() {
        if (currentUrl().startsWith(SITE)) webView.loadUrl(SITE) else load(SITE)
    }

    fun search(keyword: String) {
        load("$SITE/s/${Uri.encode(keyword)}")
    }

    fun openSong(song: Song) {
        load(song.page())
    }

    fun runJs(code: String) {
        webView.post { webView.evaluateJavascript(code, null) }
    }

    /** 问网页要当前音频地址（同步拿不到，走回调） */
    fun requestAudioUrls(onResult: (src: String, dl: String) -> Unit) {
        webView.evaluateJavascript("window.__gb && __gb.urls ? __gb.urls() : ''") { raw ->
            val json = runCatching {
                org.json.JSONTokener(raw ?: "").nextValue() as? String
            }.getOrNull().orEmpty()
            val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
            onResult(obj?.optString("src").orEmpty(), obj?.optString("dl").orEmpty())
        }
    }

    fun pauseWeb() = webView.onPause()

    fun resumeWeb() = webView.onResume()

    fun destroy() {
        runCatching {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.removeJavascriptInterface("GB")
            webView.destroy()
        }
    }

    fun clearWebCache(onDone: () -> Unit) {
        runCatching {
            webView.clearCache(true)
            webView.clearHistory()
            android.webkit.WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        onDone()
    }
}
