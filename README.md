# 轻览浏览器 LiteBrowser

一个基于 Android 系统 `WebView` 的**基础版手机浏览器**，为低端机 / 老机型设计。Kotlin 编写，界面/存储/下载/嗅探全部使用系统框架，**唯一第三方依赖是播放内核 Media3(ExoPlayer)**：APK 小、启动快、内存占用低。

[![Build APK](https://github.com/Daocaoren-debug/LiteBrowser/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Daocaoren-debug/LiteBrowser/actions/workflows/build-apk.yml)

**直接下载安装包（每次推送自动构建）：**

```
https://github.com/Daocaoren-debug/LiteBrowser/releases/download/latest/LiteBrowser-debug.apk
```

> debug 签名，可侧载安装。手机浏览器打开上面的链接即可下载；也可在仓库 Releases 页拿最新包，或在 Actions 页面下载 artifact。

## 功能

| 功能 | 说明 |
| --- | --- |
| 网页浏览 | 地址栏输入网址或关键词（自动百度搜索）；前进 / 后退 / 刷新 / 主页；双指缩放；`target="_blank"` 新窗口链接正常打开 |
| 网页视频 | HTML5 `<video>` 播放；**点击全屏**时自动接管全屏容器、隐藏系统栏、保持屏幕常亮，返回键退出全屏；支持横屏 |
| 本地 M3U8 | 从「更多 → 本地 M3U8 播放」进入：扫描手机里的 `.m3u8`/`.m3` 文件（支持 SAF 选目录），带缩略图预览与扫描缓存；播放器**手动横竖屏切换**（点右下角按钮），不跟随重力感应 |
| 资源嗅探 | 地址栏右侧 🎬 按钮：双路嗅探本页视频（请求拦截 + 页面内 JS 探针），列出候选地址，点一下进内置播放器 |
| 内置播放器 | ExoPlayer 内核，支持 m3u8 / mp4 / mpd；**±10 秒快进快退**、**进度条拖动**、**长按左右边缘快进快退**、直播流识别、带 Referer 绕过防盗链、失败可重试。嗅探按钮在网页右下角悬浮 |
| 文件下载 | 交给系统 `DownloadManager`，断点续传、通知栏进度，被杀进程也不中断；底部工具栏可进下载管理 |
| 网址收藏 | 星标一键收藏，图标即时高亮；收藏夹内可打开、删除 |
| 历史记录 | 自动记录（同 URL 去重、上限 500 条控制库体积），可打开、单条删除、一键清空 |
| 其他 | 分享此页、复制链接、用系统浏览器打开、外部链接跳转、错误页、后台回收后恢复原页面 |

## 为什么适合低端机

- **依赖极简**：只引入 Media3 播放内核（3 个 aar），没有 Material / OkHttp / 图片库；不需要 dex 分包，低版本机型无类冲突。
- **系统下载服务**：下载在独立系统进程完成，App 内不做网络与文件 IO，省内存省电。
- **CursorAdapter 直连 SQLite**：收藏/历史列表不把数据一次性读入内存，长列表滑动不卡。
- **缓存优先 + 禁止自动播放**：`LOAD_DEFAULT` 省流量；媒体需用户手势才播放，避免后台偷跑 CPU/电量。
- **单窗口模型**：不做多标签，`target="_blank"` 复用同一个 WebView，内存占用约为多标签浏览器的 1/3。
- **关掉一切可关的**：无图片预加载开关、无内置缩放按钮、历史限量、Release 混淆 + 资源压缩。
- **minSdk 26（Android 8.0）**，单 APK 覆盖绝大多数仍在服役的低端设备。

## 构建

### 用 Android Studio

1. 打开项目根目录（含 `settings.gradle.kts` 的文件夹）。
2. 等待 Gradle 同步（Studio 会自动补 `gradle-wrapper.jar`）。
3. 连接手机（开启 USB 调试）后点 **Run**。

### 命令行

需要 JDK 17 + Android SDK Platform 34：

```bash
gradle :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleRelease        # 混淆后的 release 包（未配置签名，需自行加 signingConfig）
```

本仓库刻意只保留 `gradle/wrapper/gradle-wrapper.properties`、不带二进制 `gradle-wrapper.jar`。想用 `./gradlew`，先在装有 Gradle 的机器上跑一次 `gradle wrapper`。

### 云端自动构建

推送到 `main` 即触发 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)：
编译 debug APK → 上传 Actions artifact → 覆盖 `latest` release 里的 `LiteBrowser-debug.apk`。不需要本地 Android 环境。

## 项目结构

```
LiteBrowser/
├── .github/workflows/build-apk.yml   # CI：自动编译 APK 并发版
├── settings.gradle.kts               # 仓库与模块声明
├── build.gradle.kts                  # AGP 8.5.2 / Kotlin 1.9.24
├── gradle.properties                 # useAndroidX=true（Media3 需要）
└── app/
    ├── build.gradle.kts              # 仅 3 个 Media3 aar
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/litebrowser/app/
        │   ├── MainActivity.kt              # 界面装配、导航、菜单、下载权限、状态保存
        │   ├── data/
        │   │   ├── BrowserDatabase.kt       # SQLiteOpenHelper：收藏 + 历史
        │   │   ├── SiteItem.kt              # 数据模型
        │   │   └── SiteCursorAdapter.kt     # Cursor 列表适配器
        │   ├── download/DownloadHelper.kt   # 系统 DownloadManager 封装
        │   ├── player/
        │   │   ├── MediaSniffer.kt          # 资源嗅探（请求旁听 + JS 探针）
        │   │   └── PlayerActivity.kt        # 内置播放器（ExoPlayer + 自绘控制条）
        │   └── webview/
        │       ├── BrowserWebViewClient.kt  # 导航白名单、外部协议、错误回调
        │       └── BrowserWebChromeClient.kt# 进度、视频全屏、新窗口接管
        └── res/                     # 布局、矢量图标、字符串、主题
```

## 关键实现细节

- **下载权限**：Android 8/9（API 26–28）写公共 `Download` 目录需要运行时 `WRITE_EXTERNAL_STORAGE`，这正是大量低端机在跑的版本，缺失会直接 enqueue 失败。代码在下载前检查并暂存请求，授权后继续；Android 13+ 再补 `POST_NOTIFICATIONS`。
- **blob: / data: 链接**：只在页面内存有效，系统下载器取不到，遇到时提示而不是抛异常。
- **状态恢复**：`onSaveInstanceState` 保存 `WebView.saveState`，进程被系统回收后恢复原页面而不是回主页。
- **Cookie 落盘**：切后台时 `CookieManager.flush()`，降低被杀后丢登录态的概率。
- **视频全屏**：`onShowCustomView` 挂载到全屏容器 + `FLAG_KEEP_SCREEN_ON` + 沉浸式系统栏，`onHideCustomView` 全部还原。
- **混合内容**：`MIXED_CONTENT_COMPATIBILITY_MODE`，兼容仍用 http 资源的老站点，同时不完全放开 https 页面。
- **双路嗅探**：`shouldInterceptRequest` 只旁听、原样返回响应对象（页面行为完全不变），能抓到播放器真实请求的 m3u8 主列表与 mp4 直链；点嗅探按钮时再注入 JS 读 `<video>.currentSrc` 与 `performance` 资源表，补上被 XHR 藏起来的地址。两侧结果按扩展名（mp4/m3u8/mpd/webm/flv/mkv/mov/3gp）去重合并，`.ts` 分片直接丢弃。
- **内置播放器**：ExoPlayer + 自绘控制条。没用 `PlayerView` 自带控制器，因为需求要 ±10 秒快进快退和旋转按钮；`configChanges` 保证横竖屏切换不重建 Activity、播放不断；`DefaultHttpDataSource` 带上 Referer/UA 绕防盗链；`STATE_BUFFERING` 显示转圈、`onPlayerError` 给重试。

## 已知限制（基础版取舍）

- 无多标签页、无广告过滤、无夜间模式、无无痕模式、无阅读模式——保持轻量，按需再加。
- 视频能力取决于系统 WebView 版本与站点：私有协议、DRM（Widevine）内容无法在浏览器内核播放；老机型 WebView 版本过旧时，部分站点会提示"浏览器版本过低"，可在应用商店更新 "Android System WebView"。
- 部分站点检测 UA 限制第三方浏览器，已做基础 UA 标识，但不保证全兼容。
- **嗅探不到 blob:/MSE 流**：爱奇艺、腾讯视频、B站正片这类站点把视频解密后喂给 MediaSource，真实地址只存在页面内存里，任何基于 WebView 的浏览器都拿不到可直接播放的链接；DRM（Widevine）内容同理。嗅探适合的是 mp4 直链、普通 HLS（m3u8）站点。
- m3u8 若是 AES-128 加密且密钥接口需要登录态，播放器会报错，属预期。
- Release 构建未内嵌签名配置，需自行生成 keystore；CI 产出的是 debug 签名包。

## 合规

本项目只提供浏览器框架代码，不内置任何网站内容。下载功能由用户主动触发，请仅用于下载你有权获取的内容，并遵守所在地区法律法规与网站服务条款。

## License

[MIT](LICENSE)
