# OpenAI Codex app-server 与 Harness bridge 替代关系探查

> 探查日期：2026-08-21（Asia/Shanghai）  
> 范围：OpenAI 官方 `openai/codex` GitHub 仓库的 app-server、协议、传输、daemon、remote-control 源码与 README；本地 Harness 代码只用于界定现有 bridge 职责。当前实施基线是 `/Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge`；root main 工作树仅作历史参考，不代表当前实施语义。  
> 证据标记：`FACT` 为源码/官方文档直接事实，`INFERENCE` 为基于事实的工程判断，`UNKNOWN` 为上游公开材料未确认的事项。

## 结论先行

- **FACT：app-server 已经覆盖 Codex 核心会话协议。** 它是一个长期运行的进程，使用双向 JSON-RPC（线上省略 `jsonrpc` 字段），提供 thread、turn、item、流式通知、审批和用户输入等能力；OpenAI 官方说明它用于 VS Code 等丰富客户端，并定位为完整 Codex harness 的一等集成方式。  
  [app-server README（协议与核心原语）](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L20-L83) · [OpenAI：Unlocking the Codex harness](https://openai.com/index/unlocking-the-codex-harness/)
- **INFERENCE：它可以替代 Harness bridge 内“启动 app-server 后做 JSON-RPC 请求/响应映射”的后端适配部分。** 在当前 M4 基线中，应由 `CodexAdapter` 封装 app-server 的 canonical JSON-RPC；Android 继续使用 Harness 稳定领域协议（Run、Journal/Logical Event、CommandCache、Approval、Multi-Backend），不能让 Android 或 relay 直接依赖 app-server wire protocol。这样可以替换 Codex-specific adapter 实现，但保留领域语义、跨后端路由、幂等与恢复契约。
- **FACT：它不能直接替代当前 Harness 的自有 relay/配对/安全边界。** 通用网络 WebSocket 只接受 `ws://IP:PORT` 监听，文档明确标为 experimental/unsupported；非 loopback 必须显式配置 capability token 或 signed bearer token。Unix socket 只针对本机控制面。  
  [app-server transport CLI](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/src/main.rs#L20-L63) · [WebSocket acceptor 与非 loopback 鉴权](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/websocket.rs#L129-L169) · [鉴权策略](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/auth.rs#L27-L104)
- **FACT：上游确实有一个独立的 `remote-control` 主机通道。** 它连接 ChatGPT 官方 remote-control backend，负责主机 enrollment、server token 刷新、pairing artifact、控制器设备 list/revoke、WSS 长连接、重连、序号/ACK/分片和订阅游标；但整个 daemon/remote-control 生命周期仍标记为 experimental。  
  [remote-control API 列表](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L266-L273) · [daemon README（experimental、面向 desktop/mobile remote clients）](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-daemon/README.md#L1-L15) · [remote-control 主机实现](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L498-L831)
- **UNKNOWN：Harness Android 是否能直接作为上游 remote-control controller。** 仓库公开的是 app-server 主机侧实现和 RPC 管理接口；本次上游源码探查没有找到 Android/controller 端的公开实现、配对后 controller WebSocket 握手文档或可直接复用的 Kotlin SDK。因此不能把 `remoteControl/pairing/start` 视为当前自有 relay 的 drop-in 替换。
- **建议架构判断：** 在未确认 OpenAI remote-control controller 合同和账户可用性前，保留现有 relay 的配对、设备撤销、Android Keystore、AES-GCM 和 Aliyun wake；按 M4 设计让 `CodexAdapter` 吸收 app-server 细节，向 Android 暴露稳定 Harness 领域协议。只有在接受 ChatGPT 官方 backend 依赖并取得 controller 合同后，才评估移除自有 relay。

## 上游版本与可复核方式

- **FACT：** 使用官方仓库 `https://github.com/openai/codex.git` 的 `main`，通过当前环境代理执行：

  ```text
  git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 \
    ls-remote https://github.com/openai/codex.git HEAD refs/heads/main
  536f86e5cc9ec1ff38457d099bf320b9d08eeeba  HEAD
  536f86e5cc9ec1ff38457d099bf320b9d08eeeba  refs/heads/main
  ```

- **FACT：** 固定 commit 为 `536f86e5cc9ec1ff38457d099bf320b9d08eeeba`，提交时间 `2026-08-21T06:41:28Z`，提交主题为 `Support attaching to existing realtime calls (#39876)`。app-server workspace crate 版本由 workspace 继承，当前源码值为 `0.0.0`。  
  [commit 永久链接](https://github.com/openai/codex/commit/536f86e5cc9ec1ff38457d099bf320b9d08eeeba) · [app-server Cargo.toml](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/Cargo.toml#L1-L8) · [workspace package version](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/Cargo.toml#L140-L146)

## 启动与传输

### 本地 app-server transport

- **FACT：** 默认是 `stdio://`；`--listen stdio://` 使用 newline-delimited JSONL。`--listen unix://` 使用 `$CODEX_HOME/app-server-control/app-server-control.sock`，实际承载 WebSocket HTTP Upgrade + frames；`--listen unix://PATH` 可指定路径；`--listen off` 不开启本地 transport。  
  [README Protocol](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L20-L44) · [transport 枚举与解析](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/mod.rs#L54-L160)
- **FACT：** `--listen ws://IP:PORT` 每个 WebSocket text frame 携带一个 JSON-RPC message；同一 listener 提供 `/readyz` 和 `/healthz`，含 `Origin` 的请求直接返回 `403`。README 两处明确写明该 WebSocket transport 是 experimental/unsupported，不应作为生产协议依赖。  
  [README transport/security](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L24-L37) · [acceptor 路由](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/websocket.rs#L85-L169)
- **FACT：** `codex app-server proxy` 只打开一个 Unix socket raw stream，并把 socket 与 stdin/stdout 之间的 WebSocket 握手和 frames 原样代理；这更接近本机控制面工具，不是公网 relay。
- **FACT：** 入站、处理和出站队列有界；满载请求返回 `-32001 / Server overloaded; retry later.`，客户端应指数退避并加 jitter。  
  [README backpressure/schema](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L41-L64)

### 官方 remote-control transport

- **FACT：** app-server 主进程通过配置中的 `chatgpt_base_url` 派生 enrollment、refresh、pair、pair/status 和 WSS server endpoint；URL 规范化只允许 `https://chatgpt.com`、`chatgpt-staging.com` 及其子域，或 localhost 测试地址。  
  [remote-control URL/endpoint 代码](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/protocol.rs#L10-L17) · [域名 allowlist 与 endpoint 生成](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/protocol.rs#L193-L289)
- **FACT：** enrollment POST 发送主机名、OS、架构、app-server 版本、installation id；响应包含 `server_id`、`environment_id`、`remote_control_token`、过期时间。refresh 以 server id + installation id 更新 token。  
  [enroll/refresh API](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/server_api.rs#L78-L117) · [refresh 逻辑](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/server_api.rs#L121-L211)
- **FACT：** WSS 握手携带 `x-codex-server-id`、base64 主机名、协议版本 `3`、Bearer server token、installation id，可附带 Mac mini 设备类型和 subscribe cursor。  
  [WSS 握手 headers](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L1240-L1310)
- **FACT：** remote-control host 侧负责 WebSocket ping（10 秒）和 pong 超时（60 秒），断线后以退避重连，退避上限 30 秒；保留 `subscribe_cursor`、每 stream seq、未 ACK outbound buffer，重连后重发未确认 envelope。  
  [连接常量与 reconnect loop](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L72-L87) · [重连与 cursor](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L672-L831) · [outbound buffer/ACK](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L89-L148)
- **FACT：** remote-control backend envelope是 JSON text；公开 host 侧 schema 包含 `client_id`、`stream_id`、seq/ack、消息或分片、ping/pong、client close。单个分片目标约 100 KiB，最大 150 KiB，重组消息上限 100 MiB。  
  [envelope schema](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/protocol.rs#L104-L191) · [分片限制](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/segment.rs#L19-L23)

## 初始化、thread/turn 生命周期

- **FACT：** 每个连接必须先发一次 `initialize`，服务端返回 `userAgent`、`codexHome`、`platformFamily`、`platformOs`，随后客户端发 `initialized`；握手前其它请求得到 `Not initialized`，重复 initialize 得到 `Already initialized`。`clientInfo.name`还用于 OpenAI Compliance Logs Platform 的客户端识别。
- **FACT：** `initialize.params.capabilities.experimentalApi`、通知 opt-out 和 MCP 扩展能力在连接级协商；exact method opt-out 不支持通配符，未知名称忽略。实验 API 还可以用 `generate-ts/json-schema --experimental` 导出；未 opt-in 的实验方法/字段会被拒绝。  
  [初始化规则与 clientInfo](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L85-L159) · [实验 API gate](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L2488-L2547)
- **FACT：** 三个核心持久化原语是 Thread（对话）、Turn（一次用户输入到 agent 输出）、Item（用户输入、agent message、reasoning、shell、file edit 等）。`thread/start` 创建并自动订阅；`thread/resume` 继续已有 id；`thread/fork` 从存储历史分叉；`ephemeral:true` 为内存临时线程。  
  [核心原语与 lifecycle](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L66-L83)
- **FACT：** `turn/start` 立即返回 `inProgress` turn，真正开始时通知 `turn/started`；随后是 `item/started`、item-specific delta、`item/completed`，结束时 `turn/completed`。`turn/steer`向当前可 steering 的 turn 追加输入，必须带 `expectedTurnId`；`turn/interrupt`结束为 `interrupted`，但不会清理后台终端。  
  [turn/start 与 steer](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L900-L957) · [interrupt/steer](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L1184-L1252)
- **FACT：** `clientUserMessageId` 是可选的用户消息关联字段；服务端只把它回显到对应 `userMessage.clientId`。**INFERENCE：** 它不是命令幂等键，也不能替代 M4 `commandcache` 的幂等/重放语义；Harness 领域协议应继续独立生成和持久化 command identity。
  [turn/start、turn/steer 的 clientUserMessageId 语义](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L211-L214) · [userMessage.clientId](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L1630-L1635)
- **FACT：** 冷 resume 默认返回重建的 `thread.turns`；实验客户端可以 `excludeTurns:true`，再用 `thread/turns/list`/`thread/items/list` 分页。已持久化 token usage 会在 resume 后以通知补发；paginated thread 还有 cursor。一个 paginated thread 同时只允许一个 app-server 进程写入。  
  [resume、分页与写入所有权](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L361-L421)

## Server requests：审批、用户输入及其响应

- **FACT：** app-server 是双向协议：服务端在 agent 需要用户决策时可主动发带 `id` 的 JSON-RPC request；客户端必须用同一 id 返回 result 或 error。协议定义的 server requests 包括稳定的 `item/commandExecution/requestApproval`、`item/fileChange/requestApproval`、`mcpServer/elicitation/request`、`item/permissions/requestApproval`、`account/chatgptAuthTokens/refresh`、`attestation/generate`，以及 experimental 的 `item/tool/requestUserInput`、`item/tool/call`、`currentTime/read`。  
  [ServerRequest 定义与 wire method](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-protocol/src/protocol/common.rs#L1663-L1734)
- **FACT：** command approval 的请求携带 thread/turn/item、command/cwd/commandActions、reason，并可携带实验 additional permissions；响应决定可为 `accept`、`acceptForSession`、policy amendment、`decline`、`cancel`。file-change approval 携带 diff item 上下文，响应同样由客户端决定。  
  [审批顺序与响应](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L1712-L1739) · [approval payload 类型](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-protocol/src/protocol/v2/item.rs#L1448-L1550)
- **FACT：** `item/tool/requestUserInput` 是 experimental，问题数为 1–3；`isBlocking` 是客户端应否等待显式回答的权威字段，旧 `autoResolutionMs` 仅兼容保留。答复后服务端发 `serverRequest/resolved`；turn start/complete/interrupt 清掉未决请求时也发同一通知。  
  [README user input](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L1741-L1745) · [user input schema](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-protocol/src/protocol/v2/item.rs#L1619-L1701)
- **FACT：** MCP elicitation 支持 form、OpenAI extended form、URL 三种请求；客户端必须渲染或明确 decline/cancel。权限 request 的响应只能授予请求子集，可按 turn/session 作用域。  
  [MCP elicitation 与权限请求](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L1755-L1823)

## Notifications、背压与恢复

- **FACT：** 初始化并 start/resume thread 后，客户端持续消费 `thread/*`、`turn/*`、`item/*`；turn 事件含 `turn/started`、`turn/completed`、diff/plan/token usage 等；item 通常严格经历 `item/started → delta* → item/completed`。`turn/completed`只保证最终 agent message 的 summary fallback，完整 canonical item 要靠 item 通知。
- **FACT：** agent text、reasoning、command output、plan 等都有增量通知；可以通过 `optOutNotificationMethods` 精确关闭某些通知，但不影响 request/response/error。未处理的中途错误可能先发 `error`，随后以 failed turn 收口。  
  [Events 与 item lifecycle](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L1561-L1690)
- **FACT：** `thread/unsubscribe` 只解除当前连接订阅；最后一个 subscriber 消失后，线程仍可能在有活动或 30 分钟 idle window 内保持 loaded，随后才 `thread/closed`/`notLoaded`。因此断线恢复不能只依赖 Android 内存中的 UI state。
- **FACT：** 官方 Rust `RemoteAppServerClient` 客户端在每次连接建立时执行 initialize/initialized，能路由 response、notification 和 server request；连接断开时发 `AppServerEvent::Disconnected`、结束 worker 并失败 pending requests，源码没有自动重新建立连接的循环。**INFERENCE：** 通用 WSS/Unix 客户端必须由上层自行重连、重新 initialize，再用 `thread/resume`/分页恢复。
  [官方 remote client 连接与断线处理](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-client/src/remote.rs#L165-L185) · [worker 与 Disconnected](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-client/src/remote.rs#L195-L475) · [initialize/resume 前置](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-client/src/remote.rs#L798-L940)
- **FACT：** 官方 remote-control **主机侧**比通用 client 多做了 transport recovery：服务端 envelope 有 per-stream seq/ACK，重连保留未 ACK 消息和 subscribe cursor，客户端/控制器可以通过 backend cursor 继续收消息；这不是普通 `--listen ws://` 自动获得的能力。  
  [remote-control reader/writer、ACK、cursor](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L903-L1237)
- **FACT：** remote-control host 的 outbound buffer、seq 和 subscribe cursor 属于进程内 `WebsocketState`；SQLite 持久化的是 enrollment/启用偏好等状态，不是 Bridge 的完整 logical-event/command ledger。**INFERENCE：** 官方未 ACK buffer 不能替代 M4 在 Bridge 重启后仍需存在的 `journal`、`commandcache`、outbox/恢复账本；它只解决同一 app-server 进程的 remote-control transport reconnect。
  [WebsocketState 与内存 outbound state](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L150-L156) · [连接初始化的内存 state](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/websocket.rs#L402-L449)

## 鉴权与安全模型

### 通用 app-server WebSocket

- **FACT：** loopback listener 可以无鉴权；任何非 loopback listener 若没有 `--ws-auth capability-token` 或 `--ws-auth signed-bearer-token` 会在启动时拒绝。capability token 可从文件读取或配置 SHA-256；signed bearer 使用共享密钥、可选 issuer/audience、默认 30 秒 clock skew。  
  [鉴权参数/策略](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/auth.rs#L27-L104) · [非 loopback 启动保护](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/websocket.rs#L129-L146)
- **FACT：** 内置监听器只绑定 `ws://` 地址，源码没有 `wss://` bind listener；需要公网 TLS 时应在外层 TLS reverse proxy/relay 终止 HTTPS/WSS，并仍把 bearer auth 传到 app-server。**INFERENCE：** 这不能直接满足当前 Harness 的“relay 只看 opaque AES-GCM ciphertext”安全模型。
- **FACT：** listener 会拒绝所有带 `Origin` 的请求，health probe 只在无 Origin 时成功；这属于浏览器 CSRF/跨源防护，不是端到端消息加密。

### 官方 remote-control

- **FACT：** remote-control 必须使用 ChatGPT-backed auth，加载不到 ChatGPT 账号或 account id 会等待/失败；API-key auth 明确不支持。请求 enrollment/pair/status/client list/revoke 时使用 auth provider headers + `chatgpt-account-id`。  
  [remote-control auth requirement](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/auth.rs#L13-L80) · [enroll request headers](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/server_api.rs#L213-L275)
- **FACT：** remote-control WSS 使用 enrollment 返回的 Bearer server token 和 backend server id；源码公开的 envelope 是 JSON text，不包含 Harness 当前那种以配对 secret 做 AES-GCM 的应用层 ciphertext。**INFERENCE：** 上游安全边界是 ChatGPT backend 的 TLS/Bearer/account/device 授权，不等价于当前 relay 的“中继只能转发密文”。
- **FACT：** `requirements.toml` 可通过 `allow_remote_control = false` 强制禁用全部 remote-control RPC；daemon/remote-control 启动契约仍在开发中。
  [daemon experimental 说明](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-daemon/README.md#L1-L15) · [managed requirements 测试](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/tests/suite/v2/remote_control.rs#L128-L180)

## Auth endpoints 与持久化

- **FACT：** app-server RPC 支持 `account/read`、API key 登录、ChatGPT browser OAuth、ChatGPT device-code、logout、rate limits；ChatGPT managed auth 的 token 持久化到磁盘并自动刷新。Amazon Bedrock managed auth 是 experimental，personal access token 通过 app-server 外部的 `codex login --with-access-token`/`CODEX_ACCESS_TOKEN` 加载。  
  [Auth endpoints README](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server/README.md#L2258-L2406)
- **FACT：** app-server 的本地 thread store 有 local 和 in-memory 实现；`LocalThreadStore` 以 codex-rollout JSONL 持久化 history，并在可用时以 SQLite state database 持久化可查询 metadata。  
  [官方 thread-store README](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/thread-store/README.md#L1-L30)
- **FACT：** remote-control enrollment、server id、environment id、server name 和启用偏好写入 SQLite state DB；没有 SQLite state DB 时 remote control 无法启用。  
  [enrollment persistence](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/enroll.rs#L245-L370) · [enable 前置检查](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-transport/src/transport/remote_control/mod.rs#L237-L307)
- **FACT：** daemon 的本地生命周期状态保存在 `CODEX_HOME/app-server-daemon/`：`settings.json`、pid files、`daemon.lock`；`bootstrap` 依赖 standalone managed install，并启动 detached updater。  
  [daemon bootstrap/state](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-daemon/README.md#L34-L45) · [daemon state](https://github.com/openai/codex/blob/536f86e5cc9ec1ff38457d099bf320b9d08eeeba/codex-rs/app-server-daemon/README.md#L84-L113)

## 与当前 Harness bridge 的能力矩阵

本地对照证据：当前实施基线是 [M4 worktree](/Users/tony/Documents/harness-apk/.worktrees/m4-multi-backend-bridge)，其 [M4 设计](../../.worktrees/m4-multi-backend-bridge/docs/superpowers/specs/2026-08-15-m4-multi-backend-bridge-design.md) 明确保留 `internal/appserver` 作为 Codex 协议适配层，并由 `internal/run`、`internal/journal`、`internal/commandcache`、`internal/completion` 承担稳定领域语义（约 24–32、165–208 行），Android 与 relay 的领域协议不因 app-server 替换而透传化。root main 的 [remote/cmd/bridge/main.go](../../remote/cmd/bridge/main.go) 启动 `codex app-server --listen stdio://`、映射 `thread/*`/`turn/*`/RPC 的代码（约 299–363、480–542 行）以及 [remote/README.md](../../remote/README.md) 仅作为历史对照；Android [RemoteCrypto.kt](../../app/src/main/java/com/harnessapk/remote/RemoteCrypto.kt) 使用 AES/GCM（约 14–40 行）。

| bridge 职责 | 上游 app-server 直接能力 | 判断 | 依据与边界 |
| --- | --- | --- | --- |
| Codex agent loop、thread/turn/item JSON-RPC | 完整稳定表面；还提供 schema 生成 | **能替代 CodexAdapter 内部实现** | `CodexAdapter` 可用 app-server 替换旧 Codex-specific client；Android/relay 继续说 Harness 稳定领域协议，不能直接依赖 app-server wire protocol。 |
| 流式 agent 文本、reasoning、command/file diff、turn 状态 | `item/*`、`turn/*`、token/diff/plan notifications | **能替代** | 需要保留顺序、item id、delta 合并；`turn/completed` 不是完整 canonical history。 |
| command/file approval、tool user input、MCP elicitation | 双向 server requests + JSON-RPC response | **能替代协议，不能替代产品 UI** | Android 必须渲染审批/表单并以原 request id 回答；`requestUserInput` 是 experimental。 |
| Mac 线程 history、resume/fork、分页 | JSONL rollout + SQLite metadata、`thread/resume` | **能替代主机侧存储** | Android 自己的 UI/cache/通知状态不由 app-server 持久化；断线后仍需 resume。 |
| app-server 子进程、launchctl、更新 | `app-server daemon` 可 start/restart/bootstrap/updater | **部分能替代** | daemon 明确 experimental、Unix-only、要求 standalone managed install；不能假设与现有 LaunchAgent 运维语义完全一致。 |
| 自有 relay 的公网 WSS/HTTPS 接入 | 通用 `--listen ws://`，非 loopback 必须 token/JWT | **不能直接替代** | 内置 listener 不提供 TLS/WSS bind，不提供 Harness 的 host/device 路由与离线队列；仍需外层 relay/proxy 或改造网络入口。 |
| 自有 host/device pairing、设备 token、恢复码、撤销 | 官方 remote-control 有 pairing artifact、client list/revoke | **部分/未知** | 官方 pairing 是 ChatGPT backend 的环境 enrollment；源码未公开 Android/controller 端合同，也没有 Harness recovery-code 语义。 |
| relay 只转发 opaque AES-256-GCM，relay 不见 prompt/result | app-server 通用 WS/remote-control 是 JSON-RPC/JSON envelope + bearer/TLS | **不能替代** | 上游未提供匹配的应用层 AES-GCM relay API；若保留当前威胁模型必须继续使用自有加密层。 |
| Android 后台 wake / Aliyun Push | app-server README/source 无 Android/Aliyun push 能力 | **不能替代** | 保留 relay push 或另建通知服务；`turn/completed` 只是在连接上的通知。 |
| 断线重连、未确认消息恢复 | remote-control host 有 cursor/seq/ACK/replay；普通 app-server client 只发 `Disconnected` | **部分能替代** | 采用官方 remote-control 时可依赖其 backend transport；采用 stdio/通用 WSS 时 Android/bridge 自己重连并 initialize + resume。 |
| arbitrary provider / local Codex login | app-server auth 支持 API key、ChatGPT、PAT 等；remote-control 额外要求 ChatGPT auth | **分层判断** | 本地 app-server 可继续用现有 Codex 登录；不能把 API-key-only host 接入官方 remote-control。 |

### 可执行的下一步

1. **低风险路径（推荐先做）：** 保留现有 relay、配对、AES-GCM、push 和 Android `RemoteClient`；让 `CodexAdapter` 封装 app-server JSON-RPC，继续由 Bridge 将其归一化为 Harness 稳定领域协议，保留 Run、Journal、CommandCache、Approval、Multi-Backend 和恢复语义。
2. **通用 app-server WSS POC：** 在 Mac 只绑定 `127.0.0.1` 或 Unix socket；若要跨机器，使用受控 TLS reverse proxy + capability token/JWT，验证 initialize、`thread/start`、`turn/start`、审批 request/response、`turn/interrupt`、断线重连 + `thread/resume`，不要把 experimental WebSocket 直接当生产安全边界。
3. **官方 remote-control POC：** 使用 staging backend 和非生产测试账号验证 `remoteControl/enable`、`pairing/start/status`、controller claim、`thread/list`/turn 控制、断线恢复和 client revoke；在取得公开 controller wire contract 前，不应删除自有 relay。

## 未确认项与剩余风险

- **UNKNOWN：** OpenAI remote-control controller/Android 端的配对 payload、device auth、WebSocket envelope 完整合同以及是否允许第三方 client；本次上游公开仓库未给出可直接集成的 Android 实现。
- **UNKNOWN：** remote-control backend 的账号/套餐、地区、企业策略和服务端兼容性；源码只能证明 host 的请求形状和限制，不能证明 Harness 账号可用。
- **RISK：** app-server README 仍把通用 WebSocket、remote-control API 和 daemon 生命周期标为 experimental/unsupported，协议字段可能变更；应固定 Codex 二进制版本并用生成 schema 做兼容测试。
- **RISK：** app-server 进程本身拥有 Mac workspace、Codex auth 和审批权限。无论使用哪条 transport，都不能把未鉴权 listener 暴露公网；需要保留 loopback、TLS、bearer/JWT、权限 profile 和审批 UI 的组合防线。
- **RISK：** 上游 `thread/resume` 能恢复持久化 history，remote-control 的未 ACK buffer 主要只在 app-server 进程内；它们都不自动恢复 Android 的 pending command、UI selection、Bridge 重启后的 logical journal/commandcache 或 push delivery，这些仍是 Bridge/Android 责任。

## 只读验证记录

- `git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 ls-remote https://github.com/openai/codex.git HEAD refs/heads/main`：返回 `536f86e5cc9ec1ff38457d099bf320b9d08eeeba`。
- 在临时 clone `/tmp/codex-app-server-research.Ftcgp5` 执行 `git show -s --format=fuller 536f86e5cc9ec1ff38457d099bf320b9d08eeeba`：确认提交时间、作者和主题；`git describe --tags --always` 返回 `536f86e5cc`，该 commit 未指向 tag。
- 使用 `nl -ba` 定向读取上述 README/source，核对 transport、protocol、server request、remote-control、auth、thread-store 与 daemon 的行号；未运行会改动本仓库的构建/测试命令。
- 本次只修改本文件；未修改、回退或删除其他工作区文件。
