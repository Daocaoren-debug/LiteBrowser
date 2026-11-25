# FFmpegKit（反射加载 native 库与回调）
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# WebView 相关的 JS 接口若将来添加，需要在此 keep；当前无 JS 注入接口。
-keepattributes *Annotation*
-keepattributes JavascriptInterface

# 保留泛型签名，方便序列化/调试
-keepattributes Signature

# WebView 生成的临时类
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}
