# dsh-appserver-spike（M4 G0，路线 A）

把 dsh 的 agent/session API 包成与 codex app-server 兼容的 stdio JSON-RPC 面（`dsh --profile appserver --listen stdio://`）的最小原型。

## 验证结果（2026-08-15）

**结论：路线 A 可行。** dsh agent/session 核心 API 足够包成 canonical app-server 协议面，真实模型回合（deepseek-v4-flash，经 opencode-go provider）端到端跑通：

```text
initialize -> thread/start -> turn/start
  [notify] turn/started
  [notify] item/agentMessage/delta   ← 真实回答流式到达
  [notify] turn/completed
-> thread/read -> thread/list
```

复现：`node client.mjs`（需本机 `dsh` 与 `~/.dsh/profiles/appserver` profile，见下）。

## 映射表（G0 结论，G2 按此实现）

| app-server 方法 | dsh 映射 | 状态 |
| --- | --- | --- |
| `initialize` / `initialized` | 直接应答；`ensureReady` 等待 loader | ✅ spike 已验证 |
| `thread/start {cwd}` | `agents.create({sessionId, meta:{cwd}, agentOptions, setup: installModelSelection})` | ✅ spike 已验证 |
| `turn/start` / `turn/steer` | `agent.followup(createUserMessage(...))` + `whenIdle`；steer = 同一 agent 再次 followup | ✅ spike 已验证 |
| 事件流 | 轮询 `agent.session.events`（200ms）→ `turn/started`、`item/agentMessage/delta`、`turn/completed` 通知 | ✅ 轮询可用；G2 改用 session/event observer 降低延迟 |
| `thread/read` | `session.events` 翻译为 turns/items（`turn/start`、`user/message`、`assistant/message`、`turn/end`） | ⚠️ agentMessage 文本正确；user/message 的 data 形状需 G2 锁定（spike 中文本为空） |
| `thread/list` | spike 用内存注册表；G2 改为枚举 `~/.dsh/sessions`（按 cwd 分目录、session.jsonl.zstd） | ⚠️ G2 |
| `turn/interrupt` | dsh agent-loop 有 phase AbortController / "ancestor interrupt" 语义；未找到直接 `agent.interrupt()` 公开方法 | ❌ G2 探索；不可行则 dsh 后端不声明 `run.lifecycle.v1` 的 interrupt 子能力并降级 |
| `*requestApproval` / `requestUserInput` | 直接驱动面没有等价事件（dsh 权限模型以 sandbox preset 为主） | ❌ dsh 首版**不声明** `approvals.v1` / `user-input.v1`（对应 D4 降级路径） |
| `sessions.flush` | 每回合后落盘，会话持久化可用 | ✅ spike 已调用 |

## 部署机制（G2 环境结论）

- profile：`~/.dsh/profiles/appserver/`，`package.json` 的 `dsh.profile.bundles = ["@deepseek-ai/dsh-base"]`（与 headless 同基座），`cordis.patch.yml` insert `appserver-startup` + `appserver-runner`。
- 插件包：spike 期直接复制到 `profile/node_modules/dsh-appserver-spike/`。注意 **`pnpm add <dir>` 的 `link:` 依赖不会展开子依赖，且符号链接让 Node 从仓库真实路径解析**，找不到 profile 里的 commander 等包；G2 应发布为正式 npm 包或 workspace 成员。
- 依赖：commander@15、@deepseek-ai/schemastery@3.18.1、dsh-agent/dsh-cmdline/dsh-llm/dsh-session@0.1.0-rc.6 需装在 profile 内（`pnpm add --registry=https://registry.npmjs.org/ ...`，本机 npmrc 指向不可达的 `pub.hunliji.com:8019`，必须显式换 registry 并走代理）。
- 进程模型：runner 只读 stdin、不调用 appExit，进程常驻——与 Bridge 的 exec/stdio 模型匹配。
- 多实例并存：appserver profile 与用户日常 web profile 各自独立进程；会话写入 `~/.dsh/sessions/<cwd编码>/`，与 web 共用同一存储。**并发写入同一 cwd 会话目录的安全性需 G2 验证**（spike 未并发）。

## 文件

- `index.js` — runner 插件（JSON-RPC 分发、thread/turn/read/list、事件通知）
- `startup.js` — `--listen stdio://` 参数提供者（dsh-cmdline 模式）
- `client.mjs` — 驱动原型（spawn dsh + JSON-RPC 客户端），`SPIKE_TASK` 可换任务
- `package.json` — 插件包声明（deps 与 CLI 版本对齐）
