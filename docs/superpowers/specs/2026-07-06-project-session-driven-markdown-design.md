# Project Session-Driven Markdown Design

## Goal

项目模块保留“长期项目空间”的定位，但不要求用户先经营文档分类或文档命名。Markdown 是会话工作的沉淀结果，不是开始工作的前置对象。

## Product Rules

- 项目页的主操作是新建项目会话，不再提供“新建文档 + 选择分类 + 输入标题”的前置流程。
- 项目页可以展示、搜索、预览、编辑、分享已有 Markdown 沉淀，但不把需求文档、方案文档、任务清单等分类暴露为用户必须选择的操作。
- 会话仍可绑定已有 Markdown 沉淀；绑定后写回更新当前 Markdown。
- 会话未绑定 Markdown 时，只要已绑定项目，助手输出也可以写回；系统自动在项目内生成一个 Markdown 沉淀。
- 文件路径和标题由系统从会话和 Markdown 内容推导，允许后续再做更智能的自动命名，但当前不阻塞用户开始工作。

## Implementation Shape

- `ProjectScreen` 去掉新建文档对话框和分类选择，只保留新建项目、新建会话、搜索/查看/编辑已有 Markdown。
- `SessionContextBuilder.canWriteBackMarkdown` 改为只要求项目和非空助手 Markdown，不要求已有 deliverable。
- `ProjectWorkspaceGateway.saveSessionSummary` 返回创建/更新后的 Markdown 信息，供会话在自动写回后刷新绑定状态。
- `ProjectWorkspaceGatewayAdapter` 继续复用 `FileProjectRepository.saveSessionSummary` 的 `sessions/` 持久化。

## Verification

- 单元测试覆盖写回条件：有项目但没有 deliverable 时可以写回。
- 单元测试覆盖项目页 Markdown 辅助文案：只展示路径，不展示分类标签。
- 单元测试覆盖 session summary 持久化：返回生成的 Markdown，并写入完整内容。
