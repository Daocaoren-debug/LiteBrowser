# 轻览浏览器（LiteBrowser）

一个基于 Android 系统 `WebView` 的**基础版手机浏览器**，专为低端机设计。使用 Kotlin 编写，**零 AndroidX、零第三方依赖**，APK 体积小、启动快、内存占用低。

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 🌐 网页浏览 | 地址栏输入网址或关键词（自动用百度搜索），支持前进/后退/刷新/主页 |
| 🎬 网页视频 | 基于 WebView 播放 HTML5 视频，支持**全屏播放**（横屏自动适配） |
| ⬇️ 文件下载 | 交由系统 `DownloadManager` 执行，支持断点续传、通知栏进度，可从下载管理入口查看 |
| ⭐ 网址收藏 | 一键收藏当前网址，星标高亮显示，收藏夹可打开/删除 |
| 📜 历史记录 | 自动保存访问记录（去重、上限 500 条），可打开/单条删除/一键清空 |

## 为什么适合低端机

- **零第三方依赖**：不引入 AndroidX / Material / OkHttp 等，方法数少、无 dex 分包压力，避免低版本机型上的类冲突。
- **系统下载服务**：下载在独立系统进程完成，本应用被回收也不中断，不在 App 内做网络/文件 IO。
- **CursorAdapter 直连数据库**：历史/收藏列表不把数据一次性读进内存，滚动不卡顿。
- **按需缓存 + 禁止自动播放**：`LOAD_DEFAULT` 缓存策略节省流量；网页媒体需用户手势才播放，避免后台偷跑 CPU/电量。
- **关闭非必要特性**：关闭缩放控件、限制历史条数、Release 开启代码/资源压缩。
- **低最低版本**：`minSdk 26`（Android 8.0），单 APK 覆盖绝大多数仍在服役的低端设备。

## 环境要求

- Android Studio（建议 Hedgehog 2023.1 及以上）
- JDK 17
- Android SDK Platform 34（编译目标）
- Gradle 8.7（首次打开会自动通过 Gradle Wrapper 下载）

> 本项目刻意只提供 `gradle-wrapper.properties`，未附带二进制 `gradle-wrapper.jar`。用 Android Studio 打开时会自动补齐；如需命令行构建，先在有 Gradle 的环境执行一次 `gradle wrapper` 生成包装文件即可。

## 构建与安装

1. 用 Android Studio 打开项目根目录（含 `settings.gradle.kts` 的 `LiteBrowser` 文件夹）。
2. 等待 Gradle 同步完成。
3. 连接手机（开启 USB 调试）或启动模拟器。
4. 点击 **Run**，或执行：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
LiteBrowser/
├── settings.gradle.kts              # 仓库与模块声明
├── build.gradle.kts                 # 插件版本
├── gradle.properties                # 全局构建配置
└── app/
    ├── build.gradle.kts             # 应用构建配置（无 dependencies）
    ├── proguard-rules.pro           # 混淆规则
    └── src/main/
        ├── AndroidManifest.xml      # 权限与 Activity 声明
        ├── java/com/litebrowser/app/
        │   ├── MainActivity.kt              # 主界面与功能串联
        │   ├── data/
        │   │   ├── BrowserDatabase.kt       # SQLite：收藏 + 历史
        │   │   ├── SiteItem.kt              # 数据模型
        │   │   └── SiteCursorAdapter.kt     # Cursor 列表适配器
        │   ├── download/DownloadHelper.kt   # 系统下载封装
        │   └── webview/
        │       ├── BrowserWebViewClient.kt  # 导航/加载/错误
        │       └── BrowserWebChromeClient.kt# 进度 + 视频全屏
        └── res/                     # 布局、矢量图标、字符串、主题
```

## 已知限制（基础版取舍）

- 无多标签页、无广告过滤、无夜间模式、无无痕浏览——保持轻量，按需再加。
- 视频播放能力取决于系统 WebView 版本与站点本身：部分站点使用私有协议或 DRM（如某些 App 内嵌视频）无法在浏览器中播放；DRM 视频需要系统级 Widevine 支持。
- 部分站点会检测 User-Agent 限制第三方浏览器，已在 UA 中做基础伪装，但不能保证全部兼容。
- Android 13+ 若拒绝通知权限，下载仍会进行，只是没有完成通知。

## 合规说明

本项目仅提供浏览器框架代码，不内置任何网站内容或资源。使用者应遵守所在地区法律法规，仅访问合法合规的网站，并尊重网站版权与服务条款。下载功能由用户自行触发，请仅用于下载有权获取的内容。

## License

MIT（可在仓库中添加 LICENSE 文件后使用）
