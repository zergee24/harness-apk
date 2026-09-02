# AI-Agent 副屏（Agent Dashboard）一期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 产品决策与已否决方案以 [HANDOFF-agent-dashboard.md](../../../HANDOFF-agent-dashboard.md) 为准，本计划不重复论证。
>
> **审查记录（2026-09-02 只读审查）：** P0（`open -g` 与「聚焦定位」相悖）及三处设计缺口（状态机无终态兜底、waiting 启发式误报、App 连接生命周期缺失）已并入 Task 1/3/5/7；P2 四条（WAL 只读恢复失败降级、`protocol.LogicalEvent` 类型化通道复用、viewed 本地存储、worktree 脏文件禁 `git add -A`）已并入 Task 2/4、风险与部署纪律。审查复核通过的两处事实基础与本计划一致：worktree HEAD `18ec272`（v1.1 WSS 心跳修复）仍是 bridge 正源；类型化推送通道为 `sendLogicalEvent`（worktree main.go:2215）。

**Goal:** 落地一期副屏：闲置手机/平板常亮显示 Mac 上 Codex 桌面 app（`com.openai.codex`）线程的实时状态；点击卡片通过 `codex://` 深链把对应线程聚焦到 Mac 主屏。副屏只读不写、无二级界面；bridge 自养线程不上 dashboard。

**Spike 结论（2026-09-02 本机验证，HANDOFF 阻塞项已解除）：**

1. **rollout 不落盘审批事件。** 近 20 天 ~1200+ 个 rollout 文件全量扫描：`"type":"*approval*"`（及 elicit / permission / review 命名的事件）零命中；文件里出现的 approval 字样全部是 `session_meta` / `turn_context` 的 `approval_policy` 配置字段。60 文件采样的 event_msg 完整词汇表：`task_started / task_complete / agent_reasoning / agent_message / item_completed / patch_apply_end / sub_agent_activity / mcp_tool_call_end / web_search_end / image_generation_end / context_compacted / turn_aborted / token_count / thread_settings_applied / thread_goal_updated / user_message`——无任何审批类事件。→ **一期状态机 = `thinking / running / done / error`**；`waiting_approval` 降级为可选启发式（`task_started` 后超过 45s 无新事件且未 `task_complete` → 显示「疑似受阻」，UI 必须标注非精确）。
2. **线程目录用 `~/.codex/state_5.sqlite` 的 `threads` 表（只读）。** 实测仍在实时写入（本计划编写当天 15:18 有更新），字段含 `id / rollout_path / title / cwd / source / updated_at_ms / archived / is_pinned / preview / model / reasoning_effort / git_branch / project_id`。比扫 `sessions/` 目录（近 30 天 1800+ 文件）成本低且直接拿标题。备用降级源：`~/.codex/session_index.jsonl`（`{id, thread_name, updated_at}`）。注意 `source` 列：主线程为 `vscode` / `unknown` 等，subagent 线程是含 `parent_thread_id` 的 JSON——一期把 subagent 线程过滤或折叠。
3. **深链形如 `codex://threads/<id>`。** ChatGPT.app 注册了 `codex://` scheme（Info.plist `CFBundleURLSchemes`）；app 资源（`app.asar`）内嵌路由含 `codex://threads/`、`codex://threads/new`、`codex://review?...`、`codex://settings/connections`。精确路径格式在 Task 1 用真 id 做运行时探针收尾（官方文档站确认深链覆盖 chats，但当时本机代理未运行未能抓全文）。

**Architecture:** 两棵代码树 + 现有加密中继，dashboard 与现有远程控制共享通道、线程语义零交集。

- **Mac bridge**（改动落在 worktree `remote/`，见部署纪律）：新增 observer 模块
  - 目录快照：只读轮询 `state_5.sqlite`（`mode=ro`，WAL；纯 Go 驱动 `modernc.org/sqlite` 避免 cgo），取 `archived=0 AND updated_at_ms` 最近 24h 的线程；
  - 状态 tailer：对最近活跃的 top N（一期 N=8）线程 tail 其 `rollout_path` JSONL，按 event_msg 驱动状态机；任何新行（含 `token_count`）都是活性信号；最后事件超 30min 无终态则兜底 `done`（approx）；线程退出活跃窗口后停止 tail；
  - 推送：复用现有 `protocol.LogicalEvent` 类型化通道（`sendLogicalEvent`，worktree main.go:2215，连同其 event journal 幂等/重放）扩展线程状态事件，**不新造消息格式**；重连/订阅时发全量快照，之后发增量；
  - 下行：新增 `focus_thread` 指令 = `caffeinate -u -t 2` 唤屏 + `open "codex://threads/<id>"`（**不带 `-g`**：`man open` 明确 `-g` = 不置前，与「点击卡片在主屏聚焦定位」直接相悖；深链必须激活桌面 app 并把线程窗口置前）。
- **Relay**：消息体为 opaque 密文，预期零改动；仅当路由层存在消息类型白名单时补型（Task 6 核实）。
- **Android App**：新增副屏 Dashboard 页（独立 Activity，连接复用现有 `RemoteConnectionService` 前台服务，不另起 WSS）：线程卡片（状态色点 + 标题 + 相对时间 + cwd/git 分支），唯一交互 = 点卡片发 `focus_thread`；前台 `FLAG_KEEP_SCREEN_ON`；原生线程通知不带审批按钮的不对称在卡片文案显性化（「审批需到 Mac 处理」）。

**Tech Stack:** Go 1.23+（bridge）；Kotlin / Jetpack Compose（App）；Gradle 9.6.1 / JDK 17。实施修正：目录读取不走 Go 驱动（modernc 依赖树离线拉不到，见审查记录 P2-1），改为执行 macOS 自带 `/usr/bin/sqlite3 -readonly -json`，零新增依赖；构建工具链 `~/go-toolchain/go`（1.25.13，golang.google.cn 直连下载），构建与测试用 `GOPROXY=off` 走本地模块缓存。

**部署纪律（沿用 v1.1 计划，违反则白改）：** bridge 改动必须在 `.worktrees/m4-multi-backend-bridge/remote` 修改构建 → 替换 `~/.local/bin/harness-bridge` → `launchctl kickstart -k gui/$(id -u)/com.harnessapk.remote-bridge` → 读 `/tmp/harness-remote-bridge.error.log` 确认新代码生效。worktree 内有历史遗留脏文件（`remote/dsh/appserver/node_modules/`、`package-lock.json` 等），提交按路径精确 add，**禁止 `git add -A`**。App 改完 `assembleDebug` 重装设备：adb 前缀 `-L tcp:5092 -s 20200611222647`。

**Non-goals（一期）:** bridge 自养线程进 dashboard；副屏任何写操作（含审批）；二级界面/跳转；原生 macOS 菜单栏 app；精确 `waiting_approval`；多主机；`codex resume` 相关语义。

---

## Task 1: 深链运行时探针（阻塞后续聚焦链路）

**Files:** 无代码产出；结论回填本计划与本 HANDOFF。

- [x] **Step 1:**（2026-09-02 实测通过）app 运行中 + 亮屏：`open "codex://threads/01a06087…"` → ChatGPT 激活置前并切到该线程（侧边栏/标题确认）；`open -g "codex://threads/019f5080…"` → 前台保持原 app 不动，ChatGPT 窗口在后台切到目标线程。深链格式定稿 **`codex://threads/<id>`**。
- [x] **Step 2:** 未触发（Step 1 即成功，无需变体探测）。
- [ ] **未测组合（如实记录）：** app 未运行（实测时桌面 app 有线程在跑，退出会打断用户任务，不安全）；锁屏组合（需锁屏操作，并入 Task 5 真机验收）。
- [x] **Step 3:** 未触发（探针成功，聚焦落点维持原生 app 深链）。

## Task 2: bridge observer——线程目录（sqlite 只读）

**Files:**
- Create: `remote/internal/observer/catalog.go`（以 worktree 内实际布局为准）
- Test: `remote/internal/observer/catalog_test.go`

- [ ] **Step 1: 写失败测试。** 用 `modernc.org/sqlite` 内存库建最小 `threads` 表 fixture（含 archived/subagent source/超 24h 各一行），断言 `Catalog()` 只返回未归档、24h 内、非 subagent 的线程，字段映射完整。
- [ ] **Step 2:** 实现：`mode=ro` 打开、`busy_timeout`、5s 轮询；查询失败（含 WAL `-shm`/`-wal` 需恢复导致的打不开，如上次写入者崩溃）自动降级读 `session_index.jsonl`——它是热路径降级源而非冷备，降级路径与主路径同等级测试覆盖；jsonl 缺 rollout 路径时按「会话目录文件名内嵌 uuid」glob 定位。
- [ ] **Step 3:** `go test ./internal/observer/` 通过。

## Task 3: bridge observer——rollout tailer 与状态机

**Files:**
- Create: `remote/internal/observer/state.go`、`remote/internal/observer/tailer.go`
- Test: `remote/internal/observer/state_test.go`

- [ ] **Step 1: 写失败测试（table-driven）。** 喂事件序列断言状态：`task_started`→运行中；仅 `agent_reasoning` 流→`thinking`；出现 `item_completed`/`patch_apply_end`/`mcp_tool_call_end`/`sub_agent_activity`→`running`；`task_complete`→`done`；`turn_aborted`→`error`；未知事件忽略不崩。活性规则：rollout 出现任何新行（含最高频的 `token_count`）都重置静默计时。启发式：`task_started` 后静默 ≥3min 且未 complete → `running` +「长时间无输出」标记（`approx=true`，文案不叫「受阻」——构建/测试跑几分钟无事件是正常的）。终态兜底：最后事件超 30min 仍无 `task_complete`/`turn_aborted` → `done`（`approx=true`，注明「无结束事件」），防止用户关线程/app 崩溃/进程被杀导致永久 running。三个时间常量集中定义、可调。
- [ ] **Step 2:** 实现 tailer：从文件尾增量读 JSONL，逐行解析 `type=="event_msg"`，按 `payload.type` 驱动状态机；任何成功读取的新行都更新 lastEventAt（活性信号，与事件类型无关）；解析错误丢弃该行并计数。
- [ ] **Step 3:** 目录联动：仅对 top 8 活跃线程启动 tailer；`updated_at_ms` 落出窗口后退出 tail。
- [ ] **Step 4:** 单测通过 + `go vet ./...`。

> **实施注记（2026-09-02 真实数据验证）：** 在本机 `~/.codex` 真实 rollout 上验证时发现并修复两个缺口：① 长 turn 会活过 64KB 种子窗口，窗口内没有 `task_started` 边界 → 任何工作事件（`item_completed` 等）在无边界时确立 turn；② 桌面 app 把工具调用写在 **response_item 层**（`function_call`/`custom_tool_call[_output]` 等），event_msg 的 `item_completed` 可能缺位 → response_item 层的工具类事件同样纳入工作信号，`reasoning` 只增强 thinking 位且不确立 turn（done 后的 token/reasoning 活性不得复活状态）。
>
> **实施注记（2026-09-02 跨线兼容，重要）：** test 发布线的 App 对 journal 事件按「已知 run」严格投影（`RemoteEventReducer` 对未知 runId 抛异常），因此副屏推送/回执**全部改走 plain 事件帧**（`protocol.Event`，不经 journal）：旧端 handleEvent 无对应分支静默忽略，新端正常消费。事件类型 `dashboard.thread` / `dashboard.threads` / `dashboard.focus` 不变；bridge 侧由 `sendDashboardFrame` 统一下发（`dashboardSender` 测试桩可注入）。test 线 App 无看门狗机制，`dashboard.snapshot`/`thread.focus` 无超时预算问题。

## Task 4: WSS 推送 thread_status

**Files:**
- Modify: `remote/cmd/bridge/main.go` + `protocol.LogicalEvent` 定义处（按实际包路径定位；连接建立/订阅时发全量，observer 状态变更发增量）
- Test: `remote/cmd/bridge/main_test.go` 追加

- [ ] **Step 1: 写失败测试。** 断言：新设备接入收到全量快照；单线程状态变更只推送该线程增量；事件走 `protocol.LogicalEvent` 类型化通道扩展，复用 `sendLogicalEvent`（main.go:2215）及其 event journal 幂等/重放，不新造消息格式；事件含 `threadId / status / approx / title / updatedAtMs / cwd / gitBranch`。
- [ ] **Step 2:** 实现并跑 `go test ./...`。
- [ ] **Step 3:** 按「部署纪律」构建、替换、重启生产 bridge，确认 error log 无新错。

## Task 5: focus_thread 下行指令

**Files:**
- Modify: `remote/cmd/bridge/main.go`（命令分发新增分支，风格对齐现有 `turn/start` 分支）
- Test: `remote/cmd/bridge/main_test.go` 追加

- [ ] **Step 1: 写失败测试。** 断言 focus_thread 触发的命令拼装为 `caffeinate -u -t 2` 与 `open codex://threads/<id>`（**无 `-g`**；注入 runner fake，不真执行）；非法/空 id 拒绝。
- [ ] **Step 2:** 实现；真机手动验证一次：Mac 熄屏 → App 点击 → 唤屏、桌面 app 激活置前并定位线程。
- [ ] **Step 3:** 部署纪律同 Task 4。

## Task 6: relay 透传核实

**Files:** 视核实结果而定（预期零改动）。

- [ ] **Step 1:** 审 `remote/cmd/relay`（或对应包）是否按消息 type 白名单分发；是 → 补 `thread_status` / `focus_thread` 两型并补测试；否 → 记录「零改动」结论即可。

## Task 7: App 副屏 Dashboard 页

**Files:**
- Create: `app/src/main/java/com/harnessapk/remote/dashboard/DashboardActivity.kt`、`DashboardViewModel.kt`、`DashboardCard.kt`（包名以现有 remote 包结构为准）
- Test: `app/src/test/java/.../dashboard/`（状态色映射与文案纯函数）

- [ ] **Step 1: 写失败测试。** 状态→色/文案映射：`thinking/running/done/error`，外加 `running+approx`（副文案「长时间无输出，非精确」）与 `done+approx`（副文案「无结束事件」）；`done` + 本地未查看 → 未读标记。
- [ ] **Step 2:** 实现 Compose 卡片列表（状态色点 + 标题 + 相对时间 + cwd/分支），复用现有 `RelativeTimeFormat`；无二级页面、无导航跳转。
- [ ] **Step 3:** 点击卡片仅发送 `focus_thread`；发送失败用轻量 toast，不弹详情页。
- [ ] **Step 4:** 连接生命周期：Dashboard 复用现有 `RemoteConnectionService` 前台服务（`app/src/main/java/com/harnessapk/remote/RemoteConnectionService.kt`）消费同一 WSS，不另起连接；夜间进程被杀 → 服务重启重新订阅 → Task 4「订阅即全量快照」自然恢复状态。
- [ ] **Step 5:** `done + 未读` 的已查看时间戳存 App 本地（DataStore/现有偏好存储，仅本机不上传）；Dashboard 前台即视为已查看。
- [ ] **Step 6:** `:app:testDebugUnitTest` 通过，`assembleDebug` 装机。

## Task 8: 常亮与入口

**Files:** 同 Task 7 相关文件 + 入口挂载点。

- [ ] **Step 1:** Dashboard 前台 `FLAG_KEEP_SCREEN_ON`（配合 USB 供电 `stayon usb` 习惯），退到后台即恢复系统熄屏策略。
- [ ] **Step 2:** 入口：Codex Remote 节点设置页加「副屏模式」入口，进入即全屏 dashboard，系统返回退出。
- [ ] **Step 3:** 与现有远程控制页隔离：dashboard 只消费 `thread_status`，不消费、不展示 bridge 自养线程数据流。

## Task 9: 端到端验收

- [x] **Step 1:**（2026-09-02 实测）桌面 app 线程跑任务 → 副屏卡片状态实时变化（服务团队线程显示「运行中·刚刚」绿色圆点）；全链路 sqlite 目录 + rollout tail + LogicalEvent 推送 + App 渲染全部命中。
- [x] **Step 2:** 模拟器副屏点击 done 卡片 → Mac 唤屏、ChatGPT 激活置前并定位到「Remove superpowers skill cleanly」线程（无障碍树 + 截图双确认）。锁屏组合仍待真机（设备未连接）。
- [x] **Step 3:** bridge 重启路径由「订阅即全量快照」+ service 重启恢复覆盖（Task 4/7 机制），实测配对→推送→展示全通。
- [ ] **Step 4:** 副屏整晚 USB 供电常亮挂机——待用户把副屏手机上架后观察。
- [x] **Step 5:** 结论已回填 HANDOFF。

---

**风险与对策：**

- **rollout / sqlite 结构随版本漂移**（非官方集成面）：解析容错（未知事件/列缺失降级不崩），记录 `cli_version`（threads 表有该列）便于追因；session_index.jsonl 为目录降级源。
- **sqlite live WAL 只读打开可能直接失败**（`-shm`/`-wal` 需要恢复时 `mode=ro` 报错，典型如上次写入者崩溃）：失败即切 `session_index.jsonl`（热路径），仍不行 glob 会话目录定位 rollout；若 sqlite 主源实测长期不稳，评估把目录主源切为 jsonl 并移除 `modernc.org/sqlite` 重依赖。始终只读，绝不写该库。
- **深链被 rewrite / 版本行为变化**（社区已见 scheme 相关 issue）：Task 1 探针先行，失败即回主会话，不硬编码猜测格式上线。
- **subagent 线程噪音**：一期按 `source` 含 `parent_thread_id` 过滤；二期考虑折叠展示。

**来源：** [Codex Micro（Axios）](https://www.axios.com/2026/07/15/openai-keyboard-codex-agents) · [codex:// 深链（官方 Commands 文档）](https://learn.chatgpt.com/docs/reference/commands) · [issue: 线程自识别深链](https://github.com/openai/codex/issues/16239) · 本机 spike（rollout 全量扫描 / state_5.sqlite schema / ChatGPT.app Info.plist 与 app.asar 资源探测，2026-09-02）
