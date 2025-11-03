# FFmpegBatch（Android 初版 + iOS 预留）

一个现代美观的本地批量视频压缩应用：
- 默认参数：分辨率/帧率与原视频相同；视频编解码器 H.265（libx265）、CRF=28、音频拷贝
- 设置页可自由调整参数，并在底部实时显示等效 ffmpeg 命令
- 完整使用原生 ffmpeg（通过在设备上执行 ffmpeg 可执行文件），非第三方包装库
- 先完成 Android 端（CI 自动构建），iOS 端后续按相同策略（SwiftUI + 原生 ffmpeg 二进制）

## 目录结构
- `app/` Android 应用（Kotlin + Jetpack Compose）
- `scripts/build_ffmpeg_android.sh` 构建 Android 用 ffmpeg(libx265) 二进制
- `.github/workflows/android.yml` GitHub Actions：自动编译 ffmpeg 与 APK 并产出构建产物

## 功能说明（Android）
- “队列”页：选择多个视频文件加入处理队列，后台顺序执行
- “设置”页：
	- 编码器：默认 `libx265`
	- CRF：默认 `28`
	- Preset：默认 `medium`
	- 预览命令：如 `ffmpeg -y -i input.mp4 -c:v libx265 -crf 28 -preset medium -c:a copy output.mp4`
- 输出位置：应用专属 Movies 目录 `Android/data/<pkg>/files/Movies/FFmpegBatch/`

## 本地构建（Android）
> 全新环境下建议直接使用 GitHub Actions 构建（见下节）。若需本地构建 ffmpeg：

1) 准备依赖
```bash
sudo apt update
sudo apt install -y ninja-build cmake make curl unzip zip git
# 安装 Android NDK 并设置 ANDROID_NDK_HOME
```

2) 构建 ffmpeg 二进制（arm64-v8a）
```bash
bash scripts/build_ffmpeg_android.sh
```
脚本会在 `app/src/main/assets/ffmpeg/arm64-v8a/ffmpeg` 生成二进制。

3) 构建 APK（需要 Android SDK/NDK 环境）
- 安装 JDK17、Android SDK 34、Build-Tools 34、NDK r26c
- 运行（可先生成 Gradle Wrapper）：
```bash
# 仅示例，需确保本机已安装 gradle 或使用 ./gradlew
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```
输出 APK 位于 `app/build/outputs/apk/debug/`。

## CI 构建（GitHub Actions）
推送到 `main` 分支会自动触发：
- 安装 JDK/Android SDK/NDK
- 生成 Gradle Wrapper
- 运行 `scripts/build_ffmpeg_android.sh` 构建带 libx265 的 ffmpeg（arm64-v8a）
- 构建 Debug APK 并作为 Artifact 上传
你可以用 `gh run watch` 实时查看构建进度。

## iOS 计划
- 同样采用二进制方式集成原生 ffmpeg（通过脚本交叉编译 arm64），在应用中解包并执行
- UI 使用 SwiftUI，对齐 Android 的设置与命令预览
- 将在后续 PR 中补充 `scripts/build_ffmpeg_ios.sh` 与 Xcode 工程

## 注意事项
- 当前脚本仅构建 `arm64-v8a`。后续会增加 `armeabi-v7a/x86_64` 以覆盖更多设备。
- 由于使用 `libx265`，首次 CI 构建时间较长（编译 x265 + ffmpeg）。
- Android 10+ 上建议使用 SAF 选择文件；应用会复制到缓存再处理，输出保存在应用 Movies 目录。

## 许可证
本仓库代码（除外部依赖）以 MIT 授权。ffmpeg 与 x265 遵循其各自许可证（GPL 相关条款请按需遵循）。