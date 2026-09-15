package com.dsh.gequbao.web

import android.webkit.JavascriptInterface
import com.dsh.gequbao.core.PlayerHub

/**
 * 注入脚本（assets/inject.js）回调原生用的桥。
 *
 * 约定：所有方法都在 WebView 的 JS 桥线程上被调用，**不是主线程**，
 * 所以这里只做转发，真正的状态变更由 PlayerHub 自己 post 到主线程。
 *
 * [uiConfig] 和 [consumeAutoplay] 比较特殊：它们是同步调用（JS 侧直接拿返回值），
 * 因此只读一个 volatile 字段，不做任何耗时/UI 操作。
 */
class JsBridge {

    @Volatile
    var uiConfigJson: String = "{}"

    @JavascriptInterface
    fun uiConfig(): String = uiConfigJson

    @JavascriptInterface
    fun onPlayerState(json: String) = PlayerHub.onJsState(json)

    @JavascriptInterface
    fun onSongPage(json: String) = PlayerHub.onSongMeta(json)

    @JavascriptInterface
    fun onEnded() = PlayerHub.onEnded()

    /** 网页马上要出声了：原生那一份该让位了 */
    @JavascriptInterface
    fun onWillPlay() = PlayerHub.onWillPlay()

    @JavascriptInterface
    fun consumeAutoplay(): Boolean = PlayerHub.consumeAutoplay()

    @JavascriptInterface
    fun toast(msg: String) = PlayerHub.toast(msg)

    @JavascriptInterface
    fun log(msg: String) = PlayerHub.log(msg)
}
