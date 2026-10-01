# harness-bridge 对 app-server 后端（尤其 dsh）的事件投影与 thread 列表处理路径

> 只读代码调研（未修改任何代码）。目的：为「让 dsh appserver 支持续接已持久化会话 + 上报运行状态 + interrupt」评估 bridge 改动面。
>
> 术语：**bridge** = Mac 侧 `harness-bridge`（`remote/cmd/bridge/main.go`）；**app-server** = 后端进程的 canonical JSON-RPC 面（codex app-server / `dsh --profile appserver`）；**Logical Event** = 进 journal 并加密下发手机的 `protocol.LogicalEvent`；**codex.event** = 直接透传给手机的原始通知信封（`protocol.Event{Type:"codex.event"}`）。
>
> 文中「证据」默认是仓库内相对路径 `文件:行号`（相对仓库根 `/Users/tony/Documents/harness-apk`）；以 `~/.dsh/...` 开头的是本机 dsh profile / 依赖包里的文件，仅少量用于说明 dsh 侧可用原语与陈旧副本，已在正文标注。行号基于本次调研时的工作区状态（只读，未改任何代码）。
>
> **工作区并发变更提醒（非本次调研产生，但会影响结论时效）**：调研期间工作区存在他人未提交的改动 —— `remote/dsh/appserver/package.json` 把插件依赖从 `0.1.5-rc.1` 提升到 `0.2.0-rc.2`（`schemastery` 3.18.2→3.18.4），`remote/dsh/install-appserver.sh` 增加 `DSH_PACKAGE_VERSION=0.2.0-rc.2` 与安装后版本校验，并附注「appserver resumes and interrupts sessions through dsh's own Agent API」。**本机实际安装的 profile 仍是 0.1.5-rc.1**（`~/.dsh/profiles/appserver/package.json:5-9`、`~/.dsh/profiles/node_modules/@deepseek-ai/{dsh-agent,dsh-agent-loop,dsh-session}/package.json`），因此本文所有 dsh Agent API 行号都取自 0.1.5-rc.1；升到 0.2.0-rc.2 后需重新核对（见 §7）。`remote/dsh/appserver/index.js` 本身在工作区与已安装副本中仍是 v1 实现（无 resume/interrupt），本文 §4 结论不受该并发改动影响。

---

## 0. 结论速览

| 问题 | 结论 |
| --- | --- |
| 后端进程启动 | 统一 `backend.StartCodex(spec)`，`dsh` 与 `codex` 只在 **spec 表**里分支（exec/name/capabilities/args），运行期代码完全后端无关 |
| initialize/capabilities 协商 | 只有 bridge→后端单向 `initialize` + `initialized`；**app-server 返回的 capabilities 被丢弃**，手机看到的能力位全部来自 bridge 硬编码表 |
| `turn/started` | bridge **不产生任何 Logical Event**，只作为 `codex.event` 透传；手机用它把会话卡置 RUNNING |
| `item/agentMessage/delta` | Logical Event `run.agent.delta`（`presentationKind=AGENT_DELTA`）+ `codex.event` 透传（32 KiB 截断） |
| `turn/completed` | 触发终态冻结：`thread/read(includeTurns=true)` 回读 → `run.completed` / `run.failed` / `run.cancelled` |
| `thread-execution-status.v1` 来源 | 由 bridge **自己** 调 `thread/turns/list {limit:3, sortDirection:desc, itemsView:summary}`，读每个 turn 的 **`status` 字符串**（不是 app-server 的专门字段），映射成 `execution.state` |
| thread 卡片标题 | 直接透传 app-server `thread/list` 的 `name`，回退 `preview`；bridge 只在 lazy-continuation 链上覆盖 `name` |
| `turn/interrupt` | bridge **已经存在**（`turn.interrupt` → `turn/interrupt`；`run.interrupt` → `turn/interrupt`），手机端命令类型也已存在；缺的是 **dsh 后端实现 + 能力位 + 错误码判别** |
| JSON-RPC 错误码 | bridge 不解析 `error.code`，整体 `fmt.Errorf("app-server error: %s", rawJSON)` 字符串化；手机只做 4 个字符串包含判断，其余一律「Mac 返回错误，请稍后重试」 |
| `thread/resume` | bridge 已有调用（codex 专用触发条件：错误串必须含 `thread not found: <id>`）；dsh 说 `unknown thread <id>`，因此 **dsh 的续接路径永远不会触发** |

---

## 1. `remote/internal/backend/`：后端进程启动与 initialize/capabilities 协商

### 1.1 结论

- 所有后端共用一条启动实现 `StartCodex`：`exec.Command(spec.Exec, spec.Args...)`，stdio 双向管道，读循环由 `appserverrpc.Client` 驱动。
- `args` 为空时默认填 codex 命令面 `app-server --listen stdio://`；dsh 通过显式 `Args` 覆盖，因此「codex 与 dsh 的差异被压缩成 Spec 表」。
- `Backend` 接口本身就是「规范化的 app-server 事件流」抽象：`Message{BackendID,ID,Method,Params}`，bridge 侧翻译（审批/timeline/completion）与具体协议解耦。
- bridge 在 `backend/` 层 **没有** 任何 `if backendID == "dsh"` 的运行期分支；唯一的 dsh 分支在 `cmd/bridge` 的 spec 解析里（见 1.4）。

### 1.2 证据

| 事项 | 位置 |
| --- | --- |
| Backend 接口（Call/Notify/Respond/Start/Messages/Done/Close） | `remote/internal/backend/backend.go:33-49` |
| 后端无关的规范化事件 `Message` | `remote/internal/backend/backend.go:16-30` |
| 空 backendId 归一到 `codex` | `remote/internal/backend/backend.go:51-57`（`DefaultBackendID` 定义在 `remote/internal/protocol/protocol.go:16-18`） |
| 启动进程 + 管道 + epoch | `remote/internal/backend/codex.go:42-92` |
| 空 args 默认 codex 命令面 | `remote/internal/backend/codex.go:46-51` |
| 通知流进入 `messages` chan（buffer 64） | `remote/internal/backend/codex.go:81-91` |
| 进程退出 → Done | `remote/internal/backend/codex.go:117-126` |
| `Close` 关 stdin + kill + Wait | `remote/internal/backend/codex.go:129-139` |

### 1.3 initialize / capabilities：单向、且服务端能力被丢弃

- `superviseBackend` 在 `bd.Start(ctx)`（先起读循环，注释明确写了否则响应永远读不到）之后：
  1. `initialize`：`clientInfo{name:"harness_remote_bridge", title, version:"0.2.0"}` + `capabilities{experimentalApi:true}`；
  2. `initialized` 通知；
  3. **返回值被丢弃**（`_, initErr := bd.Call(...)`）。
- 随后 `routes.BeginProcessEpoch(bd.ID(), bd.ProcessEpoch())`、注册后端、启动消息泵；退出后背退重启（同一进程内其它后端不受影响）。
- 手机看到的能力位来自 `parseBackendSpecs` 的静态表 + `hostStatusPayload()`，**从不来自 app-server 的 initialize 结果**，也不在调用点做能力校验（例如 bridge 不会因为后端没声明 `approvals.v1` 就拒绝审批路径）。
- 证据：
  - `remote/cmd/bridge/main.go:498-511`（initialize/initialized、结果被丢弃）；
  - `remote/cmd/bridge/main.go:520-544`（epoch / 注册 / 泵 / 重启退避）；
  - `remote/cmd/bridge/main.go:710-732`（`hostStatusPayload`：per-backend 能力列表 + legacy 字段取默认后端）；
  - `remote/cmd/bridge/main.go:371-383`（能力来自 `CodexCapabilities()` / `DSHCapabilities()`）；
  - 运行期唯一读 `Capabilities()` 的地方就是 `main.go:723`（全仓 `Capabilities()` 调用点：`backend.go` 定义 + `main.go:723`）。

### 1.4 bridge 里按 backendId 的分支点（全量）

| 位置 | 分支内容 |
| --- | --- |
| `remote/cmd/bridge/main.go:360-368` | 裸 id 解析 executable：`codex`→`--codex` 值，`dsh`→`"dsh"`，其它必须写 `<id>=<executable>` |
| `remote/cmd/bridge/main.go:370-380` | `name`/`capabilities`/`args`：dsh = `DeepSeek Harness` + `backend.DSHCapabilities()` + `["--profile","appserver","--listen","stdio://"]` |
| `remote/internal/backend/backend.go:59-71` | `DSHCapabilities()` = `CodexCapabilities()` **减去** `approvals.v1`、`user-input.v1` |
| `remote/internal/backend/backend.go:73-88` | `CodexCapabilities()` 11 项硬编码，含 `run.lifecycle.v1`、`thread-execution-status.v1`、`thread-lazy-continuation.v1` |
| `remote/cmd/bridge/main.go:649-708` | 命令分发按 `command.BackendID`（`backendFor(NormalizeID(...))`）选后端；未注册后端 → `error` 事件「后端不可用：dsh」 |
| `remote/internal/run/routes.go:117-141`、`256-302` | 路由键 `(backendID, runID)`、`ByThreadTurnBackend` / `ByThreadAllBackend`，同一 threadId 在两个后端互不可见 |
| `remote/cmd/bridge/main.go:2371-2375` | 事件来源后端不可用 → 丢弃（`discard app-server event from unavailable backend`） |

测试固化：`remote/cmd/bridge/main_test.go:1836-1868`（`parseBackendSpecs` 的 dsh 断言：exec/name/args/能力差 2 项）、`remote/cmd/bridge/main_test.go:1666-1717`（命令只到指定后端）、`remote/cmd/bridge/main_test.go:1744-1772`（事件不跨后端串台）。

dsh 冒烟测试：`remote/internal/backend/dsh_integration_test.go:14-53` — 只验证 `initialize`（断言 `dsh-appserver`）与 `thread/start`（断言 `session-` 前缀），**不覆盖 turn/interrupt、thread/resume、状态上报**。

---

## 2. 通知处理：app-server 通知 → Logical Event / Run 状态

### 2.1 总入口

`handleAppServer`（`remote/cmd/bridge/main.go:2367-2457`）对每条后端通知依次做这些事：

1. `serverRequest/resolved`：把审批标记为 STALE 并发 `run.approval.resolved`（`main.go:2377-2392`）；
2. 审批类 `item/*/requestApproval` → `approval.request` + `run.approval.requested`（`main.go:2395-2423`）；
3. `item/tool/requestUserInput` → `user_input.request` + `run.user_input.requested`（`main.go:2424-2431`）；
4. `turn/completed` → 终态冻结（`main.go:2432-2444`）；
5. 通用：`timelineLogicalPayload` 产出 `run.timeline` / `run.agent.delta`（`main.go:2445-2449`）；
6. 最后 `mobileCodexEventEnvelope` 白名单透传 `codex.event`，按 route 找到 device 下发（`main.go:2450-2456`，`eventTargets` 见 `main.go:3018-3024`）。

### 2.2 `turn/started`

- **结论**：bridge 对 `turn/started` 没有任何 Logical Event / Run 状态投影；它只出现在 `mobileCodexEventEnvelope` 白名单里，被投影为 `codex.event`（只保留顶层 `threadId/turnId/itemId` + `turn.id` / `turn.status`）。
- 证据：`remote/cmd/bridge/main.go:2178-2184`（case `"turn/started"`, `"turn/completed"`）、`main.go:2194-2195`（其它方法一律 `return nil,false` 丢弃）。
- 手机侧消费：`app/src/main/java/com/harnessapk/remote/RemoteClient.kt:886-899`（`turn/started` → 该会话 `RemoteThreadExecutionState.RUNNING`、`isWorking=true`）。
- 工程 Run 的 RUNNING 另有来源：`run.start` 成功后 `Coordinator.Start` 发 `run.started`（`remote/internal/run/coordinator.go:178-185`），手机 `statusFor` 映射为 RUNNING（`app/src/main/java/com/harnessapk/remote/RemoteEventReducer.kt:193-196`）。
- 注意：手机还实现了 `thread/status/changed` → RUNNING/WAITING_APPROVAL/WAITING_USER（`RemoteClient.kt:870-885`），但 bridge 的白名单**不含**该方法，因此这条通路目前是死代码（全仓 `status/changed` 只出现在文档 `docs/plans/2026-08-22-codex-session-control-task-package.md:24`）。若「上报运行状态」打算走 codex 原生 `thread/status/changed`，bridge 必须新增投影。

### 2.3 `item/agentMessage/delta`

- Logical Event：`run.agent.delta`，payload `{itemId, delta(脱敏), presentationKind:"AGENT_DELTA", latestLine:"正在整理结果"}`。
- 手机透传：`codex.event` 里 `delta` 截断到 32 KiB。
- 证据：`remote/cmd/bridge/main.go:2464-2470`（logical）、`main.go:2191-2193`（透传截断）、手机侧 `RemoteClient.kt:931`（`appendAgentDelta`）。

### 2.4 `item/started` / `item/completed`

- Logical Event `run.timeline`：按 item type 给 `presentationKind`（agentMessage→RESULT、commandExecution→TEST、fileChange→FILES、reasoning→ANALYZING、webSearch→SEARCHING），`detail` 由 `itemSummary` 取 `text|command|status`。
- 证据：`remote/cmd/bridge/main.go:2472-2513`；测试 `remote/cmd/bridge/main_test.go:335-347`。

### 2.5 `turn/completed` → 终态（权威状态不是通知里的字段）

- 收到通知先落一条 terminal observation（崩溃可恢复），再异步 `completeRun`（`main.go:2432-2444`、`main.go:2533-2549`）。
- `reconcileTerminalRun` 用 **`thread/read {threadId, includeTurns:true}` 回读**，取目标 turn：
  - `completionTurnStatus(params, turn.status)`：先看通知 params（`status` / `turn.status` / `type`，支持对象与字符串），再看回读 turn.status；
  - `cancelled`（含 `interrupted/cancelled/canceled`）→ 冻 `CANCELLED`；`failed` → `FAILED`；`completed` → 走 `buildCompletionEvidence`（workspace before/after + 最后 agentMessage + structuredOutput）冻 `COMPLETED`；其它 → 报错不冻结。
- 冻结后经 journal 发 `run.completed` / `run.failed` / `run.cancelled`（稳定 eventId，可重放）。
- 证据：`remote/cmd/bridge/main.go:2551-2597`、`main.go:2657-2674`、`main.go:2676-2691`、`main.go:2780-2831`、`main.go:2701-2731`、`main.go:2648-2655`；测试 `main_test.go:1336-1414`。
- `run.snapshot`（重连对账）不回读完整 turn 的终态，而是先查终态账本，缺失时用 `snapshotStatus` 现算：`inprogress/running→RUNNING`、`completed→COMPLETED`、`failed→FAILED`、`interrupted|cancelled|canceled→CANCELLED`、其它→`RECONCILING`（`main.go:1463-1505`、`main.go:2237-2267`）。

### 2.6 `thread-execution-status.v1` 到底从哪个字段推导

- **结论**：不是 app-server 的某个专门字段，而是 bridge 自己发起的 `thread/turns/list` 请求，从每个 turn 的 **`status` 字符串**推导：
  1. `thread.summary` 命令 → `bd.Call("thread/turns/list", {threadId, limit:3, sortDirection:"desc", itemsView:"summary"})`（`main.go:939-968`）；
  2. `mobileThreadSummaryResult` 只取返回 `data` 的**第一页首条**（desc 下即最新 turn）：`completed→COMPLETED`、`failed→FAILED`、`inProgress→RUNNING`、`interrupted→`（有 `completedAt` 则 `INTERRUPTED`，无则 `RUNNING`）、未知→`UNKNOWN`；同时 `startedAt/completedAt` 原样回填（`main.go:863-925`，映射在 `main.go:878-904`）；
  3. 响应体固定为 `{threadId, latestUserMessage, execution}`（`main.go:920-924`）。
- 「interrupted 无 completedAt 视为仍在跑」是**规格明确行为**（README：`remote/README.md:218`；测试 `main_test.go:434-462`）。
- 能力位声明在 `remote/internal/backend/backend.go:84`；手机据此开关（`app/src/main/java/com/harnessapk/remote/RemoteModels.kt:189-195`），只在卡片可见且状态为 UNKNOWN/active 时懒请求，active 时每 3s 轮询、UNKNOWN 时 10s（`app/src/main/java/com/harnessapk/ui/remote/RemoteScreen.kt:315-345`；策略描述 `remote/README.md:218`）。

### 2.7 RUNNING / COMPLETED / INTERRUPTED 出现的链路（速查）

| 词 | 产生点 | 证据 |
| --- | --- | --- |
| `RUNNING` | ① 工程 Run：`run.started` → 手机 `statusFor`；② 会话卡：`thread.summary.execution.state`（turns/list `status=inProgress`）；③ 会话卡：`codex.event turn/started` → 手机本地置 RUNNING | `run/coordinator.go:178-185`；`RemoteEventReducer.kt:193-196`；`main.go:883-893`；`RemoteClient.kt:886-899` |
| `COMPLETED` | ① 终态冻结 `run.completed`；② `snapshotStatus`/`execution.state`；③ 手机 `codex.event turn/completed`（默认 fallback COMPLETED） | `main.go:2657-2664`；`main.go:1491-1495`；`main.go:884`；`RemoteClient.kt:900-921` |
| `INTERRUPTED` | 只有一处：`thread.summary.execution.state`（turns/list `status=="interrupted"` 且 `completedAt` 非空） | `main.go:888-893` |
| `CANCELLED` | 终态路径把 `interrupted/cancelled/canceled` 全部归一成 CANCELLED（`run.cancelled` / snapshot `CANCELLED`） | `main.go:2820-2831`；`main.go:1496-1497`；`main.go:2574-2580` |

> 交叉含义：手机 `codex.event turn/completed` 分支会把 `turn.status=="interrupted"` 显示为 INTERRUPTED（`RemoteModels.kt:494-504`），但同一回合在工程 Run 视图里是 **CANCELLED**（`main.go:2826-2827`）；两套视图对「中断」用词不一致。

---

## 3. `thread/list` 与 `thread/turns/list` 的调用点、标题来源、`itemsView=summary`

### 3.1 全部调用点

| 调用点 | 方法/参数 | 用途 |
| --- | --- | --- |
| `remote/cmd/bridge/main.go:742-766` | `thread/list {limit:20, sortKey:"updated_at", sortDirection:"desc", sourceKinds:[cli,vscode,exec,appServer]}` | 手机「会话列表」`thread.list` 命令 |
| `remote/cmd/bridge/main.go:1170-1233` | `thread/list {limit:50, ...}` | `workspace.list` → 从各 thread 的 `cwd/updatedAt` 造工作区候选 |
| `remote/internal/run/coordinator.go:206-229` | `thread/list {limit:50, ...}` | `run.start` 复用同 cwd 的最近 thread（`findRecentThread`） |
| `remote/cmd/bridge/main.go:939-968` | `thread/turns/list {limit:3, sortDirection:"desc", itemsView:"summary"}` | `thread.summary`：最新用户话 + 执行状态 |
| `remote/cmd/bridge/main.go:971-1017` | `thread/read {includeTurns:false}` + `thread/turns/list {limit:8, sortDirection:"desc", itemsView:"summary"}` | `thread.read` 分页历史（`mobileThreadHistoryPageSize=8`，`main.go:735`） |
| `remote/cmd/bridge/main.go:1777-1852` | `thread/turns/list {limit:8, sortDirection:"desc", itemsView:"summary"}` + `thread/start` + `turn/start` | 超大会话的 lazy continuation（把最近 8 个 turn 的用户/Codex 文本截断成 ≤24 KiB 作为 `additionalContext` 注入新 thread） |
| `remote/cmd/bridge/main.go:2018-2058`、`main.go:2237-2267`、`main.go:2551-2567` | `thread/read {includeTurns:true/false}` | 手机历史、Run 快照、终态对账 |

### 3.2 标题 / preview 来自哪里

- **结论：直接来自 app-server `thread/list` 返回的 thread 对象的 `name`，其次 `preview`。bridge 不自己拼标题**，只有两个例外：
  1. `rememberThreadContinuationNames`：把 `name`（空则 `preview`）记进 continuation 记录，仅用于补齐 lazy-continuation 记录的 `Name`（`main.go:768-805`，取值处 `main.go:776-785`）；
  2. `mobileThreadListResult`：若该 thread 是 continuation 链的最新一环，则用链上保存的 `name` **覆盖** 它的 `name`、加 `continuedFromThreadId`，并把链上历史 thread **从列表隐藏**（`main.go:807-861`，覆盖 `main.go:835-847`，隐藏 `main.go:848-857`）。
- 手机侧解析：`title = name ?: preview ?: "未命名线程"`（截断 60 字）、`preview` 单独保留 240 字（`app/src/main/java/com/harnessapk/remote/RemoteModels.kt:446-464`）。
- 由此对 dsh 的两点直接后果：
  - dsh 的 `thread/list` 给 `name = entry.name || threadId`（live）或 `persisted.cwd || id`（持久化），`preview` 恒为 `""`（`remote/dsh/appserver/index.js:147-168`，具体 `152-153`、`158-161`）→ 持久化会话的卡片标题是**文件系统路径**，没有 preview；也解释了为什么 `rememberThreadContinuationNames` 的 preview 回退对 dsh 无效。
  - dsh 的 `updatedAt` 用**毫秒**（`Date.now()` / `statSync().mtimeMs`，`index.js:153`、`index.js:160`、`remote/dsh/appserver/persist.js:67`），而手机按**秒**解析（`updatedAt * 1000`，`RemoteModels.kt:458`）→ 时间显示会放大 1000 倍。旁证：profile 里残留的旧版插件副本 `~/.dsh/profiles/appserver/node_modules/dsh-appserver/thread-list.js`（非当前 `index.js` 导入，属陈旧文件）里有专门的 `protocolSeconds()` 把毫秒转秒，说明 canonical 面应为秒。

### 3.3 `itemsView=summary` 的用法

- bridge 在 3 处请求里显式带 `itemsView:"summary"`（`main.go:951`、`main.go:988`、`main.go:1789`），期望 app-server 只回精简 item（id/type/text/status），配合 bridge 自己的截断（`maxMobilePaginatedTextBytes=24KiB`、`maxMobilePaginatedItemsPerTurn=2`，`main.go:736-737`）。
- 分页投影时会把 `itemsView` 原样抄回给手机（`copyJSONFields(projectedTurn, turn, "id","status","itemsView")`，`main.go:1164`），但 `mobileThreadReadResult` 不抄 `itemsView`（`main.go:2107-2109` 只抄 `id/status`）。
- **dsh 侧不解析 `itemsView`**：`thread/turns/list` 只读 `threadId/limit/sortDirection`（`remote/dsh/appserver/index.js:281-293`），但恒返回精简 item 并在每个 turn 上写死 `itemsView:"summary"`（`index.js:200-209`），所以 bridge 的期望实际上「歪打正着」被满足，但这是巧合而非契约。

---

## 4. `turn/interrupt` 现状：bridge 有、手机有、dsh 没有

### 4.1 bridge 侧已经存在的两条链路

| 手机命令 | bridge 处理 | 最终 app-server 调用 |
| --- | --- | --- |
| `turn.interrupt` | `executeCommand` → `requestAppServer(..., "turn/interrupt", {threadId, turnId})` | `turn/interrupt`，参数只带 `threadId`+`turnId`，**无回合校验** |
| `run.interrupt`（工程 Run） | `executeCommand` → `controlRun` → `run.ControlCoordinator.Execute` | `turn/interrupt`，参数用 **route 上的** `threadId/turnId`，且要求 `route.TurnID == command.ExpectedTurnID`，否则失败「run turn changed; snapshot required」 |

证据：`remote/cmd/bridge/main.go:696-697`（turn.interrupt）、`main.go:679-680` + `main.go:1289-1318`（run.interrupt 入口）、`remote/internal/run/control.go:81-91`（方法/参数/payload）、`remote/internal/run/control.go:110-118`（类型白名单 + `expectedTurnId` 必填）、`remote/internal/run/control.go:59-65`（route 归属与回合一致性校验）。

成功路径产出：`run.interrupt.accepted`，payload `{commandId, threadId, turnId, latestLine:"正在停止任务", presentationKind:"INTERRUPT"}`（`control.go:84`、`control.go:92-104`）；失败/未知路径见第 5 节。

### 4.2 手机端命令类型（不是「枚举」而是字符串分发）

- `remote/internal/protocol/protocol.go` **没有命令类型枚举**：`Command.Type` 是自由字符串（`protocol.go:70-94`），bridge 用 `switch command.Type` 分发，未识别类型回 `error` 事件「unsupported command: X」（`main.go:670-708`）。
- 因此新增命令类型 **不需要改 protocol 结构**（除非要新字段）；但旧 bridge 遇到新手机命令会明确报错而不是静默忽略。
- 手机端已实现的两条中断：`RemoteClient.kt:254-257`（`turn.interrupt`，threadId+turnId）与 `RemoteRunCommandCoordinator.kt:53-68`（`run.interrupt`，commandId/runId/expectedTurnId，`RemoteModels.kt:146` 定义 payload）。UI 侧停止按钮只看 run 是否 active，**不做能力位 gate**（`app/src/main/java/com/harnessapk/ui/activity/RunDetailScreen.kt:191-199`）；事件侧把 `run.interrupt.accepted` 标为 ACCEPTED、`run.control.failed/unknown` 分别标 FAILED/UNKNOWN（`RemoteEventReducer.kt:117-130`）。
- 工程 Run 的开关能力位是 `{workspace.candidates.v1, run.lifecycle.v1, logical-replay.v1}`（`RemoteModels.kt:183-195`），而 dsh **声明了** `run.lifecycle.v1`（`remote/internal/backend/backend.go:76-88`，设计文档 `docs/superpowers/specs/2026-08-15-m4-multi-backend-bridge-design.md:148` 明确 `run.lifecycle.v1` 含 run.interrupt）→ 手机会显示停止按钮，但请求必然失败。

### 4.3 dsh 后端目前明确不支持

- `turn/interrupt` 直接回错误：`respondError(id, "unsupported", "turn/interrupt is not mapped in the dsh backend v1; the turn keeps running on the Mac")`（`remote/dsh/appserver/index.js:294-296`）；未知方法（含 `thread/resume`）回 `method_not_found`（`index.js:297-299`）。
- 文档层面已记录：`remote/README.md:97-98`「`turn/interrupt` is not mapped yet」；G0 结论「未找到直接 `agent.interrupt()` 公开方法」`remote/spike/dsh-appserver/README.md`（interrupt 行）与设计文档 `docs/superpowers/specs/2026-08-15-m4-multi-backend-bridge-design.md:226`。
- **但依赖版本里已有可用原语**（本次新增证据，取自本机 0.1.5-rc.1 依赖）：`@deepseek-ai/dsh-agent-loop` 给 live agent 增强出 `cancel(cause, options?)`（`~/.dsh/profiles/node_modules/@deepseek-ai/dsh-agent-loop/lib/types/agent.d.ts:44`，语义见 `~/.dsh/profiles/node_modules/@deepseek-ai/dsh-agent/lib/types/runtime-types.d.ts:147-157`），`AgentCancelCause` 含 `{kind:'user'}`（`~/.dsh/profiles/node_modules/@deepseek-ai/dsh-session/lib/types/types.d.ts:147-157`），turn/end 的 reason 词表含 `aborted/blocked/error/max-tokens/interrupted`（同文件 `165-200`；运行时产生点 `~/.dsh/profiles/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js:947-990`）。→ dsh 实现 interrupt 的改动面主要在插件侧，而不是「无 API 可用」。注意 `dsh-agent-loop` 由 `dsh-base` bundle 提供（appserver profile 自己的 `@deepseek-ai/` 目录下没有它），升版时它随 bundle 一起变。

### 4.4 dsh 当前状态上报的额外缺口（影响「上报运行状态」）

- `thread/list` 对 live thread 恒返回 `status:{type:"idle"}`，不看 `agent.status`（`remote/dsh/appserver/index.js:147-155`）；`thread/read` 同样写死 idle（`index.js:170-185`）。
- `thread/turns/list` 的 status 计算有键名错配：`turnStatusString(turn.status)` 读 `reason.kind`，但 `turnStatusFromReason` 产出的是 `{type: ...}`（`remote/dsh/appserver/translate.js:33-42`）→ 对**进行中**和**失败**的 turn 都会返回 `"completed"`（唯一调用点 `remote/dsh/appserver/index.js:202`）。后果：手机 `thread.summary` 看到 COMPLETED → 停止 3s 轮询并在卡片上显示已完成，即使回合仍在跑（`main.go:883-893` + `RemoteScreen.kt:315-345`）。
- `turn/completed` 通知里 `turn.status` 写死 `"completed"`，真实原因只放在顶层 `params.status`/`reason`（`remote/dsh/appserver/index.js:82-89`）→ 手机的通知分支只看 `params.turn.status`（`RemoteClient.kt:900-906` → `RemoteModels.kt:494-504`），因此 dsh 的失败/中断回合会被通知成「已完成」（权威 Run 状态仍由 bridge 的终态路径纠正，见 2.5）。
- `turn/started` 只带 `turn.id`，无状态（`index.js:67-70`）→ 手机 `turn/started` 分支只用 id（`RemoteClient.kt:886-899`），这点与 bridge/手机约定兼容。
- 续接相关：`turn/start`/`turn/steer` 只认内存注册表（`startTurn` → `registryEntry`；`index.js:216-218`），持久化会话只能被 `thread/read`/`thread/turns/list` **只读回放**（`index.js:132-145` + `persist.js:87-104`，走 `ctx.get("sessionPersistence")`，cordis 的 `get` 不要求 inject，`~/.dsh/profiles/node_modules/@deepseek-ai/cordis/lib/index.js:756-767`），**不能续跑**；`thread/resume` 不存在（`index.js:297-299`）。而 dsh 已提供 `ctx.agents.resume(ownerCtx, {resumeSessionId})`（`~/.dsh/profiles/node_modules/@deepseek-ai/dsh-agent/lib/types/index.d.ts:110-140`、`184-196`）作为续接原语。
- 附带静默降级：bridge 的 lazy continuation 依赖 `turn/start` 的 `additionalContext`（`main.go:1819-1830`）与 `outputSchema`（`coordinator.go:142-149`），dsh 只读 `threadId/input`（`index.js:264`）→ 参数被静默丢弃（不报错），大会话续聊会「成功但没有交接上下文」。
- 插件安装脚本把**固定 5 个文件**（`package.json`、`index.js`、`startup.js`、`translate.js`、`persist.js`）复制进 profile（工作区当前版本：`remote/dsh/install-appserver.sh:57-61`，另有 `:39-52` 的新增「dsh 代际校验」块），因此**新增模块文件必须同步改这里**；`remote/dsh/appserver/index.js`、`persist.js`、`translate.js`、`startup.js` 在调研时与 `~/.dsh/profiles/appserver/node_modules/dsh-appserver/` 下副本逐字节相同（`diff` 校验）——`package.json` 随后被上述并发改动改成 0.2.0-rc.2，而已安装副本仍是 0.1.5-rc.1。

---

## 5. 错误码映射：app-server JSON-RPC error 如何透传到手机

### 5.1 client 层：code 被丢掉，只剩一个字符串

```go
// remote/internal/appserver/client.go:154-158
if len(message.Error) > 0 && string(message.Error) != "null" {
    future <- callResult{err: fmt.Errorf("app-server error: %s", message.Error)}   // 整个 error 对象原样字符串化
}
```

- 结构体上有 `Error json.RawMessage`（`client.go:15-21`），但 `resolve` 从不解析 `{code,message}`。
- 全仓 Go/Kotlin 代码 **零处**出现 JSON-RPC 错误码名 `method_not_found` / `invalid_params`（`grep` 仅命中 dsh 插件 JS：`remote/dsh/appserver/index.js:266,275,288,295,298`）；Go 侧出现的 `unsupported` 全部是本地文案（如 `main.go:705`「unsupported command: 」、`main.go:2326`「unsupported mobile approval decision」），**没有任何一处比较过 app-server 返回的 code**。手机只把 `error` 当字符串/对象取 `message`（`RemoteClient.kt:30-37`）。
- 唯一的「结构化」判别是**字符串包含**：`isThreadNotFoundError` 要求 `strings.Contains(err.Error(), "thread not found: "+threadID)`（`main.go:1605-1607`，另一份在 `remote/internal/run/coordinator.go:194-196`）；`turn.start` 只用前缀 `"app-server error:"` 区分「确定性失败」与「结果未知」（`main.go:1922-1929`）。

### 5.2 各命令路径的错误出口

| 路径 | 错误去向 | 手机表现 |
| --- | --- | --- |
| 通用 RPC（`thread.read`/`thread.start`/`turn.steer`/`turn.interrupt`/`rpc`） | `rpc.response` payload `{"error": "<err.Error()>"}`（`main.go:2044-2052`） | `handleRpcResponse` 的 error 分支：清 loading、写 `errorMessage`、发通知「Mac 返回了错误」（`RemoteClient.kt:617-660`） |
| `thread.list` / `thread.summary` / `workspace.list` | 同样 `{"error": ...}`（`main.go:756-759`、`955-961`、`1178-1183`） | 同上；`thread.summary` 静默丢弃并保留本地状态（`RemoteClient.kt:618-621`） |
| `turn.start` | `turnRPCResponsePayload`：确定性失败 `{"error", "retrySafe":false}`；未知 `{"outcome":"UNKNOWN","status":"RECONCILING",...}`（`main.go:2001-2020`） | 未知走专用分支（RECONCILING + 「发送结果待确认」`RemoteClient.kt:600-615`）；确定性失败 → 通用错误文案 |
| `run.interrupt`（工程 Run） | **任何** `Call` 错误都被 `run/control.go:88-91` 记为 `MarkUnknown` 并返回 `ErrControlOutcomeUnknown`；`controlRun` 转成 `run.control.unknown` + latestLine「正在核对手机指令结果」（`main.go:1303-1309`） | Run 置 RECONCILING（`RemoteEventReducer.kt:200`），命令记 UNKNOWN（`RemoteEventReducer.kt:127-129`）；**不区分「unsupported」这种确定性拒绝** |
| 后端不可用 / 未识别命令 | `error` 事件「后端不可用：dsh」/「unsupported command: X」（`main.go:663-668`、`702-707`） | `RemoteClient.kt:487-499`：errorMessage + 通知「Codex 任务失败」 |

### 5.3 手机最终显示什么（关键结论）

`remoteRpcErrorMessage`（`app/src/main/java/com/harnessapk/remote/RemoteClient.kt:30-43`）只识别 4 种子串，其余全部落兜底：

| 错误串包含 | 手机文案 |
| --- | --- |
| `not materialized` | 会话正在初始化，请先发送第一条消息 |
| `token too long` | 会话内容过大，Mac Bridge 需要升级后重试 |
| `too large to resume remotely` | Mac Bridge 版本过旧，请升级后重试大会话 |
| `deadline exceeded` | Mac 恢复会话超时，请稍后重试或新建会话 |
| 其它（**含 dsh 的 `unsupported` / `method_not_found` / `invalid_params`**） | 「Mac 返回错误，请稍后重试」 |

因此 dsh 的 `turn/interrupt` 被拒后：
- 走 `turn.interrupt`（会话视图）→ 手机显示「Mac 返回错误，请稍后重试」+ 通知「Mac 返回了错误」；
- 走 `run.interrupt`（工程 Run）→ 手机显示「正在核对手机指令结果」并把 Run 挂到 RECONCILING（因为 bridge 把它当成了「结果未知」）。

### 5.4 对 resume 的直接后果

- codex 的续接链依赖 `thread not found: <id>` 触发（`main.go:1901-1921`：先 `thread/read {includeTurns:false}` 取元数据/体积 → 常规走 `thread/resume {threadId, excludeTurns:true}` 再重试 `turn/start`；超 256 MiB 走 lazy continuation，阈值 `maxDirectResumeThreadBytes=256<<20`，`main.go:739`）。
- dsh 对未知 thread 回的是 `invalid_params` + `unknown thread <id>`（`remote/dsh/appserver/index.js:218` → `266`/`275`/`288`），**不匹配** `thread not found: `，且 dsh 没有 `thread/resume`（`index.js:297-299`）→ dsh 的持久化会话续接今天必定失败，并且失败被归为「确定性失败」，手机只看到兜底错误文案。

---

## 6. 若要新增 interrupt / resume 能力，bridge 侧需要改哪些文件

### 6.1 必改（bridge，Go）

| 文件 | 改动点 | 为什么 |
| --- | --- | --- |
| `remote/internal/appserver/client.go` | 把 `error` 解析成结构化错误（`Code string`、`Message string`），保留原始 JSON；`resolve` 处（`:154-158`）改为类型化 error | 现在 code 被丢进字符串；interrupt 要区分 `unsupported/method_not_found`（确定性失败）与传输/超时（结果未知），resume 要区分 `invalid_params`（thread 不存在）与其它 |
| `remote/internal/run/control.go` | `Execute` 里 `c.App.Call` 的错误分类（`:88-91`）：确定性 JSON-RPC 错误（`unsupported/method_not_found/invalid_params`）→ `Cache.Fail` + `run.control.failed`；只有超时/断连才 `ErrControlOutcomeUnknown` | 现在任何错误都变「正在核对手机指令结果」，dsh 的 `unsupported` 被永久挂起 |
| `remote/cmd/bridge/main.go`（控制/终态） | ① `isThreadNotFoundError`（`:1605-1607`）改成「结构化 code 或兼容多种文案（`unknown thread <id>`）」；② 需要决定 dsh 用 `thread/resume` 还是新方法（如 `thread/resume` 语义对齐 / `session/attach`），落在 `:1913-1918` 一带；③ `snapshotStatus`（`:1463-1505`）与 `normalizeCompletionStatus`（`:2820-2831`）补齐 dsh 原因词表（`aborted→CANCELLED/INTERRUPTED`、`blocked/max-tokens`）；④ 若要修 dsh 的「中断后仍显示 RUNNING/COMPLETED」，`mobileThreadSummaryResult`（`:863-925`）需接受 app-server 直给的状态或修 dsh 输出 | resume 触发条件、终态归一、状态上报 |
| `remote/cmd/bridge/main.go`（事件白名单） | `mobileCodexEventEnvelope`（`:2171-2202`）新增 `thread/status/changed`（必要时 `turn/failed`、`error{willRetry}`）透传；手机已有消费逻辑（`RemoteClient.kt:870-885`） | 若运行状态改走 codex 原生 `thread/status/changed`，bridge 现在是硬丢弃（`:2194-2195`） |
| `remote/internal/backend/backend.go` | `DSHCapabilities()`（`:59-71`）按实际落地能力增删：至少给 interrupt 一个可 gate 的能力名（现无此名），并重新审视 `run.lifecycle.v1`（`:78`）、`thread-lazy-continuation.v1`（`:85`）是否虚标；若 dsh 落地 resume 则加对应能力位 | 手机只信 host.status 的能力位；现在 dsh 声明了 run.lifecycle.v1 却做不到 interrupt |
| `remote/cmd/bridge/main.go`（spec 表） | 若能力位按后端不同（`:370-380`），保持与 `DSHCapabilities()` 同步 | 能力表是唯一后端分支点 |

### 6.2 可能需要改（协议/手机侧，取决于是否新增命令或能力位）

| 文件 | 改动点 |
| --- | --- |
| `remote/internal/protocol/protocol.go` | `Command.Type` 是自由字符串（`:70-94`），新命令类型无需枚举；但若要传 `excludeTurns`/`resumeMode` 之类新参数，需要在 `Command` 增字段（`WireMessage`/`LogicalEvent` 不必动，`Version` 仍为 1，`:14`） |
| `app/src/main/java/com/harnessapk/remote/RemoteModels.kt` | 若新增能力位：`remoteFeatureAvailability`（`:183-195`）；若续接走新命令类型：命令 payload 定义（参考 `:146`）与 `injectBackendId`（`:386-391`） |
| `app/src/main/java/com/harnessapk/remote/RemoteClient.kt` | `remoteRpcErrorMessage`（`:30-43`）补 `unsupported`/`method_not_found`/`thread/resume` 的确定性文案，否则用户只看到兜底「Mac 返回错误」 |
| `app/src/main/java/com/harnessapk/ui/activity/RunDetailScreen.kt` | 停止按钮按能力位 gate（`:191-199`），否则 dsh 上会一直显示一个必然失败的按钮 |
| `app/src/main/java/com/harnessapk/remote/RemoteEventReducer.kt` | 若新增 `run.control.rejected` 之类事件：`statusFor`（`:187-205`）与命令状态归并（`:117-130`） |
| `app/src/main/java/com/harnessapk/storage/RemoteDao.kt`（及迁移） | 新命令类型进 outbox 需要与既有 pending 去重逻辑一起考虑（参考 `RemoteRunCommandCoordinator.kt:53-68` 的 `pendingCommandId`） |

### 6.3 dsh 后端侧（前置依赖，否则 bridge 改了也没用）

| 文件 | 改动点 |
| --- | --- |
| `remote/dsh/appserver/index.js` | ① `turn/interrupt` 用 `registryEntry(threadId).agent.cancel({kind:"user"},{...})` 实现（原语见 4.3）；按 `threadId(+turnId)` 校验运行中回合；② 新增 `thread/resume`（或与 bridge 约定的方法）用 `ctx.agents.resume(ownerCtx,{resumeSessionId})` 把持久化会话提升为 live，并注册进 `threads` 表；③ `thread/list`/`thread/read` 用 `agent.status` 上报运行状态（`idle/running`），并修 `updatedAt` 秒/毫秒；④ `turn/turns/list` 与 `turn/completed` 的 status 键名错配修复（`translate.js:33-42` + `index.js:202`、`index.js:82-89`），把 `aborted/blocked/error/max-tokens/interrupted` 映射到 `interrupted/failed/completed`；⑤ 视需要 emit `thread/status/changed` |
| `remote/dsh/appserver/translate.js` | turn/end reason → codex status 的映射表（单点真源） |
| `remote/dsh/install-appserver.sh` | 若新增模块文件，同步复制清单（`:57-61`，现在是固定 5 个文件）；工作区已有未提交的 `:39-52` 代际校验块（0.2.0-rc.2），落地前先合并/确认 |
| `remote/dsh/appserver/client.mjs` | 端到端验证脚本（`:84-120` 现覆盖 initialize→thread/start→turn/start→steer→read→turns/list→list）；补 interrupt/resume/状态断言 |

### 6.4 测试文件（改动面必须同步覆盖）

- `remote/cmd/bridge/main_test.go`：`TestLegacyTurnStartResumesPersistedThreadBeforeSafeRetry`（`:882-937`，断言 `turn/start → thread/read → thread/resume → turn/start`）、`TestMobileThreadSummaryResult*`（`:407-493`）、`TestParseBackendSpecsKnownDSH`（`:1836-1868`）、`TestMobileCodexEventEnvelopeWhitelistsTimelineAndBoundsItemPayload`（`:752-782`）。
- `remote/internal/run/control_test.go:51`（断言发出 `turn/interrupt`）；需补「确定性拒绝 → run.control.failed」用例。
- `remote/internal/appserver/client_test.go`（错误码解析）；`remote/internal/backend/dsh_integration_test.go`（补 interrupt/resume/status 冒烟，当前只有 initialize + thread/start）。

### 6.5 落地顺序（避免能力位先于实现）

1. dsh 插件先落地 interrupt/resume/status，并在 `install-appserver.sh` 安装；
2. bridge 增加结构化错误 + 控制失败分类 + 事件白名单；
3. `DSHCapabilities()` 再增补对应能力位（手机侧 gate 才有意义）；
4. 最后按需改手机文案/gate。
   > 反序的风险：能力位先声明会让手机提前显示不可用入口；旧 bridge + 新手机的新命令会得到 `error` 事件「unsupported command: X」（`main.go:702-707`，明确失败，不会静默）。

---

## 7. 未覆盖 / 需实测确认

- **代际升级后的 API 复核**：工作区未提交改动把插件依赖指向 `0.2.0-rc.2`（`remote/dsh/appserver/package.json:12-16`、`remote/dsh/install-appserver.sh:22,32-52`），但本机安装仍是 `0.1.5-rc.1`；本文 §4.3/§4.4/§6.3 引用的 `agent.cancel`、`ctx.agents.resume`、`sessionPersistence`、turn/end reason 词表都需在 0.2.0-rc.2 的 `dsh-agent`/`dsh-agent-loop`/`dsh-session` 上重新确认（尤其 `dsh-agent-loop` 由 `dsh-base` bundle 间接提供，不在 profile 的直接依赖里）。
- dsh live agent 上 `cancel({kind:'user'})` 的实际行为（并发 `whenIdle` 的返回、`turn/start` 请求的响应时机、aborted 后 `thread/turns/list` 能否立刻反映）——需真机/真进程验证。
- `thread/resume` 后 dsh 的 `turn/start` 是否需要在同一 turn 编号上续跑、`clientUserMessageId` 幂等语义（bridge 依赖 `clientUserMessageId`，dsh 目前忽略该参数）。
- codex app-server 的 `thread/turns/list` 是否原生返回 `interrupted` 与 `completedAt`（bridge 行为已按此规格实现，`remote/README.md:218`），以及 codex 的 `thread/status/changed` 是否真会发出（bridge 目前丢弃，手机逻辑存在但不可达）。
- 手机 `updatedAt` 毫秒/秒单位问题在 codex 侧的真实约定（本报告只据 bridge 测试 `remote/internal/run/coordinator_test.go:18` 的 `updatedAt:10` 与 dsh 旧副本 `protocolSeconds()` 推断 canonical 为秒）。
