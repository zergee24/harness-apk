# 任务包 v2：codex-session-control 预设插件（scoped_worker 用；已吸收 challenger 审查 P0/P1 全部修正）

## 目标

在 DSH 预设 `sol-ultra-multiagent` 内实现本地插件 `codex-session-control`，向会话注册一组 `codex_*` 工具，使 agent 能操控本机 codex（app-server）会话：启动/恢复线程、发 turn、读流式事件、steer、interrupt、审批应答、列线程、读线程。

## 范围（只允许写这些路径）

- 新建 `~/.dsh/.agent-presets/sol-ultra-multiagent/plugins/codex-session-control/`（index.js、package.json、README.md、index.test.js）
- 编辑 `~/.dsh/.agent-presets/sol-ultra-multiagent/agent.cordis.yml`（追加一行 + 不改动其他行）
- 新建运行时状态目录 `~/.dsh/plugins/codex-session-control/`（sessions.json pid 台账，原子写）
- 临时测试产物放 /tmp

禁止：DSH 安装目录（nvm 下 node_modules）、shipped presets、~/.dsh/profiles/、harness-apk 仓库文件（本文件除外）、~/.codex 任何内容。

## 权威事实（全部已实测/已读源码，直接采信，不要复查）

### codex app-server 协议（实测，codex-cli 0.149.0-alpha.4.1）

- 真实二进制：`/Applications/ChatGPT.app/Contents/Resources/codex`；`~/.local/bin/codex` 悬空链接；PATH 解析不可靠。
- 启动：`<bin> app-server --listen stdio://`；换行分隔 JSON-RPC（线上无 jsonrpc 字段）。
- 握手：`initialize {clientInfo:{name,title?,version}}` → `{userAgent,codexHome,...}`；随后通知 `{method:"initialized"}`。
- `thread/list {limit,sortKey:"updated_at",sortDirection:"desc"}` → `{data:[...]}`；`thread/read {threadId,includeTurns}`；`thread/start {cwd}` → `{thread:{id}}`（取 result.thread.id）；`thread/resume {threadId}`；`turn/start {threadId,input:[{type:"text",text}]}` → `{turn:{id,status:"inProgress"}}`；`turn/steer {threadId,expectedTurnId,input}`；`turn/interrupt {threadId,turnId}`。
- 通知：thread/started、thread/status/changed、turn/started、turn/completed、turn/failed、item/started、item/completed、item/agentMessage/delta、error{willRetry}、mcpServer/startupStatus/updated、remoteControl/status/changed、serverRequest/resolved。
- server→client request（method+id）：`item/commandExecution/requestApproval`、`item/fileChange/requestApproval`、`mcpServer/elicitation/request`、`account/chatgptAuthTokens/refresh`、`attestation/generate` 等。**必须以原 id 回 `{"id":..,"result":{"decision":"accept"|"decline"}}`**（审批类）。
- **审批无人应答 = 上游无限等待（无超时、无 auto-decline）**；仅两条解脱路：原 id 应答，或 turn 状态转换（turn/start|completed|interrupt）触发 abort_pending_server_requests。
- agentMessage item：`{type:"agentMessage", content:[{type:"text",text}]}`。
- auth 在 ~/.codex/auth.json（磁盘态，不受 env 擦除影响，ChatGPT 登录有效）；thread store 与桌面版共享。
- 噪声：每进程拉起 ~5 个 MCP server（数秒就绪）；模型端断流发 error{willRetry:true} 重试。
- 用户 ~/.codex/config.toml：approval_policy="never"、sandbox_mode="danger-full-access"（我们用命令行 `-c sandbox_mode="read-only"` 覆盖后者）。

### DSH 机制（已读安装源码）

- 插件模板：同目录 `../multi-model-subagents/index.js`（纯 ESM，export name/inject/apply）。行引用先例 agent.cordis.yml:195-196（`name: ./plugins/.../index.js`）。
- **preset 相对路径插件文件内不得使用裸包静态导入**（Node 从文件真实路径向上找 node_modules，走不到 nvm 安装处；PresetTree 的 harness-base 解析只作用于行名）。node: 内置模块可用（node:child_process、node:fs、node:os、node:path、node:process、node:util、node:crypto 等随便用）。
- dsh-tools/schemastery 获取方式（顶层 await）：发现 harness 基目录（遍历 `[process.argv[1],...process.argv]` 逐个 realpathSync，正则 `/^(.+\/node_modules\/@deepseek-ai\/dsh)\//`；fallback `process.env.DSH_CODEX_HARNESS_BASE`；失败抛错附修复指引）→ `createRequire(base+'/package.json').resolve('@deepseek-ai/dsh-tools')` → `await import(pathToFileURL(resolved).href)`。
- defineTool（抄 dsh-tool-todo）：`{name, description, parameters:{p:{type,required?,description?,enum?,items?,properties?,additionalProperties?}}, output:{schema:{type:'object',properties?...}, render:(a,v)=>[{type:'text',text:JSON.stringify(v)}]}, timeoutMs?, isConcurrencySafe?, execute:async(args)=>value}`；`ctx.tools.register(def)`。**只读类工具（sessions/threads/read/events）声明 `isConcurrencySafe:()=>true`**（避免 exclusive 屏障串行）。默认 timeoutMs 不设。
- `ctx.systemPrompt.section({name, order:116.5, text})`（先例 multi-model 用 order 116.25；取 116.5 避让）。
- subprocess：`ctx.subprocess.spawn({argv, cwd, stdio:{stdin:'pipe',stdout:'pipe',stderr:{maxBytes:262144, spill:{maxBytes:1048576}}}, graceMs:3000, env, signal})` → `{pid, stdin, stdout, done, terminate(), waitForExit(signal?)}`。**graceMs 必填**。stderr 必须 collect（pipe 不读会 64KB 反压卡死子进程）；`handle.collected.stderr.readFrom(0)` 可随时读尾部。env 显式项在擦除后合并（PATH/HOME/NO_PROXY 天然保留；含 KEY/TOKEN/SECRET/PASSWORD 的变量被擦除——所以只支持 ChatGPT 登录态，README 注明）。服务销毁/host 正常退出自动 SIGKILL 全部托管子进程。
- 每个工具 execute 里不得长阻塞独占池（exclusive 屏障）——见 P0-1 缓解。

## 必须实现的设计要点（challenger 审查已定案）

### P0-1 审批早退
`codex_send` 的等待循环除监视 turn 收口外，**同时监视 eventBuffer 出现 kind:"serverRequest" 的新事件：立即早退**，返回 `{status:'awaitingApproval', turnId, requestId, summary}`（不再等 turn/completed）。工具描述写明：收到此状态必须先 codex_events 查看、（按指引问用户后）codex_approve，再用 codex_events 继续跟进度。

### P0-2 无人应答=挂起；stop 的收尾序
README + prompt 段写明：审批不应答则 turn 永久挂起。`codex_session_stop` 顺序：若有活跃 turn 先 `turn/interrupt`（等 ≤3s）→ `stdin.end()` → `terminate()` → `waitForExit(5s)`。

### P1-1 生命周期
- **模块级共享状态**：sessions Map 放模块作用域（同一 URL 的 ESM 实例在 host 进程内共享），maxSessions 是进程级硬上限（默认 4）；每个 apply mount 对共享 registry refcount+1，ctx.effect 清理时 -1，归零 terminate 全部。（preset 在 web 会话几乎不卸载——接受，文档说明只有 codex_session_stop/host 退出释放。）
- **pid 台账**：每次 spawn/exit 原子写 `~/.dsh/plugins/codex-session-control/sessions.json`（数组：{pid, sessionId, threadId, startedAt, argv0}）。挂载时清扫：对台账中每个 pid 用 `execFile('ps',['-p',String(pid),'-o','command='])` 校验命令行同时含 "codex" 与 "app-server" 才 `process.kill(pid,'SIGTERM')`；之后清空台账。host 崩溃留下的孤儿由此回收。
- dead session 立即释放槽位（alive=false 即从 maxSessions 占用中剔除；codex_sessions 仍显示最近会话供诊断，标 alive:false）。

### P1-2 代理 env
Config 增加 `env`（Record<string,string>，默认 {}），spawn 时显式传入。本机预设行 config 里配置 `{env:{HTTPS_PROXY:"http://127.0.0.1:12334",HTTP_PROXY:"http://127.0.0.1:12334",NO_PROXY:"localhost,127.0.0.1"}}`（本机直连 OpenAI 不通，AGENTS.md 规定系统代理）。README 说明 env 层叠语义与凭据擦除。

### P1-3 连接归属
- `codex_threads`/`codex_read`：优先复用任一 alive session 的连接；无 alive session 时 spawn **临时读取进程**（同 argv，initialize→查询→terminate，5 分钟内完成为准，用完即杀）。
- 本地互斥：`codex_session_start{resumeThreadId}` 若该 thread 已被本插件任一 alive session 持有 → 报错列出持有者。README 注明与桌面端并发操作同一 thread 无上游保护。

### P1-4 spawn 细节
每 session `AbortController`（signal 传入 spec）；initialize 超时 20s（超时→terminate+报错，让 spawn 失败同步浮出）；turn/start 请求超时 30s；graceMs 3000。

### P1-5 安全
- sandboxMode Config 只允许 `"read-only"`（默认）与 `"workspace-write"`；`danger-full-access` 一律拒绝（含配置错误提示）。
- codex_approve 的 description + prompt 段：**accept 前必须先用 ask_user_question 征得用户同意**（或用户在该会话明确说过自动放行）。
- README 安全节：codex 子进程内执行的命令对 DSH 沙箱不可见；read-only 模式下写操作会被 codex 自身沙箱拒绝。

### P1-6 非审批 serverRequest
- `account/chatgptAuthTokens/refresh`：**自动回 `{"id":..,"result":{}}`**（token 管理，无需用户同意），同时入 eventBuffer 标 kind:"autoResponded"。
- 其余（attestation/generate、elicitation 等）：入 eventBuffer 标 kind:"serverRequest"；codex_approve 遇非审批类返回指引（elicitation form v1 不做）。

### P1-8 prompt 指引段
插件 inject 加 'systemPrompt'，注册 section（order 116.5）内容含：工具总览一句话；典型流程（start→send→events 轮询→steer/interrupt）；长任务用 background:true + 轮询；awaitingApproval 处置序；approve 前问用户；只读默认与写模式差异；用完 codex_session_stop 释放资源。

### P2 采纳
- session 启动时记录 `initialize` 返回的 userAgent 与二进制 `--version`（execFile bin ['--version']）到 session 元数据 + codex_sessions 输出（协议漂移可诊断）。
- stdin 写背压：write 返回 false 时等 'drain'（带 5s 超时保护）。
- eventBuffer 截断只置 truncated 标志；cursor（seq）单调递增永不回绕；codex_events 返回 truncated 状态。
- thread 互斥、turn/interrupt 不清理后台终端（上游 FACT）——README 记录。
- README 给出悬空链接修复命令：`ln -sf /Applications/ChatGPT.app/Contents/Resources/codex ~/.local/bin/codex`。

## 工具清单（10 个）

| 工具 | 参数 | 返回 | isConcurrencySafe |
| --- | --- | --- | --- |
| codex_sessions | {} | {sessions:[{sessionId,threadId,cwd,alive,activeTurnId,startedAt,userAgent,pendingServerRequests,stderrTail}]} | true |
| codex_session_start | {cwd?(默认 os.tmpdir()), resumeThreadId?} | {sessionId, threadId, resumed} | false |
| codex_session_stop | {sessionId} | {stopped:true, exit:{exitCode,signal}} | false |
| codex_send | {sessionId, text, background?=false, waitMs?≤180000 默认 120000} | 见 P0-1；completed 时含 agentText(拼接 item/completed agentMessage) 与 itemCount | false |
| codex_events | {sessionId, cursor?=0, limit?=100} | {events:[{seq,method,kind,summary...}], cursor, truncated} | true |
| codex_steer | {sessionId, text, expectedTurnId?} | {steered:true, turnId} | false |
| codex_interrupt | {sessionId, turnId?} | {interrupted:true} | false |
| codex_approve | {sessionId, requestId, decision:'accept'\|'decline'} | {responded:true}（非审批 requestId → 指引文本） | false |
| codex_threads | {limit?=20} | {threads:[{id,name,preview?,updatedAt,status,cwd}]} | true |
| codex_read | {threadId, includeTurns?=false} | {thread:{id,name,cwd,...}, turns?（摘要：每 turn status+item 类型计数+最后 agentMessage 前 200 字）} | true |

事件记录形状：`{seq, method, threadId?, turnId?, itemId?, kind:'notification'|'serverRequest'|'autoResponded'|'exit', text?}`；item/agentMessage/delta 合并进同 item 的单条记录；error 记 message+willRetry；serverRequest 记 requestId+method+摘要（command/cwd/reason）。

## 交付物结构

```
plugins/codex-session-control/
  package.json  {"name":"@local/dsh-plugin-codex-session-control","private":true,"type":"module","scripts":{"test":"node --test index.test.js"}}
  index.js      插件本体（无裸静态导入；纯函数与 IO 分离便于单测）
  index.test.js node --test 单测（不启动真 codex、不依赖 cordis 运行时）：
                - wire.feed 分类/半行重组/坏JSON跳过/id 匹配
                - 事件归并：delta 合并、上限截断+truncated、seq 单调、cursor 语义
                - 二进制解析（注入 fake existsSync/realpathSync）
                - harness base 发现（注入 fake argv/realpathSync）
                - send 状态机：busy 判定、awaitingApproval 早退
                - 台账清扫判定（注入 fake execFile）
                - sandboxMode 校验（拒绝 danger-full-access）
  README.md     使用说明、安全模型、已知限制、修复指引
```

agent.cordis.yml 追加（multi-model-delegation-policy 行后，缩进与先例一致）：

```yaml
  - id: codex-session-control
    name: ./plugins/codex-session-control/index.js
    config:
      env:
        HTTPS_PROXY: http://127.0.0.1:12334
        HTTP_PROXY: http://127.0.0.1:12334
        NO_PROXY: localhost,127.0.0.1
```

Config（schemastery，全部可选带默认）：bin?、sandboxMode?（默认 read-only）、cwdDefault?、maxSessions?（默认 4）、eventBufferLimit?（默认 2000）、env?（默认 {}）。

## 单测运行方式

`cd ~/.dsh/.agent-presets/sol-ultra-multiagent/plugins/codex-session-control && node --test`（node 24）。全绿是完成条件之一。

## 明确不做（v1）

client UI/settings/theme；WebSocket/远程监听；多 thread 复用单进程；elicitation form 应答；policy amendment 决策；codex exec 路线；跨 host 的 session 恢复。

## 完成定义

1. 四个文件就位，agent.cordis.yml 行追加，`node --test` 全绿。
2. `node -e "import('file://.../index.js')"` 语法/顶层导入可加载（不 mount，仅验证模块可导入——注意插件顶层发现 harness base 应成功）。
3. 回报：修改文件清单、测试输出摘要、任何偏离本任务包的决定及理由、未验证范围。
