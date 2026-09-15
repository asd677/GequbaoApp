# 歌曲宝 App（WebView 套壳 + 原生增强）

把 [gequbao.com](https://www.gequbao.com/) 装进一个 Android WebView 外壳里，
再补上原生音乐 App 该有的那些东西：**收藏、自建歌单、播放历史、搜索历史、下载管理、
通知栏控制、后台播放、歌单连播、夜间模式**。

> 一句话定位：网页负责「有什么歌」，App 负责「怎么听」。

---

## 一、它做了什么（原生补的部分）

| 功能 | 说明 |
|---|---|
| **收藏** | 播放条上点 ♥ 即收藏，数据在本地。网页上的「收藏」要登录，本地的不用 |
| **自建歌单** | 新建/删除歌单，从任意列表「加入歌单」，歌单可「播放全部」 |
| **连续播放（队列）** | 收藏/歌单/历史点一首即成为播放队列，一首放完自动下一首；支持 顺序 / 单曲 / 随机 |
| **播放历史** | 真的播了才记，同一首只留最近一次，上限 300 条 |
| **搜索历史** | 记住搜索词，点一下直接搜，可一键清空 |
| **下载管理** | 走系统 DownloadManager：断点续传、通知栏进度、文件落到 `Music/歌曲宝/`，App 内有下载列表和进度 |
| **通知栏 / 控制中心** | 封面 + 歌名 + 上一首/播放暂停/下一首，锁屏可用，耳机线控可用 |
| **后台播放** | 前台服务保活，切后台、锁屏、划走界面都不打断 |
| **迷你播放条** | 常驻底部的封面/歌名/进度条（可点进度条跳转）/收藏/上一首/下一首/更多菜单 |
| **播放队列面板** | 底部弹出，看得到队列和当前播到第几首，点哪首放哪首 |
| **夜间模式** | WebView 深色渲染（不支持的老内核自动退回注入式反色） |
| **极简模式** | 隐藏网页顶部导航和页脚，只留内容（原生底部导航已经覆盖了这些入口） |
| **网页缩放 / 桌面版 UA / 省流模式** | 字太小放大、想看电脑版排版、流量紧张时不加载图片 |
| **分享 / 外链** | 歌曲分享为「歌名 + 歌曲页链接」；站外链接交给系统浏览器 |
| **清理缓存** | 清 WebView 缓存 / Cookie / localStorage（不影响本地收藏歌单） |

## 二、它是怎么做到的

```
┌────────────────────────── App 进程 ──────────────────────────┐
│  MainActivity (Compose)                                      │
│   ├── 底部导航：发现(网页) 榜单(网页) 音乐库(原生) 我的(原生)   │
│   ├── 迷你播放条 / 播放队列面板                                │
│   └── AndroidView ── WebView ── 载入 gequbao.com              │
│            ▲   │                                             │
│   evaluateJavascript │  GB.onPlayerState(json)               │
│            │   ▼                                             │
│     PlayerHub（播放中枢，唯一状态源）                          │
│       ├── NowPlaying  → 迷你播放条 + 通知栏                    │
│       ├── 播放队列     → 放完自动跳下一首的歌曲页               │
│       └── LocalStore  → files/store/*.json（收藏/歌单/历史…）   │
│                                                              │
│  PlaybackService：前台服务 + MediaSessionCompat（通知栏控制）   │
│  DownloadManager：下载 + 进度查询                              │
└──────────────────────────────────────────────────────────────┘
```

**关键设计：和网页的耦合只有一个文件** —— `app/src/main/assets/inject.js`。

* 它给页面加一个 `window.__gb` 控制接口（`play/pause/seek/toggle/replay/urls`）；
* 它监听 `<audio id="custom-audio-player">` 的事件，把歌名/歌手/进度/是否在播报给原生；
* 它从 `window.appData`（站点自己挂在页面上的 JSON）里取歌曲信息，取不到再退回 DOM 选择器；
* 站点改版时，**只需要改这个文件**，Kotlin 侧一行都不用动。

**连播是怎么实现的**：站点自己不知道我们的收藏和歌单，所以队列放在原生。
一首歌 `ended` 时，注入脚本调 `GB.onEnded()`，`PlayerHub` 决定下一首是谁，
让 WebView 跳到那首歌的详情页，并让注入脚本自动点一下播放（靠 `consumeAutoplay()` 这个
同步桥把「这次跳页要自动播」的意图传进新页面）。

## 三、编译

### 云端编译（推荐）

仓库自带 GitHub Actions：推送到 `main` 就会自动编译，产物在 **Releases → latest**（免登录下载），
也可以在 Actions 页面的 Artifacts 里拿。

```bash
# 打 tag 会发正式 Release
git tag v1.0 && git push origin v1.0
```

产物里 `app-debug.apk` 和 `app-release.apk` 都是 debug 签名，**拿到就能直接装**。

### 本地编译

```bash
export JAVA_HOME=/path/to/jdk17          # 需要 JDK 17+
export ANDROID_HOME=/path/to/android-sdk # 需要 SDK Platform 34 + Build-Tools 34
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 四、已知限制（先说清楚）

1. **音源、歌词、播放地址全部来自 gequbao.com**，App 不含任何音源、不解析任何接口，
   站点挂了或者改版了，App 就只能打开一个普通网页（但收藏/歌单/历史这些本地数据不受影响）。
2. **下载地址是「边播边解析」出来的**。站点要先点一次播放才会去拿直链，
   所以对「当前正在播的这首歌」下载最稳；列表里对没播过的歌点下载，会跳到它的歌曲页，
   需要你在页面上点一下「下载歌曲」（或先播一次）。
3. **站点改版会打断注入**：注入脚本靠 `#custom-audio-player`、`window.appData`、
   `#player-toggle-btn` 这几个锚点。锚点没了 → 迷你播放条不更新、连播失效，
   但网页本身照常能用。修法就是更新 `inject.js`。
4. **原生「音乐库」是在网页不可见时打开歌曲页的**。绝大多数机型上正常（网页在后台继续加载和播放），
   但如果你的机型出现「点收藏的歌没声音」，点一下迷你播放条的播放键即可。
5. 只针对手机竖屏 + 单窗口优化，没做平板分栏。

## 五、数据与隐私

* 所有原生数据都存在本机 `files/store/*.json`（收藏、歌单、历史、搜索、下载记录、设置），
  **不联网、不上传、不需要账号**。
* 唯一的运行时权限：通知（后台播放的通知栏）、Android 9 及以下的存储权限（写下载目录）。
* Cookie / 登录状态由 WebView 自己管，和浏览器一样。

## 六、目录结构

```
app/src/main/
├── assets/inject.js                 # 页面注入脚本（和网页的唯一耦合点）
├── java/com/dsh/gequbao/
│   ├── MainActivity.kt              # 唯一 Activity
│   ├── App.kt
│   ├── core/
│   │   ├── Models.kt                # Song / Playlist / DownloadRec / AppSettings / NowPlaying
│   │   ├── LocalStore.kt            # JSON 持久化（StateFlow + 原子写）
│   │   ├── PlayerHub.kt             # 播放中枢：网页状态 <-> 原生 UI，播放队列
│   │   └── DownloadHelper.kt        # DownloadManager 封装
│   ├── web/
│   │   ├── WebShell.kt              # WebView 配置、URL 分流、注入装配
│   │   └── JsBridge.kt              # @JavascriptInterface 桥（名字叫 GB）
│   ├── player/PlaybackService.kt    # 前台服务 + MediaSessionCompat + 通知
│   └── ui/
│       ├── AppRoot.kt               # 底部导航 / 迷你播放条 / 队列面板
│       ├── LibraryScreen.kt         # 收藏 / 歌单 / 历史 / 下载
│       ├── MineScreen.kt            # 设置 / 搜索历史 / 关于
│       ├── Common.kt                # 歌曲行、封面、加入歌单对话框
│       └── Theme.kt
└── res/                             # 图标、通知图标、网络安全配置
```

## 七、免责声明

本仓库只是一个浏览器外壳的**技术实现**，不提供、不存储、不分发任何音频内容。
歌曲、歌词、封面等一切内容均来自第三方网站 gequbao.com，版权归各权利人所有。
请把它当个人学习 WebView / MediaSession 用，别拿去上架分发。
