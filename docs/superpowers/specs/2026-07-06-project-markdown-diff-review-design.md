# Project Markdown Diff Review Design

## Goal

项目会话不绑定单个 Markdown。用户在项目会话中触发“沉淀到项目”后，系统让 LLM 基于项目上下文、现有 Markdown 索引和助手输出生成多文件更新计划；用户先审查 diff，再决定每项变更保留或撤回，最后只写入保留项。

## Product Rules

- 会话只选择项目，不选择或绑定 Markdown。
- 一个会话可以生成或更新多个 Markdown；一个 Markdown 可以被多个会话继续更新。
- LLM 只负责生成候选更新计划，不直接静默写入文件。
- 确认式自动管理必须包含 diff 审核：每项候选变更展示目标路径、原因、旧内容与新内容差异。
- 每项候选变更默认“保留”，用户可以逐项“撤回”，撤回后也可以重新“保留”。
- 点击确认时，只写入仍处于“保留”状态的候选变更；全撤回时不写入。
- 项目页和会话页必须复用同一套 Markdown 渲染组件。

## Implementation Shape

- 把 `ui.chat.MarkdownMessage` 迁移到共享包 `ui.markdown`，会话和项目共同使用。
- 新增 `MarkdownUpdatePlannerUseCase`：调用当前会话模型生成 JSON 更新计划，并解析为本地模型。
- 新增简单行级 Markdown diff 构建器，用于审核弹窗展示。
- 新增项目写入接口，按相对路径创建或更新 Markdown，继续使用项目目录边界校验。
- `ChatScreen` 移除 Markdown 单选绑定 UI，保留项目选择和提示词配置。
- `ChatScreen` 的助手消息按钮改为“沉淀到项目”，点击后生成审核弹窗；弹窗支持保留/撤回和批量写入保留项。

## Non-Goals

- 本轮不做 hunk 级别保留/撤回，只做到文件级候选变更。
- 本轮不做静默自动写入。
- 本轮不删除已有文件，也不让 LLM 执行删除操作。
