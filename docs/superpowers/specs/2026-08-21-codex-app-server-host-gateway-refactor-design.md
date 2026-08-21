# Codex App Server 驱动的 Host Gateway 重构设计

日期：2026-08-21

状态：G1 Run Runtime vertical slice 已交付（`effdfa0`..`1028904`）；实体机冒烟因 ADB 未授权外部阻塞。G2 待启动。

实施基线：`codex/m4-multi-backend-bridge`，HEAD `d520703`

关联资料：

- `docs/superpowers/specs/2026-08-15-m4-multi-backend-bridge-design.md`
- `docs/superpowers/plans/2026-08-15-m4-multi-backend-bridge.md`
- OpenAI Codex app-server README（固定上游 commit `536f86e5cc9ec1ff38457d099bf320b9d08eeeba`）

## 1. 决策

本次不删除 Harness Bridge 的产品职责，而是把它重构为 **Host Gateway**：

```text
Harness Android 稳定领域协议
  -> AES/WSS Relay
  -> Host Gateway
       |-- CodexAdapter -> codex app-server
       `-- DshAdapter   -> dsh appserver profile
```

Host Gateway 继续拥有设备身份、路由、幂等、恢复、审批账本、工作区和完成证据。Codex app-server 只替换 Codex-specific 的 thread/turn/item、审批和历史协议实现。

本设计替代 M4 spec 第 6.1 节将 `Call(method, params)` 作为长期 `Backend` interface 的决定；其余 M4 的多后端、Wire V1、兼容和恢复目标继续有效。

## 2. 问题

当前 `remote/internal/backend.Backend` 是浅模块：

- 同时暴露进程生命周期和 raw JSON-RPC；
- `run.Coordinator`、`ControlCoordinator` 和 `bridge` 都直接知道 `thread/list`、`turn/start` 等上游 method；
- Android 领域行为会受 Codex experimental schema 变化影响；
- DSH 被当成“另一个 Codex app-server executable”，能力差异只能靠粗粒度字符串表达；
- `backend.Message{Method, Params}` 让审批、完成和 timeline 的 provider 解析散落在 4400 行 Bridge 主文件中。

结果是 app-server 协议变化需要同时修改 adapter、run、bridge 和 Android 投影，缺少 locality。

## 3. 目标与非目标

### 3.1 目标

1. Codex/DSH wire protocol 只存在于各自 adapter 内。
2. `run` 与 Host Gateway 只依赖 provider-neutral 的强类型 operation、outcome 和 event。
3. 保留 M2/M4 的 commandcache、journal、route、approval、completion 和 workspace 语义。
4. 支持增量迁移；每个 Gate 都能切回当前兼容 binary，不双发真实 turn。
5. 固定受支持 Codex 版本，并用生成 schema/契约测试发现上游漂移。
6. capability 同时表达移动端粗粒度能力和 adapter 的单操作支持。

### 3.2 非目标

- 不让 Android 或 Relay 直接连接 app-server。
- 不采用 app-server experimental TCP WebSocket 作为生产远程边界。
- 不以官方 ChatGPT remote-control 替换自有 Relay、配对、AES-GCM 或 Aliyun Push。
- 不把 `threadId` / `turnId` 当成 Harness `commandId` / `runId`。
- 不在本轮重写 Relay、Room 数据模型或 Android 本地 Chat execution。
- 不为了复用官方 Rust client 将 Go Bridge 整体改写为 Rust。

## 4. 不变量

1. `commandId`、`runId`、`deviceId`、`workspaceId`、`bindingId` 是 Harness 稳定身份；provider handle 不能替代它们。
2. `clientUserMessageId` 只用于 provider 消息关联，不是命令幂等键。
3. outcome 不确定时写入 `UNKNOWN`，必须读取权威 thread/turn 状态对账，禁止自动重放有副作用的操作。
4. `(backendId, processEpoch)` 变化只使该 backend 的 pending interaction stale。
5. completion 必须经过权威 turn read、workspace evidence、terminal freeze 和 journal 后才能发布。
6. 事件只保证单 backend/process stream 内有序，不假设跨 backend 全局顺序。
7. 未声明的操作返回 `ErrUnsupported`；DSH 不得伪造 interrupt、approval 或 user-input 能力。
8. adapter 初始化完成前不得注册进 Host Gateway roster。
9. raw JSON、JSON-RPC request id 和 method string 不得出现在 `internal/agent` interface。

## 5. Seam 与深模块

### 5.1 外部 seam：`internal/agent.Runtime`

```go
package agent

type Executor interface {
    Manifest() Manifest
    Execute(context.Context, Operation) (Outcome, error)
}

type Runtime interface {
    Executor
    Events() <-chan Event
    Done() <-chan error
    Close() error
}

type Factory interface {
    Open(context.Context, Spec) (Runtime, error)
}
```

`Factory.Open` 负责启动进程、启动 read loop、完成 `initialize/initialized`、验证协议与 capability，再返回可注册 Runtime。Host Gateway 不再单独编排 handshake。

G1 vertical slice 先让 `run` 只依赖 `Executor`；现有 supervisor 仍拥有 raw backend 生命周期。G2 才由 adapter 完整实现 `Runtime` 并切换 registry。这个内部迁移 seam 不暴露给 Android，也不改变持久化格式。

`Operation` 是由 `internal/agent` 定义的封闭强类型联合，不提供 `RawRPC`：

```go
type Operation interface { isOperation() }

type ListThreads struct { Query ThreadQuery }
type ReadThread struct { ThreadID string; IncludeTurns bool }
type ListTurns struct { ThreadID string; Cursor string; Limit int }
type StartThread struct { CWD string }
type ResumeThread struct { ThreadID string }
type StartTurn struct {
    ThreadID string
    Text string
    ClientMessageID string
    CompletionSchema map[string]any
}
type SteerTurn struct { ThreadID, ExpectedTurnID, Text string }
type InterruptTurn struct { ThreadID, TurnID string }
type RespondInteraction struct { Ref InteractionRef; Response InteractionResponse }
```

`Outcome` 也是 provider-neutral 的 one-of 结果：

```go
type Outcome struct {
    Threads *ThreadPage
    Thread  *ThreadSnapshot
    Turns   *TurnPage
    StartedThread *ThreadRef
    StartedTurn   *TurnRef
    Empty bool
}
```

调用方必须按 operation 读取对应 outcome；adapter 返回不匹配或缺失结果时统一归类为 `ErrProtocol`。

### 5.2 事件 interface

```go
type Event struct {
    BackendID string
    ProcessEpoch string
    Kind EventKind
    ThreadID string
    TurnID string
    ItemID string
    Timeline *TimelineItem
    Delta string
    Interaction *InteractionRequest
    Turn *TurnSnapshot
}
```

第一期 `EventKind`：

- `turn.started`
- `turn.completed`
- `item.started`
- `item.completed`
- `agent.delta`
- `interaction.approval.requested`
- `interaction.user-input.requested`
- `interaction.resolved`
- `runtime.stopped`

未知 provider 通知只允许记录诊断日志，不直接下发 Android。

### 5.3 interface 深度取舍

评估过三种设计：

1. 单一 `Execute`：方法最少，但若允许 raw payload 会把复杂度搬进 Request/Result。
2. 十余个平铺方法：Go 调用清晰，但 interface 过宽，新增 provider operation 会扩散修改。
3. **选定方案：封闭 typed Operation + typed Outcome + typed Event。** 保留小 interface，同时禁止 method/params 逃逸。

## 6. Adapter 实现

### 6.1 私有 transport seam

当前 `internal/appserver.Client` 和 raw backend interface 暂时保留，但改为 adapter implementation 私有依赖：

```go
type canonicalTransport interface {
    Call(context.Context, string, any) (json.RawMessage, error)
    Notify(string, any) error
    Respond(appserver.ServerRequestRef, any) error
    Messages() <-chan appserver.Message
    Done() <-chan error
    Close() error
}
```

只有 `CodexAdapter` / `DshAdapter` 可导入或持有它。`run`、`bridge` 迁移完成后不得再出现 `.Call(ctx, "thread/` 或 `.Call(ctx, "turn/`。

### 6.2 CodexAdapter

- 启动 `codex app-server --listen stdio://`；daemon + Unix socket 只作为后续可选 adapter。
- 固定并记录 Codex binary 版本。
- `Open` 完成稳定 capability 握手；experimental API 必须按操作 gate。
- 用固定版本生成的 JSON schema/fixtures 验证 request/response/event。
- 将 provider overload `-32001` 分类为 retryable `ErrUnavailable`，重试由无副作用操作策略控制。

### 6.3 DshAdapter

- 启动 `dsh --profile appserver --listen stdio://`。
- DSH JS profile 可以在 adapter 内继续说 canonical app-server，但 Host Gateway 不知道这一事实。
- `InterruptTurn` 当前返回 `ErrUnsupported`；manifest 不声明该 operation。
- 不声明 approval/user-input；收到意外 interaction event 视为 `ErrProtocol`。

### 6.4 FakeRuntime

测试 adapter 使用 typed operation/outcome/event，不脚本化 method string。它是第二个真实 seam 使用者，用于 run、恢复和 Bridge 路由测试。

## 7. Manifest 与 capability

```go
type Manifest struct {
    BackendID string
    Name string
    ProcessEpoch string
    RuntimeProtocol Version
    AdapterVersion Version
    MobileCapabilities []string
    Operations map[OperationKind]bool
}
```

- runtime protocol major 不兼容：拒绝注册。
- `MobileCapabilities` 保持当前 Android `host.status` 兼容。
- `Operations` 供 Host Gateway 做精确 gate。
- DSH 可以声明 `run.lifecycle.v1`，但 `interrupt.turn=false`；Android 后续新增 `interrupt.v1` 显式 gate。

## 8. 职责归属

### 8.1 保留在 Host Gateway

- Relay 连接、设备认证、配对、撤销、AES-GCM、TTL、ACK、Push。
- commandcache 幂等和 UNKNOWN reconciliation。
- RouteStore、backend/process epoch、approval ledger。
- encrypted Logical Event Journal、snapshot、gap/replay。
- workspace binding/fingerprint/baseline。
- frozen completion evidence。
- Android 稳定 command/event 投影。

### 8.2 放入 adapter

- executable 参数和进程启动。
- initialize/initialized。
- app-server method/params/response decode。
- provider event 归一化。
- interaction raw request id 和 provider response shape。
- provider error 分类。
- thread/turn/history pagination shape。

## 9. Android 通用 RPC 决策

当前 Android 正式调用只发送 `thread.list`、`thread.read`、`turn.start` 等 typed command，没有使用 `type="rpc"`。Bridge 的通用 `rpc` 分支违反新 seam：

- 新版本立即拒绝 `type="rpc"`，返回稳定错误 `RAW_RPC_DISABLED`；
- 不把通用 RPC 迁入 `agent.Operation`；
- 如未来需要诊断入口，单独定义受鉴权、显式 allow-list、默认关闭的 host-only 命令。

## 10. 错误模型

```go
var (
    ErrInvalid       = errors.New("invalid runtime operation")
    ErrUnsupported   = errors.New("runtime operation unsupported")
    ErrUnavailable   = errors.New("runtime unavailable")
    ErrProtocol      = errors.New("runtime protocol violation")
    ErrExpired       = errors.New("runtime interaction expired")
    ErrOutcomeUnknown = errors.New("runtime outcome unknown")
)
```

规则：

- 明确 provider error -> 保留可诊断原因并包装稳定类别。
- 写入后断连/超时 -> `ErrOutcomeUnknown`，交 commandcache 对账。
- decode/shape 缺失 -> `ErrProtocol`，不得猜测成功。
- 不支持的 capability -> `ErrUnsupported`，不得发送 provider 请求。
- durability/delivery error 仍属于 Host Gateway，不由 adapter 吞掉。

## 11. 绞杀式迁移 Gate

### G0：规格、基线和契约锁定

- 固定 M4 worktree 和 Codex upstream/binary 版本。
- 保存 app-server schema/fixture compatibility 测试。
- 修正文档回滚声明：旧 schema-v1 binary 不是有效回滚目标。

### G1：Run Runtime vertical slice

- 新建 `internal/agent` typed 模型。
- 建 `AppServerAdapter`，先覆盖 list/read/start thread、start/steer/interrupt turn。
- `run.Coordinator` 与 `ControlCoordinator` 改依赖 typed runtime。
- commandcache、route、journal 和外部 protocol 不变。

### G2：Supervisor 与 roster

- registry 改持有 `agent.Runtime`。
- adapter `Open` 吸收进程启动和 handshake。
- 增加 operation-level capability；DSH interrupt negative test。

### G3：Typed events 与 interaction

- adapter 归一化 timeline、completion、approval、user input。
- Bridge 不再按 method string 解析事件。
- approval ledger 迁移为 opaque `InteractionRef`，兼容读取旧 raw id。

### G4：History、pagination 与 completion

- thread list/read/turn pages 全部走 typed operation。
- experimental pagination 必须由 manifest gate；无能力时使用稳定 resume/read fallback。
- completion reconciliation 只读取 typed snapshot。

### G5：删除 raw seam

- 拒绝 Android `rpc`。
- `rg` 证明 `run`、`bridge` 无 app-server method string 和 raw Call。
- raw transport 只留在 adapter implementation。

### G6：可选 daemon/Unix socket adapter

- feature flag 下试验 `app-server daemon` + Unix socket。
- stdio adapter 保留为回滚路径；daemon experimental 时不设为唯一生产路径。

## 12. 回滚

- G1/G2 不修改 Wire、Room 或持久化账本 schema，可回滚到当前 `d520703` 兼容 binary。
- G3 以后若修改账本：先停止 LaunchAgent，备份整个 `~/.harness-remote`，只能回滚到理解当前 schema 的 binary。
- 不允许删除单个 routes/journal/command/completion 文件来“修复”状态。
- typed/legacy 对比只允许 shadow decode，禁止双发 `turn/start`、steer 或 approval response。

## 13. 验收矩阵

### 自动化

- Codex adapter request shape 与 response/event decode fixtures。
- DSH operation capability negative tests。
- run.start 重复命令只产生一个 turn。
- steer/interrupt UNKNOWN 不自动重放，权威 read 后再对账。
- stale interaction 按 backend + epoch 隔离。
- unknown provider event 不下发 Android。
- adapter Close 与 event delivery 通过 `go test -race`。
- old APK/new Gateway 与 new APK/current Gateway contract tests。

### 故障

- Android 断网 10 分钟后恢复。
- Relay、Host Gateway、单个 app-server 分别重启。
- 单 backend 崩溃时另一个 backend 继续运行。
- 重复、延迟和丢失 response。
- approval response 晚于 process epoch 变化。
- journal gap 触发 snapshot 对账。
- completion evidence 在重启后仍只发布一次。

### 联想实体机

- ADB 必须显示目标状态为 `device`。
- 执行 `svc power stayon usb`，并确认 `stay_on_while_plugged_in` bitmask 包含 USB 位 `2`。
- 安装测试包后使用真实 Codex/DSH backend 做 thread list、run.start、steer、interrupt capability 降级、approval 和断网恢复冒烟。

## 14. 已识别的配套修正

1. M4 spec 旧的“schema v3 可由 v2 直接读取”表述不成立；以完整目录备份和兼容 binary 为准。
2. `runId` 当前实际按全局唯一使用；在 Android 迁移复合主键前，新增 invariant test 禁止跨 backend 复用同一 runId。
3. snapshot 顶层单一 `processEpoch` 不能代表所有 backend；后续改为 per-backend epoch map。
4. backend event channel 关闭与 notification writer 存在竞态风险；adapter 必须拥有 producer shutdown，并用 race test 验证。
5. 空 backend 表示默认 Codex，不再兼作 any-backend wildcard。
