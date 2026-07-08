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

本地打包：

```bash
scripts/release_apk.sh test
scripts/release_apk.sh prod
```

## Emulator QA

```bash
avdmanager create avd -n harness_api36 -k "system-images;android-36;google_apis;arm64-v8a" --device "medium_phone"
emulator -avd harness_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
adb wait-for-device
./gradlew :app:installDebug :app:connectedDebugAndroidTest --console=plain
```

## Release Hosting

Manifest、APK 完整包和 APK 分片由 GitHub Actions 或本地打包机生成后上传到阿里云 OSS。
测试包和正式包使用独立通道：

```text
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/update.json
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/app-debug.apk
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/test/chunks/app-debug.apk.part-000

https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/prod/update.json
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/prod/app-release.apk
https://harness-zerg.oss-cn-hangzhou.aliyuncs.com/harness-apk/prod/chunks/app-release.apk.part-000
```

GitHub 仓库需要配置：

```text
Secrets:
ALIYUN_ACCESS_KEY_ID
ALIYUN_ACCESS_KEY_SECRET
ANDROID_RELEASE_KEYSTORE_BASE64
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD

Variables:
OSS_BUCKET=harness-zerg
OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
OSS_TEST_PREFIX=harness-apk/test
OSS_PROD_PREFIX=harness-apk/prod
OSS_ACL=public-read
ENABLE_OSS_DEPLOY_ON_PUSH=true
```

默认可以在 GitHub Actions 手动运行 `Deploy APK to OSS`，并选择 `test` 或 `prod`。
推送自动部署只建议用于测试通道；如果要开启，把 `ENABLE_OSS_DEPLOY_ON_PUSH` 设为 `true`。
`prod` 通道必须配置正式签名 secrets，否则不会上传未签名 APK。
