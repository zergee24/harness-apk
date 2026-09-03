# HANDOFF: AI-Agent 副屏（Agent Dashboard）一期方案

> 来源：2026-09-02 主会话侧支讨论的结论。主会话可直接以本文档为准继续推进（写 plan / 实施），无需回溯对话。

## 产品概念

参照 OpenAI **Codex Micro**（2026-07 与 Work Louder 合作的桌面小键盘：6 颗 RGB Agent Key 实时映射 Codex 线程状态，单击在后台聚焦该 agent，双击把 Codex 界面置前；仅配合 ChatGPT 桌面 app 工作）。我们做同构能力的软件版：

- **形态**：闲置手机/平板插电立在桌面，常亮跑 Harness 的 dashboard 页 = 只读的 agent 状态副屏。
- **交互原则（2026-09-02 侧支修订）**：副屏**只读不写**。单击卡片 = 聚焦到 Mac 主屏原生 app 对应线程；**长按卡片 = 只读详情二级页**（零按钮：无审批、无输入框；打开拉一次 + 手动刷新，不做流式）。入口双挂：Codex 远程节点设置页「副屏模式」+ 工作页远程控制区。
- **状态机（目标）**：`thinking / running / waiting_approval / done / error`（可选 `unread`）。

## 核心架构决策：按"线程住在哪"划分读写

背景事实（讨论中确认）：Codex 活线程是 app-server **进程私有**的；跨进程只有 resume 一条路，而 resume 是 **fork**（新 session id，旧 id 从此无事件）。因此谁创建谁拥有，磁盘 sessions 只是交换格式。（**2026-09-02 修正：此结论绝对化——app-server 有跨进程 `thread/read`（实测可用），返回含 `status`/`canAcceptDirectInput` 协调字段；桌面 app 前端同时使用 resume/read/fork 跨 surface 续聊。详见下节「写通道再评估」。一期只读决策不变，但"给原生线程发消息永不可行"不成立。**）

| 线程归属 | 读 | 写 | 副屏可见性 |
|---|---|---|---|
| 原生 ChatGPT/Codex 桌面 app | bridge 新增 **observer** 模块，tail `~/.codex/sessions` rollout 文件 | 不写（原生 app 自己管） | ✅ dashboard 显示；点击 = 深链聚焦 |
| bridge 自养（现有 remote control） | 现有链路不动 | 现有链路不动（发消息/批审批） | ❌ 一期不上 dashboard（无法深链，会引入 fork/跳转） |

两条轨道只在基础设施上交汇（同一 relay / bridge 进程 / App），线程语义零交集。dashboard 只读 ⇒ 永无 fork、无双进程驱动、无状态打架。

## 写通道再评估（2026-09-02 侧支实验）

- 实测：全新 app-server 进程 `thread/read` 可读任意线程元数据+状态（归档老线程验证通过），字段含 `status`（notLoaded/…）与 **`canAcceptDirectInput`**——协议层为"此线程能否接受输入"预留了协调信号。
- 桌面 app.asar 证据：前端同时调用 `thread/list`(20 处)/`thread/resume`(13)/`thread/read`(11)/`thread/fork`(11)——**跨 surface 打开并续聊原生线程是产品既有能力**（vscode 创建的线程出现在桌面 app 侧栏并被续聊，即此机制）。
- 修正后判断：**空闲线程**跨进程接管续话大概率可行（协调靠"同时只有一人驱动"的使用纪律，桌面 app 即此用法）；**活线程**双进程驱动冲突风险仍在，`canAcceptDirectInput` 是否足以安全判定未验证。原"resume 必 fork"系 CLI `codex resume` 的文档结论，不能外推到 app-server `thread/resume`。
- 决策：一期维持只读不变；**二期 spike**——挑已完成线程，bridge `thread/resume` + `turn/start` 发一条消息，观察 `forkedFromId`、桌面 app 事件流与原进程行为，再决定是否开"空闲线程回复"能力。

## 关键机制

- **聚焦**：`open "codex://..."` 深链直达桌面 app 内指定线程。scheme 文档：https://learn.chatgpt.com/docs/reference/commands （app 侧边栏 "Copy deeplink" 即此机制；已知 bug https://github.com/openai/codex/issues/37431 ）。深链路径格式已实测核实（2026-09-02 探针）：`codex://threads/<id>`。
- **状态观察**：bridge 增加 rollout 文件 watcher（追加写 JSONL，事件级实时性足够副屏用）。这是非官方集成面，rollout 格式变动需跟随修复。
- **通知**：照常推（push 是展示不是"写"，不违反只读）。但原生线程的通知**不带审批按钮**，与 bridge 线程通知的不对称要在 UI 上显性化。
- **常亮**：dashboard 前台 `FLAG_KEEP_SCREEN_ON` + USB 供电（与 AGENTS.md 的 `stayon usb` 习惯一致）。
- **熄屏**：Mac 侧收到 focus 指令先 `caffeinate -u -t 2` 唤屏；锁屏时只能亮到锁屏界面，一期如实接受。

## 已否决的方案（勿走回头路）

1. **`codex resume` 开新终端 tab 作为聚焦落点**：fork 脱钩——副屏盯的旧 id 与 Mac 上实际干活的会话分离，需要"已接管"态等补丁语义。已否。
2. **bridge 本地 web 监控页作为聚焦落点**：可行备选（无 fork、可交互），但体验不如原生 app；保留为二期/降级选项。
3. **原生 macOS 菜单栏小窗 app**：体验最优但成本最高，二期。
4. **混合模式（bridge 线程也深链进原生 app）**：两个进程都能驱动同一线程，必然分叉。禁止。

`codex resume` 仅作为用户主动"深度接管"的逃生舱存在，与副屏无关。

## 第一步（阻塞项）：验证 spike

**rollout 文件里是否落盘"等待审批"事件**——审批是 app-server 协议层的请求-响应，不保证写入 rollout。这决定 `waiting_approval` 状态能否做全（Codex Micro 状态灯里最有价值的态）。

做法：桌面 app 跑一个会触发审批的线程，tail rollout 看事件流。若看不到：一期状态机降级为 thinking/running/done，或用"exec 开始后长时间无新事件"启发式推断（UI 需标注非精确）。深链路径格式的核实可并入同一 spike。

> **✅ 2026-09-02 spike 结论（阻塞项已解除）：** ① rollout **不落盘**审批事件——近 20 天 ~1200 个文件全量扫描，approval/elicit/permission/review 命名事件零命中（命中的 approval 字样均为 `approval_policy` 配置字段），一期状态机降级为 `thinking/running/done/error` + 「长时间无输出」启发式（UI 标注非精确）。② 深链实测可用：`codex://threads/<id>` 无 `-g` 激活置前并定位线程，`open -g` 后台切换不置前；格式已定稿。实施计划见 docs/superpowers/plans/2026-09-02-agent-dashboard-phase-1.md。
>
> **✅ 2026-09-02 一期实施完成 + 端到端验收通过：** bridge observer（sqlite 目录 + rollout tail + 状态机）已上线生产 `~/.local/bin/harness-bridge`；App 新增副屏 Dashboard（设置 → Codex 远程节点 → 副屏模式）。实测：真实线程状态实时上屏（含 git 分支/cwd/相对时间/未读标记），模拟器点卡 → Mac 激活置前定位线程全链路打通。实施中发现并修复：长 turn 种子窗口恢复、response_item 层工具信号两个状态机缺口（详见 plan Task 3 实施注记）。待办：真机锁屏组合与整晚常亮挂机观察。
>
> **⚠️ 跨线状态（2026-09-02）：** App 侧功能已移植并提交到 **test 发布线**（该线的 remote 包为强类型 Runtime 演进版，main 上没有；推送因此统一走 **plain 事件帧**，journal 化会令 test 线 reducer 对未知 run 抛异常——bridge 已同步改为 plain 帧并重新部署）。bridge 侧 observer 提交在 `codex/m4-multi-backend-bridge` 分支（生产二进制的构建源）。主仓库工作分支已从 main 切换到 test。

## 来源

- Codex Micro：[Axios 报道](https://www.axios.com/2026/07/15/openai-keyboard-codex-agents) · [Work Louder setup](https://worklouder.cc/openai-micro-setup) · [TechTimes：仅配合 ChatGPT 桌面 app](https://www.techtimes.com/articles/320670/20260716/openai-codex-micro-ships-today-agent-keys-only-work-chatgpt-desktop.htm)
- codex:// 深链与 app-server：[Commands 文档](https://learn.chatgpt.com/docs/reference/commands) · [App Server 文档](https://learn.chatgpt.com/docs/app-server) · [OpenAI: Unlocking the Codex harness](https://openai.com/index/unlocking-the-codex-harness/)
- codex resume（fork 语义依据）：[InventiveHQ](https://inventivehq.com/knowledge-base/openai/how-to-resume-sessions) · [Verdent 指南](https://www.verdent.ai/guides/codex-cli-resume-continue-save-chat)
