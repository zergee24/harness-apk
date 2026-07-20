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

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

本地打包：

```bash
scripts/release_apk.sh test
scripts/release_apk.sh prod
scripts/release_apk.sh test --version-code 1015009
```

## Agent Builder（M4 macOS）

在仓库目录的桌面 Codex 任务中调用项目 Skill `agent-builder`，提供本地 TXT、Markdown、EPUB 或带文本层的 PDF，并说明智能体名称和版本。Skill 会先生成可审阅的构建目录，再补全人格、观点与评测资料；只有 `validate` 通过后才会输出签名 `.hbundle`。也可以直接运行确定性 CLI：

```bash
scripts/agent-builder.sh prepare --agent-id person.example --name 资料研究代理 --version 1 --output build/my-agent /path/to/books
scripts/agent-builder.sh validate build/my-agent
scripts/agent-builder.sh pack build/my-agent --output build/agent-dist
```

V2 Android 验收 fixture 只在 `build/` 中生成。以下三步必须复用同一个测试 key：

```bash
scripts/agent-builder.sh -m tools.agent_builder.tests.fixture_v2 \
  --source app/src/test/resources/agent/source.md \
  --workspace build/agent-v2-fixture \
  --dist build/agent-v2-dist --reset
scripts/agent-builder.sh validate build/agent-v2-fixture
scripts/agent-builder.sh recommend build/agent-v2-fixture \
  --key build/agent-v2-fixture/test-key.pem
scripts/agent-builder.sh pack build/agent-v2-fixture \
  --output build/agent-v2-dist \
  --key build/agent-v2-fixture/test-key.pem \
  --profile balanced
```

生成的 key、workspace、`.hagent`、`.hcorpus`、`.hsource` 和 `.hbundle` 都是临时测试产物，不进入 Git。

## Android 智能体导入

人物身份在新会话输入区选择，发送第一条消息后固定。“设置 -> 智能体包”用于导入和安装：V2 `.hbundle` 默认展示一张紧凑预览并直接安装推荐的 `balanced` 档；只有空间不足或用户主动调整时，才展示 `轻量 / 推荐 / 完整证据 / 包含原文` 四档选择。安装完成后直接回到包列表并展开状态详情，不再显示二次成功确认。

只有 `READY` 智能体可开始新对话；`WAITING_FOR_CORPUS`、`DISABLED` 和 `FAILED` 都会阻止入口。独立 `.hcorpus` 仅扩展当前版本覆盖，历史会话仍绑定原 version；`.hsource` 只用于阅读核验，不参与回答。人格运行时使用固定会话版本、可选项目上下文和本地资料证据，不启用联网补写，也不创建智能体所属项目。

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
推送到 `test` 分支会自动部署测试通道；正式通道只建议手动运行 workflow 并选择 `prod`。
`test` 和 `prod` 通道都必须配置固定签名 secrets，避免同包名覆盖安装时出现签名不一致。

版本策略：

- `versionName` 按产品版本走，例如 `0.1.15`；测试包自动显示为 `0.1.15-debug`。
- `versionCode` 按机器更新版本走，必须递增。当前基础 code 使用 `1015000` 这类格式，对应 `0.1.15`。
- GitHub Actions 的 `test` 通道默认使用 `基础 versionCode + GitHub run number`，所以同一个 `versionName` 可以重复打测试包并触发更新。
- 本地打测试包时，如需强制让手机收到更新，传 `--version-code` 或环境变量 `APK_VERSION_CODE`。
