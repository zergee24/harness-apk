# M1 零摩擦捕获实施计划与进度

日期：2026-08-07

实施周期：2026-08-08 至 2026-09-07

当前状态：`IN_PROGRESS`

目标分支：`test`

## 1. Source Of Truth

- 产品路线图：`docs/product-plan.md` v5 的 M1 章节。
- 完整产品与技术规格：`docs/superpowers/specs/2026-08-07-m1-zero-friction-capture-design.md`。
- 本文件只负责实施顺序、进度、验收证据和范围控制；发现冲突时以 M1 Spec 为准。

M1 只交付一个结果：用户在手机上产生意图后，可以在十几秒内通过文字、系统语音或 Android 分享，把内容送入正确的项目或会话上下文；失败、切屏和进程重建均不要求重新输入。

## 2. 当前基线

截至 2026-08-07，规格已完成，M1 功能代码尚未开始。

| 基线项 | 当前事实 | 证据 |
| --- | --- | --- |
| 分支 | 本地 `test`；M1 文档在本地提交中，尚未推送 | `git status --short --branch` |
| 数据库 | Room schema version 20 | `AppDatabase.kt` |
| 语音入口 | 设置开关控制是否显示；点击只提示“暂未接入” | `ChatScreen.kt`、`VoiceSettingsScreen.kt` |
| 输入主按钮 | 空输入显示附件，有内容显示发送，生成中显示停止 | `chatInputTrailingAction` |
| 分享入口 | 仅接收 `.hbundle`、`.hwiki` 和兼容 ZIP MIME | `AndroidManifest.xml`、`MainActivity.kt` |
| 执行上下文 | 已持久化项目提示、Wiki 精确引用、联网搜索参数 | `ChatExecutionRequestContext` |
| 草稿与附件 | 会话草稿恢复和图片私有复制已有基础能力 | `ChatSendRecoveryStore`、`QueuedAttachmentStore` |
| 搜索 | 仅有会话内搜索和 Wiki 搜索，没有跨项目全局搜索 | `ChatConversationSearch.kt`、`WikiSourceSearch.kt` |
| 上下文控件 | 项目、联网、语音、模型、身份、上下文等横向滚动 | `ChatInputBar` |

实施前必须先运行基线命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

若基线失败，先记录失败项并判断是否与 M1 有关，不得为了“跑绿”顺手修改无关代码。

## 3. 进度总览

状态只使用 `DONE / IN_PROGRESS / BLOCKED / PENDING`，每个 `DONE` 必须附测试或真机证据。

| Gate | 交付物 | 状态 | 完成证据 |
| --- | --- | --- | --- |
| G0 | Spec、范围切线、实施台账 | DONE | M1 Spec 与本文件已入库 |
| G1 | 系统语音输入、尾部单一主按钮、草稿恢复 | IN_PROGRESS | `82c6c8e`；JVM/Debug 构建通过；API 36 模拟器定向测试 4/4，通过首次权限、识别中停止按钮、旋转草稿恢复；待补真机语音文本回调 |
| G2 | Context Snapshot V2 与原子入队 | DONE | `bd36fef`；V1/V2 往返、私有附件 SHA-256、身份固定、编码失败全事务回滚与进程重读通过 |
| G3 | Android 分享、私有暂存、目的地推荐、项目导入 | DONE | `4080e3b`；URL、两图、PDF、私有哈希、超限清理、进程重建恢复与安装包优先路由通过 |
| G4 | 单一上下文条与“在其他项目继续” | DONE | `ae94d53`；320dp/字体 1.3、项目仓储不可变、草稿携带与新会话导航通过 |
| G5 | 本地全局搜索与精确深链 | DONE | `0ab9f5c`；中文 2/3-gram、删除同步、精确消息/项目定位、v20 -> v21 迁移与 50,000 消息 p95 通过 |
| G6 | 极端环境回归与 test 发布候选 | PENDING | 待补完整黄金链路和升级迁移证据 |

范围熔断：G1、G2、G3、G4 是 M1 必达。若主链路在第 4 周仍未稳定，G5 可顺延；不得牺牲语音、分享或不可变上下文快照去保全局搜索。

### 2026-08-08 G1 阶段证据

- 提交：`82c6c8e 功能：接入系统语音输入与持久草稿`。
- 自动化：`./gradlew :app:testDebugUnitTest :app:assembleDebug`，成功；`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.voice.SystemSpeechRecognizerInstrumentedTest,com.harnessapk.ui.chat.ChatVoiceInputTest,com.harnessapk.chat.ConversationDraftStoreInstrumentedTest`，API 36 模拟器 4/4 成功。
- 设备链路：全新麦克风权限下，空输入只显示“开始语音输入”；点击后直接出现系统录音授权；授权后进入识别并切为“停止语音输入”；输入 `draft123` 后旋转，文字和发送按钮保持。
- 已知限制：API 36 模拟器音频输入不可稳定提供真实语音，因此“说 -> 返回文本 -> 编辑 -> 发送”仍需授权真机补证；USB 设备 `HA2FW767` 当前为 `unauthorized`。G1 在补齐该证据前保持 `IN_PROGRESS`。
- 下一 Gate：先完成 G2 Context Snapshot V2 与原子入队，再回到授权真机补齐 G1 语音文本证据。

### 2026-08-08 G2 完成证据

- 提交：`bd36fef 功能：持久化不可变会话上下文快照`。
- 自动化：`./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功；API 36 模拟器运行 G2 定向 instrumentation 3/3 成功。
- 覆盖：旧无版本 JSON 按 V1 读取；V2 冻结项目、项目上下文 SHA-256、实际固定后的 Agent 版本、Wiki 精确版本、供应商、模型、推理强度、联网状态、附件 MIME/大小/SHA-256 和捕获时间。
- 原子性：Context Snapshot 编码异常时，身份固定、用户消息、附件引用和执行项全部回滚；私有附件副本在未落库时清理。
- 可核查性：历史用户消息提供可展开“发送上下文”，可查看精确版本与内容哈希。
- 下一 Gate：G3 Android 分享、私有暂存、目的地推荐和项目文件导入。

### 2026-08-08 G3 完成证据

- 提交：`4080e3b 功能：接入Android系统分享与私有暂存`。
- 自动化：G3 定向 JVM 测试、`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin` 成功；API 36 模拟器运行 `CaptureStagingInstrumentedTest` 4/4 成功。
- 覆盖：`.hbundle` / `.hwiki` 优先路由；文字、URL、单图、两图和普通文件分类；最多 10 项、单项 50 MiB、单次 100 MiB；进入目的地面板前私有复制和 SHA-256；源 URI 失效后仍可读取；普通 PDF 禁止进入会话并仅导入项目 `files/`。
- 设备链路：API 36 模拟器通过浏览器 URL 分享；目的地面板停留后强制停止进程并重开，URL 和目的地仍恢复；选择“新建临时会话”后只填入输入框且未自动发送。证据为 `build/m1-share-text.png`、`build/m1-share-restart.xml`、`build/m1-share-chat.png`。
- 已知限制：系统模拟器链路和进程重建已覆盖；不同厂商相册、浏览器与文件管理器的 MIME/URI 差异仍列入最终真机验收清单。
- 下一 Gate：G4 单一上下文条与“在其他项目继续”。

### 2026-08-08 G4 完成证据

- 提交：`ae94d53 优化：收敛会话上下文与项目继续交互`。
- 自动化：`./gradlew :app:testDebugUnitTest :app:assembleDebug` 成功；API 36 模拟器运行 `ConversationContextBarComposeTest` 1/1 成功。
- 覆盖：输入区删除项目、联网、模型、身份、上下文的横向 Chip 行，改为单一上下文摘要；底部面板纵向展示项目、身份、Wiki、模型、联网与上下文；空会话可更新项目，首条用户消息后仓储层拒绝原地改项目。
- 继续规则：已有消息时选择其他项目会创建新会话，沿用当前身份和已启用 Wiki 精确版本，携带未发送文字与图片草稿，不复制历史消息或 Markdown 弱关联。
- 设备链路：API 36 模拟器在输入法打开时摘要条保持单行；面板完整可滚动。证据为 `build/m1-context-bar.png`、`build/m1-context-sheet.png`。
- 下一 Gate：G5 本地全局搜索、精确深链与 Room 迁移。

### 2026-08-08 G5 完成证据

- 提交：`0ab9f5c 功能：实现本地全局搜索与精确定位`。
- 自动化：搜索与项目仓储定向 JVM 测试、`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin` 成功；API 36 模拟器在关闭 Wi-Fi 和移动数据后运行 G5 定向 instrumentation/Compose 4/4 成功。
- 索引：Room 21 新增 `local_search_documents` 与 FTS4 `unicode61` 索引；会话、用户/助手消息和 Wiki 引用由主表触发器同步写入和删除，项目名称由文件仓储同步；索引可从主数据重建，索引异常不阻断已完成的项目文件操作。
- 检索与定位：中文复用 `WikiSourceSearch` 的归一化及 CJK 2/3-gram；一个搜索输入和一个统一结果列表；消息结果携带 `conversationId + messageId` 并滚动高亮，项目结果打开对应工作台会话页。
- 迁移：`migration20To21BackfillsSearchAndPreservesExistingData` 使用真实 v20 fixture，确认旧会话、消息、`requestContextJson`、Agent、Wiki 和索引回填保持；未使用破坏性迁移。
- 性能与可达性：50,000 条消息、20 次检索 p95 为 13ms；Compose 在 320dp、字体 1.3 下结果标题和类型可见且具备点击语义；真实 App 全局搜索入口和空态证据为 `build/m1-global-search-entry.png`、`build/m1-global-search-empty.png`。
- 下一 Gate：G6 极端环境回归、六条黄金链路汇总和 `test` 发布候选。

## 4. 实施批次

### Batch 1：输入基础与不可变快照

目标：先完成“说 -> 改 -> 发 -> 可恢复”的闭环，并确保发送时使用的上下文此后不会被配置变化改写。

#### 1.1 语音纯逻辑与平台边界

建议新增：

- `app/src/main/java/com/harnessapk/voice/VoiceInputState.kt`
- `app/src/main/java/com/harnessapk/voice/SystemSpeechRecognizer.kt`
- `app/src/test/java/com/harnessapk/voice/VoiceInputStateTest.kt`
- `app/src/androidTest/java/com/harnessapk/voice/SystemSpeechRecognizerInstrumentedTest.kt`

实施要求：

- 状态机固定为 `IDLE -> REQUESTING_PERMISSION -> LISTENING -> FINALIZING -> IDLE`，并覆盖 `ERROR`、`CANCELLED`。
- partial result 只更新界面，保留识别前草稿；final result 才通过 `mergeTranscriptIntoInput` 合并并持久化草稿。
- 用户点击停止时保留最终可用文本；取消、页面离开、Activity 停止或音频焦点丢失时不得继续后台录音。
- 优先使用 `SpeechRecognizer`；不可用时降级 `RecognizerIntent`；两者都不可用时只给“此设备没有可用的系统语音识别”。
- 不保存原始音频，不引入云端 STT，不自动发送。

验收：状态转换、partial/final 合并、取消恢复、权限拒绝、系统能力缺失均有自动化测试。

#### 1.2 输入尾部主按钮收敛

主要修改：

- `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- `app/src/test/java/com/harnessapk/ui/chat/ChatUiStateTest.kt`
- `app/src/androidTest/java/com/harnessapk/ui/chat/ChatVoiceInputTest.kt`

交互必须只有一个尾部主按钮：

| 状态 | 按钮 | 行为 |
| --- | --- | --- |
| 空文本、无附件、空闲 | 麦克风 | 请求权限或开始识别 |
| 有文本或附件、空闲 | 发送 | 沿用现有发送入口 |
| 正在识别 | 停止 | 结束识别并等待 final result |
| 正在生成 | 停止 | 沿用现有停止生成 |

同时删除上方 Chip 行中的第二个语音入口。`ChatScreen` 只消费状态和回调，不能持有 `SpeechRecognizer` 的生命周期实现。

验收：首次点击麦克风可直接授权并识别；识别结果可编辑；发送仍由用户确认；旋转和拒绝权限不清空原草稿。

#### 1.3 语音设置收敛

主要修改：

- `app/src/main/java/com/harnessapk/ui/voice/VoiceSettingsScreen.kt`
- `app/src/main/java/com/harnessapk/voice/VoiceModels.kt`
- `app/src/test/java/com/harnessapk/voice/VoiceSettingsTest.kt`

界面只保留：转写语言、回复朗读开关与语速、麦克风权限状态。旧字段继续兼容读取，但 `speechInputEnabled`、Cloud Provider、自动填入、自动发送、保存音频不再作为主链路前置条件，也不再对用户展示。

验收：全新安装不进入设置即可看到麦克风；旧设置数据升级后不崩溃；任何配置都不能触发自动发送。

#### 1.4 Context Snapshot V2

主要修改：

- `app/src/main/java/com/harnessapk/chat/ChatExecutionModels.kt`
- `app/src/main/java/com/harnessapk/chat/ChatExecutionRepository.kt`
- `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`
- `app/src/test/java/com/harnessapk/chat/ChatExecutionModelsTest.kt`
- `app/src/androidTest/java/com/harnessapk/chat/ChatExecutionRepositoryInstrumentedTest.kt`

快照沿用 `ChatExecutionEntryEntity.requestContextJson`，不新增第二套上下文表。V2 至少冻结：

- `projectId`、`projectName`、`projectContextSha256`
- `agentId`、`agentVersion`
- 精确 `WikiRef(wikiId, version)` 列表
- `providerId`、`model`、`reasoningEffort`
- `webSearchEnabled`
- 附件内容哈希
- `capturedAt`

必须满足：

- JSON 带 `schemaVersion = 2`，旧无版本 JSON 仍按 V1 读取。
- 快照在点击发送时捕获一次，之后设置变化不得覆盖。
- 用户消息、附件引用、执行队列项和快照在同一 Room 事务成功或失败。
- 编码失败时阻止入队并保留草稿，不能降级为无快照发送。

验收：V1/V2 编解码、旧记录兼容、设置变化不改旧快照、事务失败无半条消息、进程重建后按原快照继续执行。

Batch 1 退出条件：无需进入设置即可完成“说 -> 改 -> 发”；权限拒绝、识别失败、旋转和进程重建不丢草稿；旧消息能证明发送时的项目、Agent/Wiki 精确版本、模型和附件范围。

### Batch 2：Android 系统分享

建议新增：

- `capture/CaptureDraftRepository.kt`
- `capture/IncomingShareParser.kt`
- `capture/CaptureStagingStore.kt`
- `ui/capture/CaptureDestinationSheet.kt`
- 对应 JVM 与 instrumentation 测试

实施顺序：先 Intent 分类和包格式优先级，再做私有暂存与哈希，随后做确定性目的地推荐，最后接项目 `files/` 导入和会话草稿。

硬约束：

- `.hbundle` / `.hwiki` 路由优先级不得回归。
- 支持 `ACTION_SEND` 文字、URL、图片、普通文件和 `ACTION_SEND_MULTIPLE`。
- 图片和文件进入目的地面板前复制到 `cacheDir/capture-staging/<draftId>/` 并计算 SHA-256。
- 单项不超过 50 MiB，单次总计不超过 100 MiB，最多 10 项；原子失败并清理本次临时文件。
- 普通文件只导入项目 `files/`，不自动塞入模型上下文。
- 文字正常路径为“确认目的地 + 发送”两次确认；文件到最近项目只需一次确认。

Batch 2 退出条件：浏览器 URL、两张相册图片、文件管理器 PDF 三条真机链路通过；外部 URI 权限失效和目的地面板杀进程后仍可恢复。

### Batch 3：单一上下文条

建议新增：

- `ui/chat/ConversationContextBar.kt`
- 上下文摘要和项目继续逻辑的 JVM/Compose 测试

实施要求：

- 把项目、Agent、Wiki、模型压缩为一条摘要；联网、上下文占用和文件变更只作为摘要状态或二级配置。
- 删除当前横向滚动的常驻 Chip 行。
- 已发过首条用户消息的会话不得原地改 `projectId`。
- 选择其他项目时执行“在其他项目继续”：创建新会话、携带当前草稿，不迁移历史消息或 Markdown 弱关联。
- Bottom Sheet 在 320dp、字体 1.3、输入法打开和系统返回手势下均不遮挡选中项。

Batch 3 退出条件：输入区不再横向滚动；一眼可读当前项目/Agent/Wiki/模型；切换项目不篡改旧会话归属。

### Batch 4：本地全局搜索与发布收口

建议新增：

- `search/LocalSearchRepository.kt`
- `search/LocalSearchTokenizer.kt`
- `ui/search/GlobalSearchScreen.kt`
- Room 的 `local_search_documents`、`local_search_fts`
- 搜索、迁移、性能和深链测试

实施要求：

- 首期只索引 `CONVERSATION | MESSAGE | MESSAGE_SOURCE | PROJECT_NAME`。
- Room FTS4 使用 `unicode61`；中文复用并抽取 `WikiSourceSearch` 的归一化和 CJK 2/3-gram 逻辑。
- 主数据写入、删除与搜索索引在同一事务同步；索引损坏可从主数据重建。
- 一个顶栏搜索入口、一个结果列表，不增加分类 Tab；点击结果精确定位消息或项目。
- 50,000 条消息下首批结果 p95 小于 200ms。

Batch 4 退出条件：搜索、升级迁移、320dp、字体 1.3、TalkBack、分屏、离线、空间不足和六条黄金链路全部留存证据；若必达链路尚未通过，按范围熔断顺延搜索。

## 5. 数据迁移纪律

- 当前 Room version 为 20。实现时重新读取 `AppDatabase.kt`；若没有并行迁移，M1 使用 20 -> 21。
- 若其他任务已占用 21，M1 必须使用下一个版本，并把中间迁移串联起来，不能改写或删除已有迁移。
- 不允许 `fallbackToDestructiveMigration`。
- 迁移测试至少覆盖：v20 真实 schema 升级、旧 `requestContextJson` 读取、新增索引表、已有会话/消息/Agent/Wiki 数据保持不变。

## 6. 验收与证据格式

每个 Batch 完成后在本文件对应 Gate 的“状态”和“完成证据”中更新，不以主观判断代替证据。至少记录：

- 执行命令及结果。
- 新增或修改的关键测试名。
- 真机或模拟器型号、Android 版本和黄金链路结果。
- 已知限制与剩余风险。
- 对应提交 SHA。

最终自动化命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

最终真机黄金链路：

1. 浏览器分享 URL -> 最近项目会话 -> 补一句 -> 发送。
2. 相册分享两张图片 -> 最近生活会话 -> 发送。
3. 文件管理器分享 PDF -> 最近项目 -> 导入 `files/`。
4. 冷启动 -> 最近项目 -> 语音输入 -> 编辑 -> 发送。
5. 分享目的地面板停留时杀进程 -> 重开 -> 内容仍在。
6. 切换 Agent/Wiki 后发送 -> 旧消息仍显示原版本范围。

## 7. Out Of Scope

- 新增会话/项目之外的第三种顶层模式。
- 收件箱、分享历史、分类搜索 Tab 或向量搜索。
- 云端 STT Provider、原始音频保存、语音自动发送。
- 把普通项目文件自动作为模型附件或通用附件 RAG。
- 自动写项目 Markdown、自动 Commit、自动 Push。
- M2 的跨设备远程运行与 M3 的项目记忆闭环。
- 版本号升级和远端推送，除非用户另行明确要求。

## 8. 首批开发指令

代码开发 Agent 收到本文件后，从 Batch 1 开始，按 TDD 完成 G1 与 G2，再进入 Batch 2。执行期间：

1. 先确认 `test` 工作区与 M1 文档提交均可见，并运行基线测试。
2. 不创建长期平行实现；沿用当前 `test` 和现有仓库边界。
3. 每个可独立验收的 Batch 只提交本批相关文件，提交信息使用中文，不推送。
4. 遇到并行数据库迁移、无法稳定复现的系统语音行为或会破坏既有发送事务的冲突时，先记录最小阻塞证据；其余可推进任务继续执行。
5. 每完成一个 Gate，立即更新本文件状态和证据，再继续下一个 Gate。
