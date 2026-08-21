# Host Gateway Runtime G1 外部 Coding Agent 接力说明

日期：2026-08-21

状态：G1 已完成（Task 2 收口至 Task 6 自动化 Gate 全通过）；实体机冒烟为外部阻塞（ADB 未授权）。未 push。

项目：`harness-apk`

## 立即动作

在同一台 Mac 上继续时，直接进入：

```bash
cd /Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge
git status --short
git diff -- remote/internal/backend/appserver_adapter.go remote/internal/backend/appserver_adapter_test.go
```

当前工作树包含有意保留的未提交修改和未跟踪文档。以现状继续，先完成 Task 2 契约修正；不要 reset、checkout、clean 或覆盖这些文件。

## Summary

目标是把现有 Harness Bridge 逐步重构成 Host Gateway：Android、Relay、Wire 和账本保持稳定，Codex/DSH 的 app-server method、params、response decode 收口到 adapter；`internal/run` 只依赖 provider-neutral 的 typed operation/outcome。

本轮仅完成设计、实施计划、typed runtime seam，以及 AppServerAdapter 的第一版和一组未提交契约修正。`run.start`、steer、interrupt、UNKNOWN reconciliation 和 Bridge wiring 尚未迁移。

## Source Of Truth

按以下顺序读取并执行：

1. 仓库规则：`/Users/tony/Documents/harness-apk/AGENTS.md`
2. 已确认设计：`docs/superpowers/specs/2026-08-21-codex-app-server-host-gateway-refactor-design.md`
3. G1 逐任务计划：`docs/superpowers/plans/2026-08-21-host-gateway-runtime-g1.md`
4. 本接力说明：记录暂停点、WIP 和当前验证证据
5. 实际代码、测试和 `git diff`：发生冲突时以可验证的仓库事实为准，并同步修正文档

设计固定参考为 OpenAI Codex app-server README 的上游 commit `536f86e5cc9ec1ff38457d099bf320b9d08eeeba`。不要根据 floating `main` 静默改变 G1 协议契约；若必须升级上游版本，先更新 fixture/spec 并单独说明迁移影响。

## Repository State

G1 完成后（2026-08-21）：

```text
worktree: /Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge
branch:   codex/m4-multi-backend-bridge
HEAD:     文档提交（本文档状态更新）
upstream: origin/codex/m4-multi-backend-bridge
ahead:    12 commits（含本文档提交）
push:     未执行
```

G1 新增提交：

```text
effdfa0 修复：收紧 App Server 适配契约
abfce8d 重构：Run 启动改用 Agent Runtime
c630c00 重构：Run 控制改用 Agent Runtime
1028904 重构：Bridge 接入强类型 Runtime
+ 文档提交（计划/spec/handoff 状态更新）
```

本次 Host Gateway 重构已提交：

```text
9f0f755 重构：定义强类型 Agent Runtime 接口
194491f 修复：隔离 Runtime 测试数据副本
0aaa9a0 重构：封装 App Server Runtime 适配器
```

三个提交作者均应保持为：

```text
fang_zhou <fang_zhou@hunliji.com>
```

暂停时工作树：

```text
 M remote/internal/backend/appserver_adapter.go
 M remote/internal/backend/appserver_adapter_test.go
?? docs/superpowers/plans/2026-08-21-host-gateway-runtime-g1.md
?? docs/superpowers/plans/2026-08-21-host-gateway-external-coding-agent-handoff.md
?? docs/superpowers/specs/2026-08-21-codex-app-server-host-gateway-refactor-design.md
```

`remote/internal/run` 没有未提交修改；先前启动的 Task 3 worker 已在写文件前停止。

## Completed Work

### Task 1：typed runtime seam

已完成并提交：

- `remote/internal/agent/runtime.go`
- `remote/internal/agent/fake.go`
- `remote/internal/agent/runtime_test.go`

现有 seam：

```go
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
```

`Operation` 是封闭 typed union；`internal/agent` 内不得加入 raw method、params 或 `json.RawMessage`。FakeRuntime 对 manifest、operation、outcome、event 及 JSON-shaped completion schema 做防御性复制。

### Task 2：AppServerAdapter 第一版

第一版已在 `0aaa9a0` 提交，覆盖：

- ListThreads
- ReadThread
- StartThread
- StartTurn
- SteerTurn
- InterruptTurn
- DSH interrupt capability gate
- invalid / unsupported / unavailable / protocol 分类基础

规格复核后发现四个 Important 契约偏差，已用 TDD 修改，但尚未提交：

1. `ListThreads{Query.CWD:""}` 应返回全部 thread，不能报 `ErrInvalid`。
2. `thread/list` 必须发送 `sortDirection:"desc"`，不能发送 `sortOrder`。
3. `sourceKinds` 必须精确为 `cli/vscode/exec/appServer`，不能额外发送 `subAgent/unknown`。
4. 只有 `context.Canceled`、`context.DeadlineExceeded`、`io.EOF`、`io.ErrClosedPipe`、`syscall.EPIPE` 归为 `agent.ErrUnavailable`；明确 provider/app-server 拒绝必须保留原错误，不能伪装成 unavailable。

暂停前 RED 已确认对应三组失败；当前未提交实现已使新增测试转绿。该 WIP 仍需独立规格复核和代码质量复核后提交。

## Current Verification Evidence

以下命令在当前未提交 WIP 上于 2026-08-21 运行并通过：

```bash
cd /Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge/remote

GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -count=1 ./internal/backend
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -count=1 ./internal/agent
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -count=1 ./...
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -race -count=1 ./internal/agent ./internal/backend

cd dsh/appserver
npm test
```

结果：全量 Go tests 通过；agent/backend race tests 通过；DSH `16/16` 通过；`git diff --check` 通过。

这些证据只说明当前暂停点可测试，不代表 G1 已完成。

## Required Execution Sequence

严格按计划逐任务 RED -> GREEN -> review -> scoped commit。每一步完成标准都必须同时满足代码、测试和提交范围。

### 1. 收口 Task 2 WIP

1. 阅读当前两个 backend 文件及 diff，核对上述四项契约。
2. 运行 adapter 定向、backend、全量 Go 和 agent/backend race tests。
3. 做规格复核：每项 request shape、decode、capability 和错误分类均与 spec/plan 一致。
4. 做代码质量复核：重点检查 error wrapping、nil/context、response envelope、defensive manifest copy 和未声明 operation 不触达 raw backend。
5. 仅提交两个 backend 文件：

```bash
git add remote/internal/backend/appserver_adapter.go remote/internal/backend/appserver_adapter_test.go
git commit -m "修复：收紧 App Server 适配契约"
```

完成标准：上述四项都有直接测试；全量 Go 通过；提交中只有这两个文件。完成后勾选 G1 计划 Task 2。

### 2. Task 3：迁移 run.start

只修改：

- `remote/internal/run/coordinator.go`
- `remote/internal/run/coordinator_test.go`

必须实现：

- 删除 `AppServerCaller`；Coordinator 改持有 `Runtime agent.Executor`。
- `findRecentThread` 执行 `agent.ListThreads{Query: agent.ThreadQuery{CWD: cwd}}`。
- 无近期 thread 时执行 `agent.StartThread`。
- turn gate 改为 `ExecuteTurn func(context.Context, agent.Operation) (agent.Outcome, error)`。
- `StartTurn` 使用 typed fields 和 `StartedTurn.ID`；run 包不再解码 provider JSON。
- 保持 commandcache 幂等：重复 command 只产生一个 turn，UNKNOWN 不自动重放。
- 保持 stale recent thread fallback：明确 `thread not found` 时新建 thread，并只重试一次 turn start。

提交：

```text
重构：Run 启动改用 Agent Runtime
```

### 3. Task 4：迁移 run control 与 reconciliation

只修改：

- `remote/internal/run/control.go`
- `remote/internal/run/control_test.go`

必须实现：

- steer -> `agent.SteerTurn`
- interrupt -> `agent.InterruptTurn`
- reconciliation -> `agent.ReadThread{IncludeTurns:true}`
- steer 成功必须返回非空 `StartedTurn.ID`
- `ErrUnavailable`、context timeout/EOF、`ErrOutcomeUnknown` 才进入 UNKNOWN
- `ErrUnsupported`、`ErrInvalid`、明确 provider 拒绝进入确定失败，不标 UNKNOWN
- UNKNOWN command 重复执行不得重放副作用

提交：

```text
重构：Run 控制改用 Agent Runtime
```

### 4. Task 5：Bridge 注入 typed vertical slice

只修改：

- `remote/cmd/bridge/main.go`
- `remote/cmd/bridge/main_test.go`

必须实现：

- run.start/control 构造并注入 `backend.NewAppServerAdapter(bd)`。
- FIFO turn gate 包装 typed `Runtime.Execute`，不重新构造 raw `turn/start`。
- Android 通用 `type="rpc"` 立即返回稳定错误 `RAW_RPC_DISABLED`，且不触达任何 backend。
- 外部 run.started、route、commandcache、journal 和 Android payload 保持兼容。

提交：

```text
重构：Bridge 接入强类型 Runtime
```

### 5. Task 6：Gate、文档与实体机

按实施计划完成 seam 扫描、全量测试、race、DSH tests、Android 构建与实体机冒烟。更新计划复选框和真实 Gate 结果后，再提交 spec/plan/handoff；不要把未修改文件加入暂存区。

建议文档提交：

```text
文档：锁定 Host Gateway Runtime 重构方案
```

## Invariants And Guardrails

- Host Gateway 继续拥有 Relay、设备认证、AES-GCM、commandcache、RouteStore、journal、workspace、approval 和 completion evidence。
- `commandId/runId/deviceId/workspaceId/bindingId` 是 Harness 稳定身份；provider thread/turn id 不能替代。
- G1 不修改 Android/Wire、Room、routes、journal、command 或 completion 持久化 schema。
- raw app-server method/params 只存在于 adapter；`internal/agent`、`internal/run` 和 Android 领域协议保持 provider-neutral。
- 有副作用 operation 的 outcome 不确定时进入 UNKNOWN，并通过权威 read 对账；禁止自动双发或重放。
- DSH interrupt 明确 unsupported；capability gate 必须在 raw call 前生效。
- 每个提交只暂存本任务文件，使用中文 commit message；不自动 push。
- 新提交作者使用 `fang_zhou <fang_zhou@hunliji.com>`。
- 保护当前用户工作树：保留未跟踪 docs 和不属于当前 Task 的修改。

## Verification

G1 完成前至少运行：

```bash
cd /Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge/remote

GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -count=1 ./...
GOTOOLCHAIN=local /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go test -race -count=1 ./internal/agent ./internal/backend ./internal/run ./cmd/bridge

cd dsh/appserver
npm test

cd ../../..
git diff --check
rg -n 'AppServerCaller|\.Call\(ctx, "(thread|turn)/|case "rpc"' remote/internal/run remote/cmd/bridge/main.go
```

G1 seam 扫描的判定：

- `remote/internal/run` 不得再命中 `AppServerCaller` 或 raw thread/turn calls。
- Bridge 的旧 history/event 投影路径在 G1 可保留，但通用 `rpc` 分支不得调用 backend。

## Device And Network State

联想 LEGION Y900 已曾被 macOS USB 层识别，USB serial 为 `HA2FW767`；暂停时 `adb devices -l` 仍为空，说明 ADB 调试接口/RSA 授权尚未建立。实体机步骤必须从重新枚举开始：

```bash
ADB=/Users/tony/Library/Android/sdk/platform-tools/adb
$ADB devices -l
$ADB -s <adb-serial> shell svc power stayon usb
$ADB -s <adb-serial> shell settings get global stay_on_while_plugged_in
```

只有目标状态为 `device` 且 bitmask 包含 USB 位 `2` 后，才可安装、抓日志、截图或做 UI 自动化。所有设备命令显式带 `-s <adb-serial>`。设备 PIN 仅在人工授权的解锁步骤临时使用，从用户处获取，不写入文件、命令日志或测试产物。

Clash 当前监听 `127.0.0.1:7897`；项目 `AGENTS.md` 中旧端口 `12334` 已过期。联网前先实测监听端口和 `git remote -v`。当前 remote 是 SSH：`git@github.com:zergee24/harness-apk.git`。本任务默认不 fetch、不 push；需要联网操作时先取得用户授权并按实际 remote/proxy 配置执行。

## Out Of Scope

- G2 的 Runtime lifecycle、supervisor/registry 切换
- G3 typed events、approval/user-input interaction 归一化
- G4 history pagination 与 completion 全迁移
- G6 daemon/Unix socket 实验
- Relay、Room、Android 本地 Chat execution 重写
- Wire 或持久化 schema 迁移
- 自动推送、合并主分支或发布生产版本

## Acceptance Criteria

交付 G1 时必须全部满足：

- Task 2 WIP 已复核并形成 scoped commit。
- `run.Coordinator` 和 `ControlCoordinator` 只依赖 typed executor。
- generic Android RPC 返回 `RAW_RPC_DISABLED`，backend 调用数为零。
- commandcache 幂等、UNKNOWN reconciliation、stale thread fallback 行为有回归测试。
- DSH interrupt 在 provider call 前返回 `ErrUnsupported`。
- 全量 Go、指定 race、DSH 16 tests、`git diff --check` 全部通过。
- 实体机若 ADB 已授权，完成计划中的冒烟并记录证据；若仍未授权，明确记录为外部阻塞，不把 G1 设备验收写成通过。
- spec/plan 的状态与真实提交一致；没有 `TBD/TODO` 或虚构 Gate 结果。
- 所有提交范围可审计，工作树剩余修改有清晰归属；未 push。

## Final Report Contract

完成后只报告可验证事实：

- 完成的 Task 与 commit SHA
- 修改文件
- RED/GREEN 和最终验证命令结果
- seam 扫描结果
- 设备验收结果或明确阻塞
- 未解决限制与 G2+ 剩余范围
- branch ahead/behind 和是否 push

不要用主观置信度代替证据。
