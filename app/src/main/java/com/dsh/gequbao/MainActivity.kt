package com.dsh.gequbao

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dsh.gequbao.core.PlayerHub
import com.dsh.gequbao.player.PlaybackService
import com.dsh.gequbao.ui.AppRoot
import com.dsh.gequbao.web.WebShell

/**
 * 唯一 Activity。WebView 的生命周期跟它绑在一起：
 *  - 退到后台不销毁页面，音乐继续放（配前台服务）；
 *  - 返回键在根页面且正在播放时是把任务压到后台，而不是销毁页面（否则歌曲会被打断）。
 */
class MainActivity : ComponentActivity() {

    lateinit var shell: WebShell
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerHub.init(applicationContext)
        shell = WebShell(this)
        PlayerHub.attach(
            runner = { js -> shell.runJs(js) },
            nav = { url -> shell.load(url) }
        )
        setContent { AppRoot(shell = shell, activity = this) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        if (url.contains("gequbao.com")) shell.load(url)
    }

    override fun onResume() {
        super.onResume()
        shell.webView.onResume()
    }

    override fun onDestroy() {
        if (isFinishing) {
            PlayerHub.detach()
            shell.destroy()
            if (!PlayerHub.state.value.playing) PlaybackService.shutdown(this)
        }
        super.onDestroy()
    }
}
