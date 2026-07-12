# Harness APK

自用的 Android LLM 聊天 APK。第一版支持本地 Provider 配置、API Key 本机加密保存、本地多会话、图片选择入口，以及自建 APK 更新检查/下载/安装链路。

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
scripts/release_apk.sh test --version-code 1015009
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
https://www.zerg.work/harness-apk/test/update.json
https://www.zerg.work/harness-apk/test/app-debug.apk
https://www.zerg.work/harness-apk/test/chunks/app-debug.apk.part-000

https://www.zerg.work/harness-apk/prod/update.json
https://www.zerg.work/harness-apk/prod/app-release.apk
https://www.zerg.work/harness-apk/prod/chunks/app-release.apk.part-000
```

GitHub 仓库需要配置：

```text
Secrets:
ALIYUN_ACCESS_KEY_ID
ALIYUN_ACCESS_KEY_SECRET
ANDROID_TEST_KEYSTORE_BASE64
ANDROID_TEST_STORE_PASSWORD
ANDROID_TEST_KEY_ALIAS
ANDROID_TEST_KEY_PASSWORD
ANDROID_RELEASE_KEYSTORE_BASE64
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD

Variables:
OSS_BUCKET=harness--zerg
OSS_ENDPOINT=oss-ap-southeast-1.aliyuncs.com
OSS_TEST_PREFIX=harness-apk/test
OSS_PROD_PREFIX=harness-apk/prod
OSS_ACL=public-read
```

默认可以在 GitHub Actions 手动运行 `Deploy APK to OSS`，并选择 `test` 或 `prod`。
推送到 `test` 分支会自动部署测试通道；推送到 `main` 分支会自动部署正式通道。
`test` 和 `prod` 通道都必须配置固定签名 secrets，避免同包名覆盖安装时出现签名不一致。

版本策略：

- `versionName` 按产品版本走，例如 `0.1.15`；测试包自动显示为 `0.1.15-debug`。
- `versionCode` 按机器更新版本走，必须递增。当前基础 code 使用 `1015000` 这类格式，对应 `0.1.15`。
- GitHub Actions 的 `test` 通道默认使用 `基础 versionCode + GitHub run number`，所以同一个 `versionName` 可以重复打测试包并触发更新。
- 本地打测试包时，如需强制让手机收到更新，传 `--version-code` 或环境变量 `APK_VERSION_CODE`。
