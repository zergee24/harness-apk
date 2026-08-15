# M4：Mac Bridge 多后端（Codex + DeepSeek Harness）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Mac Bridge 从"独占一个 Codex app-server"升级为"一台 Mac 同时挂多个后端（Codex、DeepSeek Harness，架构上支持任意 JSON-RPC/JSON-stream CLI 后端），同一部手机通过现有单条设备连接同时控制全部后端，各后端独立会话、独立任务、互不干扰、分别满足 M2 的恢复语义"。

**详细规格：`docs/superpowers/specs/2026-08-15-m4-multi-backend-bridge-design.md`**（协议契约、backend 接口、状态升级、Android 数据模型与 UI、验收标准以 spec 为准）。

**用户方向（2026-08-15 确认）：**

1. "同一个移动设备同时连接" = 同一台手机 ↔ 一台 Mac 上的多个后端，可并行控制。
2. 第一期后端 = Codex + DeepSeek Harness（dsh）。
3. 架构走"通用后端适配器"路线（等价于选项 3：抽象适配器接口，非 Codex 后端作为第一验证对象），不做逐 CLI 手写胶水。
4. **D1 已定案：DSH 集成走路线 A（dsh canonical profile，`dsh --profile appserver` 讲与 codex app-server 相同的 stdio JSON-RPC）**；路线 B/C 仅作为 spike 失败时的备选记录。
5. **D2 不适用**：不要求按产品计划"三月熔断"替换现有项，本里程碑独立排期。

**Architecture 总览（目标态）：**

```text
Harness APK <-- HTTPS/WSS --> Aliyun Relay <-- WSS --> Mac Bridge
                                                       |-- backend: codex <-- stdio --> codex app-server
                                                       |-- backend: dsh   <-- stdio --> dsh appserver profile
手机单条 WS 连接不变；backend 只是密文内 Command/Event 的路由维度
```

**Tech Stack:** Go 1.23（Bridge/Relay）、Kotlin/Compose/Room/OkHttp（Android）、Codex app-server JSON-RPC（Codex 侧）、Node/Cordis 插件（DSH 侧，路线 A：`dsh --profile appserver`）、AES-256-GCM Wire V1、Aliyun Push。

---

日期：2026-08-15

实施周期：G0 已完成（2026-08-15）；G1 起待排期

实施分支：`codex/m4-multi-backend-bridge`（从 `test` 切出；合入目标 `test`，不自动合并、不推送）

当前状态：G0-G4 DONE；已合入 test（e0bab5e）并推送；G5（运维与文档）待办

## 1. Source Of Truth 与范围纪律

按优先级使用：

1. 用户本次明确方向（本文件 Goal）与 `docs/product-plan.md` 的熔断规则。
2. `docs/superpowers/specs/2026-08-07-m2-cross-device-run-design.md` 与 M2 实施计划（M2 是前置，本文不重述其恢复语义）。
3. 本文件：把多后端需要落地的协议、路由、生命周期、验收收敛为可执行 Gate。
4. 当前 `test` / `codex/m2-cross-device-run` 的 Android、Relay、Bridge、Codex app-server 实现与 `remote/README.md`。

冲突处理：**不改变 Wire V1 传输层（Relay 零改动）**；不改变"项目是唯一长期归属""不自动写文件/Commit/Push""不新增任务 Tab"等既有不可协商原则。多后端是新增能力方向，按用户确认**不要求替换现有里程碑工作量**（D2 不适用），但实施仍只在独立 worktree/分支进行，不干扰 M1/M2/M3 已合入 `test` 的代码。

并行隔离硬约束沿用 M2：独立 worktree/分支、独立 AVD、独占 emulator/adb 端口；DSH 后端验证在 Mac 本机进行，不动手机真机的 M2/M3 验收环境。

## 2. 研究结论（现状盘点）

### 2.1 传输与路由现状（main 分支）

- `remote/`：Go 模块，`cmd/relay`（阿里云 Docker 部署）与 `cmd/bridge`（Mac LaunchAgent）。
- Bridge 启动**一个** `codex app-server --listen stdio://`，`threadOwners` 内存路由（一线程一设备），Bridge 重启即丢失。
- Wire V1：AES-256-GCM 端到端加密，Relay 只转发密文 + PushKind，**Relay 看不到 backend 维度**——这是多后端可以零 Relay 改动的前提。
- Android `RemoteProfileStore` 是单 profile（relayUrl/hostId/deviceId/token/secret），设备侧只有一条 WS。

### 2.2 M2/M3 已合入 `test`（2026-08-15 核实）

- **基线事实**：`test`（`b356a98`）包含 `codex/m2-cross-device-run`（`e697c1a`）与 `codex/m3-project-memory-closure`（`95f115d`）全部提交（merge-base 验证均为祖先），并已推送 `origin/test`；`main`（`29ed3e1`）不包含 M2/M3。用户在用的测试包即 `test` 线。
- M2 关键产出（在 `test` 上）：`internal/appserver`（Codex app-server JSON-RPC client + 契约校验）、`internal/run`（RouteStore、Coordinator、审批账本）、`internal/journal`（Logical Event 加密重放）、`internal/commandcache`（幂等账本）、`internal/workspace`（绑定/基线）、`internal/completion`（完成证据）；Android 端 Run/Binding/Outbox/Sync 持久化与多设备安全配对等后续修复。
- **结论：M2 已经完成"手机↔Mac 多设备 + 路由账本 + 恢复语义"的全部地基，且明确把 Codex 协议适配隔离在 `internal/appserver`；多后端正好落在该包之上。** 现状仍是单后端：`runServe` 只拉起一个 app-server，`host.status` 不携带能力列表（Android 端 `remoteFeatureAvailability` 的能力集合目前是硬编码的）。

### 2.3 DeepSeek Harness（dsh）接入面探明（2026-08-15 本机调研）

本机安装：`@deepseek-ai/dsh@0.1.0-rc.6`（npm 全局，`dsh` CLI）。

- dsh 是 Cordis 插件栈：profile（web / tui / headless）是插件补丁层的组合；`dsh plugin add` 可加插件；`$DSH_HOME`（默认 `~/.dsh`）保存会话等状态。
- `dsh --profile headless "task"`：**一次性**任务 → 打印最终文本后退出；内部 `agents.create` + `followup` + `whenIdle`，会话经 `sessions.flush` 落盘（有 SessionId，可扩展 resume）。
- **没有** codex 式 `app-server --listen stdio://` JSON-RPC；`dsh web` 的 API 是内部 Typert Remote 协议（HTTP/WS + browser-trust 栅栏），不是公开后端接口，不应作为 Bridge 的集成面。
- agent 事件流存在（`assistant/message`、`turn/end` 等）；**未发现**与 Codex 等价的 `requestApproval` 审批事件（dsh 的执行权限模型以 sandbox 为主，bundle/sandbox 相关包里有 approval 字样，需要 G0 spike 确认是否可映射）。

**DSH 适配路线（D1 已定案 = 路线 A）：**

| 路线 | 做法 | 状态 |
| --- | --- | --- |
| A. dsh canonical profile（**已选定**） | 新增 `dsh --profile appserver --listen stdio://`（Cordis 插件），实现与 codex app-server 相同的 JSON-RPC 面（thread/list、thread/read、thread/start、turn/start、turn/steer、turn/interrupt、事件流；审批按能力可选） | Bridge 的通用 stdio 后端与 Codex 完全共用；M2 的 journal/routes/证据全复用；能力协商自然。G0 spike 锁定实现细节 |
| B. Bridge 侧 headless 适配 | Go adapter 每次 turn 起 `dsh --profile headless`，解析最终文本；会话 resume 需 DSH 侧小补丁 | 仅作 A 失败的备选；无流式、无 item 级时间线、审批/中断弱 |
| C. dsh web API 适配 | Bridge 驱动本机 `dsh web` 的 Typert Remote API | 已排除：内部协议不稳定 + browser-trust 栅栏 |

## 3. 目标架构与关键机制

### 3.1 Bridge 侧：`internal/backend` 后端抽象

新增 `remote/internal/backend`，定义桥路由层唯一依赖的接口：

```go
type Backend interface {
    ID() string                       // "codex" | "dsh"
    Capabilities() Capabilities       // 线程/审批/用户输入/流式等
    ProcessEpoch() string             // 进程代际，审批 STALE 判定沿用 M2
    ListThreads(ctx, limit) (…)
    ReadThread(ctx, threadID) (…)
    StartThread(ctx, cwd) (…)
    StartTurn(ctx, threadID, text) (…)
    SteerTurn(ctx, threadID, expectedTurnID, text) (…)
    InterruptTurn(ctx, threadID, turnID) (…)
    RespondApproval(ctx, ref, decision) (…)
    Notifications() <-chan BackendEvent // 统一事件流（item/turn/approval/completion）
    Close() error
}
```

- `BackendEvent` 是归一化事件（方法名、params、线程身份），`handleAppServer` 的翻译逻辑（approval/时间线/完成证据）改为消费 `BackendEvent`，与具体协议解耦。
- `codexBackend`：包装现有 `internal/appserver` client，行为不变。
- `dshBackend`：按 G0 结论实现（路线 A：对接 `dsh --profile appserver`；路线 B：headless 子进程池 + 会话恢复）。
- `serve` 增加可重复的 `--backend` 参数（如 `--backend codex`、`--backend dsh --dsh-profile appserver`），每个后端一个受监督子进程：**独立启动/重启、独立 epoch、一个后端崩溃不影响其他后端**；`host.status` 返回后端列表。

### 3.2 协议：Wire V1 不动，密文内加 backend 维度

- `protocol.Command`、`protocol.Event` 各加 `BackendID string json:"backendId,omitempty"`。
- `host.status` 事件的 payload 升级为 `{online, backends:[{id,name,capabilities:[...]}]}`；新增 `backend.list` 命令（或由 host.status 携带，二选一，G0 锁定）。
- **Relay 零改动**（只转发密文）；旧版 Android（不认识 backendId）按空 backendId 路由到默认后端（codex），保持向后兼容；旧版 Bridge 与新手机：`backend.list` 返回 codex 单后端。
- Logical Event / Route / Approval 账本增加 `backendId` 字段；`routes.json` 的 Route 键从 `runID` 改为 `(backendID, runID)`，防止跨后端身份串扰。

### 3.3 Android 侧：单连接、多后端状态

- `RemoteProfile` 不变（仍是一个 host 的配对）；新增 per-backend 状态：`RemoteBackend(id, name, capabilities)` 列表、当前选中后端、按后端分组的线程列表。
- `RemoteRunRepository` / Room Run 表增加 `backendId` 列；Outbox/Journal 游标仍按 `(hostId, deviceId)`，事件按 backendId 过滤/分组。
- UI：Codex Remote 屏幕加后端切换（chips 或 Tab）；Run 卡片、通知标题带后端名；**能力降级**：DSH 无审批时手机端不出现审批入口（沿用 M2 的 `remoteFeatureAvailability` 模式，能力来源改为后端上报）。
- 同一手机并行的定义：**单条 WS 连接内多个 backend 的 run 同时 in-flight**（不是多 socket）。

### 3.4 依赖与前置

- **基线 = `test`（`b356a98`）**：M2/M3 已合入并推送，路由账本、journal、outbox、appserver 适配层都在 `test` 上；`main` 落后，本里程碑不依赖 `main` 进度。
- M2 遗留的 `requestUserInput` 误分类、能力硬编码等问题在本里程碑内一并修正（带 backendId 的能力上报）。

## 4. 关键决策（已确认项与实施默认）

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | DSH 集成路线 | **已确认：路线 A**（dsh canonical profile），G0 spike 只做 A 的实现细节锁定 |
| D2 | 产品计划熔断 | **不适用**（用户已免除替换要求） |
| D3 | backend 标识与默认 | `codex` / `dsh`；空 backendId 恒等于默认后端（codex），向后兼容 |
| D4 | 审批能力边界 | DSH 首版若无可映射审批事件，手机端对该后端隐藏审批入口并正确显示"等待 Mac"状态，不伪造审批 |
| D5 | 工作区绑定 | Binding 保持 cwd 维度、按 backend 隔离（同一 cwd 可同时被 codex 与 dsh 绑定为不同 Binding） |

## 5. Gate 计划

实施只在 `.worktrees/m4-multi-backend-bridge` 与 `codex/m4-multi-backend-bridge` 进行；每个提交显式 `git add` 本 Gate 文件，禁止 `git add .`。

### G0：契约锁定与 DSH spike — **DONE（2026-08-15）**

- [x] 协议 diff：`Command/Event` 增加 `backendId`；`host.status` payload 加性升级（`schemaVersion:1` + 顶层 `capabilities` 保留 + 新增 `backends` 数组）；能力名清单按现有能力名 + 新增 `approvals.v1` / `user-input.v1`（见 spec 5.3）。
- [x] Go 契约测试（`internal/protocol`，8 项全绿：backendId roundtrip、旧 payload 空值、Event roundtrip、host.status backends 与 legacy 兼容）+ Android 解析单测（`RemoteBackendContractTest`，7 项全绿；全量 `:app:testDebugUnitTest` 无回归）。
- [x] DSH spike（路线 A）：`dsh --profile appserver --listen stdio://` 最小插件（`remote/spike/dsh-appserver/`）端到端跑通真实模型回合——`initialize → thread/start → turn/start →（流式通知 turn/started / item/agentMessage/delta / turn/completed）→ thread/read → thread/list`。映射结论与部署机制见 spike README 与 spec 7.2。
- [x] 台账：D1 路线 A 实现细节锁定——thread/start→`agents.create`、turn/start/steer→`followup`+`whenIdle`、事件流→轮询 `session.events`（G2 换 observer）、`thread/read` 的 user/message 事件形状 G2 锁定、interrupt 无公开 API（G2 探索，否则降级）、审批/用户输入无等价事件（dsh 不声明 `approvals.v1`/`user-input.v1`，D4 降级生效）。

**G0 关键决策记录**：host.status 能力上报必须加性（保留顶层 `capabilities` = 默认后端 codex 的能力），否则旧版 Android（依赖 `parseRemoteHostCapabilities`）会因 schemaVersion/字段变化退化——旧 APK ↔ 新 Bridge 兼容由此保证。

### G1：Bridge 后端抽象与多进程生命周期 — **DONE（2026-08-15）**

- [x] `internal/backend` 接口 + `Message`（BackendEvent）归一化；`codexBackend`（`backend.StartCodex`）迁移，原 appserver client 行为不变；`Fake` 测试后端（脚本化响应/事件/崩溃）。
- [x] `serve --backend <id>|<id>=<executable>`（可重复）多后端启动/监督/重启隔离（每后端独立 goroutine + 退避 + 独立 epoch）；就绪等待 60s 上限不卡死 relay 循环；`host.status` 上报 backends 列表，顶层 `capabilities` 保持 = 默认后端（codex）能力。
- [x] Route/Approval/LogicalEvent 增加 `backendId`；`routes.json` schema v2（复合键 `(backendID, runID)` + 每后端 epoch 表），v1 旧文件加载时自动迁移；旧审批只在所属后端 epoch 变化时置 STALE。
- [x] 单测：`TestSuperviseBackendRestartsCrashedBackendAndLeavesOthersAlive`（崩溃重启 + 他端不受扰）、`TestBeginProcessEpochScopedPerBackend`（epoch 隔离）、`TestExecuteCommandRoutesByBackendID` / `TestRouteForParamsScopesEventsToOwningBackend`（双后端路由不串扰）、`TestLegacySchemaV1RoutesMigrateToBackendScopedKeys`（迁移兼容）；全量 `go vet` + `go test ./...` 12 包全绿。

**G1 关键决策记录**：Coordinator 层本就依赖 `AppServerCaller` 接口，后端抽象零改动接入；`Backend` 接口的 `Capabilities() []string` 直接采用既有能力名字符串（spec §6.1 的 bool 结构体落为字符串集合，wire 与 Android 门控均以字符串为准）；`sendEvent` 保持无 backendId，新增 `sendBackendEvent` 只在需要归属的事件（后端事件/错误）上打标。

### G2：DSH 后端 v1 — **DONE（2026-08-15）**

- [x] 正式插件 `remote/dsh/appserver/`（dsh-appserver v0.2.0，取代 G0 spike）：`thread/start`、`turn/start/steer`（多轮）、`thread/read`、`thread/turns/list`（移动端分页摘要视图）、`thread/list`（内存 + `~/.dsh/sessions` 持久化枚举）；事件流对齐 codex 方法名（turn/started、item/agentMessage/delta、turn/completed）；**user/message 形状修正**（dsh 的 user/message data 即消息本体，assistant/message 才是 data.message；空文本条目过滤）。
- [x] 持久化恢复：通过 dsh 树内 `sessionPersistence` 服务 `loadStored`（多帧 zstd、格式版本、torn 写全由 dsh 处理）；**跨进程验证**：新进程 thread/list 枚举旧线程、thread/read 从磁盘日志恢复出用户文本。
- [x] 安装脚本 `remote/dsh/install-appserver.sh`（幂等：profile 初始化 + pnpm 依赖（registry 覆盖 + 代理）+ 插件复制 + patch 层）；`remote/README.md` 新增 dsh 后端章节。
- [x] bridge 接入：`serve --backend dsh`（bare id 注册表：exec `dsh` + `--profile appserver --listen stdio://` + `backend.DSHCapabilities()` = canonical 能力减 approvals.v1/user-input.v1）；`TestParseBackendSpecsKnownDSH` 等单测；**真实 dsh 进程冒烟** `TestDSHBackendSmoke`（initialize + thread/start + 能力断言，无 dsh 自动 skip）。
- [x] 能力降级生效：dsh 不声明 `approvals.v1`/`user-input.v1`（D4）；`turn/interrupt` v1 返回 unsupported（任务在 Mac 继续跑），记为 G2.5 探索项。
- [x] 验证证据：`node client.mjs` 12 项断言全过（含持久化探针）；全量 `go vet` + `go test ./...` 12 包全绿。

**G2 关键决策记录**：spike 用轮询 `session.events` 流式；正式版沿用（G2.5 可换 session/event observer 降延迟）。`StartCodex` 的 args 语义改为"spec.Args 非空时完全自持"，否则 dsh 会被拼上 codex 的 `app-server` 参数。

### G3：Android 多后端 UI 与路由 — **DONE（2026-08-15）**

- [x] `host.status` 的 backends 解析与缓存（`RemoteUiState.backends`）；旧 Bridge 无 backends → 单 codex 回退（`fallbackRemoteBackends`）；选中后端失效自动回退 codex。
- [x] 命令统一注入 `backendId`（`injectBackendId`，outbox 重放 payload 已带 backendId 则保留）；事件按 backendId 过滤（codex.event/approval.request/error）；逻辑事件解析 backendId。
- [x] Room：`remote_runs`/`project_remote_bindings` 加 `backendId` 列（MIGRATION_23_24，默认 codex），binding 唯一索引改 `(projectId, backendId)`（D5：同一项目可分别绑定 codex/dsh 工作区）；`RemoteRunLauncher` 落库带 backendId；删除项目清全部后端绑定。
- [x] 后端切换 UI：RemoteScreen 线程列表顶部 `BackendSwitcher`（多后端时显示 chips）；切换后清空视图并重拉该后端线程列表；能力集合按选中后端计算（`canStartM2Run` 等门控随之降级）。
- [x] 活动页按后端标注：`RemoteActivityRun.backendId` 透传，非 codex 的 run 标题带后端名（如"项目 · DeepSeek Harness"）。
- [x] JVM 单测：`RemoteBackendSelectionTest` 6 项（injectBackendId/回退/选中校准/逻辑事件 backendId 解析）+ G0 契约测试；全量 `:app:testDebugUnitTest` 全绿，`:app:compileDebugKotlin` 通过。
- [ ] **Instrumented 双后端并行测试延期至 G6**：需要模拟器/真机 + 真实 relay + 双后端 Bridge 环境，与 G6 故障矩阵一并验收（真机人工项已有）。

**G3 关键决策记录**：绑定/运行按 `(projectId, backendId)` 隔离（D5 落地）；snapshot 恢复的旧 run（无 backendId）按 codex 展示；通知标题带后端名但点击目标不变（按 runId 精确定位）。

### G4：并行与恢复语义 — **DONE（2026-08-15）**

- [x] 双后端并行/路由不串扰（G1 已落地）：`TestExecuteCommandRoutesByBackendID`、`TestRouteForParamsScopesEventsToOwningBackend`、`TestByThreadBackendScopesRoutes`。
- [x] 单后端崩溃后该端 run 置 RECONCILING、他端照常：`TestSnapshotForRouteReconcilesWhenBackendUnavailable`（dsh 后端消失 → run-dsh RECONCILING，codex 后端正常回 RUNNING）。
- [x] Snapshot/对账带 backendId 回归：`TestRunSnapshotPayloadCarriesBackendIDPerRunAndApproval`（payload 每 run/approval 带 backendId）；journal 重放保留 `LogicalEvent.BackendID`（`TestReplayPreservesBackendID`）；Android `RemoteRunSnapshot`/`RemoteApprovalSnapshot` 解析 backendId（`RemoteSyncSnapshotBackendTest` 2 项，旧 payload 默认 codex）。
- [x] 全量回归：Go 12 包 `go vet`+`go test` 全绿；`:app:testDebugUnitTest` 全绿。
- [ ] **黄金链路与四类故障矩阵（断网 10 分钟、进程重建、Bridge 重启、单后端崩溃）的 instrumented/真机验收归入 G6**（需要双后端 Bridge + 真机/模拟器 + 真实 relay 环境，与 G6 故障矩阵合并执行）。

**G4 关键决策记录**：`sendRunSnapshot` 的 payload 构造抽为纯函数 `runSnapshotPayload` 以便契约测试；快照对账只 reconcile 本地已有 run（不凭空创建），backendId 以 run.start 落库值为准。

### G5：运维与文档

- [ ] LaunchAgent 示例（多后端参数）、`remote/README.md` 多后端章节、升级/回滚说明。
- [ ] 日志按后端标识；状态目录迁移说明（v2 → v3）。

### G6：验收与回归

- [ ] 自动化：Go（`go test ./...`、`go vet ./...`）+ Android JVM/Instrumented；故障矩阵沿用 M2 的 fault-matrix 模式扩展 backend 维度。
- [ ] 真机人工项（荣耀真机）：双后端真机验收、Push 通知区分后端、锁屏审批（仅 codex）。

### G7：发布候选

- [ ] `test` 通道发布候选、更新说明；M2 台账/文档交叉核对。

## 6. 验收标准（用户可感知）

1. 手机在 Codex Remote Node 中看到 Mac 的后端列表（至少 Codex + DeepSeek Harness），可切换、可从两个后端并行发起任务。
2. 后端互不影响：一个后端进程崩溃/重启，另一个的进行中任务不受影响；重连后各自恢复。
3. M2 恢复语义（Outbox、幂等、Journal、Snapshot、审批去重）对每个后端独立成立。
4. 不支持的能力（如 DSH 无审批）在手机端正确降级显示，不伪装成审批。
5. 旧版 Android App 与旧版 Bridge 互不破坏（空 backendId 向后兼容）。

## 7. 不在范围

- 同一手机同时连接多台 Mac（多 host）——用户已明确本期不做，架构上不预先封死。
- 手机终端、文件同步、自动 Pull/Merge/强推。
- 第三后端接入（架构留接口，不做实现）。
- 修改 M1/M3 范围；不新增任务 Tab；不改变"项目是唯一长期归属"。

## 8. 风险与回滚

- **DSH 接入面不确定（最高风险）**：路线 A 已定案，但 spike 若发现 agent/event API 无法包成 codex 同款 JSON-RPC 面，需回到用户重新确认（备选 B：headless + resume 补丁 + 能力降级）。本期降级红线：不伪报能力。
- **回滚**：Bridge 状态目录先备份；v3 状态可被 v2 Bridge 读取（新字段忽略）即可回滚；Android 升级后回退需 Room 迁移可逆（列删除策略与 M2 一致）。
