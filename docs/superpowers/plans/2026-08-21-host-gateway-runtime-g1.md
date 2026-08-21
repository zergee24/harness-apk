# Host Gateway Runtime G1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 Android/Wire/账本格式的前提下，引入 provider-neutral typed runtime seam，并把 `run.start`、steer、interrupt 和 UNKNOWN reconciliation 从 raw app-server JSON-RPC 迁移到该 seam。

**Architecture:** `internal/agent` 定义封闭的 typed Operation/Outcome interface；`internal/backend.AppServerAdapter` 是兼容 adapter，内部继续使用当前 raw `Backend`。G1 的 `internal/run` 只依赖 `agent.Executor`；完整 event/lifecycle `agent.Runtime` 留到 G2 切 registry。Bridge 其它 history/event 调用暂时保留旧路径，形成可回滚的 vertical slice。

**Tech Stack:** Go 1.23+、Codex app-server JSONL、DSH canonical appserver profile、现有 commandcache/RouteStore/Fake backend。

**Detailed spec:** `docs/superpowers/specs/2026-08-21-codex-app-server-host-gateway-refactor-design.md`

---

## File map

- Create `remote/internal/agent/runtime.go`: provider-neutral interface、operation、outcome、manifest 和稳定错误。
- Create `remote/internal/agent/fake.go`: typed in-process adapter，供 run/bridge 测试使用。
- Create `remote/internal/agent/runtime_test.go`: interface validation、fake 行为和 outcome shape 测试。
- Create `remote/internal/backend/appserver_adapter.go`: raw `Backend` 到 typed executor 的兼容 adapter。
- Create `remote/internal/backend/appserver_adapter_test.go`: method/params/response decode characterization tests。
- Modify `remote/internal/run/coordinator.go`: run.start 改用 typed operation/outcome。
- Modify `remote/internal/run/coordinator_test.go`: fake 改为 typed runtime，锁定幂等/UNKNOWN 行为。
- Modify `remote/internal/run/control.go`: steer/interrupt/read reconciliation 改用 typed runtime。
- Modify `remote/internal/run/control_test.go`: 锁定 typed control 与 reconciliation。
- Modify `remote/cmd/bridge/main.go`: 为 run vertical slice 注入 `AppServerAdapter`；拒绝通用 `rpc`。
- Modify `remote/cmd/bridge/main_test.go`: adapter wiring、raw RPC rejection 和行为等价测试。
- Modify `docs/superpowers/plans/2026-08-21-host-gateway-runtime-g1.md`: 实施过程中勾选步骤并记录 Gate 结果。

## Task 1: 定义 typed runtime seam 与 FakeRuntime

**Files:**

- Create: `remote/internal/agent/runtime.go`
- Create: `remote/internal/agent/fake.go`
- Test: `remote/internal/agent/runtime_test.go`

- [x] **Step 1: 写失败测试，锁定封闭 operation 和 FakeRuntime 行为**

```go
func TestFakeRuntimeRecordsTypedOperationAndReturnsOutcome(t *testing.T) {
    fake := NewFake(Manifest{BackendID: "dsh", Operations: map[OperationKind]bool{OperationStartTurn: true}})
    fake.Script(OperationStartTurn, Outcome{StartedTurn: &TurnRef{ID: "turn-2"}}, nil)

    outcome, err := fake.Execute(context.Background(), StartTurn{
        ThreadID: "thread-1", Text: "继续", ClientMessageID: "command-1",
    })

    if err != nil || outcome.StartedTurn == nil || outcome.StartedTurn.ID != "turn-2" {
        t.Fatalf("outcome=%#v err=%v", outcome, err)
    }
    calls := fake.Calls()
    if len(calls) != 1 || calls[0].Kind() != OperationStartTurn {
        t.Fatalf("calls=%#v", calls)
    }
}

func TestFakeRuntimeRejectsUnsupportedOperationBeforeScript(t *testing.T) {
    fake := NewFake(Manifest{BackendID: "dsh", Operations: map[OperationKind]bool{}})
    _, err := fake.Execute(context.Background(), InterruptTurn{ThreadID: "thread-1", TurnID: "turn-1"})
    if !errors.Is(err, ErrUnsupported) {
        t.Fatalf("error=%v", err)
    }
}
```

- [x] **Step 2: 运行测试并确认 RED**

Run:

```bash
cd remote
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/agent
```

Expected: FAIL，`NewFake`、`Manifest` 或 operation 类型尚不存在。

- [x] **Step 3: 写最小 runtime interface 和 typed models**

```go
type Runtime interface {
    Executor
    Events() <-chan Event
    Done() <-chan error
    Close() error
}

type Executor interface {
    Manifest() Manifest
    Execute(context.Context, Operation) (Outcome, error)
}

type Operation interface {
    Kind() OperationKind
    isOperation()
}

type ListThreads struct { Query ThreadQuery }
type ReadThread struct { ThreadID string; IncludeTurns bool }
type StartThread struct { CWD string }
type StartTurn struct { ThreadID, Text, ClientMessageID string; CompletionSchema map[string]any }
type SteerTurn struct { ThreadID, ExpectedTurnID, Text string }
type InterruptTurn struct { ThreadID, TurnID string }

type Outcome struct {
    Threads       *ThreadPage
    Thread        *ThreadSnapshot
    StartedThread *ThreadRef
    StartedTurn   *TurnRef
    Empty         bool
}
```

`Operation.Kind()` 和私有 marker 均在 `internal/agent` 内实现；不增加 raw operation。

- [x] **Step 4: 实现 FakeRuntime**

Fake 必须：

- 按 `OperationKind` script typed outcome/error；
- 记录 operation 副本；
- 在 manifest 未声明 operation 时返回 `ErrUnsupported` 且不记录 provider side effect；
- 提供有界 event channel、`Done`、幂等 `Close`；
- 不暴露 method string/json.RawMessage。

- [x] **Step 5: 运行 agent 测试并确认 GREEN**

Run: same as Step 2.

Expected: PASS。

- [x] **Step 6: 提交 Task 1**

```bash
git add remote/internal/agent/runtime.go remote/internal/agent/fake.go remote/internal/agent/runtime_test.go
git commit -m "重构：定义强类型 Agent Runtime 接口"
```

## Task 2: 实现 AppServerAdapter 兼容 executor

**Files:**

- Create: `remote/internal/backend/appserver_adapter.go`
- Test: `remote/internal/backend/appserver_adapter_test.go`

- [x] **Step 1: 写失败 characterization tests**

至少逐个锁定：

```go
func TestAppServerAdapterMapsStartTurn(t *testing.T) {
    raw := NewFake("codex")
    raw.OnScript("turn/start", func(_ string, params any) (json.RawMessage, error) {
        got := params.(map[string]any)
        if got["threadId"] != "thread-1" || got["clientUserMessageId"] != "command-1" {
            t.Fatalf("params=%#v", got)
        }
        return json.RawMessage(`{"turn":{"id":"turn-2"}}`), nil
    })
    adapter := NewAppServerAdapter(raw)
    outcome, err := adapter.Execute(context.Background(), agent.StartTurn{
        ThreadID: "thread-1", Text: "目标", ClientMessageID: "command-1",
    })
    if err != nil || outcome.StartedTurn.ID != "turn-2" {
        t.Fatalf("outcome=%#v err=%v", outcome, err)
    }
}

func TestAppServerAdapterRejectsMalformedSteerResult(t *testing.T) {
    raw := NewFake("codex").OnScript("turn/steer", func(string, any) (json.RawMessage, error) {
        return json.RawMessage(`{}`), nil
    })
    _, err := NewAppServerAdapter(raw).Execute(context.Background(), agent.SteerTurn{
        ThreadID: "thread-1", ExpectedTurnID: "turn-1", Text: "继续",
    })
    if !errors.Is(err, agent.ErrProtocol) {
        t.Fatalf("error=%v", err)
    }
}
```

还必须覆盖：ListThreads CWD/ID、ReadThread turns、StartThread ID、Interrupt empty outcome、provider error 包装、未声明 operation 不触达 raw backend。

- [x] **Step 2: 运行 adapter 测试并确认 RED**

```bash
cd remote
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/backend -run AppServerAdapter
```

Expected: FAIL，`NewAppServerAdapter` 尚不存在。

- [x] **Step 3: 实现 operation -> app-server 映射**

```go
type AppServerAdapter struct { raw Backend }

func NewAppServerAdapter(raw Backend) *AppServerAdapter

func (r *AppServerAdapter) Execute(ctx context.Context, op agent.Operation) (agent.Outcome, error) {
    switch value := op.(type) {
    case agent.StartThread:
        raw, err := r.raw.Call(ctx, "thread/start", map[string]any{"cwd": value.CWD})
        return decodeStartedThread(raw, err)
    case agent.StartTurn:
        raw, err := r.raw.Call(ctx, "turn/start", mapStartTurn(value))
        return decodeStartedTurn(raw, err)
    case agent.SteerTurn:
        raw, err := r.raw.Call(ctx, "turn/steer", mapSteerTurn(value))
        return decodeStartedTurn(raw, err)
    case agent.InterruptTurn:
        _, err := r.raw.Call(ctx, "turn/interrupt", map[string]string{"threadId": value.ThreadID, "turnId": value.TurnID})
        return agent.Outcome{Empty: err == nil}, classifyRuntimeError(err)
    default:
        return agent.Outcome{}, agent.ErrUnsupported
    }
}
```

实现 ListThreads/ReadThread 的 decode；缺少必需 ID 或无效 JSON 统一包装 `agent.ErrProtocol`。不得在 agent types 中加入 raw JSON。

- [x] **Step 4: 运行 adapter tests 和 backend 包 tests**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/backend
```

Expected: PASS。

- [x] **Step 5: 提交 Task 2**

```bash
git add remote/internal/backend/appserver_adapter.go remote/internal/backend/appserver_adapter_test.go
git commit -m "重构：封装 App Server Runtime 适配器"
```

## Task 3: 迁移 run.start 到 typed runtime

**Files:**

- Modify: `remote/internal/run/coordinator.go`
- Modify: `remote/internal/run/coordinator_test.go`

- [x] **Step 1: 先把 coordinator tests 改为 typed FakeRuntime**

期望 fixture 使用 `agent.NewFake` 或测试内 typed runtime，断言 `Operation.Kind()` 和字段；删除 method-string 断言。新增：

```go
func TestRunStartUsesTypedRuntimeWithoutRawRPC(t *testing.T) {
    coordinator, runtime := runFixture(t)
    _, err := coordinator.Start(context.Background(), startCommand())
    if err != nil { t.Fatal(err) }
    kinds := operationKinds(runtime.Calls())
    if got, want := kinds, []agent.OperationKind{
        agent.OperationListThreads,
        agent.OperationStartThread,
        agent.OperationStartTurn,
    }; !reflect.DeepEqual(got, want) { t.Fatalf("kinds=%v want=%v", got, want) }
}
```

- [x] **Step 2: 运行 test 并确认 RED**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/run -run RunStart
```

Expected: compile FAIL，因为 Coordinator 仍要求 `AppServerCaller`。

- [x] **Step 3: 最小迁移 Coordinator**

- 删除 `AppServerCaller`。
- `Coordinator.Runtime` 改为 `agent.Executor`。
- `findRecentThread` 执行 `agent.ListThreads` 并读取 typed `ThreadPage`。
- stale recent thread fallback 执行 `agent.StartThread`。
- turn gate 的注入从 raw callback 改为：

```go
ExecuteTurn func(context.Context, agent.Operation) (agent.Outcome, error)
```

- `turn/start` 结果直接读取 `outcome.StartedTurn.ID`；不再在 run 包 decode provider JSON。
- `completionOutputSchema()` 暂保留为 provider-neutral map，通过 `agent.StartTurn.CompletionSchema` 传给 adapter。

- [x] **Step 4: 运行 run.start tests，确认 GREEN 且旧行为测试仍通过**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/run -run RunStart
```

Expected: PASS，重复 command 仍只执行一次 StartTurn，UNKNOWN 不重放。

- [x] **Step 5: 提交 Task 3**

```bash
git add remote/internal/run/coordinator.go remote/internal/run/coordinator_test.go
git commit -m "重构：Run 启动改用 Agent Runtime"
```

## Task 4: 迁移 steer、interrupt 与 UNKNOWN reconciliation

**Files:**

- Modify: `remote/internal/run/control.go`
- Modify: `remote/internal/run/control_test.go`

- [x] **Step 1: 写 typed control RED tests**

测试必须断言：

- steer 发送 `agent.SteerTurn` 并使用 returned `TurnRef` 推进 route；
- interrupt 发送 `agent.InterruptTurn`，不完成 run；
- provider error 后 command 标 UNKNOWN，重复 Execute 不重放；
- reconciliation 发送 `agent.ReadThread{IncludeTurns:true}`，只在 expected turn 后出现权威 next turn 时完成；
- `ErrUnsupported` 走确定失败，不标 UNKNOWN。

- [x] **Step 2: 运行 control tests 并确认 RED**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/run -run 'Control|Steer|Interrupt'
```

Expected: compile/assertion FAIL，因为 ControlCoordinator 仍 raw Call。

- [x] **Step 3: 最小迁移 ControlCoordinator**

```go
switch command.Type {
case "run.steer":
    outcome, err = c.Runtime.Execute(ctx, agent.SteerTurn{
        ThreadID: route.ThreadID, ExpectedTurnID: dispatchTurnID, Text: command.Text,
    })
case "run.interrupt":
    outcome, err = c.Runtime.Execute(ctx, agent.InterruptTurn{
        ThreadID: route.ThreadID, TurnID: dispatchTurnID,
    })
}
```

- `SteerTurn` 必须返回 non-empty `StartedTurn`；否则 `ErrOutcomeUnknown`。
- `ReconcileUnknown` 从 typed `ThreadSnapshot.Turns` 寻找 next turn。
- 只有 `ErrUnavailable`、context timeout/EOF 或明确 `ErrOutcomeUnknown` 标 UNKNOWN；`ErrUnsupported`、`ErrInvalid`、provider 明确拒绝写 FAILED。

- [x] **Step 4: 运行整个 run 包并确认 GREEN**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./internal/run
```

Expected: PASS。

- [x] **Step 5: 提交 Task 4**

```bash
git add remote/internal/run/control.go remote/internal/run/control_test.go
git commit -m "重构：Run 控制改用 Agent Runtime"
```

## Task 5: Bridge 注入 vertical slice 并关闭 raw RPC

**Files:**

- Modify: `remote/cmd/bridge/main.go`
- Modify: `remote/cmd/bridge/main_test.go`

- [x] **Step 1: 写 Bridge RED tests**

```go
func TestExecuteCommandRejectsRawRPCWithoutCallingBackend(t *testing.T) {
    raw := backend.NewFake("codex")
    b := bridgeFixture(t, raw)
    err := b.executeCommand(context.Background(), "phone-1", protocol.Command{
        Type: "rpc", RequestID: "request-1", Method: "thread/read",
    })
    if err != nil { t.Fatal(err) }
    if len(raw.Calls()) != 0 { t.Fatalf("raw calls=%#v", raw.Calls()) }
    // fixture 断言返回 error event 的 payload.code == RAW_RPC_DISABLED
}
```

另新增一个 run.start Bridge test，断言注入 adapter 后只触达 adapter 对应 raw calls，外部 `run.started` payload 不变。

- [x] **Step 2: 运行 Bridge tests 并确认 RED**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./cmd/bridge -run 'RawRPC|Runtime'
```

Expected: FAIL，现有 `rpc` 会触达 backend。

- [x] **Step 3: 注入 AppServerAdapter**

- 在 `startRun` / `controlRunWithReconciledTurn` 构造 `backend.NewAppServerAdapter(bd)`。
- `Coordinator.Runtime`、`ControlCoordinator.Runtime` 使用该 typed executor。
- turn FIFO gate 包装 `Runtime.Execute`，不得重新构造 raw `turn/start`。
- `case "rpc"` 改为发送：

```json
{"code":"RAW_RPC_DISABLED","latestLine":"不支持通用后端调用"}
```

不得调用 backend。

- [x] **Step 4: 运行 Bridge 包 tests 和全量 Go tests**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./cmd/bridge
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./...
```

Expected: PASS。

- [x] **Step 5: 运行 race tests**

```bash
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -race ./internal/agent ./internal/backend ./internal/run ./cmd/bridge
```

Expected: PASS；若现有 backend event close race 被触发，先写最小回归测试再修复，不忽略。

- [x] **Step 6: 运行 DSH tests**

```bash
cd remote/dsh/appserver
npm test
```

Expected: 16 tests, 16 pass。

- [x] **Step 7: 提交 Task 5**

```bash
git add remote/cmd/bridge/main.go remote/cmd/bridge/main_test.go
git commit -m "重构：Bridge 接入强类型 Runtime"
```

## Task 6: Gate 验证、文档更新与实体机冒烟

**Files:**

- Modify: `docs/superpowers/plans/2026-08-21-host-gateway-runtime-g1.md`
- Optionally modify: `remote/README.md` only if operator-visible behavior changed

- [x] **Step 1: seam 扫描**

```bash
rg -n 'AppServerCaller|\.Call\(ctx, "(thread|turn)/|case "rpc"' remote/internal/run remote/cmd/bridge/main.go
```

Expected for G1: `internal/run` 无命中；Bridge 历史/投影旧路径允许保留明确命中，但 `case "rpc"` 不得调用 backend。

- [x] **Step 2: 完整验证**

```bash
cd remote
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test ./...
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -race ./internal/agent ./internal/backend ./internal/run ./cmd/bridge
cd dsh/appserver && npm test
```

- [x] **Step 3: 实体机前置**

```bash
ADB=/Users/tony/Library/Android/sdk/platform-tools/adb
$ADB devices -l
$ADB -s <serial> shell svc power stayon usb
$ADB -s <serial> shell settings get global stay_on_while_plugged_in
```

只有设备状态为 `device` 且 bitmask 包含 `2` 后继续。多设备命令全部携带 `-s <serial>`。

Gate 结果（2026-08-21）：`adb devices -l` 输出为空，目标设备（联想 LEGION Y900，USB serial `HA2FW767`）未被 ADB 枚举，USB 调试授权未建立。实体机冒烟记为**外部阻塞**，不得视为 G1 设备验收通过。

- [ ] **Step 4: 构建、安装和冒烟**（阻塞：Step 3 前置未满足，未执行）

```bash
./gradlew assembleDebug
$ADB -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

使用用户提供的设备 PIN 解锁后，验证：Codex thread list、run.start、steer、approval；DSH run.start 和 interrupt unsupported 降级；断网恢复一次。不得将 PIN 写入文件或日志。

- [x] **Step 5: 更新计划 Gate 结果并提交文档**

```bash
git add docs/superpowers/specs/2026-08-21-codex-app-server-host-gateway-refactor-design.md \
  docs/superpowers/plans/2026-08-21-host-gateway-runtime-g1.md remote/README.md
git commit -m "文档：锁定 Host Gateway Runtime 重构方案"
```

只 stage 实际修改且属于本任务的文件；若 `remote/README.md` 未修改，不得传给 `git add`。

## Gate 结果（2026-08-21，G1 完成）

- seam 扫描（grep -rnE 'AppServerCaller|\.Call\(ctx, "(thread|turn)/|case "rpc"'）：`internal/run` 零命中；`cmd/bridge/main.go` 仅剩 `case "rpc"`（拒绝分支，零 backend 调用）与 G1 允许保留的 legacy history/投影路径。
- `go test -count=1 ./...` 全部通过；`go test -race -count=1 ./internal/agent ./internal/backend ./internal/run ./cmd/bridge` 通过。
- DSH `npm test`：16/16 通过。`git diff --check` 通过。
- 实体机：外部阻塞（见 Task 6 Step 3），未安装、未冒烟；G1 设备验收记为未完成。
- 提交序列：`effdfa0` 修复：收紧 App Server 适配契约；`abfce8d` 重构：Run 启动改用 Agent Runtime；`c630c00` 重构：Run 控制改用 Agent Runtime；`1028904` 重构：Bridge 接入强类型 Runtime。未 push。

## Self-review checklist

- [x] Spec 的每个 G1 目标都有对应 Task。
- [x] 无 `TBD`、`TODO`、`similar to` 或未定义类型。
- [x] `Executor`、`Runtime`、`Operation`、`Outcome`、`AppServerAdapter` 命名在所有 Task 一致。
- [x] 每个生产代码步骤前都有明确 RED 测试和预期失败。
- [x] 不修改 Wire、Room、routes/journal/command/completion schema。
- [x] 不让 Android/Relay 获得 app-server raw method/params。
- [x] 不自动 push。
