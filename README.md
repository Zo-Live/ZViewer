# ZViewer

一个使用 Kotlin、Jetpack Compose 和 Material Design 3 构建的原生 Android 本地漫画阅读器。

## 安装与开始阅读

支持 Android 10 及以上，目标平台为 Android 16 / API 36。通用安装包包含 **arm64-v8a** 与 **x86_64**。

1. 将 APK 复制到手机，使用文件管理器打开，按系统提示允许该来源安装应用。
2. 首次打开，点击「选择书库文件夹」，在系统文件选择器中进入漫画目录，点击「使用此文件夹」并授权。
3. 应用递归扫描该目录，显示每本漫画的封面和名称；以后启动直接显示这个书库。
4. 点击漫画开始阅读。轻点画面显示浮动进度条，再次轻点收起；拖动进度条或点击页码可以跳页。
5. 双指捏合、双击图片，或使用进度面板的放大 / 缩小按钮调整图片。点击「适合屏幕」恢复初始比例。
6. 在阅读设置中切换垂直连续、水平翻页及从右向左翻页。使用系统边缘返回手势或左上角返回按钮回到书库。

Android 通常不允许授权内部存储根目录、Download 根目录及 Android/data。请创建一个具体的漫画子文件夹，例如 `Download/comics`，再选择它。应用无需「所有文件访问」权限。

## 功能

| 类别     | 支持                                                                             |
| -------- | -------------------------------------------------------------------------------- |
| 漫画来源 | ZIP / CBZ、RAR / CBR、7Z、PDF、图片文件夹                                        |
| 静态图片 | JPG、JPEG、PNG、WebP、BMP、HEIF、HEIC、AVIF                                      |
| 动态图片 | APNG、Animated WebP、GIF、Animated AVIF                                          |
| 书库     | 递归扫描、自然排序、高清封面、搜索、阅读状态筛选、最近阅读、收藏、2–5 列网格    |
| 阅读     | 垂直连续、水平分页、左右阅读方向、缩放、可拖动进度、页码跳转、自动记忆进度、常亮 |
| 外观     | 跟随系统 / 浅色 / 深色主题、预设主题色、自定义十六进制主题色、独立亮暗阅读背景   |
| 系统     | 边到边显示、沉浸阅读、系统预测返回、横竖屏、SAF 持久目录授权                     |
| 存储     | 私有解压与 PDF 缓存、缓存清理、约 1.5 GiB 自动回收阈值                           |

封面以最高 1000 像素宽度生成并缓存。阅读图片会按屏幕尺寸优化解码，宽度上限为 3200 像素；PDF 以最高 2560 像素宽度渲染。缩放范围为适合屏幕至 5 倍，并可缩回适合屏幕。

## 格式支持与限制

- 支持 RAR4 / RAR5。7Z 固实压缩包首次打开需要完整准备图片。
- 加密压缩包、分卷文件、密码 PDF 和 DRM 内容不属于此版本的支持范围。请先解密或解压为图片文件夹。
- HEIF / HEIC 的支持范围取决于设备系统解码器；此版本不将 HEIF 多图集合或动态 HEIF 作为动画播放。
- 动态 AVIF、APNG、GIF 和动态 WebP 均可播放；GIF 与动态 WebP 不会降级为首帧静图。
- 图片文件夹中的直接图片组成一本书；子目录各自扫描。压缩包中的图片按完整路径自然排序，例如 `2.jpg` 位于 `10.jpg` 前面。
- 单页输入限制 256 MiB、每本解压数据限制 4 GiB、最多 20,000 页，目录递归最多 32 层。超限或损坏内容会报错，避免无限解压。
- 进度保存到页级；不会恢复同一页内部的具体滚动位置或缩放位置。进程被系统终止后再次打开回到书库，并可继续上次阅读。

## 隐私与数据

ZViewer 不申请联网权限，不包含账号或广告。漫画文件不会被修改，阅读记录、封面与缓存都保存在设备本地。应用内提供缓存清理功能，并会在缓存达到约 1.5 GiB 时自动回收。

## 本地构建

需要 JDK 17、Android SDK 36、网络（仅构建时下载依赖）。工程自带 Gradle 8.11.1 Wrapper，依赖版本固定。

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

若 SDK 不在上述位置，请调整 `ANDROID_HOME`，或在未跟踪的 `local.properties` 中设置 `sdk.dir`。

## 发布构建与签名

发布签名密钥通常位于 `.signing/zviewer-release.jks`，别名为 `zviewer`。该目录不应提交到 Git，并应单独安全备份。以后发布需使用同一密钥和更高的 `versionCode`，已安装应用才能直接覆盖升级并保留数据。

构建时通过 `ZVIEWER_STORE_PASSWORD` 与 `ZVIEWER_KEY_PASSWORD` 传入口令；不要将私钥或口令提交到仓库。新机器首次构建正式版时，先恢复原密钥；若只是独立试用，也可以生成自己的密钥，但它无法覆盖安装由原密钥签名的 APK。

```sh
mkdir -p .signing
keytool -genkeypair -keystore .signing/zviewer-release.jks \
  -alias zviewer -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$ZVIEWER_STORE_PASSWORD" -keypass "$ZVIEWER_KEY_PASSWORD" \
  -dname 'CN=ZViewer, OU=Local Release, O=ZoLive'
./gradlew :app:assembleRelease
```

构建产物为 `app/build/outputs/apk/release/app-release.apk`。应用启用 R8 代码压缩和资源裁剪；三种原生库均需满足 16 KB 页对齐。

## 测试与实现文档

- [开发计划](docs/开发计划.md)
- [架构与兼容性](docs/架构与兼容性.md)
- [测试记录](docs/测试记录.md)
- 核心逻辑测试：`app/src/test/`
- 设备格式集成测试：`app/src/androidTest/`
- 样本生成：`tools/generate_fixtures.py`，生成的样本位于被 Git 忽略的 `tools/fixtures/`；样本不会打包进正式 APK。

生成测试样本需要 Pillow、pillow-heif、reportlab、py7zr、文泉驿字体与 RAR 命令。当前开发环境曾将依赖安装在 `~/.cache/zviewer-test-venv/`，测试用 RAR 命令位于 `/tmp/rar/rar`；请按本机环境调整。

```sh
~/.cache/zviewer-test-venv/bin/python tools/generate_fixtures.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <设备序列号> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <设备序列号> shell am instrument -w \
  dev.zolive.zviewer.test/androidx.test.runner.AndroidJUnitRunner
```

正式 APK 与测试 APK 使用不同签名；在已安装正式版的设备上安装 debug 包会被系统拒绝。请使用独立模拟器执行 debug 测试，避免卸载正式应用导致阅读记录丢失。

## 开源组件与许可

项目使用 Apache 2.0 许可证。使用了 AndroidX / Compose、Kotlin、libarchive-android、libarchive、APNG4Android 和 libavif。实际构建使用了 Maven Central 的固定稳定版本。

第三方许可文本包含在 `app/src/main/assets/open_source_notices.txt`，可通过应用「设置 → 关于 → 开源许可」离线查看。
