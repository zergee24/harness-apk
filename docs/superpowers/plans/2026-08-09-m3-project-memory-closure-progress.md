# M3 项目记忆闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让当前项目 Markdown、项目消息和冻结的 Remote Run 证据可在本地检索、引用、防漂移，并让 Assistant/Remote/显式文件变更统一经过持久 Draft、Diff、Apply、Commit、Push 分步闭环。

**Architecture:** Room 23 扩展现有唯一 `local_search_fts`，所有项目检索先在 SQL 层按 `projectId` 限定；检索命中先冻结为不可变 Evidence Snapshot，再注入请求并写入 Context Snapshot V3。Android 继续是唯一 Markdown Draft/Apply 写入器；Bridge 只冻结 completion evidence v2，不新增文件同步或远程写文件命令。

**Tech Stack:** Kotlin、Room/FTS4、Coroutines、Jetpack Compose、WorkManager、Go、Codex app-server、JUnit/AndroidX Test。

---

## 1. Source of truth 与基线

- M3 产品范围：`docs/product-plan.md:218-267`。
- M3 设计：`docs/superpowers/specs/2026-08-07-m3-project-memory-closure-design.md`。
- M2 起点：`e697c1a`，分支 `codex/m2-cross-device-run`；M2 自动化完成，目标荣耀真机与真实 Relay/Mac Bridge 仍 `PENDING`。
- M1 代码 Gate 已完成，厂商/阿里云语音真机复验仍在进行；M3 禁止修改语音录制、转写、Push 接入文件。
- M3 worktree：`/Users/tony/.codex/worktrees/0e43/harness-apk`；分支 `codex/m3-project-memory-closure`。
- Android 基线：设置 `ANDROID_HOME=/Users/tony/Library/Android/sdk` 后 `:app:testDebugUnitTest :app:assembleDebug` 退出 0；首次未设置 SDK 的失败只属于隔离环境配置。
- Go 基线：Go 1.26.5 下 `go test ./...`、`go vet ./...`、Bridge/Relay build 均退出 0。

## 2. 实施前契约决策

以下是 Spec 与 HEAD 的实现差距，按现有产品边界作最小加法决策：

1. `MarkdownChangeDraftEntity` 当前强制关联 Conversation，Remote Run 无法复用。Room 23 将 `conversationId/sourceUserMessageId` 改为可空；每个新 Draft 必须有 `markdown_draft_origins`，项目会话仍保留原外键，Remote 不伪造会话。
2. 现有 Gateway 没有执行 `baselineSha256/expectedAbsent`。`MarkdownUpdateProposal` 增加这两个字段，Apply 前重读文件并比较 SHA；冲突保留 Draft，不覆盖。
3. Context Snapshot V3 是 V2 的完整超集，编码 `schemaVersion=3`；decoder 同时读 V2/V3，旧 JSON 不重写。
4. 消息结构化来源类型统一命名为 `PROJECT_SOURCES`，和既有 `WIKI_SOURCES/AGENT_SOURCES` 对齐。
5. Remote completion v2 为加法 envelope；Bridge 首次终态时生成稳定 evidence ID、内容 SHA 和 workspace locator 并持久冻结。Gap Snapshot 只返回冻结值；旧 v1 标记 `legacy/unverified`，Wire/Logical Event v1 不变。
6. `ProjectSourceRevision` 字段并入 `local_search_documents`；不创建第二份 revision 真相表。
7. 当前用户明确决定可在 Planner 前冻结为 `USER_STATED` 的 Project Message Evidence；只有 Assistant Proposal 仍不能证明决定/完成。
8. `context_fact_dedupe` 同时保存规范化 statement key、evidence hash 和 `APPLIED/DISMISSED` 状态；判重按 statement key，来源 hash 只审计，避免换来源后重复段落。
9. FTS4 不声称原生 BM25。候选使用 matchinfo/词覆盖、标题路径、权威、关联、多样性和小幅时间权重的确定性排序；默认注入阈值和权重写入黄金数据集，后续可调但不可跳过 SQL 项目边界。

## 3. Gate 总览

| Gate | 交付物 | 状态 | 退出条件 |
| --- | --- | --- | --- |
| G0 | 契约、计划、Bridge completion v2、能力协商 | AUTOMATED_PASS | Go race/vet/build、v1/v2 兼容、冻结终态、Turn 幂等和 capability fixture 均有自动化证据 |
| G1 | Room 22 -> 23、项目索引、40 Query | AUTOMATED_PASS | 全量 JVM、222 connected、迁移、项目隔离、40 Query 与两组 10k 性能均通过 |
| G2 | Retrieval/Snapshot/V3/引用防漂移 | AUTOMATED_PASS | Snapshot/V2-V3/终态引用/漂移与来源 UI 已进入全量 JVM/connected；真实文件手验仍属人工 Gate |
| G3 | Assistant/Remote/Explicit 统一 Draft/Diff/Apply | AUTOMATED_PASS | 三类 origin、持久恢复、单写 Apply、基线冲突及共享 Diff 自动化通过；真实 Git 边界仍属人工 Gate |
| G4 | UI、20 黄金链路、10k 性能、发布/回滚 | AUTOMATED_PASS / MANUAL_PENDING | 隔离脚本完整通过并清理端口；20 条确定性链路已有自动化覆盖，外部设备/Relay/Mac/Git 人工 Gate 待执行 |

状态口径：`AUTOMATED_PASS` 表示同一候选工作树的声明自动化退出条件已通过；它不替代荣耀真机、真实 Relay/Mac/Codex、TalkBack 与 Commit/Push 人工 Gate。人工 Gate 未完成时，0.4.0 仍不是可发布结论。

## 4. Task 细化

### Task 1：锁定项目检索与证据契约（G0）

**Files:**
- Create: `app/src/test/java/com/harnessapk/projectsearch/ProjectSearchContractTest.kt`
- Create: `app/src/main/java/com/harnessapk/projectsearch/ProjectSearchModels.kt`
- Create: `app/src/main/java/com/harnessapk/projectsearch/MarkdownSemanticChunker.kt`
- Create: `app/src/main/java/com/harnessapk/projectsearch/ProjectEvidenceSelector.kt`
- Create: `app/src/main/java/com/harnessapk/projectsearch/ProjectCitationVerifier.kt`
- Create: `app/src/main/java/com/harnessapk/session/ContextFactPolicy.kt`
- Test: `app/src/test/java/com/harnessapk/session/ContextFactPolicyTest.kt`

- [x] 先写测试：标题/列表/表格/代码围栏分块；每块不超过 1,200 code point；最多 6 条、8,000 字、同文件最多 2 条；决定/状态不能只依赖 `ASSISTANT_PROPOSAL`；未知 `⟦P#⟧` 被剥离并记录失败；Agent 关系记忆不能构造 Project Evidence。
- [x] 定向 JVM 首次因 API 缺失 RED。
- [x] 实现最小模型、分块、选择、引用校验和 Fact Policy。
- [x] 最终全量 JVM 已进入 `1087/1087` 绿色证据。

### Task 2：冻结 Remote completion evidence v2（G0）

**Files:**
- Modify: `remote/internal/completion/evidence.go`
- Create: `remote/internal/completion/ledger.go`
- Create: `remote/internal/completion/ledger_test.go`
- Modify: `remote/cmd/bridge/main.go`
- Modify: `app/src/main/java/com/harnessapk/remote/RemoteCompletionEvidence.kt`
- Test: `app/src/test/java/com/harnessapk/remote/RemoteCompletionEvidenceTest.kt`

- [x] Go/Android fixture 覆盖 v1 legacy 读取、v2 稳定 evidence ID/sha256/Mac locator 和冻结 Snapshot。
- [x] 定向 Go/JVM 已确认 RED 后转绿。
- [x] completion ledger 先原子落盘再发布；Snapshot 只读 ledger；ledger 缺失保持 `RECONCILING/UNKNOWN`。
- [x] Go 全量 race/vet/build 已通过；Bridge 修复与 capability 提交见第 7 节。

### Task 3：Room 23 与唯一项目 FTS（G1）

**Files:**
- Create: `app/src/main/java/com/harnessapk/storage/ProjectSearchEntities.kt`（含 `ProjectSearchDao`）
- Modify: `app/src/main/java/com/harnessapk/storage/LocalSearchEntities.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/LocalSearchDao.kt`
- Modify: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Test: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`

- [x] 真实 22 -> 23 文件迁移测试覆盖 M1/M2 数据保留、新表、nullable owner、FK、唯一约束和 `foreign_key_check`。
- [x] DAO 测试证明 SQL 层按 `projectId` 隔离。
- [x] compile/connected 定向测试经历 RED 后修复。
- [x] `local_search_documents` 与唯一 `local_search_fts` 完成加法扩展并注册迁移。
- [x] 专用 AVD 最终全量 connected `222/222`，0 skipped、0 failed。

### Task 4：索引、40 Query 与 10k 性能（G1）

**Files:**
- Create: `app/src/main/java/com/harnessapk/projectsearch/ProjectIndexCoordinator.kt`
- Create: `app/src/main/java/com/harnessapk/projectsearch/ProjectRetrievalRepository.kt`
- Create: `app/src/test/resources/projectsearch/m3-retrieval-golden.json`
- Test: `app/src/test/java/com/harnessapk/projectsearch/ProjectRetrievalGoldenTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/projectsearch/ProjectSearchDaoInstrumentedTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/projectsearch/ProjectRetrievalPerformanceInstrumentedTest.kt`
- Test: `app/src/androidTest/java/com/harnessapk/projectsearch/ProjectMessageIncrementalIndexInstrumentedTest.kt`

- [x] 40 Query fixture 已覆盖 10 决策、10 状态/待办、8 路径/标题、6 历史消息、6 No Match/跨项目。
- [x] Markdown/项目消息/冻结 Run evidence 索引已实现 2 MiB/50 MiB/dirty 边界。
- [x] 最终真实样本：JVM 10k×10 p95 `44.255 ms`、Room 10k×10 p95 `5.124 ms`，均低于 250 ms；全局消息 50k p95 `27 ms`。
- [x] 最终工作树 40 Query 为 Recall@6 `1.000`、跨项目泄漏/No Match/unexpected 注入均 `0`。

### Task 5：Evidence Snapshot、V3 和注入（G2）

**Files:**
- Create: `app/src/main/java/com/harnessapk/projectsearch/ProjectEvidenceSnapshotRepository.kt`（含 `ProjectContextAssembler`）
- Create: `app/src/main/java/com/harnessapk/chat/ProjectSourcePartWriter.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/ChatExecutionModels.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/common/AppContainer.kt`
- Test: corresponding JVM/Room tests.

- [x] V2/V3 round-trip、retry/process death 复用同一 retrieval run、Hash 发送前二次核对、Snapshot 不可变、失败记录 `FAILED` 且普通回复继续均有测试。
- [x] 在创建 assistant message 后、外部模型请求前，事务写 Retrieval Run + Evidence Snapshot；每个 token 本轮稳定。
- [x] Project Context 与 Agent/Wiki 分栏注入；无证据时不得把关系记忆当项目事实。
- [x] 终态保存 `PROJECT_SOURCES`，未知 token 降级普通文本并记录验证失败；实现已提交。

说明：G2 的确定性自动化退出项已签署；真实项目文件修改/删除后的视觉与 TalkBack 仍保留为人工 Gate。

### Task 6：来源 UI 与防漂移（G2）

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/MessageSourcesPart.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/markdown/MarkdownMessage.kt`

实施差异：没有新增独立 Source Reader 路由；统一 Snapshot Sheet 集成在 ChatScreen，正文 token、来源卡和跨会话消息均复用同一 evidence ID 打开路径，避免第二套来源状态。

- [x] Compose 测试覆盖项目/Wiki 来源、精确 token 点击、历史快照文字状态、320dp/1.3 字体和可访问名称。
- [x] Project/Wiki/Agent 分组但数据仓库分离；列表不批量重算文件 hash；正文 `⟦P#⟧` 直达统一 Snapshot Sheet。
- [x] 专用模拟器最终全量 GREEN；实现已提交。

### Task 7：统一持久 Draft、基线冲突与 Context Fact（G3）

**Files:**
- Modify: `app/src/main/java/com/harnessapk/storage/MarkdownChangeDraftEntity.kt`
- Create: `app/src/main/java/com/harnessapk/session/MarkdownDraftCoordinator.kt`
- Create: `app/src/main/java/com/harnessapk/session/MarkdownDraftApplyCoordinator.kt`
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownUpdateModels.kt`
- Modify: `app/src/main/java/com/harnessapk/session/MarkdownUpdatePlannerUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/project/ProjectWorkspaceGatewayAdapter.kt`

- [x] Assistant/Remote/Explicit 三种 origin、生活会话选项目但不改 projectId、Remote 无 synthetic conversation、空稳定内容不造文件均有测试。
- [x] Apply 基线变化/删除/新建冲突均 fail closed，保留 Draft 且不覆盖。
- [x] Planner 只接受选中结果、必要片段、Evidence、context 和相关 Markdown；Context Fact 无 Evidence、仅 Assistant Proposal、重复/撤回均拒绝。
- [x] 所有 Draft 进 Room 并可进程恢复；applicationScope、稳定 Draft ID 与 Room claim 保证离页/重复点击不分叉；实现已提交。

说明：Draft/Apply 自动化已绿色；真实 Relay/Mac 与人工 Git Commit/Push 边界仍待手验。

### Task 8：Assistant/Remote 共用一次 Diff 审核（G3）

**Files:**
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/activity/RunDetailScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/components/MarkdownDraftDiff.kt`

- [x] Compose/Controller 测试证明入口产生同一 Draft 状态与共享 Diff 组件；生活会话多一次项目选择。
- [x] 文案统一“沉淀到项目”；每个结果最多一次状态；Remote Mac 路径明确标 Mac，不进入手机索引。
- [x] Apply 后只刷新 Files/Git 和本轮白名单；Commit/Push 按钮及确认保持分离；实现已提交。

### Task 9：自动验收、发布与回滚（G4）

**Files:**
- Create: `remote/scripts/m3-automated-acceptance.sh`
- Create: `docs/superpowers/plans/2026-08-09-m3-acceptance-checklist.md`
- Create: `docs/releases/0.4.0.md`
- Modify: `remote/README.md`
- Modify: this progress ledger.

- [x] 脚本在执行任何设备动作前强制：AVD=`HarnessM3Api36`、ADB=`5041`、serial=`emulator-15672`、console/adb=`15672/15673`、`ADB_LOCAL_TRANSPORT_MAX_PORT=5553`，列表不是唯一目标立即失败；禁止 5037/裸 adb/自动 USB 选择。
- [x] 运行 JVM、assemble、全量 connected、Go race/vet/build、40 Query、20 条确定性黄金链路和 10k p95。
- [x] 自动化完成或中止都关闭 `emulator-15672` 和 ADB 5041；成功与一次失败运行后均验证 5041/15672/15673 无监听。
- [x] 发布文档已明确：Room 23 不能直接降回旧 schema 22 APK；回滚包必须读 23 并关闭 M3 feature gate；Bridge 回滚必须兼容冻结 completion ledger；不清应用数据、不删除 Journal。
- [x] 不 push、不 merge；记录人工 Gate 与 M2 -> M3 -> test 合并顺序；文档提交后再次核对工作区 clean。

最终成功日志：`/tmp/harness-m3-acceptance-20260809/final-full`。脚本将在本轮文档提交中纳入候选。

## 5. 专用 Android 环境

```bash
export ANDROID_HOME=/Users/tony/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/tony/Library/Android/sdk
export HARNESS_M3_AVD=HarnessM3Api36
export HARNESS_M3_ADB_SERVER_PORT=5041
export HARNESS_M3_EMULATOR_CONSOLE_PORT=15672
export HARNESS_M3_EMULATOR_ADB_PORT=15673
export HARNESS_M3_SERIAL=emulator-15672
export ADB_LOCAL_TRANSPORT_MAX_PORT=5553
```

任何设备命令完整形态必须为：

```bash
/Users/tony/Library/Android/sdk/platform-tools/adb -P 5041 -s emulator-15672 <command>
```

Gradle connected test 必须同时设置：

```bash
ADB_LOCAL_TRANSPORT_MAX_PORT=5553 \
ANDROID_ADB_SERVER_PORT=5041 \
ANDROID_SERIAL=emulator-15672 \
./gradlew :app:connectedDebugAndroidTest --console=plain
```

## 6. 完整自动化与人工 Gate

自动化最终入口：`remote/scripts/m3-automated-acceptance.sh`。自动化不能替代：目标荣耀真机的 Push/OEM 后台/锁屏、真实 Relay + Mac Bridge + 当前 Codex app-server、显式 Git Fetch/受控导入、Commit/Push 非快进处置、TalkBack 人工听读。

人工第一批顺序：M3 项目检索/回跳 -> 文件变化快照 -> Assistant 沉淀 -> Remote Completion 沉淀 -> Commit -> 独立 Push 确认。需要真机时由用户提供明确 serial，另启独立 ADB server；禁止自动选择 USB 设备。

## 7. 执行证据台账

### 2026-08-09 基线与 G0/G1

- 分支起点：`e697c1a`；独立分支：`codex/m3-project-memory-closure`。
- Android 基线：`ANDROID_HOME=/Users/tony/Library/Android/sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`，退出 0，耗时 3m28s。
- Go 基线：Go 1.26.5，`go test ./...`、`go vet ./...`、Relay/Bridge build 全部退出 0。
- 项目检索契约 TDD：定向 JVM 首次因 API 缺失 RED；实现分块、选择、引用与 Fact Policy 后同命令退出 0。
- 专用设备：`HarnessM3Api36`，ADB `5041`，serial `emulator-15672`，console/adb `15672/15673`；隔离校验只看到该设备。
- Room 22 -> 23：首跑因新增 JUnit 方法被推断为非 `void` 而 RED，修正测试夹具后迁移测试 1/1 退出 0；增加 SQL 项目隔离后迁移+隔离 2/2 退出 0。日志：`/tmp/harness-m3-acceptance-20260809/migration-22-23-rerun.log`、`/tmp/harness-m3-acceptance-20260809/g1-room-project-isolation.log`。
- Bridge 首提交：`e0a8e65`；随后经独立复审连续修复：`71f6b2f`（ledger miss/瞬时失败 fail closed）、`8ddfc70`（持久终态观察、journal 补发、route 回填、Git `UNVERIFIED`）、`65c9b36`（legacy `turn.start` 持久幂等和 UNKNOWN 对账）、`6e918fe`（`host.status` 能力协商）。
- 最终候选代码提交：`608ffd7`（Android/Room/统一沉淀）与 `c99837f`（v23 本地搜索性能 fixture）；Bridge/G0 提交为 `e0a8e65`、`71f6b2f`、`8ddfc70`、`65c9b36`、`6e918fe`，G1 检索提交为 `f513194`。
- 最终 JVM/assemble：`1087/1087`，0 failures、0 errors；40 Query 的 `Recall@6=1.000`、跨项目泄漏 `0`、No Match 注入 `0`、unexpected 注入 `0`；JVM 10k×10 p95 `44.255 ms`。
- 最终 connected：专用 `HarnessM3Api36`、ADB `5041`、serial `emulator-15672`，`222/222`、0 skipped、0 failed；Room FTS 10k×10 p95 `5.124 ms`，全局消息 50k p95 `27 ms`，Remote timeline 10k p95 `10 ms`。
- 最终 Go：`92` 个 pass test/subtest event、11 个 package；`go test -race ./...`、`go vet ./...`、`go build ./cmd/relay ./cmd/bridge` 均退出 0。
- 完整入口：`remote/scripts/m3-automated-acceptance.sh`。日志 `/tmp/harness-m3-acceptance-20260809/final-full`；`android-jvm-assemble.log`、`android-connected.log`、`go-race.log`、`go-vet.log`、`go-build.log` 的 SHA-256 已本地记录。
- 首次完整运行在既有 50k 性能 fixture 暴露 v23 NOT NULL 列缺失，222 项中 1 项失败；按 RED 证据补齐 fixture 后定向 `2/2`，第二次完整运行 `222/222`。两次运行的 trap 均关闭 AVD/ADB，最终 `5041/15672/15673` 均无监听。
- 自动化已完成；尚未完成的只剩验收台账列出的荣耀真机、真实 Relay/Mac/Codex、TalkBack 和真实 Commit/Push 人工 Gate。

### 2026-08-15 test 集成回归：大会话懒续聊

- 真机问题：未加载的 403 MiB 持久会话在手机发消息时先长期停留“发送中”；直接 `thread/resume` 会触发高 CPU 全量恢复，临时拒绝大会话又错误缩小了产品边界。
- 最终契约：常规会话保持原 threadId 安全恢复；超过直接恢复边界时，Bridge 只分页读取最近 8 个 Turn 的 summary，提取用户/Codex 文本并限制为 24 KiB，以 `untrusted additionalContext` 交给同 cwd 新 Thread。Bridge 持久记录物理 Thread 链，列表折叠为沿用原标题的单一逻辑会话；Android 自动选中续聊尾部并显示“大会话续聊”说明，上翻到边界时才跨 Thread 懒加载原历史，实际用户输入不会混入内部 handoff。
- TDD：Bridge 大会话用例先因旧的明确拒绝 RED，Android 用例先因仍停留原 Thread RED；实现后直接恢复/懒续聊/稳定 command identity/新 route/自动切换全部 GREEN。异步 summary 测试暴露一次 TempDir 清理竞态，改为等待响应状态后定向 race 连续 10 轮通过。
- 最终统一自动化：JVM `1142/1142`、connected `261/261`、0 skipped/failed；Go `118` pass test/subtest event、11 package，race/vet/build 通过。专用环境 `HarnessM3Api36`、ADB `5041`、serial `emulator-15672`，结束后 `5041/15672/15673` 均无监听。日志：`/tmp/harness-stuck-sending-20260815/m3-lazy-continuation` 与 `/tmp/harness-lazy-logical-go.json`。
