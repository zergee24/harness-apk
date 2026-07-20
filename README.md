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
该验收是离线 reader/repository/assembler 集成测试：验证送入模型的 grounded 输入和稳定来源元数据，不调用 Provider，也不把 assembler context 记作模型答案。

## Android 智能体导入

人物身份在新会话输入区选择，发送第一条消息后固定。“设置 -> 智能体包”用于导入和安装：V2 `.hbundle` 是按 profile 生成的 convenience bundle，只有当前 manifest 实际包含全部子包的档位可以选择；例如 `balanced.hbundle` 不能直接切换为未随包提供的 `complete` 或 `source`。默认展示一张紧凑预览并直接安装推荐的 `balanced` 档；只有空间不足或用户主动调整时，才展示 `轻量 / 推荐 / 完整证据 / 包含原文` 四档选择。安装完成后直接回到包列表并展开状态详情，不再显示二次成功确认。

只有 `READY` 智能体可开始新对话；`WAITING_FOR_CORPUS`、`DISABLED` 和 `FAILED` 都会阻止入口。独立 `.hcorpus` 仅扩展当前版本覆盖，历史会话仍绑定原 version；`.hsource` 只用于阅读核验，不参与回答。人格运行时使用固定会话版本、可选项目上下文和本地资料证据，不启用联网补写，也不创建智能体所属项目。

## 人物关系记忆

关系记忆归属于人物身份，不归属于单个会话或项目，所以同一人物可在日常会话和项目会话之间自然承接称呼、稳定偏好、共同经历和关系变化。项目仍是独立上下文：项目目标、文件、决定和业务事实不会写入人物关系记忆，也不会跟随人物进入另一个项目。

应用会在每 10 个成功问答回合以及离开会话时，在后台尝试从用户明确表达中提取关系记忆。提取失败不会阻断聊天，也不会显示逐轮确认。会话中的“人物资料 -> 关系记忆”是唯一管理入口，可查看精确来源、编辑、删除或清空当前人物的全部关系记忆；清空后，下一轮请求会重新读取空列表，不再注入旧内容，但不会删除原会话的可见历史或压缩摘要。关系记忆、会话和导入资料均保存在本机。

### 离线 Fixture 回归

离线人格回归只评估可验证信号，不调用 Provider：

```bash
scripts/agent-builder.sh benchmark-score \
  tools/agent_builder/tests/fixtures/persona-regression.jsonl \
  --responses tools/agent_builder/tests/fixtures/persona-regression-passing-responses.jsonl \
  --output build/benchmark/scorer-contract-report.json

scripts/agent-builder.sh benchmark-blind prepare \
  --v1 build/benchmark/v1-responses.jsonl \
  --v2 build/benchmark/v2-responses.jsonl \
  --output build/benchmark/blind
scripts/agent-builder.sh benchmark-blind score \
  --pairs build/benchmark/blind/pairs.jsonl \
  --answer-key build/benchmark/blind/answer-key.json \
  --choices build/benchmark/blind/choices.jsonl \
  --output build/benchmark/report.json
```

仓库内的 passing fixture 只证明 scorer 和 CLI 契约，不代表真实人物质量。

### 真实 Provider 关系记忆冒烟

前置条件是本机 Debug App 已配置可用 Provider/模型，并安装一个 `READY` 人物包。只使用无敏感信息的测试表达，Provider key、请求响应和真实会话不得写入仓库。

1. 新建该人物的日常会话，明确表达称呼、稳定偏好或共同经历；完成 10 个成功问答回合，或离开会话触发后台提取。
2. 从“人物资料 -> 关系记忆”确认新记忆、精确来源和人物归属。
3. 分别新建同一人物的日常会话和项目会话，确认称呼或关系可承接，同时项目目标、文件和决定不会进入另一会话的关系记忆。
4. 清空该人物的关系记忆并确认列表为空；再新建一个无历史、无项目的同人物会话发送下一轮，确认旧内容不再注入。

当前仓库环境没有可用的真实 Provider 配置，因此这项冒烟验收为 blocked，不能由 fixture 结果替代。

### 真实 V1/V2 盲测

真实 V1/V2 验收必须使用相同 Provider、模型、温度和上下文生成 `build/benchmark/v1-responses.jsonl` 与 `v2-responses.jsonl`，再由看不到 answer key 的评审者完成 choices。真实回答、选择和私钥只放在默认不提交的 `build/benchmark/`；当前缺少可比真实 responses 和人工 choices，因此不报告真实胜率。

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

- `versionName` 按产品版本走，例如 `0.2.0`；测试包自动显示为 `0.2.0-debug`。
- `versionCode` 按机器更新版本走，必须递增。当前基础 code 使用 `2000000` 这类格式，对应 `0.2.0`。
- GitHub Actions 的 `test` / `prod` 通道在未显式传 `version_code` 时，默认使用 `基础 versionCode + GitHub run number`，所以同一个 `versionName` 可以重复打包并触发更新。
- 本地打测试包时，如需强制让手机收到更新，传 `--version-code` 或环境变量 `APK_VERSION_CODE`。
