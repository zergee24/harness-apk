# M3 项目记忆、引用与沉淀闭环设计

日期：2026-08-07

实施周期：2026-10-08 至 2026-11-07

状态：可进入实施拆解，依赖 M1 本地搜索与 Context Snapshot、M2 Remote Run 完成卡

关联路线图：`docs/product-plan.md` v5

## 1. 目标

M3 让项目中的历史事实可以被重新找到、引用和继续使用，并让一次会话或 Mac Codex Run 的结果经审核后真正回到项目 Markdown。

核心结果：

- 在项目会话中直接问“上次决定了什么”，回答引用项目文件或历史消息，并可回到当时原文。
- 项目文件更新或删除后，旧回答仍能展示生成时实际使用的片段，不静默指向新内容。
- 助手回答和 Remote Run 完成卡使用同一个“沉淀到项目”流程。
- 稳定决策、当前状态和待办可以成为 `context.md` 候选，但永不静默写入。
- Agent 关系记忆只影响称谓和表达方式，不能作为项目事实来源。
- 全部检索和索引在手机本地完成，M3 不引入 embedding、向量数据库或 GraphRAG。

北极星指标：**一次有价值的会话或 Run，可以从结果卡经过一次 Diff 审核形成项目 Markdown，并在下次会话中带来源召回。**

## 2. 当前基线

可直接复用：

- 项目已使用 `context.md` 作为目标、关键决策、当前状态和待跟进的事实根。
- `ConversationEntity.projectId` 明确项目会话归属。
- `ConversationMarkdownLinkEntity` 已表达同一项目内会话与 Markdown 的多对多弱关联。
- `MarkdownChangeDraftEntity` 和 Item 已持久化生成、审核、Apply 和部分失败状态。
- `ProjectWorkspaceGateway.applyMarkdownUpdates` 已执行路径边界、基线哈希和冲突检查。
- Apply 后已有 Git 刷新、白名单 Commit 和独立 Push 确认。
- Wiki 引用已保存精确版本、原文快照、SHA-256 和回跳定位，可作为项目引用防漂移的实现参照。
- Agent 关系记忆已按稳定 `agentId` 存在本机 Room，不属于 `.hagent` 包版本。

需要补齐：

- 项目 Markdown 只能按文件名和路径搜索，不能在会话前按需检索内容。
- 历史项目消息没有项目级 FTS 和统一来源对象。
- `SessionRequestContext` 会注入项目上下文，但没有记录本轮究竟使用了哪些项目片段。
- 项目文件路径和行号是可变定位，不能满足旧引用防漂移。
- Remote Run 完成卡还不能复用 Markdown Change Draft。
- `context.md` 更新缺少稳定事实判定、去重和证据约束。

## 3. 第一性原理取舍

### 3.1 项目记忆是什么

项目记忆不是一份不可见的模型摘要，而是以下可审计事实的集合：

1. 项目 Markdown 当前版本。
2. 历史项目会话中的用户和助手消息。
3. Remote Run 的目标、事件和完成证据。
4. 每次回答生成时实际使用的来源快照。

索引和摘要只是读取这些事实的工具，不能成为新的事实源。

### 3.2 只保留一套写入器

- 所有 Markdown 创建和更新继续进入 `MarkdownChangeDraft`。
- 所有变更继续先看 Diff，再 Apply。
- `context.md` 建议不是第二套自动写入逻辑，只是 Draft 中的一种候选文件。
- Apply、Commit 和 Push 保持三个独立动作。

### 3.3 不新增的交互

- 不新增“记忆”Tab、知识库模式或项目 Dashboard。
- 不要求用户先整理标签、文件夹、知识图谱或文档分类。
- 不在每次回复后弹“是否记住”。
- 不为普通闲聊生成项目文件。
- 不展示检索参数、BM25 分数、Chunk ID 或索引状态作为主界面内容。

## 4. 核心对象

### 4.1 Project Search Document

扩展 M1 的 `LocalSearchDocument`：

```kotlin
data class ProjectSearchDocument(
    val documentKey: String,
    val projectId: String,
    val sourceType: ProjectSourceType,
    val authority: ProjectSourceAuthority,
    val sourceKey: String,
    val conversationId: String?,
    val messageId: String?,
    val relativePath: String?,
    val title: String,
    val headingPath: String,
    val ordinal: Int,
    val text: String,
    val searchableText: String,
    val sourceSha256: String,
    val gitBlobId: String?,
    val sourceUpdatedAt: Long,
    val indexedAt: Long,
)
```

`ProjectSourceType`：`CONTEXT | MARKDOWN | PROJECT_MESSAGE | RUN_EVIDENCE`。

`ProjectSourceAuthority`：`REVIEWED_ARTIFACT | USER_STATED | VERIFIED_RUN | ASSISTANT_PROPOSAL`。

`sourceKey` 稳定规则：

- 文件：`projectId + relativePath + headingStableKey + ordinal`。
- 消息：`projectId + messageId`。
- Run：`projectId + runId + evidenceId`。

### 4.2 Source Revision

```kotlin
data class ProjectSourceRevision(
    val projectId: String,
    val sourceType: ProjectSourceType,
    val sourceKey: String,
    val relativePath: String?,
    val messageId: String?,
    val contentSha256: String,
    val gitBlobId: String?,
    val updatedAt: Long,
)
```

- `contentSha256` 基于规范化前的实际 UTF-8 内容计算。
- Git 项目可以额外保存 Blob ID；没有 Git 时 SHA-256 已足够判断漂移。
- mtime 和 size 只用于快速判断是否需要重算，不能作为引用版本。

### 4.3 Project Evidence Snapshot

```kotlin
data class ProjectEvidenceSnapshot(
    val id: String,
    val executionId: String,
    val messageId: String,
    val token: String,
    val projectId: String,
    val sourceType: ProjectSourceType,
    val sourceKey: String,
    val title: String,
    val locatorLabel: String,
    val relativePath: String?,
    val sourceMessageId: String?,
    val sourceSha256: String,
    val gitBlobId: String?,
    val excerpt: String,
    val capturedAt: Long,
)
```

这是回答生成时实际使用的证据，创建后不可变。`token` 使用 `⟦P1⟧`、`⟦P2⟧` 等本轮稳定编号。

`excerpt` 只保存实际注入的完整语义块，单条最多 1,200 个 Unicode code point；每次执行最多 6 条。不得为了防漂移复制整份项目文件。

### 4.4 Project Retrieval Run

```kotlin
data class ProjectRetrievalRun(
    val id: String,
    val executionId: String,
    val projectId: String,
    val query: String,
    val selectedEvidenceIds: List<String>,
    val status: RetrievalStatus,
    val createdAt: Long,
)
```

`RetrievalStatus`：`SKIPPED | NO_MATCH | SELECTED | FAILED`。

失败不阻断普通回复，但必须记录为 `FAILED`，不能假装本轮使用了项目记忆。

### 4.5 Markdown Draft Origin

不改变已有 Draft 主表语义，新增一对一来源：

```kotlin
data class MarkdownDraftOrigin(
    val draftId: String,
    val sourceType: DraftOriginType,
    val sourceId: String,
    val sourceSha256: String,
    val sourceProjectId: String?,
    val createdAt: Long,
)
```

`DraftOriginType`：`ASSISTANT_MESSAGE | REMOTE_RUN_COMPLETION | EXPLICIT_FILE_CHANGE`。

该对象使项目 Markdown 可以追溯到生活会话消息或 Remote Run，而不必改变原会话的项目归属。

## 5. 项目索引

### 5.1 索引范围

M3 索引：

- 当前项目 `context.md`。
- 当前项目所有 `.md` / `.markdown` 文件。
- `projectId` 等于当前项目的历史消息。
- 已绑定项目的 Remote Run 目标和结构化完成证据。

M3 不索引：

- 生活会话和其他项目。
- PDF、Office、图片、音视频正文。
- `.git`、构建目录、隐藏 Harness 元数据和二进制文件。
- Agent 关系记忆和 `.hagent` 资料。
- `.hwiki` 内容；Wiki 使用自己的精确版本检索器。

### 5.2 来源权威级别

| 来源 | 权威级别 | 可以证明 |
| --- | --- | --- |
| 已经 Diff/Apply 或用户直接维护的项目 Markdown | `REVIEWED_ARTIFACT` | 当前项目文档中的目标、决定、状态和待办 |
| 项目会话中的用户消息 | `USER_STATED` | 用户当时明确提出、确认或否定的内容 |
| 有结构化退出状态的 Run 文件、测试和 Git 证据 | `VERIFIED_RUN` | 对应动作实际发生及其结果 |
| 历史助手消息和无结构化证据的总结 | `ASSISTANT_PROPOSAL` | 当时提出过的方案或表达，不证明已决定、已完成 |

- “上次讨论了什么”可以召回 `ASSISTANT_PROPOSAL`，但必须标记为历史提案。
- “上次决定了什么”“现在做到哪了”不能只用 `ASSISTANT_PROPOSAL` 回答。
- 助手消息只有经过用户确认、Apply 到 Markdown 或形成结构化 Run Evidence 后，才通过新的来源获得更高权威级别；不能原地升级历史消息。

### 5.3 Markdown 分块

按语义结构分块，不按固定字符粗切：

1. 标题开启新块，并把完整 Heading Path 带入每块。
2. 普通段落、连续列表和表格按完整块保留。
3. 代码围栏整体保留，超限时按行切分并重复语言和 Heading Path。
4. 目标块大小 300-800 个中文字符或等价长度。
5. 小于 80 字的相邻块合并；不使用机械重叠，避免重复证据。
6. `context.md` 的四个固定章节分别成块，并获得检索加权。

单文件最大索引 2 MiB，单项目 Markdown 正文索引上限 50 MiB。超限文件保留文件名、路径和标题索引，并在来源详情标记“正文未索引”。

### 5.4 中文检索

- 复用 M1 抽取的 `LocalSearchTokenizer`。
- FTS4 使用 `unicode61`，`searchableText` 同时写入规范化词、中文 2-gram 和 3-gram。
- 英文、路径、代码符号和驼峰词保留原词及拆分词。
- 繁简转换只作为额外搜索通道，快照和展示始终保留原文。

### 5.5 增量更新

以下事务完成后标记来源 Dirty：

- Markdown Apply、项目文件编辑、新建、导入、删除或重命名。
- 项目消息进入稳定状态。
- Remote Run 完成或完成证据更新。

索引协调器：

- 前台轻量变更立即更新。
- 项目导入或批量变化使用 WorkManager 分批更新。
- 启动时比较 path、mtime、size，疑似变化再计算 SHA-256。
- 索引失败不回滚真实文件，只保留 Dirty 并后台重试。
- 删除项目时同一事务删除索引、快照可检索入口和未应用 Draft；历史回答中的 Evidence Snapshot 随消息生命周期保留或级联删除。

## 6. 检索与信息熵预算

### 6.1 何时检索

仅当会话具有 `projectId` 时运行项目检索。

每轮都可以执行低成本本地检索，但只有满足以下任一条件才注入：

- 查询出现“上次、之前、决定、进度、现状、待办、文件、方案”等项目历史意图。
- 命中标题、路径、关键决策或待跟进的高阈值匹配。
- 用户明确要求基于项目资料回答。

低于最低相关阈值时记录 `NO_MATCH`，不为了显得有记忆而注入弱相关内容。

### 6.2 候选排序

先用 SQL 强制 `projectId = currentProjectId`，再排序；禁止全局召回后在内存中过滤。

排序信号：

1. 标题或路径精确命中。
2. FTS BM25 / 词覆盖。
3. `context.md` 对决策、状态和待办查询加权。
4. 按查询意图应用来源权威约束；决定和状态查询优先 `REVIEWED_ARTIFACT / USER_STATED / VERIFIED_RUN`。
5. 当前会话已弱关联 Markdown 加权。
6. 来源多样性。
7. 更新时间只做小幅加权，不能覆盖更高相关度的旧决策。

### 6.3 注入预算

默认上限：

- 最多 6 个 Project Evidence。
- 同一文件最多 2 个块。
- `context.md` 最多 2 个块并预留 2,000 字符。
- 项目证据总计最多 8,000 字符。
- 每块在注入前保留标题、路径、定位和 SHA-256，不只给正文。

预算不足时按“相关度 -> 来源多样性 -> 稳定事实 -> 新近状态”取舍。不得截断到一句话失去语义，也不得把全部项目 Markdown 塞入 Prompt。

### 6.4 与 Agent、Wiki 的边界

请求上下文职责：

| 来源 | 只负责 |
| --- | --- |
| Agent 身份包 | 身份、判断框架和表达方式 |
| Agent 关系记忆 | 称谓、关系和跨会话表达偏好 |
| Project Evidence | 当前项目目标、决定、状态、文件和任务事实 |
| Wiki Evidence | 已授权知识库中的外部事实与原文 |

- Agent 关系记忆按 `agentId` 从本机 Room 读取，不写进 `.hagent`，也不按包版本重置。
- 关系记忆即使包含项目化措辞，也不能作为 `⟦P#⟧` 来源。
- Project 与 Wiki 冲突时同时给出处并指出版本或语境差异，不让人格 Prompt 覆盖事实证据。

## 7. 回答与引用

### 7.1 生成约束

项目证据注入格式：

```text
⟦P1⟧ context.md / 关键决策
版本：sha256:abcd...
原文：...
```

系统约束：

- 使用项目历史事实时，在对应句末附 `⟦P#⟧`。
- 没有 Project Evidence 时必须说“当前项目资料不足”，不能把关系记忆或模型常识伪装成项目事实。
- `ASSISTANT_PROPOSAL` 只能表述为“曾建议、曾讨论”，不能表述为“已决定、已完成”。
- 直接引语必须来自 Evidence Snapshot 中的原文。
- 回答生成后校验所有 `⟦P#⟧` 都属于本轮，未知 Token 降级为普通文本并记录验证失败。

### 7.2 消息展示

- 正文 Token 渲染为轻量上标，与 Wiki 引用视觉一致。
- 回答底部只显示一行：`项目依据 3 · Wiki 来源 2`；没有来源时整行不出现。
- 点击进入统一来源 Sheet，按“项目 / Wiki / Agent”分组，但三类数据仍由各自 Repository 提供。
- 来源 Sheet 是详情工具，不在消息气泡内嵌套卡片。

### 7.3 防漂移回跳

点击项目来源时：

1. 读取当前来源并计算 SHA-256。
2. 与 Evidence Snapshot 的 `sourceSha256` 比较。
3. 相同：打开当前文件或消息并定位 Heading/Message。
4. 不同：默认展示“回答当时使用的片段”，顶部标记“来源已更新”，提供“查看当前版本”。
5. 当前来源已删除：仍展示快照，标记“来源已删除”。

路径、Heading 和行号只负责导航；SHA-256、可选 Git Blob 和 Excerpt 才负责证明当时内容。

### 7.4 Context Snapshot V3

在 M1 V2 基础上增加：

```kotlin
data class ContextSnapshotV3(
    val projectRetrievalRunId: String?,
    val projectEvidenceIds: List<String>,
    val relationshipMemoryIds: List<String>,
)
```

旧消息的 Evidence IDs 和内容快照不可被重建索引覆盖。

## 8. 沉淀到项目

### 8.1 单一入口

以下对象使用同一个动作和同一个 Draft Pipeline：

- 普通助手回答的消息操作：文档加号图标，长按提示“沉淀到项目”。
- Remote Run 完成卡：`沉淀到项目`。
- 现有显式“生成文件变更”入口。

不存在“保存回答”“加入记忆”“生成笔记”三个同义动作。

### 8.2 目标项目

- 项目会话：自动使用 `conversation.projectId`，不弹项目选择。
- Remote Run：自动使用 `run.projectId`，不弹项目选择。
- 生活会话：第一次点击后打开紧凑项目选择 Sheet；选定后创建目标项目 Draft，但不修改原生活会话的 `projectId`。
- 生活会话来源不写入 `ConversationMarkdownLink`；使用 `MarkdownDraftOrigin` 保留源消息。应用后的 Markdown 仍完整归属目标项目。

### 8.3 Planner 输入

Planner 只接收：

- 用户选中的助手回答或 Remote Completion。
- 对应用户请求和必要会话片段。
- 本轮 Evidence Snapshot。
- 目标项目 `context.md`。
- 目标项目 Markdown 路径、标题和相关内容快照。
- Remote Run 的结构化文件、测试、Git 和遗留证据。

不接收隐藏推理过程，不把整个项目或整个聊天历史塞入 Planner。

### 8.4 候选规则

Planner 只允许 `CREATE | UPDATE`，不允许删除文件。

候选优先级：

1. 更新已有相关 Markdown。
2. 有稳定项目决策、状态变化或明确待办时更新 `context.md`。
3. 没有合适文件时创建 `research/`、`sessions/` 或 `reports/` 记录。
4. 内容没有长期项目价值时返回空计划，UI 显示“没有可沉淀的稳定内容”，不强行造文件。

### 8.5 前台体验

```text
用户点击沉淀
  -> 原位置出现“正在准备项目变更”
  -> Ready 后打开现有文件级 Diff Sheet
  -> 默认保留建议项
  -> 用户点击“应用所选”
  -> 消息或完成卡显示真实写入路径
```

- 规划中离开页面不取消 Draft。
- 用户仍在当前页面时，Ready 后自动打开一次 Diff；用户已离开时进入 Activity“需要我处理”，不弹后台对话框。
- Diff Sheet 复用现有组件，不另做“笔记预览”。
- Apply 成功后只展示一次结果；不再追加“是否更新上下文”第二次询问。

## 9. `context.md` 刷新

### 9.1 可进入的事实

只允许：

- 项目目标的明确新增或变更。
- 用户已经确认的关键决策。
- 有证据的当前状态变化。
- 明确负责人或下一动作的待跟进。

禁止：

- 临时讨论、脑暴候选和未拍板方案。
- 模型推理过程或人格化表达。
- 没有来源的外部事实。
- 只对当前会话成立的短期待办。
- Agent 关系记忆。

### 9.2 结构化候选

Planner 对每个 Context 更新返回：

```kotlin
data class ContextFactCandidate(
    val section: ContextSection,
    val statement: String,
    val evidenceIds: List<String>,
    val operation: FactOperation,
    val dedupeKey: String,
)
```

- 没有 Evidence ID 的候选不得进入 Diff。
- 对“用户刚刚明确决定”的消息，可使用该用户消息快照作为 Evidence。
- 只有 `ASSISTANT_PROPOSAL` 的内容不得生成关键决策、当前状态或完成类 Context Fact。
- `dedupeKey` 由 section + 规范化陈述 + 来源哈希生成。
- 已应用相同 key 不再建议；用户撤回后本 Run 不重复弹出。

### 9.3 一个审核面

`context.md` 与其他 Markdown 候选在同一 Diff Sheet：

```text
项目变更  2

M context.md
  当前状态：M1 进入实现

A reports/m1-acceptance.md
  保存本次验收证据

[应用所选]
```

不增加单独“更新项目记忆”卡片。

## 10. 与 Git 闭环

- Apply 后沿用 `projectContentInvalidation` 刷新 Files 和 Git。
- 实际写入路径加入现有本轮白名单。
- 完成区域可显示“Git 工作区已更新（未提交）”。
- Commit 仍需用户点击并确认提交信息。
- Commit 成功后才单独询问 Push。
- 非快进、冲突、自动 Pull/Merge 和强推规则不变。
- Remote Run 在 Mac 修改的文件不会被伪装成手机项目文件；只有显式 Git Fetch/受控导入后，手机项目才能索引对应内容。

## 11. 极致移动体验标准

### 11.1 默认安静

- 没有高相关项目证据时，不展示“未使用记忆”提示。
- 没有稳定内容时，不展示 Context 更新建议。
- 每个回答或 Run 最多出现一次沉淀状态。
- 用户忽略或撤回后，本次结果不再次提醒。

### 11.2 动作预算

| 场景 | 最多动作 |
| --- | ---: |
| 查看回答使用的项目原文 | 点击来源 Token 或来源汇总一次 |
| 项目回答沉淀 | 沉淀 + 应用所选，两次核心确认 |
| Remote 完成结果沉淀 | 沉淀 + 应用所选，两次核心确认 |
| 生活回答沉淀 | 沉淀 + 选项目 + 应用所选 |
| 查看漂移前原文 | 点击来源后默认直接看到快照 |

### 11.3 信息密度

- 消息正文不插入大块来源卡。
- 来源 Sheet 每项最多显示标题、定位、两行片段和版本状态。
- Diff 首屏先显示文件级摘要，完整正文按文件展开。
- 项目检索过程默认不展示；诊断信息只在来源详情的“检索信息”二级区域。

### 11.4 性能

- 10,000 个项目 Chunk 的本地检索 p95 小于 250ms。
- 检索和 Snapshot 写入不能阻塞输入提交的 100ms 本地排队反馈。
- 大项目首次索引分批执行，前台每批不超过 50ms 主线程工作。
- 来源 Sheet 只按需读取当前文件并计算 Hash；列表不批量重读全部文件。
- 320dp、字体 1.3 下引用汇总和 Diff 操作不重叠。

### 11.5 无障碍

- 引用 Token 的可访问名称包含来源序号、标题和定位。
- “来源已更新”“来源已删除”必须由文字表达，不只靠颜色。
- Diff 增删行同时播报“新增/删除”，不只朗读 `+/-`。
- 文档加号图标有“沉淀到项目”描述和长按提示。

## 12. 数据与代码边界

### 12.1 Room

若 M1/M2 顺序使用 20 -> 21 -> 22，M3 使用 22 -> 23；实施时采用下一个可用迁移号。

新增：

- `project_retrieval_runs`
- `project_evidence_snapshots`
- `markdown_draft_origins`
- `context_fact_dedupe`

扩展 M1：

- `local_search_documents` 增加 Project File/Message/Run 来源字段和版本字段。
- `local_search_fts` 继续作为唯一 App 内 FTS 表，不创建第二套项目 FTS。

扩展：

- `ChatExecutionRequestContext` -> V3，保存 Retrieval Run 和 Evidence IDs。
- `MessagePartType` 增加 `PROJECT_SOURCE` 或等价结构化来源类型。

### 12.2 建议代码边界

新增：

- `projectsearch/ProjectIndexCoordinator.kt`
- `projectsearch/MarkdownSemanticChunker.kt`
- `projectsearch/ProjectRetrievalRepository.kt`
- `projectsearch/ProjectEvidenceSelector.kt`
- `projectsearch/ProjectEvidenceSnapshotRepository.kt`
- `projectsearch/ProjectCitationVerifier.kt`
- `projectsearch/ProjectContextAssembler.kt`
- `session/MarkdownDraftOriginRepository.kt`
- `session/ContextFactPolicy.kt`
- `ui/source/UnifiedSourceSheet.kt`
- `ui/source/ProjectSourceReaderScreen.kt`

修改：

- `ChatExecutionModels.kt`
- `SendMessageUseCase` / 当前请求编排层
- `ChatScreen.kt`
- `MarkdownUpdatePlannerUseCase`
- `MarkdownNotebookRepository.kt`
- `FileProjectRepository.kt`
- `ActivityScreen` / `RunDetailScreen`
- `AppDatabase.kt`

`ChatScreen` 不直接执行索引、检索、Hash 或 Planner；所有工作通过 Coordinator/Repository 注入状态。

### 12.3 弱关联不变式

- 项目会话与 Markdown 的 `ConversationMarkdownLink` 仍是多对多。
- `linkMarkdown` 继续要求会话属于目标项目，防止项目会话跨项目串联。
- 生活会话跨项目沉淀只记录 `MarkdownDraftOrigin`，不伪造 Conversation Markdown Link。
- 删除弱关联不删除会话或 Markdown；删除项目按现有用例清理关联和未应用 Draft。

## 13. 异常与信任

| 场景 | 行为 |
| --- | --- |
| 索引未完成 | 搜索已完成部分；项目回答可只用 `context.md`，明确来源范围 |
| 检索失败 | 普通回复继续，但不声称使用项目历史 |
| 无高相关结果 | 不注入、不显示伪来源 |
| 文件在检索后、发送前变化 | Snapshot 前重新核对 Hash；变化则重取或放弃该 Evidence |
| 点击引用时文件变化 | 默认展示生成时快照，并提供当前版本 |
| 来源删除 | 保留快照，标记已删除 |
| Planner 返回无证据 Context Fact | 丢弃该候选并记录策略错误 |
| Apply 基线冲突 | 保留 Draft，重新生成 Diff，不覆盖新文件 |
| 生活会话未选项目 | 不创建 Draft，停留项目选择 Sheet |
| Agent 记忆与项目证据冲突 | 项目事实引用优先，关系记忆只影响表达 |
| Remote 文件只存在 Mac | 完成卡显示 Mac 路径，不写入手机索引 |

## 14. 评测与验收证据

### 14.1 检索数据集

至少 40 个本地 Query：

- 关键决策 10。
- 当前状态与待办 10。
- 文件路径和标题 8。
- 历史消息 6。
- 应返回 No Match 的跨项目或无关查询 6。

每个 Query 标记允许来源集合，记录 Recall@6、跨项目泄漏数和无关注入数。M3 发布门槛：跨项目泄漏为 0；No Match 查询不得强行注入。

### 14.2 JVM

- Markdown 标题、列表、表格、代码块和超长内容分块。
- 中文 2/3-gram、路径、英文和混合代码检索。
- SQL 项目范围、排序、多样性和 8,000 字符预算。
- 来源权威约束：旧助手提案不能单独证明已决定或已完成。
- Evidence Snapshot 不可变、Hash 漂移、删除回退和 Git Blob。
- Citation Token 校验和未知 Token 拒绝。
- Agent 关系记忆不能生成 Project Citation。
- Context Fact 类型、Evidence 必填、去重和撤回不重提示。
- Assistant/Remote/Explicit 三种 Draft Origin。

### 14.3 Instrumentation / Compose

- 项目问“上次决定”并点击回到消息或文件。
- 文件更新后旧回答默认显示历史快照。
- 项目回答和 Remote Completion 进入同一个 Diff Sheet。
- 生活会话选择项目后生成 Draft，但原会话仍为生活会话。
- Apply 后 Files/Git 刷新，Commit/Push 仍分步确认。
- 320dp、字体 1.3、TalkBack 引用和 Diff。

### 14.4 二十条端到端黄金链路

必须包含：

1. 项目目标召回。
2. 关键决策召回。
3. 当前状态召回。
4. 待办召回。
5. 历史消息召回。
6. Markdown 标题命中。
7. 中文模糊命中。
8. 无匹配时不注入。
9. 跨项目同名文件不泄漏。
10. Agent 关系记忆不变成项目事实。
11. Wiki + Project 同轮引用。
12. 文件更新后快照回退。
13. 文件删除后快照回退。
14. 项目回答沉淀。
15. Remote Completion 沉淀。
16. 生活回答选项目沉淀。
17. Context Fact 无证据被拒绝。
18. Diff 基线冲突恢复。
19. Apply 后白名单 Commit。
20. Push 非快进停止并去桌面处理。

## 15. 四周交付

### 第 9 周：索引与检索

- Project Search Document、Markdown Chunker、中文 FTS 和增量索引。
- 项目范围 SQL、排序和信息熵预算。

退出条件：40 Query 数据集可重复运行，跨项目泄漏为 0。

### 第 10 周：来源快照与引用

- Retrieval Run、Evidence Snapshot、Context Snapshot V3 和 `⟦P#⟧`。
- 漂移、删除和当前版本切换。

退出条件：修改 `context.md` 后，旧回答仍展示原快照且明确提示变化。

### 第 11 周：统一沉淀

- Assistant / Remote Completion 共用 Draft Origin 和现有 Diff/Apply。
- Context Fact Policy、Evidence 约束和去重。

退出条件：两类结果都能经一次 Diff 审核写入真实项目 Markdown。

### 第 12 周：黄金链路与发布

- 20 条端到端链路、10,000 Chunk 性能、窄屏、TalkBack 和迁移。
- 本地闭环报告、已知问题和回滚说明。

范围熔断：项目检索带来源、引用防漂移和审核式沉淀是必达项；若闭环未通过，每周报告、更复杂排序和 Git Blob 增强顺延。

## 16. 完成定义

- 项目事实只能来自当前项目 Markdown、项目消息或 Run Evidence，并带可回跳来源。
- Agent 关系记忆明确由 `agentId` 关联并存于 Room，只影响关系和表达。
- 项目引用保存内容 Hash、当时片段和可选 Git Blob；路径变化或内容更新不会静默漂移。
- 检索严格在 SQL 层限制项目，跨项目泄漏测试为 0。
- 一次请求最多注入 6 个项目证据和 8,000 字符，不用全量上下文换取虚假完整。
- Assistant Answer 和 Remote Completion 复用现有 Markdown Draft/Diff/Apply。
- `context.md` 只有带证据的稳定事实候选，和其他文件在同一个审核面处理。
- Apply、Commit、Push 仍分别确认，没有静默写入和隐藏同步。
- 没有新增记忆 Tab、向量数据库、GraphRAG、通用附件 RAG 或第二套 Markdown 写入器。
