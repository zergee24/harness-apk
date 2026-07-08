# Harness APK

家人自用的 Android LLM 聊天 APK。第一版支持本地 Provider 配置、API Key 本机加密保存、本地多会话、图片选择入口，以及自建 APK 更新检查/下载/安装链路。

## Build

本机需要 Android SDK。当前验证环境：

- Android Gradle Plugin 9.2.1
- Gradle Wrapper 9.6.1
- compileSdk / targetSdk 37
- Android emulator system image android-36 google_apis arm64-v8a

```bash
export ANDROID_HOME=/Users/tony/Library/Android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Emulator QA

```bash
avdmanager create avd -n harness_api36 -k "system-images;android-36;google_apis;arm64-v8a" --device "medium_phone"
emulator -avd harness_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
adb wait-for-device
./gradlew :app:installDebug :app:connectedDebugAndroidTest --console=plain
```

## Release Hosting

Manifest 和 APK 推荐放在对象存储，通过自定义 HTTPS 域名访问：

```text
https://download.example.com/harness-apk/update.json
https://download.example.com/harness-apk/releases/0.1.0/app-release.apk
```

上传 APK 后运行：

```bash
scripts/compute-sha256.sh app-release.apk
```

把 SHA-256 写入生产 `update.json`。

