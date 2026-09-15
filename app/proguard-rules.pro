# WebView JS 接口：R8 关闭时无影响，打开时保住注解方法
-keepclassmembers class com.dsh.gequbao.web.JsBridge {
    public *;
}
-keep class com.dsh.gequbao.core.Song { *; }
-keep class com.dsh.gequbao.core.Playlist { *; }
-keep class com.dsh.gequbao.core.DownloadRec { *; }
-keep class com.dsh.gequbao.core.AppSettings { *; }
