# 第三方组件声明

## FFmpegKit (maintained fork)

- 依赖：`dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7`
- 上游项目：https://github.com/ffmpegkit-maintained/ffmpeg
- FFmpeg 源码：https://github.com/ffmpegkit-maintained/ffmpeg
- 许可：GNU Lesser General Public License v3.0 (LGPL-3.0)
- 完整许可证文本随 AAR 打包在 APK 的 `res/raw/license.txt` 中，也可查看：
  https://github.com/ffmpegkit-maintained/ffmpeg/blob/main/LICENSE

本项目仅通过公开 API 调用该库，未修改其源码。该库以动态链接库形式集成，
应用本身不由 LGPL 覆盖。若你需要对应的库源码，可从上面的上游仓库按相同版本获取。

## Media3 / ExoPlayer

- 依赖：`androidx.media3:*` 1.3.1
- 许可：Apache License 2.0
- 源码：https://github.com/androidx/media

## AndroidX RecyclerView / DocumentFile / Core

- 许可：Apache License 2.0
- 源码：https://android.googlesource.com/platform/frameworks/support/
