# Harness APK test 版与 RikkaHub 对比

## 版本口径

本文对比时间为 2026-07-10。

Harness APK 采用当前 test 通道发布包：

- 版本：`0.1.15-debug`
- versionCode：`1015030`
- 发布时间：`2026-07-10T07:02:09Z`
- test manifest：`https://www.zerg.work/harness-apk/test/update.json`
- release notes 关键词：温暖无障碍主题、项目工作台操作层级、聊天消息和输入区可读性、核心界面视觉验收。

RikkaHub 采用公开最新版本和官方文档：

- GitHub latest release：`2.4.1`
- 发布时间：`2026-07-08T10:14:51Z`
- 官方定位：原生 Android LLM chat client，统一 OpenAI、Gemini、Claude 和 OpenAI-compatible provider。

## 总结判断

RikkaHub 已经是成熟的通用 Android AI Chat / Agent 客户端。它的强项是多 Provider、多模态、会话分支、助手人格、记忆、MCP、技能、Workspace、备份同步、Web Access 和整体聊天体验。

Harness APK test 版不应该在当前阶段追 RikkaHub 的通用能力广度。Harness 更有价值的方向是“移动端受控项目工作台”：项目会话、Markdown 文件变更卡片、diff 审核、项目文件夹、Git 状态、低风险提交和自建 APK 更新。

因此，前面确认的“P2 不做”是正确的。Provider、搜索、语音、技能、MCP、Workspace、图片生成等能力不应成为 Harness 近期主线。Harness 需要从 RikkaHub 借鉴的是成熟交互和风险控制，不是功能横向扩张。

## 定位差异

| 维度 | Harness APK test 版 | RikkaHub |
| --- | --- | --- |
| 核心定位 | 移动端个人 AI 项目工作台 | 通用 Android AI 聊天客户端 |
| 主用户场景 | 在手机上推进项目、沉淀文件、审核变更、查看 Git 状态 | 日常多模型聊天、文档/图片问答、Agent 工具调用、跨设备访问 |
| 产品主线 | 会话 -> 文件变更 -> diff 审核 -> 项目文件 -> Git 状态 -> APK 更新 | Provider/Assistant -> Chat -> 多模态/搜索/工具/Workspace -> 备份同步 |
| 近期正确策略 | 收敛 P0/P1/P3，做深项目闭环 | 已经做广通用 AI 客户端能力 |
| 不应追赶 | Provider 能力矩阵、MCP、技能市场、图片生成、语音生态、Web 端 | 不适用 |

## 功能矩阵

### 1. 聊天基础体验

RikkaHub 明显更成熟：

- 会话历史抽屉、搜索、置顶、重命名、删除。
- 会话中切换模型。
- 编辑用户消息、重新生成助手回复。
- 消息节点多版本和会话 fork。
- 收藏消息、分享 Markdown 或图片。
- Markdown 渲染覆盖代码高亮、LaTeX、表格、Mermaid。
- token 使用统计。

Harness 当前具备：

- 多会话、本地保存、流式回复。
- Provider 和模型选择。
- Markdown 渲染能力已经持续补强。
- 停止生成、复制、朗读、上下文压缩。
- 项目会话和文件变更卡片。

结论：

Harness 不需要完整复制 RikkaHub 的聊天系统，但 P0 闭环必须保证聊天基础不拖后腿。最低要求是：文件变更入口清晰、生成中状态稳定、失败可重试、长 Markdown 不拖垮聊天流、普通聊天和文件变更互不混淆。

### 2. Provider 与模型能力

RikkaHub 明显领先：

- 支持 OpenAI、Gemini、Claude 和 OpenAI-compatible provider。
- 内置服务商持续扩展，`2.4.1` 新增 MiniMax、MIMO 和 Fish Audio 语音朗读支持。
- 支持自定义 HTTP headers 和 request bodies。
- 支持 OCR 模型、图片生成模型、TTS/ASR provider。

Harness 当前具备：

- Provider 配置、本地加密 API Key。
- 模型列表、默认模型、上下文窗口和压缩阈值配置。
- 文本、图片、联网搜索、部分原生搜索模式。
- 当前路线图明确不做完整 Provider 能力矩阵。

结论：

Harness 不应继续在 Provider 广度上追 RikkaHub。近期只保留两类工作：

- 阻塞核心闭环的错误修补，例如图片不支持、模型请求失败、上下文过长。
- 让用户知道当前使用哪个 Provider/模型，以及失败时如何恢复。

### 3. 多模态和文件输入

RikkaHub 更强：

- 支持图片、PDF、DOCX、PPTX、EPUB。
- 支持相机、图库/文件、剪贴板粘贴、Android 分享入口。
- 对非视觉模型自动走 OCR，并缓存 OCR 结果。
- 文档会提取文本后注入 prompt。
- `2.4.1` 增强 HEIF/HEIC 图片上传和裁剪。

Harness 当前具备：

- 图片选择和压缩。
- 项目文件夹可索引 Markdown、Office、PDF、代码、图片、文本等文件。
- Markdown 文件变更、PDF 导出、项目 ZIP 导入导出。
- 暂不做通用文档输入问答。

结论：

Harness 不需要做 RikkaHub 式“任意文件问答”。更应该把项目文件作为工作台对象处理：

- 项目文件夹可看、可筛、可预览、可导出。
- LLM 只通过受控文件变更写入 Markdown。
- 如果未来需要读项目文档，也应先服务“项目上下文”和“文件变更闭环”，不是泛化成通用附件能力。

### 4. Agent / Workspace / 工具

RikkaHub 明显更强且风险更高：

- MCP 是一等能力，支持 SSE 和 Streamable HTTP。
- 每个 MCP tool 可单独 enable/disable，可设置 `needsApproval`。
- Workspace 是 PRoot Linux 环境，可安装 git、Python、Node.js 等包。
- Workspace 可让 AI 克隆仓库、编辑文件、生成 PPT，并导出或推送结果。
- Skills 可从 GitHub、文件或手动内容导入，并绑定到 Assistant。

Harness 当前具备：

- 内置技能展示和开关，但第三方插件执行未开放。
- 项目文件夹、Markdown diff 审核和 Git 面板。
- Git / Gitee 设置、初始化、克隆、提交、推送、fetch、快进拉取、分支切换。
- 低风险 Git 仍需要产品化约束。

结论：

RikkaHub 的 Workspace 是通用 Agent 方向，Harness 的优势应是“受控写入 + 可审核 + Git 可见”。这比在手机里跑完整 Linux Agent 更适合当前产品：

- Harness 不开放任意工具执行。
- 文件写入必须 diff 审核。
- Git 操作必须确认、不能强推、不能自动处理冲突。
- 可以借鉴 RikkaHub 的 `needsApproval` 思路，把文件写入、提交、推送都做成显式确认动作。

### 5. 项目与文件工作台

Harness 更聚焦：

- 项目是长期容器。
- 项目会话是工作入口。
- 文件夹是沉淀产物视图。
- Markdown 写入通过文件变更卡片和 diff 审核。
- Git tab 能看到当前分支、clean/dirty、ahead/behind 和文件变化。

RikkaHub 更通用：

- 有 Workspace 文件管理、导入导出和系统文件管理器访问。
- 可通过 AI 在 Workspace 内操作文件。
- 可通过 Web Access 在浏览器使用完整聊天界面。
- 不以“项目会话 -> 文件变更审核 -> Git 状态”作为固定主链路。

结论：

这是 Harness 最值得守住的差异化。RikkaHub 能“让 AI 在 Workspace 里做事”，Harness 应该让用户“清楚看到 AI 准备改什么、实际改了什么、Git 里产生了什么”。对个人项目管理来说，后者更可控。

### 6. 备份、同步和分发

RikkaHub 更完整：

- 本地 ZIP 备份和恢复。
- WebDAV 同步。
- S3-compatible 同步。
- 备份提醒。
- 可导入 Chatbox、Cherry Studio 等应用历史。
- Web Access 支持局域网浏览器访问。
- 可从 Google Play 和官网分发。

Harness 当前具备：

- 项目 ZIP 导入导出。
- APK test/prod 通道。
- manifest、APK 完整包、分片、SHA-256 校验。
- App 内检查更新、下载、拉起系统安装器。

结论：

Harness 的分发链路是自用/小范围内测导向，不需要追 Google Play 和云备份。更高优先级是：

- test/prod 发布稳定。
- 更新失败不破坏当前版本。
- 项目 ZIP 导入导出可靠。
- 发布前回归清单固定。

## Harness 应该借鉴 RikkaHub 的点

### 高优先级

1. 明确入口层级
   RikkaHub 的主入口围绕 chat、assistant、extension、settings 展开。Harness 项目页也应把“项目会话、文件夹、Git”层级固定，默认进入会话，文件夹和 Git 作为辅助确认视图。

2. 文件附件的可见反馈
   RikkaHub 用附件 chip 和缩略图确认用户输入内容。Harness 文件变更卡片也要做到：当前改几个文件、每个文件是什么操作、为什么改、增删多少行、能否应用，一眼清楚。

3. 工具执行前确认
   RikkaHub MCP 的 `needsApproval` 非常适合借鉴。Harness 的文件写入、Git 提交、推送、项目删除、项目导入覆盖都应采用明确确认。

4. 失败后可恢复
   RikkaHub 在附件、OCR、备份、Workspace 等链路都有明确失败解释。Harness P0 应优先补齐：LLM JSON 解析失败、写入失败、更新校验失败、Git 操作失败。

5. 分享和导出心智
   RikkaHub 的会话分享和备份很成熟。Harness 可把重点放在项目 ZIP、Markdown/PDF 导出和写回事件记录，不必扩展成全量会话备份。

### 中优先级

1. 会话搜索和置顶
   对 Harness 有帮助，但不是 P0。等项目闭环稳定后再做。

2. 消息编辑和分支
   RikkaHub 很强，但 Harness 当前重点不是聊天实验。可以只保留“重试/停止/复制/文件变更重试”。

3. token 统计
   对模型成本有价值，但当前不是核心闭环。

4. Web Access
   对桌面编辑有吸引力，但会削弱“移动端工作台”当前主线，暂不做。

## Harness 不应该追的点

- 不追 RikkaHub 的完整 Provider 广度。
- 不追 MCP 服务器生态。
- 不追 PRoot Linux Workspace。
- 不追通用图片生成。
- 不追通用文档问答。
- 不追跨设备 Web UI。
- 不追 WebDAV/S3 全量应用备份。
- 不追角色卡、lorebook、AI 翻译等泛聊天生态。

这些能力都很有价值，但它们会把 Harness 拉回通用 AI 客户端赛道，和 RikkaHub 正面竞争。Harness 当前更应该做窄：项目文件闭环、Git 可见、更新稳定。

## 对当前路线图的影响

### P0：稳定文件变更闭环

RikkaHub 对比后，P0 的关键不是“加更多能力”，而是把文件变更体验做到足够可信：

- 普通发送和生成文件变更入口必须清晰分开。
- 文件变更卡片必须比完整 Markdown 气泡更轻、更可靠。
- diff 审核必须可理解。
- 应用成功后必须能跳到文件和 Git 变更。
- 失败必须可重试、可撤回，不丢上下文。

### P1：项目工作台增强

RikkaHub 的 Workspace 说明用户确实需要“AI 操作文件”的能力，但 Harness 应保持更安全：

- 项目会话作为默认入口。
- 文件夹作为真实产物视图。
- Git 作为变更确认层。
- 项目 ZIP 导入导出作为迁移和备份手段。
- 不做任意命令执行。

### P3：分发和维护

RikkaHub 有 Google Play 和官网分发，Harness 目前更适合 test/prod 自建分发：

- test 通道要继续快速验证。
- prod 通道必须签名、人工确认。
- 更新 manifest 和 SHA-256 校验要保持可靠。
- release notes 要从“技术提交列表”逐步变成用户可读更新说明。

## 建议的下一轮优先级

1. 文件变更卡片完成产品级闭环
   包括查看 diff、部分保留、应用、失败重试、撤回、应用后跳转文件/Git。

2. Git 操作确认增强
   提交前展示文件统计，推送前展示分支和 ahead/behind，冲突状态禁用高风险操作。

3. 项目工作台入口减噪
   项目 header 保留必要动作，把高风险或低频动作收进菜单，默认突出“新建项目会话”。

4. 更新页用户化
   更新失败、校验失败、权限跳转和继续安装入口要更稳；release notes 改为面向用户的变更摘要。

5. 保持 P2 取消
   Provider、MCP、技能、语音、搜索、图片生成都不进近期主线，只修核心闭环阻塞。

## 参考来源

- Harness APK test manifest: https://www.zerg.work/harness-apk/test/update.json
- Harness 产品计划: `docs/product-plan.md`
- Harness 工作台闭环规格: `docs/superpowers/specs/2026-07-09-harness-apk-workbench-closure-design.md`
- RikkaHub GitHub: https://github.com/rikkahub/rikkahub
- RikkaHub latest release 2.4.1: https://github.com/rikkahub/rikkahub/releases/tag/2.4.1
- RikkaHub docs index: https://docs.rikka-ai.com/llms.txt
- RikkaHub introduction: https://docs.rikka-ai.com/introduction.md
- RikkaHub conversation docs: https://docs.rikka-ai.com/chat/conversations.md
- RikkaHub multimodal docs: https://docs.rikka-ai.com/chat/multimodal.md
- RikkaHub branching docs: https://docs.rikka-ai.com/chat/branching.md
- RikkaHub Workspace docs: https://docs.rikka-ai.com/extensions/workspace.md
- RikkaHub MCP docs: https://docs.rikka-ai.com/extensions/mcp.md
- RikkaHub skills docs: https://docs.rikka-ai.com/extensions/skills.md
- RikkaHub backup docs: https://docs.rikka-ai.com/settings/backup.md
- RikkaHub Web Access docs: https://docs.rikka-ai.com/settings/web-access.md
