# Chat Rendering Stability Design

## 背景

Harness APK 当前的聊天链路已经能完成本地多会话、流式回复、暂停、上下文压缩、联网搜索、图片输入和项目沉淀，但长文本和复杂 Markdown 场景仍然容易出现卡顿、滚动错位、渲染压力过大、暂停不稳定和错误诊断不足。

RikkaHub 的公开仓库把消息抽象成 `UIMessage`，支持文本、图片、文档、reasoning、tool call/result 等 content parts，并通过 chunk merging 支持流式更新；会话数据还用 `MessageNode` 支持分支。这说明稳定聊天客户端的核心不是单纯优化一个 Compose 组件，而是要把“网络流式事件、消息结构、渲染块、滚动策略、诊断日志”一起收敛成稳定管线。

参考资料：

- RikkaHub README：多供应商、多模态、Markdown、搜索、记忆等能力说明，https://github.com/rikkahub/rikkahub/blob/master/README_ZH_CN.md
- RikkaHub AGENTS：`UIMessage`、`MessageNode`、`Message Transformer` 和模块结构说明，https://github.com/rikkahub/rikkahub/blob/master/AGENTS.md
- RikkaHub 2.4.1 release：仍在修复回复丢字、公式显示、按钮遮挡和状态栏细节，https://github.com/rikkahub/rikkahub/releases/tag/2.4.1

## 当前状态

当前代码的关键入口：

- `ChatRepository.appendAssistantText()` 每次流式 delta 都把 `current.content + delta` 写回整条消息。
- `MessageEntity` 只持久化整段 `content`，没有 message part、reasoning part、tool call 或 token usage 字段。
- `OpenAiCompatibleClient.streamChat()` 只输出 `ChatDelta(text)`，没有 finish reason、usage、reasoning、tool call、provider raw event。
- `StreamingTextBuffer` 已经做了 250ms / 160 chars 的粗粒度节流，但节流发生在文本层，不能阻止整条消息反复重组。
- `MarkdownMessage` 有 `IncrementalMarkdownBlockCache`，但 UI 仍把一个消息气泡当作完整 markdown 输入，尾部重排和长列表仍会压 Compose。
- `ChatScreen` 已经有停止按钮、自动滚动和选择复制，但这些行为仍围绕单条字符串消息组织。

## 问题定义

长流式回复卡顿的根因有四类：

1. 数据写入粒度过粗
   每个 delta 追加后都会更新整条消息内容，Room flow 会把完整消息列表重新发给 UI。即使 UI 有 cache，也会触发大量无意义对象变化。

2. 渲染粒度过粗
   Markdown 按整条消息输入，复杂内容中的标题、表格、代码块、列表和未闭合代码 fence 会持续影响尾部和前文布局。

3. 流式事件语义不足
   网络层只暴露文本 delta，无法表达 reasoning、工具调用、搜索结果、token usage、finish reason、错误详情和中断状态。

4. 会话树结构缺失
   未来要支持重新生成、分支对话、选择不同回复、项目会话版本沉淀时，单线性消息列表会很快变得不可控。

## 目标

1. 长流式回复稳定
   在普通中端 Android 设备上，连续流式输出 30k 中文字符或 15k Markdown 字符时，不出现明显卡死、ANR、输入栏失焦、底部控件遮挡或无法滚动。

2. 流式期间滚动可控
   用户位于底部时自动跟随最新内容；用户主动向上滚动后停止强拉到底；用户回到底部后恢复跟随。

3. 暂停可靠
   点击停止后 1 秒内停止网络读取和 UI 增量，消息状态落为 `CANCELLED`，不会继续追加文本，也不会被最终成功状态覆盖。

4. 内容不丢不重
   delta merge 必须保证字符顺序稳定，不丢字、不重复、不打乱 reasoning/text/tool part。

5. 渲染可扩展
   支持 Markdown、reasoning、搜索引用、工具调用、文件变更卡片、图片、语音转写等不同消息块独立渲染。

6. 为分支预留数据结构
   第一阶段可以不做完整分支 UI，但数据结构要允许“重新生成为同一节点的候选回复”或“从某条消息继续分支”。

## 非目标

- 不在本轮实现 RikkaHub 式 proot Linux workspace。
- 不在本轮实现完整 MCP 工具生态。
- 不强制重写全部历史消息 UI，一期允许 legacy adapter 兼容旧数据。
- 不在聊天列表里展示完整大型文件内容；项目 Markdown 文件生成继续走文件变更卡片和项目预览。

## 推荐方案

采用“UIMessage parts + chunk merge + transformer pipeline”的渐进式重构。

不推荐只继续微调 `MarkdownMessage`，因为它无法解决网络事件语义和数据写入粒度问题。也不推荐一次性做完整 conversation graph 和 workspace agent，因为会把当前急需的稳定性修复拖太大。

### 方案分层

1. Message Event 层
   网络客户端输出结构化流式事件，而不是只输出字符串。

2. Message Accumulator 层
   把 provider event 合并为 `UiMessageDraft`，按 part 追加内容，按刷新策略批量落库。

3. Message Part 存储层
   新增 message part 表，旧消息迁移为单个 text part，保留旧字段作为兼容窗口。

4. Transformer 层
   输入请求前和输出展示前都走可测试的 transformer pipeline，避免把 provider 特化逻辑散落在 UI。

5. Renderer 层
   Compose 只按 message part 渲染，不直接关心 provider 原始事件。

6. Conversation Node 层
   数据结构预留分支，UI 先展示选中路径，后续支持重新生成和候选切换。

## 数据模型

### MessageNode

用于描述会话树节点。一轮用户消息和其后的助手回复可以各自是节点，也可以采用“用户节点 -> 助手候选节点”的形式。第一阶段采用更容易迁移的线性兼容模式：每条 message record 归属一个 node，列表展示 selected path。

字段：

- `id: String`
- `conversationId: String`
- `parentNodeId: String?`
- `selectedMessageId: String?`
- `sortOrder: Long`
- `createdAt: Long`
- `updatedAt: Long`

约束：

- 同一 conversation 内 `sortOrder` 单调递增。
- `parentNodeId == null` 表示根节点。
- 第一阶段所有节点按线性链路创建，后续 regenerate 时允许一个 node 下多个候选 message。

### MessageRecord

替代当前 `MessageEntity` 的核心消息记录。

字段：

- `id: String`
- `conversationId: String`
- `nodeId: String`
- `role: USER | ASSISTANT | SYSTEM | TOOL | ERROR`
- `status: PENDING | STREAMING | SUCCEEDED | FAILED | CANCELLED`
- `providerId: String?`
- `model: String?`
- `finishReason: String?`
- `inputTokens: Int?`
- `outputTokens: Int?`
- `totalTokens: Int?`
- `errorCode: String?`
- `errorMessage: String?`
- `diagnosticJson: String?`
- `createdAt: Long`
- `updatedAt: Long`

保留字段：

- `content` 可以在迁移期保留，用于旧 UI 和全文搜索；新逻辑以 parts 为准。

### MessagePart

消息的最小渲染单元。

字段：

- `id: String`
- `messageId: String`
- `partIndex: Int`
- `type: TEXT | REASONING | IMAGE | DOCUMENT | TOOL_CALL | TOOL_RESULT | SEARCH_RESULT | FILE_CHANGE | ERROR_DETAIL | SYSTEM_EVENT`
- `content: String`
- `metadataJson: String`
- `stable: Boolean`
- `createdAt: Long`
- `updatedAt: Long`

规则：

- 正在流式输出的尾部 text/reasoning part 可以 `stable = false`。
- 一旦 part 关闭或新 part 开始，前一个 part 标记为 stable。
- UI 对 stable part 使用稳定 key，只有 tail part 会重组。
- `FILE_CHANGE` part 不存完整大文档，只存文件列表、diff 摘要、审核状态和引用 id。

### StreamEvent

网络层统一输出：

```kotlin
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallDelta(val id: String, val name: String?, val argumentsDelta: String) : StreamEvent
    data class ToolResult(val toolCallId: String, val content: String) : StreamEvent
    data class SearchResult(val title: String, val url: String, val snippet: String) : StreamEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?, val totalTokens: Int?) : StreamEvent
    data class Finished(val reason: String?) : StreamEvent
    data class RawProviderEvent(val provider: String, val payload: String) : StreamEvent
}
```

第一阶段 OpenAI-compatible client 可以只产生 `TextDelta`、`Finished` 和有限 `Usage`，但接口必须先稳定下来。

## Transformer 管线

### Input Transformers

发送给模型前按顺序执行：

1. `SystemPromptTransformer`
   合并项目提示词、会话提示词、技能提示词。

2. `MemoryTransformer`
   注入本地 memory 和上下文压缩摘要，标记为系统上下文，不伪装成用户指令。

3. `SearchContextTransformer`
   联网搜索开启且已有搜索结果时，注入来源摘要和引用要求。

4. `AttachmentTransformer`
   图片转为 provider 支持的 content part；不支持 vision 时在发送前拦截。

5. `ModelCapabilityTransformer`
   根据模型能力移除不支持的参数，例如 reasoning effort、web search、image part。

6. `PrivacySanitizerTransformer`
   诊断日志中只保留必要 request metadata，不保存 API key 或完整用户隐私内容。

### Output Transformers

接收 provider event 后按顺序执行：

1. `ThinkTagTransformer`
   把 `<think>...</think>` 或 provider reasoning 字段转为 `REASONING` part。

2. `ToolCallTransformer`
   把工具调用参数增量合并成 tool call part。

3. `SearchCitationTransformer`
   把 provider 返回的搜索引用转为 `SEARCH_RESULT` 或文本尾注。

4. `MarkdownSafetyTransformer`
   只做安全、可解释的 Markdown 修正，例如未闭合 fence 的临时展示，不在存储层篡改原文。

5. `FileChangeDetector`
   对明确“生成/更新 Markdown 文件”的请求输出 `FILE_CHANGE` part，而不是把整篇文件塞进聊天气泡。

6. `ErrorTransformer`
   统一生成可读错误和可复制诊断日志。

Output transformer 必须区分：

- `visualTransform()`：流式期间临时展示，不改变持久化原文。
- `finalize()`：消息完成后一次性整理 metadata，例如 token usage、引用索引、文件变更草稿状态。

## 流式合并策略

### Accumulator

`StreamingMessageAccumulator` 维护当前 assistant draft：

- 当前 active part 类型。
- 每个 part 的内容 buffer。
- delta 总字符数。
- flush 次数。
- 最后一次 provider event 时间。
- cancellation flag。

合并规则：

- 相邻 `TextDelta` 追加到同一个 TEXT part。
- 相邻 `ReasoningDelta` 追加到同一个 REASONING part。
- 当 event 类型变化时，关闭前一个 part 并新建 part。
- `ToolCallDelta` 按 tool id 合并 arguments。
- `Finished` 到达后关闭所有 active part，状态转为 `SUCCEEDED`。
- cancellation 发生后忽略后续 provider event，并把 active part 标记为 stable。

### FlushPolicy

落库刷新不应等同于每个 delta：

- 首包：收到第一个非空 delta 后立即刷新，给用户反馈。
- 时间：距离上次刷新超过 300ms。
- 大小：buffer 累积超过 320 字符。
- 结构：part 类型切换、tool call 完成、finished、cancelled、failed 时立即刷新。

目标：

- 常规流式期间每秒最多 4 次 Room update。
- 超长回复不因为 delta 过密造成 UI 高频重组。
- 停止和失败状态不被节流延迟。

## UI 渲染策略

### MessageList

- LazyColumn 只接收 `List<RenderableMessageNode>`。
- 每个 message node 使用稳定 key。
- 每个 part 使用稳定 key。
- `stable = true` 的 part 不参与尾部重解析。
- 只有最后一个 streaming part 可以高频变化。

### Auto Scroll

状态机：

- `FOLLOWING_BOTTOM`：用户接近底部，流式更新自动滚到底。
- `USER_READING_HISTORY`：用户主动向上滚动，不再强拉。
- `RETURNED_TO_BOTTOM`：用户滚回底部，恢复跟随。
- `NEW_MESSAGE_FORCE_FOLLOW`：用户发送新消息后，本轮回复默认跟随。

阈值：

- 距离底部小于 640px 视为接近底部。
- 新用户消息和新 assistant placeholder 用 `animateScrollToItem`。
- streaming tail 增长用 `scrollToItem`，减少动画堆积。

### Message Bubble

- USER 在右，ASSISTANT/SYSTEM/TOOL 在左。
- 大屏/折叠屏使用内容最大宽度，不让气泡横跨整个屏幕。
- assistant 气泡内标题栏只展示模型、状态和必要操作，不重复展示冗余标签。
- 长 reasoning 默认折叠，点击展开。
- 长代码块和表格内部横向滚动，不撑宽气泡。
- 文件变更卡片只展示摘要和按钮，完整内容进入项目文件。

### Selection And Copy

- 每个 TEXT/MARKDOWN part 支持系统文本选择。
- 气泡菜单支持：复制全部、选择复制、复制 Markdown 原文、复制诊断日志。
- 代码块支持单独复制。

## 暂停与错误处理

### 暂停

点击停止后：

1. UI 立即禁用发送按钮并显示“正在停止”不超过 1 秒。
2. 取消 active coroutine job。
3. `OpenAiCompatibleClient` 调用 `call.cancel()`。
4. Accumulator 收到 cancellation，停止处理后续 event。
5. Repository 把消息状态设为 `CANCELLED`。
6. UI 保留已生成文本，并显示“已停止”。

如果 provider 在 cancel 后仍返回事件：

- 事件进入 ignored count。
- 不再更新 message part。
- 诊断日志记录 cancel time、ignored event count、last event time。

### 错误

失败消息必须包含：

- 用户可读错误。
- HTTP status 或 exception class。
- provider id/name。
- baseUrl host，不展示 API key。
- model。
- request id 或本地 trace id。
- startedAt / failedAt / elapsedMs。
- flushCount / receivedChars。

复制诊断日志时可以包含更详细 metadata，但仍要脱敏。

## 数据迁移

### 阶段 1：兼容读取

- 新增 message nodes、records、parts 表。
- 旧 `messages` 表继续存在。
- 读取时如果新表无 part，则把旧 message content 包装成单个 TEXT part。
- 写入新消息时同时写新表和旧 content 字段，保持回滚能力。

### 阶段 2：新链路写入

- `ChatRepository` 新增 part 写入 API。
- `appendAssistantText()` 降级为 legacy adapter。
- 新发送链路使用 accumulator 写 parts。

### 阶段 3：旧数据迁移

- 后台轻量迁移旧 messages 到 parts。
- 每次启动最多迁移固定数量，避免阻塞。
- 迁移完成后设置 schema flag。

### 阶段 4：移除双写

- 确认一个版本周期内无回滚需求后，停止旧 content 双写。
- 仍可保留 content 作为全文搜索缓存，由 finalize 阶段生成。

## 测试策略

### 单元测试

- `StreamingMessageAccumulatorTest`
  - delta 顺序合并。
  - 首包立即 flush。
  - 300ms 节流。
  - 320 chars size flush。
  - text/reasoning/tool part 切换。
  - cancel 后忽略后续 event。
  - finish 后不再接受 delta。

- `MessageTransformerPipelineTest`
  - input transformer 顺序稳定。
  - unsupported capability 会移除参数。
  - `<think>` 转 reasoning part。
  - provider error 脱敏。

- `MessageMigrationTest`
  - 旧 content 可读为 TEXT part。
  - 新消息双写 content 缓存。
  - 空消息、失败消息、系统事件迁移正确。

- `ChatAutoScrollStateTest`
  - 底部跟随。
  - 用户上滑后停止跟随。
  - 回到底部后恢复。
  - 新用户消息强制跟随。

### Compose / Android 测试

建立固定长文本样例：

- 30k 中文普通文本。
- 15k Markdown，含标题、嵌套列表、表格、代码块。
- 未闭合代码 fence 的流式过程。
- reasoning + answer 双 part。
- 快速 cancel。

验收：

- 滚动可操作。
- 输入栏不遮挡。
- 停止按钮响应。
- 不出现明显重复文本或丢字。
- 截图检查 assistant 在左、user 在右、折叠屏宽度合理。

### 性能测试

指标：

- 每秒 Room message update 不超过 4 次，结构切换除外。
- 流式 15k Markdown 时主线程无连续 700ms 以上卡顿。
- 单个 tail part 解析长度默认不超过 1,200 字符。
- Chat 列表初次打开定位到底部，不从第一条滚动到最后一条。

## 验收标准

1. 使用 Kimi 或 OpenAI 连续生成大段 Git 使用文档，5 分钟内不出现 UI 卡死。
2. 流式超过一屏时，如果用户在底部，始终能看到最新一句。
3. 流式期间用户向上滚动后，App 不再强行拉到底。
4. 点击停止后 1 秒内停止网络和 UI 更新。
5. 复制诊断日志包含 trace 信息，且不泄漏 API key。
6. 旧会话历史能正常打开。
7. 新消息可以表达 text、reasoning、image、search result、file change 至少五类 part。
8. 单元测试覆盖 accumulator、transformer、migration、auto-scroll 状态机。

## 实施顺序

1. 定义 `StreamEvent`、`UiMessagePart`、`StreamingMessageAccumulator`，先用纯单元测试锁定行为。
2. 给 `OpenAiCompatibleClient` 增加结构化 event 输出，保留旧 `ChatDelta` adapter。
3. 新增 message part 存储表和 legacy adapter。
4. `ChatScreen` 改为按 renderable parts 渲染，保留旧 MarkdownMessage 作为 TEXT part renderer。
5. 接入暂停诊断和 flush metrics。
6. 增加长文本 Compose / emulator 回归样例。
7. 再考虑 regenerate 和分支 UI。

## 风险与取舍

- 数据迁移风险：通过双读双写降低风险，避免一次性删除旧表。
- UI 改动面大：先让旧气泡承载新 part，再逐步细化 part renderer。
- 性能测试不稳定：以可重复长文本样例和 flush metrics 为主，emulator 截图为辅。
- Provider 事件差异大：统一事件模型先覆盖 OpenAI-compatible 的文本流，reasoning/tool/search 逐步接入。
