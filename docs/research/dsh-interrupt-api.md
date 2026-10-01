# DSH「中断运行中的 turn / 取消 agent 流式生成」插件层 API 调研

> 只读调研。所有结论均来自 macOS 上 `/Applications/DeepSeek Harness.app/Contents/Resources/app.asar` 内的实际代码，
> 每条附 asar 内文件路径 + 代码片段（≤15 行）。

## 0. 版本校正（重要）

任务描述里的 `0.1.5-rc.1` 与本机实际安装版本**不一致**。本机 asar 内的包版本为：

| 包 | 版本 |
|---|---|
| `@deepseek-ai/dsh`（Electron app `CFBundleShortVersionString` 同值） | **0.2.0-rc.2** |
| `@deepseek-ai/dsh-agent` | 0.2.0-rc.2 |
| `@deepseek-ai/dsh-agent-loop` | 0.2.0-rc.2 |
| `@deepseek-ai/dsh-api-session-controller` | 0.2.0-rc.2 |

证据：asar 条目 `/dsh/node_modules/@deepseek-ai/dsh/package.json`（`"name": "@deepseek-ai/dsh"`, `"version": "0.2.0-rc.2"`）、
`/Applications/DeepSeek Harness.app/Contents/Info.plist` → `CFBundleShortVersionString = 0.2.0-rc.2`。

**下文全部结论仅对 0.2.0-rc.2 成立。** 若目标机器装的是 0.1.5-rc.1，需在对应 asar 上重跑本文的检索命令复核。

---

## 1. 结论速览

1. **不存在 `agent.interrupt()` / `agent.abort()` / `cancelTurn()` / `turn/interrupt` 这类 API。** 全 asar 检索
   `cancelTurn|abortTurn|stopTurn|interruptTurn|turnInterrupt|turn/interrupt` **零命中**。
2. **插件层唯一合法的「中断正在跑的 turn」入口是 `Agent.cancel(cause, options)`**，签名
   `cancel(cause: AgentCancelCause, options?: CancelOptions): void`（同步、void、不等待结算）。
3. **官方自己（UI 停止按钮、Session 归档、子代理中断、workspace/session-stop）全部走 `agent.cancel(...)`**，
   没有任何一条走别的东西。
4. **`cancel` 的语义是「中断当前 activity」而不是「杀掉 agent」**：它 abort 当前 phase 的 `AbortController`，
   默认还会清空 inbox；`{ keepInbox: true }` 只中断 in-flight turn 而保留排队输入。
   agent 本体仍在 registry 里、仍可继续 `followup()`。
5. **官方 Remote 面等价物是 `session.cancel`（Host 方法名 `cancel`，namespace `session`）**：
   `@Remote('cancel') cancel(request: SessionCancelRequest): SessionCancelValue`，内部就是
   `agent.cancel({ kind: 'user' }, { keepInbox: true })`。这是 codex `turn/interrupt` 最贴切的映射目标。
6. **子代理（subagent）走另一条 Remote：`subagents.interruptByParent(childSessionId, parentSessionId, 'continuable')`**，
   最终同样落到 `agent.cancel({ kind: 'user' | 'parent' }, { keepInbox: true })`。
7. **turn 状态可读**：`Agent.status: AgentStatus = 'idle' | 'running'`；另有 `agent/status` 事件
   `{ agent, status }` 在状态跃迁时发射。**没有 `currentTurn`/`turnId` 这种公开字段。**
8. **`ctx.agents.resume()` 的字段名是 `resumeSessionId`**（不是 `sessionId`），签名
   `async resume(options: ResumeAgentOptions): Promise<AgentHandle>`；`ownerCtx` 由 `ctx.agents` 服务自己注入，
   插件**不需要也不能**传。
9. **`dispose()` 是「销毁」，不是「中断」**：它会 `cancel({kind:'disposed'})` + `whenIdle()` + 销毁 scope +
   关闭 session 写入句柄 + 从 registry 注销 + 断开 session。用它实现 `turn/interrupt` 会连带杀掉会话。

---

## 2. 证据一：`Agent` 的公开方法清单（唯一权威契约）

`Agent` 接口的 `.d.ts` 原文被内嵌在两处生成产物里（内容一致）：

- `/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/typert.host.js` 第 1582 行、第 2505 行（`"declaration"` JSON 字段）
- `/dsh/node_modules/@deepseek-ai/dsh-tool-cordis/lib/types/api-catalog.js`（`name: 'Agent'`）

```
export interface Agent {
    readonly id: SessionId;
    readonly options: AgentOptions;
    readonly session: Session;
    readonly inbox: Inbox;
    readonly status: AgentStatus;
    readonly ctx: Context;
    cancel(cause: AgentCancelCause, options?: CancelOptions): void;
    whenIdle(): Promise<void>;
    runMaintenance<T>(task: (signal: AbortSignal) => Promise<T>): Promise<T>;
    send(message: UserMessage, target: InboxTarget, wakeup: boolean): void;
    followup(message: UserMessage): void;
    steer(message: UserMessage): void;
    inject(message: UserMessage): void;
}
```

**断言：`Agent` 上没有 `interrupt`、没有 `abort`、没有 `cancelTurn`。** 中断语义只由 `cancel` 承担。

### 相关类型（同一 api-catalog.js）

```ts
export type AgentStatus = 'idle' | 'running';

export type AgentCancelCause =
  | { readonly kind: 'user' }
  | { readonly kind: 'parent' }
  | { readonly kind: 'hook'; readonly reason: string }
  | { readonly kind: 'disposed' };

export interface CancelOptions { keepInbox?: boolean | undefined; }

export interface AgentHandle { agent: Agent; dispose(): Promise<void>; }
```

来源：`/dsh/node_modules/@deepseek-ai/dsh-tool-cordis/lib/types/api-catalog.js`
（`AgentStatus` / `AgentCancelCause`+`CancelOptions` 在 typert.host.js 亦可交叉验证）。

### `agent/status` 事件（读状态变化的官方入口）

来源 `/dsh/node_modules/@deepseek-ai/dsh-tool-cordis/lib/types/api-catalog.js`：

```
name: 'agent/status',
mode: 'emit',
signature: 'agent/status'(this: Scoped<Agent>, payload: { agent: Agent; status: AgentStatus }): void
summary: Agent status changed (`idle` ⇄ `running`).
description: ... A waking delivery enters `running` synchronously after reserving cancellation;
             `idle` means no driver remains scheduled or active.
```

scope-filtered：agent-scoped listener 只收到该 agent 的事件。

---

## 3. 证据二：`cancel()` 的真实实现（dsh-agent-loop）

`ReactLoopAgent` 类声明于 `/dsh/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js:748`。
`cancel` / `send` / `followup` / `steer` / `inject` / `whenIdle` 的实现在第 801–876 行：

```js
send(message, target, wakeup) {
    const wakingAfterAbort = wakeup && this.phase.kind !== "idle" && this.phase.abort.signal.aborted;
    const resolvedTarget = wakingAfterAbort ? "next-turn" : target;
    this.inbox.splice(resolvedTarget, Infinity, 0, [message]);
    if (wakeup) this.wakeDriver(wakingAfterAbort);
}
followup(input) { this.send(input, "next-turn", true); }
steer(input)    { this.send(input, "next-step", true); }
inject(input)   { this.send(input, "next-step", false); }
cancel(cause, options = {}) {
    if (!options.keepInbox) {
        this.inbox.clear();
        if (this.phase.kind !== "idle") this.phase.wakeRequested = false;
    }
    if (this.phase.kind !== "idle") this.phase.abort.abort(cause);
}
```

（`/dsh/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js:801-822`）

**断言：**
- `cancel` 是**同步**方法，返回 `undefined`；不返回 Promise，不等待结算。
- phase 为 `idle` 时 `cancel()` 是**无害 no-op**（只可能清 inbox）。
- 非 idle 时执行 `this.phase.abort.abort(cause)`——即真正切断当前 phase 的 `AbortController`。
- `keepInbox` 只影响 `inbox.clear()`，不影响 abort 动作本身。

### `status` getter 与 phase 机（第 784–799 行）

```js
this.phase = { kind: "idle", lastTurn };
...
get status() {
    return this.phase.kind === "idle" || this.phase.kind === "maintenance" ? "idle" : "running";
}
/** Commit a phase and publish its externally visible status transition. */
setPhase(next) {
    const previousStatus = this.status;
    this.phase = next;
    const status = this.status;
    if (status !== previousStatus) this.dispatch.emit("agent/status", { status });
}
```

（`/dsh/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js:784-799`）

**断言：`maintenance` 阶段对外报 `idle`。** 所以 `status === 'running'` 正是「有 turn 在跑」的判据，
可以直接用于 `turn/interrupt` 的前置校验。

### 官方 README 对这一点的自述（强烈建议一并引用）

`/dsh/node_modules/@deepseek-ai/dsh-agent/README.md:179`（Known Limitations 段）：

```
- **`cancel()` clears the inbox by default** — it aborts the in-flight turn plus queued and steering
  work; `cancel(cause, { keepInbox: true })` aborts only the turn and preserves pending items,
  and there is no step-only abort that keeps the turn running.
```

`/dsh/node_modules/@deepseek-ai/dsh-agent-loop/README.md:76`：

```
Cancellation is cooperative: `agent.cancel()` aborts the current activity and, unless `keepInbox`
is set, clears pending work; a cancelled stream finalizes the text already delivered to the user.
```

**断言：文档明确声明「没有只中断 step 而保留 turn 的 API」。** 因此 `turn/interrupt` 只能映射到
`cancel(..., { keepInbox: true })`，中断粒度是 **turn（当前 activity）**。

### `whenIdle()`（第 871–876 行）

```js
async whenIdle() {
    let activity;
    do
        await (activity = this.activityDone);
    while (activity !== this.activityDone);
}
```

**断言：`whenIdle()` 解析于整个 agent 达到 quiescence，不是「当前 turn 结束」。** 官方在 `dispose()` 里用它等 drain。

---

## 4. 证据三：官方认可的 Remote 中断路径（反推结论）

### 4.1 UI 停止按钮的调用链

UI 停止按钮在 `dsh-client-ui-conversation`，不在 `dsh-client-ui-chat`。

```js
// /dsh/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js:18023-18025
const stop = (sessionId) => {
    scopedConversation(sessions, sessionId).cancel().catch((_error) => {});
};
```

同一个 `stop` 同时绑定：主按钮 onClick、独立 Stop 按钮、`Esc Esc` 固定快捷键
（`response.stop` command，见同文件 18023 附近与 `installStopShortcut`）。

**关于任务里「搜 `interrupt`」的预期：`dsh-client-ui-chat/lib/client.js` 里没有任何 interrupt Remote 调用。**
该文件中的 `interrupt` 全部是滚动动画（`follow.interrupt(body, ...)`，第 2104 行）和
「被中断的 assistant 消息」展示态（`interrupted: data.status === "interrupted"`）。这条线索是**否定性证据**。

### 4.2 client 侧 `cancel()` 的真实 Remote 方法名

```js
// /dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/client.js:1803-1811
async cancel() {
    const address = this.address;
    const result = address !== void 0
        ? await this.remote.subagents.interruptByParent(
              address.childSessionId, address.parentSessionId, "continuable")
        : await this.remote.session.cancel({ sessionId: this.sessionId });
    if (!result.ok) {
        this.promptError = { op: "stop", error: result.error };
        this.notifier.markDirty();
    }
    return result;
}
```

**断言：停止按钮最终打到两个 Remote 方法之一：**
- 普通会话 → `session.cancel({ sessionId })`
- 子代理会话（有 address） → `subagents.interruptByParent(childSessionId, parentSessionId, 'continuable')`

### 4.3 `session.cancel` 的 Host 端注册（typert）

```js
// /dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/typert.host.js:962-985（节选）
{
  id: '@deepseek-ai/dsh-api-session-controller#session/cancel',
  service: 'sessionController',
  namespace: 'session',
  method: 'cancel',
  invocation: { kind: 'direct' },
  parameters: [ { name: 'request', wire: 'request', source: 'json', codec: {
      mode: 'strict',
      typeSymbol: '@deepseek-ai/dsh-api-session-controller/types#SessionCancelRequest',
      create: _deepseek_ai_dsh_api_session_controller_session_cancel_parameter_0$schema } } ],
  result: { mode: 'strict',
      typeSymbol: '@deepseek-ai/dsh-api-session-controller/types#SessionCancelValue',
      create: _deepseek_ai_dsh_api_session_controller_session_cancel_result$schema },
  sourceLocation: {"file":"packages/api/session-controller/src/index.ts","line":457,"column":3},
}
```

JSON-Schema 形状（同文件第 45–53 行）：

```js
const ..._session_cancel_parameter_0$schema = () => (... = z.object({
  'sessionId': z.intersection(z.string(), z.unknown()).readonly(),
}))
const ..._session_cancel_result$schema = () => (... = z.object({
  'accepted': z.literal(true).readonly(),
}))
```

即 `SessionCancelRequest = { readonly sessionId: SessionId }`，
`SessionCancelValue = { readonly accepted: true }`。

### 4.4 `session.cancel` 的 Host 端实现（它怎么触发中断）

```js
// /dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js:989-1000
/**
* Cancel one live ordinary Agent while retaining pending inbox work.
* @param request - Session whose active Agent turn is cancelled.
* @returns acknowledgement that cancellation was requested.
*/
cancel(request) {
    const agent = this.ctx.agents.get(request.sessionId);
    if (agent === void 0) throw new RemoteError("session/not-found",
        `session "${request.sessionId}" not found (not attached)`, { sessionId: request.sessionId });
    if (hasApiSessionSubagentOwner(this.ctx, agent.session, agent)) throw apiSessionSubagentOwnershipError(request.sessionId);
    agent.cancel({ kind: "user" }, { keepInbox: true });
    return { accepted: true };
}
```

`sessionController` 服务上的声明签名（api-catalog.js）：

```
@Remote('cancel') cancel(request: SessionCancelRequest): SessionCancelValue
```

**断言：官方 Remote 中断的语义 = `ctx.agents.get(sessionId)` → `agent.cancel({ kind: 'user' }, { keepInbox: true })`。**
这就是 codex `turn/interrupt` 应当映射的目标行为。

**注意副作用：`accepted: true` 只代表「cancel 信号被受理」，不代表目标已 quiescent**（见 4.6 的同类 jsDoc）。

### 4.5 对照路径一：`workspace/session-stop`（会话归档时）

```js
// /dsh/node_modules/@deepseek-ai/dsh-agent/lib/index.js:34-37
ctx.on("workspace/session-stop", ({ sessionId }) => {
    const agent = lookup(sessionId);
    if (agent?.status === "running") agent.cancel({ kind: "user" });
});
```

**断言：又一次落到 `agent.cancel({ kind: 'user' })`**；这里**不带** `keepInbox`，
注释（同文件 16–23 行）说明「but without the stop button's `keepInbox`, so queued input is discarded」——
即「停止按钮 = `cancel({kind:'user'}, {keepInbox:true})`」是官方自述的一致语义。

### 4.6 对照路径二：`subagents.interruptByParent`（子代理）

```js
// /dsh/node_modules/@deepseek-ai/dsh-subagent/lib/index.js:3039-3069（节选）
/**
* Remote face of {@link interrupt} under one durable parent address. ...
* @returns acknowledgement that the cancel signal was admitted, not that the target is quiescent.
* @throws {RemoteError} `gateway/bad-request` for an empty id,
*   `subagent/unauthorized` when the address does not own the live target, otherwise `gateway/internal`.
*/
interruptByParent(childSessionId, parentSessionId, mode) {
    validateControlRequest("subagent.interrupt", { childSessionId, parentSessionId, mode });
    try {
        this.interrupt(childSessionId, { kind: "user", parentSessionId });
    } catch (error) {
        if (error instanceof SubagentError && error.code === "UNAUTHORIZED")
            throw new RemoteError("subagent/unauthorized", "subagent does not belong to this parent", { childSessionId }, { cause: error });
        throw new RemoteError("gateway/internal", "subagent interrupt failed", {}, { cause: error });
    }
    return { accepted: true };
}
```

它调用的 `interrupt` 最终也是 `agent.cancel`：

```js
// /dsh/node_modules/@deepseek-ai/dsh-subagent/lib/index.js:844-857（节选）
interrupt(targetSessionId, authority) {
    ...
    const activation = this.resident.get(targetSessionId);
    if (activation === void 0) return;                 // 目标不 live ⇒ 静默 no-op
    if (authority.kind === "user") {
        if (activation.handle.agent.session.header.parentSession !== authority.parentSessionId)
            throw new SubagentError(`subagent "${targetSessionId}" belongs to another parent session`, "UNAUTHORIZED");
    } else if (!activation.ancestry.has(authority.agent)) throw new SubagentError(...);
    if (activation.inbox.closing !== void 0) return;
    activation.handle.agent.cancel(
        authority.kind === "user" ? { kind: "user" } : { kind: "parent" },
        { keepInbox: true });
}
```

服务面签名（api-catalog.js，service key `subagents`）：

```
interrupt(targetSessionId: SessionId, authority: SubagentInterruptAuthority): void
@Remote('interruptByParent') interruptByParent(
    childSessionId: SessionId, parentSessionId: SessionId, mode: 'continuable',
): SubagentInterruptReceipt

export type SubagentInterruptAuthority =
  | { readonly kind: 'user'; readonly parentSessionId: SessionId }
  | { readonly kind: 'ancestor'; readonly agent: Agent };
export interface SubagentInterruptReceipt { readonly accepted: true; }
```

工具侧用法示例（`dsh-tool-subagent-control`，即 `interrupt_agent` 模型工具）：

```js
// /dsh/node_modules/@deepseek-ai/dsh-tool-subagent-control/lib/index.js:86-92
execute(args, exec) {
    const caller = exec.agent;
    if (!caller) throw new Error("interrupt_agent requires a calling agent (exec.agent was undefined)");
    ctx.subagents.interrupt(brandString(args.agent_id), { kind: "ancestor", agent: caller });
    return Promise.resolve({ accepted: true });
}
```

**断言：不存在「按 turn id 中断」的 API。** 所有中断都是「按 target session/agent 中断其当前 activity」。

---

## 5. `ctx.agents` 服务的完整公开方法（api-catalog.js, service key `agents`）

```
currentInitiator(): Agent | undefined
requireInitiator(): Agent
withInitiator<T>(agent: Agent, operation: () => T): T
withoutInitiator<T>(operation: () => T): T
setFactory(factory: AgentFactory): () => void
async create(options: CreateAgentOptions): Promise<AgentHandle>
async resume(options: ResumeAgentOptions): Promise<AgentHandle>
register(agent: Agent): ReturnType<Context['effect']>
```

`get` / `list` / `roots` / `isOwnedBy` / `enter` / `announce` 未出现在 catalog 的 signature 列表里，
但在 `dsh-agent/lib/index.js` 中确实存在且被官方调用：

```js
// /dsh/node_modules/@deepseek-ai/dsh-agent/lib/index.js:590-624（节选）
get(id)            { return this.store.get(id)?.agent; }        // → Agent | undefined
isOwnedBy(id, owner) { return this.store.get(id)?.owner === owner; }
list()             { return [...this.store.values()].map((entry) => entry.agent); }
roots()            { return [...this.store.values()].filter((entry) => entry.owner === void 0).map((entry) => entry.agent); }
```

**断言：`ctx.agents.get(sessionId)` 是「把 wire 上的 sessionId 解析成 live Agent」的官方做法**
（`sessionController.cancel` 自己就是这么写的，见 4.4）。返回 `undefined` 表示该 session 当前没有 attached agent。

---

## 6. `ctx.agents.resume()` 的确切参数形状

### 6.1 服务层（`dsh-agent`）

```js
// /dsh/node_modules/@deepseek-ai/dsh-agent/lib/index.js:458-470
/**
* Load a persisted session and resume an agent on it through the registered factory.
* @param options - persisted identity, optional live parent, configuration, and setup.
* @returns the handle after setup, rollback-covered publication, and loop start complete.
*/
async resume(options) {
    const ownerCtx = this.ctx;
    const { target } = this.requireFactory();
    const receiver = getTraceable(ownerCtx, target);
    return Reflect.apply(target.resume, receiver, [ownerCtx, options]);
}
```

**断言：插件只传 `options`；`ownerCtx` 由服务用 `this.ctx` 自己注入。**

### 6.2 工厂层（`dsh-agent-loop`）

```js
// /dsh/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js:1916-1929
/**
* Resume an owned agent from the configured persistence service.
* @param ownerCtx - caller context that owns load, setup, and the live lifecycle.
* @param options - persisted identity, optional live parent, loop options, setup, and cancellation.
* @returns the published handle.
*/
async resume(ownerCtx, options) {
    const persistence = this.runtime.ctx.get("sessionPersistence");
    if (persistence === void 0) throw new Error("cannot resume: session persistence is not configured (load a dsh-session-persistence backend)");
    return this.resumeWith(ownerCtx, persistence, options);
}
/** Resume through an explicit persistence handle used by the deferred config path. */
resumeWith(ownerCtx, persistence, options) {
    const id = options.resumeSessionId;
```

**断言：字段名是 `resumeSessionId`，不是 `sessionId`。**
（对照：`create` 用的是 `options.sessionId`，见 `createAgent` 第 1853 行 `this.runtime.ctx.sessions.prepare(options.sessionId, ...)`。）

### 6.3 `ResumeAgentOptions` / `CreateAgentOptions` 原文

来源 `/dsh/node_modules/@deepseek-ai/dsh-tool-cordis/lib/types/api-catalog.js`：

```ts
export interface ResumeAgentOptions {
    readonly resumeSessionId: SessionId;
    readonly parentAgent?: Agent;
    readonly agentOptions?: AgentOptions;
    readonly signal?: AbortSignal;
    readonly setup?: AgentSetup;
}
```

```ts
export interface CreateAgentOptions {
    readonly sessionId: SessionId;
    readonly parentAgent?: Agent;
    readonly meta?: {
        readonly cwd?: string;
        readonly parentSession?: SessionId;
        readonly isSeeded?: boolean;
        readonly origin?: 'subagent';
        readonly delegationDepth?: number;
        readonly agentPreset?: string;
    };
    readonly inheritedEventCount?: SessionLogOffset;
    readonly seed?: readonly SessionEvent[];
    readonly agentOptions?: AgentOptions;
    readonly signal?: AbortSignal;
    readonly setup?: AgentSetup;
}

export interface AgentOptions {
    provider?: string;
    model?: string;
    reasoningEffort?: ReasoningEffortId;
    maxTokens?: number;
}
export type AgentSetup =
  (agentCtx: Context, agent: Agent) => AgentSetupCommit | Promise<AgentSetupCommit | void> | void;
export interface AgentHandle { agent: Agent; dispose(): Promise<void>; }
```

**断言：**
- `resume()` **不需要** `ownerCtx`（服务注入）、**不需要** `setup`（可选）、**不需要** `agentOptions`（可选，
  缺省时 `options.agentOptions ?? {}`，见第 1971 行）；只有 `resumeSessionId` 是必需项。
- `resume()` 若没有挂 `sessionPersistence` 后端会**直接 reject**，错误文本为
  `cannot resume: session persistence is not configured (load a dsh-session-persistence backend)`。
- `agentOptions` 用的是 `provider` / `model` / `reasoningEffort` / `maxTokens`（`AgentOptions`），
  注意 catalog 中 `dsh-api-session-controller` 内嵌的 `AgentOptions` 还多一个 `subagentDepth`，
  **两个声明不一致**（见「不确定」清单）。

### 6.4 `dispose()` 的确切语义

```js
// /dsh/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js:1678-1710（节选）
const dispose = (ownerTriggered = false) => disposing ??= (async () => {
    abort.abort(new Error(`agent "${id}" lifecycle disposed`));      // 1. 切断 setup/生命周期 signal
    callerSignal?.removeEventListener("abort", onCallerAbort);
    this.ownership.signal.removeEventListener("abort", onFactoryTeardown);
    const failures = [];
    try {
        if (publication !== void 0) await publication.promise;
        if (machine === void 0) await machineReady.promise;
        if (machine !== void 0) {
            machine.cancel({ kind: "disposed" });                    // 2. 中断当前 activity（注意：无 keepInbox ⇒ 清 inbox）
            await machine.whenIdle();                                // 3. 等 drain 到 quiescence
            await machine.scope.dispose();                           // 4. 卸载 agent-scoped 注册
        }
    } catch (error) { failures.push(error); }
    try { await handle?.close(); } catch (error) { failures.push(error); }   // 5. 关闭 session 写句柄
    try { detachAgent?.(); detachSession?.(); } finally {                    // 6. 从 registry / session 注销
        untrack();
        if (!ownerTriggered) await unfollowOwner();
    }
    if (failures.length === 1) throw failures[0];
    if (failures.length > 1) throw new AggregateError(failures, `agent "${id}" disposal failed`);
})();
```

`dsh-agent` README 第 41 行的自述：

```
await handle.dispose()   // stops the loop, unregisters, removes the session, unwinds the scope
```

**断言：`dispose()` 是幂等的（`disposing ??=`）、异步的、破坏性的。**
它内部**确实**调用了 `cancel({kind:'disposed'})`，但**绝不能**拿它当 `turn/interrupt`：
它会连带清空 inbox、销毁 scope、关闭 session 写句柄、把 agent 从 registry 摘掉。
`dispose()` 之后 agent 对象不再可用。

---

## 7. 对 appserver 插件实现 `turn/interrupt` 的映射建议（基于以上断言）

可直接落地的等价实现，全部使用已证实存在的 API：

| codex app-server 语义 | DSH 等价调用 | 依据 |
|---|---|---|
| `turn/interrupt`（主会话） | `ctx.agents.get(sessionId)?.cancel({ kind: 'user' }, { keepInbox: true })` | §4.4：官方 `sessionController.cancel` 的逐字实现 |
| `turn/interrupt`（子代理/continuable 子会话） | `ctx.subagents.interrupt(childSessionId, { kind: 'user', parentSessionId })`，或远端面 `interruptByParent(child, parent, 'continuable')` | §4.6 |
| 判断「是否有 turn 在跑」 | `agent.status === 'running'`，或订阅 `agent/status` 事件 | §3、§2 |
| `turn/interrupt` 的 `{ accepted: true }` 回执 | 立即返回，与官方一致 | §4.6 jsDoc：`accepted` 不表示 quiescent |
| 等待真正停下 | `await agent.whenIdle()`（注意：等的是整个 agent 到 quiescence，不是单个 turn） | §3 |
| 中断原因区分 | `AgentCancelCause` 的 `kind`：`'user'`（用户主动停）/ `'parent'`（父代理）/ `'hook'`（带 `reason`）/ `'disposed'` | §2 |

**`kind` 的选择：`turn/interrupt` 是外部用户发起的，语义上应传 `{ kind: 'user' }`**——
与 `sessionController.cancel` 和 `workspace/session-stop` 一致。

**不要**使用：`dispose()`（破坏性，§6.4）、任何 `interrupt`/`abort`/`cancelTurn` 命名（不存在，§1）。

---

## 8. 不确定 / 未找到（显式清单）

以下项目本轮**没有**在 asar 中找到能支撑断言的证据，故不作结论：

1. **`abort` 信号如何流向 LLM 流式请求的具体链路未逐层核实。**
   已确认 `cancel()` 执行 `this.phase.abort.abort(cause)`（§3），但 `phase.abort.signal` 传入
   `ctx.llm.prepareCall()` / 流式 dispatch 的完整传递链未逐行追踪。
2. **`agent.cancel()` 是否保证「已流出的文本被 finalize」的实现细节未核实。**
   仅有两处文档性表述：`dsh-agent-loop/README.md` 第 76 行
   「a cancelled stream finalizes the text already delivered to the user」、
   第 126 行「`turn/end` declares `TurnEndCancelCause`」。未找到对应代码行。
3. **`agent.cancel()` 与 `turn/end` 事件之间的确切异步时序未确定。**
   `cancel` 本身同步返回；`turn/end` 何时落盘（是否需 `await agent.whenIdle()` 才可见）未验证。
4. **`AgentOptions` 存在两处不一致声明。**
   `dsh-tool-cordis/lib/types/api-catalog.js` 中 `AgentOptions` 为
   `{provider?, model?, reasoningEffort?, maxTokens?}`；而
   `dsh-api-session-controller/lib/typert.host.js` 内嵌的 `AgentOptions` 多一个 `subagentDepth?: number`。
   未确定哪个是 `ctx.agents.create/resume` 的实际接受面。
5. **`ctx.agents` catalog signature 列表缺少 `get`/`list`/`roots`/`isOwnedBy`。**
   这些方法在 `dsh-agent/lib/index.js` 中确实实现且被官方调用，但未出现在生成的 API catalog 里
   （可能是继承自基类、未被 AST walk 捕获）。是否属于「稳定公开面」未经文档确认。
6. **`0.1.5-rc.1` 上的行为未验证。** 本机只有 0.2.0-rc.2；§0 已说明。
7. **未找到任何名为 `interrupt` 的 turn 级 API，也未找到 `turn/interrupt` 字符串。**
   这是「零命中」结论，不是「找到了但不同名」——检索模式见 §1 第 1 条。
8. **`dsh-client-ui-chat/lib/client.js` 内 `interrupt` 的全部 56 处命中均为滚动动画与展示态，
   没有一处是 Remote 调用**——因此任务假设的「UI 停止按钮调用名为 interrupt 的 Remote 方法」**不成立**，
   正确名字是 `session.cancel`（+ `subagents.interruptByParent`）。

---

## 9. 复现用检索命令（asar 必须用 Python 解析）

```python
# 通用：把 asar 内单文件 dump 到 /tmp 后用 grep 分析
import json, struct
p = "/Applications/DeepSeek Harness.app/Contents/Resources/app.asar"
f = open(p, "rb"); hdr = f.read(16); size = struct.unpack("<I", hdr[12:16])[0]
j = json.loads(f.read(size).decode("utf8")); data_off = 16 + size
entries = []
def walk(node, prefix):
    for k, v in (node.get("files") or {}).items():
        np = prefix + "/" + k
        if "files" in v: walk(v, np)
        else: entries.append((np, v.get("size", 0), v.get("offset")))
walk(j, "")
# 注意：部分条目 offset 为 None；部分 .js/.json 正文前有一个非 UTF-8 前缀字节（0x82 / ';'），
#       decode 前需 strip。
```

本轮实际检出的关键 asar 路径：

| 路径 | 用途 |
|---|---|
| `/dsh/node_modules/@deepseek-ai/dsh-agent/lib/index.js` | `ctx.agents` 服务、`workspace/session-stop`、`get/list/roots` |
| `/dsh/node_modules/@deepseek-ai/dsh-agent/README.md` | `Agent` 契约文档（第 48、179 行是关键断言） |
| `/dsh/node_modules/@deepseek-ai/dsh-agent-loop/lib/index.js` | `ReactLoopAgent.cancel/status/whenIdle`、`resume`、`dispose` |
| `/dsh/node_modules/@deepseek-ai/dsh-agent-loop/README.md` | `resume` 示例、cancellation 说明 |
| `/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js` | `sessionController.cancel()` 实现 |
| `/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/typert.host.js` | Remote 注册 + 内嵌全部 `.d.ts` 原文 |
| `/dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/client.js` | client 侧 `cancel()` 分流 |
| `/dsh/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js` | 停止按钮 `stop()` 实现（第 18023 行） |
| `/dsh/node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js` | 反证：无 interrupt Remote |
| `/dsh/node_modules/@deepseek-ai/dsh-subagent/lib/index.js` | `interrupt` / `interruptByParent` 实现 |
| `/dsh/node_modules/@deepseek-ai/dsh-tool-cordis/lib/types/api-catalog.js` | **全量 API catalog**：服务方法签名 + 所有 `.d.ts` 声明 |
| `/dsh/node_modules/@deepseek-ai/dsh-tool-subagent-control/lib/index.js` | `interrupt_agent` 工具用法示例 |

> 提示：`/dsh/node_modules/@deepseek-ai/dsh-tool-cordis/lib/types/api-catalog.js` 是本轮信息密度最高的单文件
> （609 KB，含每个 `ctx.<key>` 服务的签名/描述/参数，以及全部导出类型声明），后续调研应优先读它。
