package com.dsh.gequbao

import android.app.Application
import com.dsh.gequbao.core.LocalStore
import com.dsh.gequbao.core.PlayerHub

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 单例先热身：设置、收藏这些 StateFlow 在 UI 起来之前就可用
        LocalStore.get(this)
        PlayerHub.init(this)
    }
}
