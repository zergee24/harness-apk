# Markdown File Change Cards Design

## Goal

长会话中生成或修改 Markdown 文件时，Harness APK 不再把完整 Markdown 正文作为助手气泡流式渲染，而是像 Codex 一样在聊天流里展示文件变更条；完整内容进入项目文件，由用户通过 diff 审核、应用和项目文件预览来查看。

## Problem

当前“沉淀到项目”链路已经能把助手输出转换成 Markdown 更新计划，并在写入前展示 diff。但是用户仍需要先让助手在聊天气泡里生成完整 Markdown，再点击“沉淀到项目”。长文档会导致聊天列表变重、流式 Markdown 渲染卡顿，也把“文件修改”伪装成“聊天回复”，和项目工作台、Git 工作树的语义不一致。

## Product Rules

- 短问答继续走普通流式聊天。
- 明确要求生成、更新、整理、沉淀 Markdown 文件的请求进入文件变更模式。
- 文件变更模式下，助手消息不展示完整 Markdown 正文，只展示生成状态、摘要和文件变更条。
- LLM 不直接静默写入项目；所有候选变更必须经过用户审核。
- 文件变更条以文件为单位展示新增或修改，不做 hunk 级保留/撤回。
- 完整 Markdown 只出现在 diff 审核、项目文件预览和项目文件编辑器中。
- 应用变更后，聊天流写入一条系统事件，列出实际写入的文件路径。
- 如果当前会话未选择项目，文件变更模式必须先提示用户选择项目，不能退化成流式输出整篇 Markdown。
- 如果模型没有返回可解析的更新计划，聊天里展示错误和重试入口，不把原始失败内容写入项目。

## User Experience

用户在项目会话里输入“生成 PRD”“更新方案文档”“把这段沉淀成 md”“整理项目 README”等请求时，发送后聊天区域显示一个轻量助手卡片：

```text
正在生成 Markdown 文件变更...
```

生成完成后，卡片变为文件变更列表：

```text
已生成 2 个 Markdown 文件变更

A requirements/mobile-prd.md
  新建移动端项目需求文档
  +128 -0

M reports/review.md
  补充验收结论和风险项
  +18 -4

[查看 diff] [应用全部] [撤回]
```

点击“查看 diff”打开现有 Markdown 更新审核弹窗。用户可以按文件保留或撤回，然后应用保留项。应用成功后，聊天流插入系统事件：

```text
已沉淀到项目：requirements/mobile-prd.md、reports/review.md
```

项目页文件夹和 Git 标签页随后能看到真实文件变更。

## Triggering

第一版不做复杂意图分类器，采用保守触发：

- 用户显式点击“生成文件变更”发送，强制进入文件变更模式。
- 普通发送仍走现有聊天流。
- 会话中已有“沉淀到项目”按钮保留，但其文案调整为“生成文件变更”，作为对现有助手回复的补救入口。
- 输入栏首版加入轻量启发式提示：当用户输入包含“生成 md”“写 PRD”“更新文档”“沉淀到项目”“生成方案”“整理 README”等文件写作意图词时，在输入栏旁提示“建议使用文件变更模式”。
- 启发式提示只做推荐，不自动切换发送模式；用户仍需手动点击“生成文件变更”，避免误判把普通聊天变成文件操作。

## Data Model

新增聊天级文件变更草稿模型，独立于最终项目文件：

```kotlin
data class MarkdownFileChangeDraft(
    val id: String,
    val conversationId: String,
    val projectId: String,
    val sourceUserMessageId: String,
    val status: MarkdownFileChangeStatus,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MarkdownFileChangeItem(
    val draftId: String,
    val operation: MarkdownUpdateOperation,
    val path: String,
    val title: String,
    val reason: String,
    val markdown: String,
    val addedLineCount: Int,
    val removedLineCount: Int,
    val retained: Boolean,
)
```

状态：

- `PLANNING`：正在生成更新计划。
- `READY`：已生成候选变更，等待审核或应用。
- `APPLIED`：保留项已写入项目。
- `DISMISSED`：用户撤回。
- `FAILED`：生成或解析失败。

第一版可以先以内存状态驱动 UI，不强制落 Room；但一旦需要跨进程恢复或应用重启后继续审核，就把 draft 和 items 持久化到 Room。

## Flow

1. 用户在项目会话中选择“生成文件变更”发送。
2. UI 插入用户消息，并创建 `PLANNING` 草稿卡。
3. `MarkdownUpdatePlannerUseCase` 基于项目上下文、现有 Markdown 快照和用户请求生成 JSON 更新计划。
4. 解析为 `MarkdownUpdateProposal` 后，计算每个文件的 diff 和 `+/-` 行数。
5. 草稿卡进入 `READY`，聊天流展示文件变更条。
6. 用户查看 diff，按文件保留或撤回。
7. 用户应用保留项后，复用 `ProjectWorkspaceGateway.applyMarkdownUpdates` 写入项目。
8. UI 刷新项目 Markdown 索引、项目文件夹和 Git 状态。
9. 聊天流插入系统事件记录实际写入路径。

## Architecture

- `ChatScreen` 只负责展示文件变更卡片、触发生成和处理用户确认。
- `MarkdownUpdatePlannerUseCase` 继续负责让模型生成结构化更新计划。
- `MarkdownUpdateModels` 增加 diff 统计能力，用现有 `buildMarkdownDiff` 派生新增/删除行数。
- 新增 `MarkdownFileChangeCard` 组件，展示状态、文件列表、操作按钮。
- 新增 `MarkdownFileChangeController` 或等价 use case，封装“生成计划 -> 构建审核状态 -> 应用保留项”的编排，避免继续扩大 `ChatScreen`。
- 项目写入仍走 `ProjectWorkspaceGateway`，继续使用项目目录边界校验。
- Git 标签页不参与生成流程，只在文件写入后通过现有刷新机制展示 working tree 变化。

## Error Handling

- 未选择项目：阻断文件变更模式，提示“请先选择项目”。
- 项目没有 Markdown 快照：允许生成新文件，diff 中只显示新增行。
- LLM 返回非 JSON：草稿进入 `FAILED`，展示“LLM 未返回 Markdown 更新 JSON”和重试按钮。
- LLM 返回空更新：草稿进入 `FAILED`，展示“没有生成可审核的 Markdown 更新”。
- 写入失败：保留 `READY` 草稿，展示错误，允许用户重试或撤回。
- 用户撤回全部：草稿进入 `DISMISSED`，不写项目文件。

## UI Boundaries

- 聊天气泡不渲染完整 Markdown 正文。
- 文件变更卡片最多直接展示 6 个文件；超过后显示“还有 N 个文件”，完整列表在审核弹窗中查看。
- diff 弹窗沿用现有截断策略，避免一次渲染超长 Markdown。
- 项目文件预览继续负责完整 Markdown 阅读和编辑。
- Git 面板只展示写入后的 working tree 状态，不复制 Markdown diff 审核 UI。

## Testing

- `MarkdownUpdateModelsTest`：验证 diff 统计能正确计算新增和删除行数。
- `ChatUiStateTest`：验证文件变更卡片文案、按钮状态、空项目提示和应用成功事件。
- `ChatUiStateTest`：验证输入命中文件写作关键词时显示文件变更模式提示，未命中时不显示，并且提示不会自动切换发送模式。
- `MarkdownFileChangeControllerTest`：验证生成计划、失败、撤回、应用保留项的状态转换。
- `ProjectWorkspaceGatewayAdapter` 相关测试继续覆盖路径边界和写入结果。
- Compose 层不做完整端到端渲染测试，保留关键纯函数和状态机测试，避免 UI 测试过重。

## Rollout

第一步先保留现有“助手完整回复 -> 沉淀到项目”链路，同时新增“生成文件变更”发送入口，并在输入命中文件写作关键词时提示用户切换到文件变更模式。验证稳定后，再把项目会话里的 Markdown 写作默认入口切到文件变更模式。

第二步把原“沉淀到项目”按钮降级为补救入口：当普通回复里确实产生了 Markdown 内容时，仍可手动转换成文件变更。

第三步评估是否需要持久化 `MarkdownFileChangeDraft`，支持应用重启后继续审核。

## Non-Goals

- 不做自动识别并静默写入文件。
- 不做 hunk 级选择，只做文件级保留或撤回。
- 不做删除文件操作。
- 不做完整 IDE 式 Markdown 编辑器。
- 不在聊天流展示完整长 Markdown。
- 不把 Git commit、push 和文件变更应用绑定成一个动作。
