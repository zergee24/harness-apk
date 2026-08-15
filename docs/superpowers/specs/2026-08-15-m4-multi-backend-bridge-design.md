# M4 Mac Bridge 多后端（Codex + DeepSeek Harness）设计

日期：2026-08-15

实施周期：待定（基线 `test` 已含 M2/M3，可随时开始 G0）

状态：D1/D2 已确认（DSH 路线 A；免产品计划熔断替换）；G0 契约锁定与 DSH spike 执行中

关联路线图：`docs/product-plan.md`；前置设计：`docs/superpowers/specs/2026-08-07-m2-cross-device-run-design.md`；实施计划：`docs/superpowers/plans/2026-08-15-m4-multi-backend-bridge.md`

## 1. 目标

把 Mac Bridge 从"独占一个 Codex app-server"升级为"一台 Mac 同时挂多个后端（第一期 Codex + DeepSeek Harness，架构上支持任意 JSON-RPC/JSON-stream stdio CLI 后端），同一部手机通过现有单条设备连接同时控制全部后端"。

核心结果：

- 手机在 Codex Remote Node 中看到 Mac 的后端列表（至少 `codex`、`dsh`），可切换、可从两个后端并行发起任务。
- 各后端独立会话、独立任务、独立进程生命周期：一个后端崩溃/重启不影响另一个，重连后各自按 M2 恢复语义恢复。
- 后端能力差异正确表达：DSH 首版若无审批事件，手机端对该后端隐藏审批入口，不伪造审批。
- 旧版 Android App 与旧版 Bridge 互不破坏：空 `backendId` 恒等于默认后端（codex）。

北极星指标：**同一部手机、一条 WS 连接，同时监督 Mac 上两个后端各自的"发起 -> 通知 -> 查看 -> 转向 -> 审批（若有）-> 验收"闭环，互不干扰。**

## 2. 当前基线

可直接复用（均在 `test`，`b356a98`，含 M2/M3）：

- Relay 只转发加密 `WireMessage`，不读取正文 —— **多后端维度在密文内，Relay 零改动**。
- Android 与 Bridge 的 AES-GCM、Message ID、Sequence、ACK、离线队列、退避重连。
- Bridge 的 `internal/appserver`（Codex JSON-RPC client + 契约校验）—— 已是事实上的 Codex 协议适配层。
- Bridge 的 `internal/run`（RouteStore、Coordinator、审批账本）、`internal/journal`（Logical Event 重放）、`internal/commandcache`（幂等）、`internal/workspace`（绑定/基线）、`internal/completion`（完成证据）。
- Android 的 Run/Binding/Outbox/Sync/ApprovalPolicy 持久化与多设备安全配对。
- dsh CLI（`@deepseek-ai/dsh@0.1.0-rc.6`，本机 npm 全局）：Cordis 插件栈、profile 机制、`~/.dsh` 会话落盘、`--profile headless` 一次性任务、web/Typert Remote 内部 API。

必须修正/新增：

- Bridge `runServe` 只拉起一个 app-server；`host.status` 不携带后端/能力列表；Android 端能力集合硬编码（`remoteFeatureAvailability`）。
- 所有路由/账本（Route、Approval、LogicalEvent）没有 backend 维度；`routes.json` 键为 `runID`。
- Android `RemoteCommand`/`RemoteEvent`/Run 记录没有 `backendId`；UI 无后端切换。
- M2 遗留的 `requestUserInput` 误分类问题随能力上报一并修正。

## 3. 第一性原理取舍

### 3.1 用户操作的对象

用户操作的是**后端上的任务（Run）**。Backend 是 Run 的第一归属维度（类似 Project 之于会话）：

- Backend：一台 Mac 上一个可执行任务的 agent 进程（`codex`、`dsh`）。
- Run：一次明确目标的执行，`(backendId, runId)` 全局唯一。
- Thread/Session：后端内部连续上下文，可承载多个 Run。
- Device 与 Transport：手机仍只有一条 WS 连接；backend 只是密文内 Command/Event 的路由维度，**不是新的网络通道**。

### 3.2 保留

- 项目是唯一长期归属；不自动写文件/Commit/Push；不新增任务 Tab。
- Wire V1 传输层（AES-GCM、TTL、AAD 字段）不动。
- M2 的恢复语义（Outbox、幂等账本、Logical Event Journal、权威 Snapshot、审批去重）对每个后端独立成立。
- Mac 侧权威：后端进程使用 Mac 用户自己的登录/凭据/权限。

### 3.3 不做

- 同一手机同时连接多台 Mac（多 host）：本期不做，协议不封死。
- 第三后端接入：只留接口，不做实现。
- 手机终端、文件同步、自动 Pull/Merge/强推。
- 不要求 DSH 首版具备与 Codex 相同的能力；差异用能力协商表达。

## 4. 架构

```text
Harness APK <-- HTTPS/WSS --> Aliyun Relay <-- WSS --> Mac Bridge
                                                       |-- backend: codex <-- stdio --> codex app-server
                                                       |-- backend: dsh   <-- stdio --> dsh --profile appserver

手机单条 WS 不变；backendId 在密文内 Command/Event 上路由
```

- Bridge 持有 N 个 `Backend`，各自独立子进程、独立 ProcessEpoch、独立重启。
- `host.status` / `backend.list` 向手机上报后端与能力。
- 手机命令带 `backendId`（空 = 默认后端 codex）。
- Bridge 按 `(backendId, threadId)` 路由入站命令、按 Route 的 `(backendId, runId)` 路由出站事件。

## 5. 协议契约（G0 锁定）

### 5.1 Command / Event 增加 backendId

`remote/internal/protocol/protocol.go`：

```go
type Command struct {
    // ...现有字段（M2 版）不变
    BackendID string `json:"backendId,omitempty"` // 空 = 默认后端
}

type Event struct {
    // ...现有字段不变
    BackendID string `json:"backendId,omitempty"`
}
```

Wire `WireMessage` 不动；`backendId` 只存在于密文内。Android 对应：

```kotlin
data class RemoteCommand(
    // ...现有字段不变
    val backendId: String? = null,      // null/空 = 默认后端
)
data class RemoteEvent(
    // ...现有字段不变
    val backendId: String? = null,
)
```

### 5.2 后端清单上报

`host.status` 事件 payload 升级为**加性**结构（沿用 `schemaVersion: 1`，新增 `backends` 数组；`message` 是 Event 字段，不在 payload 内）：

```json
{
  "schemaVersion": 1,
  "capabilities": ["workspace.candidates.v1", "run.lifecycle.v1", "logical-replay.v1", "approvals.v1"],
  "backends": [
    {
      "id": "codex",
      "name": "Codex",
      "capabilities": ["workspace.candidates.v1", "run.lifecycle.v1", "logical-replay.v1", "completion-evidence.v2", "approvals.v1"]
    },
    {
      "id": "dsh",
      "name": "DeepSeek Harness",
      "capabilities": ["workspace.candidates.v1", "run.lifecycle.v1", "logical-replay.v1", "completion-evidence.v2"]
    }
  ]
}
```

兼容规则：

- 顶层 `capabilities` 保留给旧版 Android（等于默认后端 codex 的能力，保证旧 APK 行为不变）；新版 Android 优先读 `backends`，缺失时回退单后端 codex 视图。
- 新增命令 `backend.list`（requestId 语义与现有命令一致），响应为 `rpc.response` 事件，`result` 为同一 backends 数组。**G0 锁定：两者都实现，`host.status` 为主，`backend.list` 用于刷新。**

### 5.3 能力名清单（G0 锁定）

沿用现有能力名（它们本来就是后端级特性），新增 `approvals.v1`、`user-input.v1` 两个显式能力：

| 能力名 | 含义 | codex | dsh 首版 |
| --- | --- | --- | --- |
| `workspace.candidates.v1` | 工作区候选/绑定/基线 | ✓ | ✓ |
| `run.lifecycle.v1` | run.start / run.steer / run.interrupt / run.snapshot | ✓ | ✓ |
| `logical-replay.v1` | sync.resume / event.ack / journal 重放 | ✓ | ✓ |
| `completion-evidence.v2` | 完成证据卡 | ✓ | ✓ |
| `turn-command-idempotency.v1` | turn 命令幂等 | ✓ | ✓ |
| `thread-history-pagination.v1` / `thread-latest-user-message.v1` / `thread-execution-status.v1` / `thread-lazy-continuation.v1` | 线程历史/状态能力 | ✓ | ✓（G2 逐项确认） |
| `approvals.v1`（新增） | approval.request / approval.respond | ✓ | ✗ |
| `user-input.v1`（新增） | user_input.request / 对应响应 | ✓（修正误分类后） | ✗ |

Android 端 `remoteFeatureAvailability` 按**选中后端的**能力集合计算；新增 `canHandleApprovals = "approvals.v1" in capabilities` 用于审批入口降级。

### 5.4 兼容规则

- 手机命令无 `backendId` → Bridge 路由到默认后端（`codex`）。
- 旧 Bridge（不认识 backendId）→ 手机端收到无 backends 字段的 host.status，默认视为单后端 codex，行为与现状一致。
- 后端列表变化（后端进程退出）→ host.status 在重连时重新上报；已存在的 Run 仍可读（路由账本持久化）。
- 同一 cwd 可被 codex 与 dsh 各自绑定为不同 Binding（`(backendId, bindingId)`）。

## 6. Bridge 侧设计

### 6.1 `remote/internal/backend` 接口

```go
// G1 落地签名（2026-08-15 锁定）：能力直接用既有能力名字符串集合，
// 事件为归一化 Message（BackendID/Method/Params），RPC 面保持 app-server
// canonical 协议（Call/Notify/Respond），进程面由监督者持有。
type Message struct {
    BackendID string          // 事件来源后端
    ID        json.RawMessage // server request id（审批），可空
    Method    string          // 归一化方法名（如 "turn/completed"、"item/started"）
    Params    json.RawMessage // 原样透传，翻译逻辑在 Bridge 侧
}

type Backend interface {
    ID() string
    Name() string
    Capabilities() []string            // 既有能力名（workspace.candidates.v1 等）+ approvals.v1/user-input.v1
    ProcessEpoch() string              // 进程代际，审批 STALE 判定沿用 M2
    Start(ctx context.Context)         // 启动读取循环
    Call(ctx context.Context, method string, params any) (json.RawMessage, error)
    Notify(ctx context.Context, method string, params any) error
    Respond(ctx context.Context, ref ServerRequestRef, result any) error
    Messages() <-chan Message          // 归一化事件流
    Done() <-chan error                // 进程退出原因（监督者据此重启）
    Close() error
}
```

### 6.2 实现

- `codexBackend`：包装现有 `internal/appserver` client（JSON-RPC over stdio），行为不变；`ProcessEpoch` 沿用。
- `dshBackend`：按路线 A 对接 `dsh --profile appserver --listen stdio://`（见第 7 节）。
- `serve` 增加可重复 `--backend` 参数（`--backend codex`、`--backend dsh` 及后端专属 flag，如 `--dsh-exec`），每个后端一个受监督子进程：独立启动/重启退避/独立 epoch；`Close` 时全部停止。
- `handleAppServer` 的翻译逻辑（approval/时间线/完成证据）改为消费 `BackendEvent`；`routeForParams` 先按 backend 过滤路由表。

### 6.3 状态与路由升级

- `routes.json`：Route 键从 `runID` 改为 `(backendID, runID)`；Route 结构加 `BackendID`；旧记录按 `backendID=""` 读为 codex。
- `logical-events.log`：LogicalEvent 加 `BackendID` 字段（journal schema v1 → v2，向后兼容读）。
- Approval 账本：`Approval` 加 `BackendID`；审批响应校验 `(backendID, processEpoch, serverRequestID)`。
- `bridge.json`：加 `Backends` 配置段（serve 用参数生成，持久化便于 host.status 恢复上报）。
- 状态目录 schema v2 → v3：新字段可被 v2 Bridge 忽略，回滚安全。

## 7. DSH 后端（路线 A，已定案）

### 7.1 `dsh --profile appserver`

新增 dsh profile（Cordis 插件补丁层），向 stdio 暴露与 codex app-server 相同的 JSON-RPC 面：

- `initialize` / `initialized`（clientInfo 标识 `harness_remote_bridge`）。
- `thread/list`、`thread/read`、`thread/start`：映射到 dsh `sessions` 服务（SessionId、事件回放）。
- `turn/start`、`turn/steer`、`turn/interrupt`：映射到 dsh agent `followup` / 会话事件；steer 语义按 dsh agent 实际 API 收敛（spike 锁定）。
- 事件流：把 dsh agent 事件（`assistant/message`、`item/*`、`turn/end` 等）翻译为 codex 同款 `item/started`、`item/completed`、`item/agentMessage/delta`、`turn/completed` 通知。
- 审批：若 dsh 无等价机制，则不发送 `*requestApproval` 通知，能力清单不含 `approvals.v1`；`approval.respond` 返回"不支持"错误事件。

### 7.2 spike 验证点（G0，2026-08-15 已验证）

1. ✅ 最小插件以 `~/.dsh/profiles/appserver`（bundle：`@deepseek-ai/dsh-base`）启动并持有 stdin/stdout JSON-RPC 循环（进程常驻，不调用 appExit）。
2. ✅ `agents.create` + `followup` + `whenIdle` + `sessions.flush` 足以实现 thread/start、turn/start、thread/read、thread/list；事件流经轮询 `session.events`（200ms）验证（`turn/started`、`item/agentMessage/delta`、`turn/completed`）。
3. ⚠️ steer = 同一 agent 再次 followup（已验证同一会话续跑）；**interrupt 未找到直接公开 API**（agent-loop 内部有 phase AbortController / "ancestor interrupt" 语义）——G2 探索，不可行则 dsh 不声明 interrupt 并降级。
4. ✅ 审批/用户输入在直接驱动面没有等价事件（权限模型以 sandbox preset 为主）——dsh 首版不声明 `approvals.v1` / `user-input.v1`，按 D4 降级。
5. ⚠️ 与 `dsh web` 共用 `~/.dsh/sessions`（按 cwd 编码分目录、`session.jsonl.zstd`）；**并发写入同一会话目录的安全性 G2 验证**，必要时独立 `$DSH_HOME`。

详细结论与部署机制见 `remote/spike/dsh-appserver/README.md`（G0 提交内）。

## 8. Android 侧设计

### 8.1 数据模型

```kotlin
data class RemoteBackend(
    val id: String,          // "codex" | "dsh"
    val name: String,
    val capabilities: Set<String>,
)

data class RemoteProfile(/* 不变：仍是一个 host 的配对 */)

data class RemoteRun(/* 现有 M2 字段 */, val backendId: String) // Room 新增列，默认 "codex"
```

`RemoteUiState` 增加：`backends: List<RemoteBackend>`、`selectedBackendId: String`（默认 `codex`）。

### 8.2 RemoteClient / Outbox / Sync

- `send(command)`：命令 JSON 增加 `backendId = selectedBackendId`（null 兼容旧 Bridge：为空则不发该字段）。
- `RemoteCommandOutbox`：重放载荷保留 `backendId`。
- `handleEvent`：`RemoteEvent.backendId` 解析；按 `(backendId, threadId)` 过滤事件到当前视图；事件落库带 backendId。
- 能力计算：`remoteFeatureAvailability(backend.capabilities)` 按选中后端计算，取代硬编码。
- 旧 Bridge 兼容：host.status 无 backends 字段 → `backends = [codex 默认]`，selectedBackendId = codex。

### 8.3 UI

- Codex Remote 屏幕顶部后端切换（chips）：切换后线程列表、Run 列表按后端过滤；Run 卡片与通知标题带后端名。
- DSH 无 `approvals.v1`：审批区域对该后端不出现；`approval.request` 事件若意外到达则显示"等待 Mac"降级文案。
- 活动页（M2）按后端分组显示进行中/待处理。

### 8.4 兼容与迁移

- Room 迁移：`remote_runs` 等表加 `backendId TEXT NOT NULL DEFAULT 'codex'`；旧数据回填 codex。
- 降级路径：新 APK + 旧 Bridge → 单后端 codex 视图；旧 APK + 新 Bridge → 全部命令无 backendId → codex 路由，功能与现状一致。

## 9. 验收标准

1. 手机看到 Mac 后端列表（codex + dsh），可切换、可从两个后端并行发起任务（同一 WS 连接内两个 run 同时 in-flight）。
2. 后端进程崩溃/重启隔离：kill dsh 后端，codex 进行中任务不受影响；重连后各自恢复。
3. M2 恢复语义对每个后端独立成立（断网 10 分钟、进程重建、Bridge 重启、单后端崩溃四类故障矩阵）。
4. DSH 无审批能力时手机端正确降级显示，不伪造审批。
5. 旧 APK ↔ 新 Bridge、新 APK ↔ 旧 Bridge 双向兼容。

## 10. 迁移与回滚

- Bridge：备份 `~/.harness-remote` 全目录；v3 状态（routes/logical-events/bridge.json 新字段）可被 v2 Bridge 读取（新字段忽略）即可回滚；禁止只删单个账本文件（沿用 M2 恢复集纪律）。
- Android：Room 迁移可逆（新列删除策略与 M2 一致）；测试通道自动更新，不强制用户手动装包。
- dsh profile 安装到 Mac 用户环境（`~/.dsh` 或 profile 目录），与 Bridge 状态分离；卸载不影响既有 dsh 使用。

## 11. 不在范围

- 同一手机同时连接多台 Mac（多 host）——本期不做，协议不封死。
- 第三后端接入（架构留接口，不做实现）。
- 手机终端、文件同步、自动 Pull/Merge/强推。
- 修改 M1/M3 范围；不新增任务 Tab；不改变"项目是唯一长期归属"。
