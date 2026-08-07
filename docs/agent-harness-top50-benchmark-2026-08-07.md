# Agent / Harness Top 50 个性化对标

日期：2026-08-07

## 1. 口径

这不是按融资额、下载量或 GitHub Star 机械排序的全球榜单。闭源产品没有可比的公开数据，单纯按 Star 也会把框架、客户端和消费产品混成一类。

本次选择 50 个代表性产品，依据是：

1. 对 Harness 的真实链路有直接参考价值。
2. 是消费助手、移动客户端、编码 Harness、Agent Runtime、知识工作或自动化平台中的头部代表。
3. 开源项目优先选择仍在活跃维护、社区规模较大的项目；GitHub 数据抓取时间为 2026-08-07。
4. 只提取已被产品或官方文档验证的交互模式，不把宣传口号当成路线图依据。

评估维度固定为：

- 用户能否快速发起并恢复工作。
- 上下文、记忆和来源边界是否清楚。
- 长任务是否可离开、可恢复、可转向。
- 高风险动作是否可查看、可批准、可拒绝。
- 结果能否成为可验证、可继续使用的产物。
- 移动端是否减少操作，而不是复制桌面复杂度。

## 2. Top 50 对标

### 2.1 消费助手与知识工作

| # | 产品 | 最值得借鉴 | Harness 的取舍 |
| --- | --- | --- | --- |
| 1 | [ChatGPT](https://help.openai.com/en/articles/10169521-projects-in-chatgpt) | Project 作为聊天、文件、指令和记忆边界；移动端可继续长期任务 | 借项目边界和跨端接力，不做账号云同步平台 |
| 2 | [Claude](https://support.claude.com/en/articles/9519177-how-can-i-create-and-manage-projects) | Project RAG、项目记忆、历史会话检索及回链 | 借可管理记忆和历史引用，不把不可见摘要当事实源 |
| 3 | [Gemini](https://support.google.com/gemini/answer/15719111?hl=en) | Deep Research 先展示计划，并允许明确选择搜索、文件或应用来源 | 借来源范围和计划可见性，不接入 Google 全家桶 |
| 4 | [Perplexity](https://hub-prod.perplexity.ai/hub/faq/what-are-spaces) | Space 内组合线程、文件、链接和 Web，并保持引用优先 | 借来源选择，不复制搜索产品形态 |
| 5 | [Microsoft Copilot](https://www.microsoft.com/en-us/microsoft-copilot) | 助手嵌入现有工作入口，减少应用切换 | 借上下文就地出现，不追企业连接器广度 |
| 6 | [DeepSeek](https://api-docs.deepseek.com/news/news250115/) | 搜索、思考和文件入口简单直接，移动端学习成本低 | 借模式清晰度，不做另一个通用聊天客户端 |
| 7 | [豆包 / 火山方舟](https://www.volcengine.com/docs/82379) | 语音、多模态、知识检索和托管 Agent 的完整能力面 | 借低摩擦语音，不采用云端托管执行作为核心 |
| 8 | [Kimi](https://www.kimi.com/help/getting-started/overview) | 长任务后台执行、完成通知、交付文档以及自动搜索 | 借异步任务和交付物心智，不追 Agent Swarm |
| 9 | [NotebookLM](https://support.google.com/notebooklm/answer/16179559?hl=en) | 回答严格受选中来源约束，引用可直接跳到原文上下文 | 借来源约束和引用回跳，不追内容 Studio 全品类 |
| 10 | [Notion AI](https://www.notion.com/help/notion-agent) | Agent 出现在页面和数据库所在位置，结果直接成为可编辑工作对象 | 借“结果回到项目”，不做通用协作套件 |

### 2.2 AI 客户端与个人 Agent

| # | 产品 | 最值得借鉴 | Harness 的取舍 |
| --- | --- | --- | --- |
| 11 | [RikkaHub](https://github.com/rikkahub/rikkahub) | 原生 Android 聊天、多 Provider、附件反馈、工具审批和分享入口 | 借 Android 交互成熟度，不追 Provider、MCP 和 Workspace 广度 |
| 12 | [Chatbox](https://github.com/chatboxai/chatbox) | Provider 配置和跨平台客户端的低学习成本 | 借设置清晰度，不做跨平台客户端 |
| 13 | [LobeHub](https://github.com/lobehub/lobehub) | Agent、Skills、知识库和运行观测形成统一资产心智 | 借“能力包”与运行状态，不做 Agent 团队运营台 |
| 14 | [Cherry Studio](https://github.com/CherryHQ/cherry-studio) | 助手库、知识库和模型入口组织成熟 | 借资产管理，不追数百助手和模型矩阵 |
| 15 | [Open WebUI](https://github.com/open-webui/open-webui) | 自托管、本地模型和知识库的统一入口 | 借本地所有权，不建设 Web 管理后台 |
| 16 | [LibreChat](https://github.com/danny-avila/LibreChat) | Preset、Agent、Artifact、会话搜索和多模型切换 | 借可恢复配置，不复制 ChatGPT 功能全集 |
| 17 | [Jan](https://github.com/janhq/jan) | 完全离线和本机模型的明确承诺 | 借离线降级原则，三个月内不塞本地大模型运行时 |
| 18 | [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) | Workspace 是知识、Agent 和会话的权限边界 | 借工作区边界，不做服务器式工作区平台 |
| 19 | [Khoj](https://github.com/khoj-ai/khoj) | 个人知识、Agent、Deep Research 和计划任务组合 | 借个人记忆控制，计划任务延后 |
| 20 | [OpenClaw](https://github.com/openclaw/openclaw) | 本机 Gateway 连接会话、渠道、Skills、工具和移动入口 | 借“手机是控制面”，不开放手机任意工具执行 |

### 2.3 编码 Agent 与 Harness

| # | 产品 | 最值得借鉴 | Harness 的取舍 |
| --- | --- | --- | --- |
| 21 | [OpenAI Codex](https://openai.com/index/introducing-the-codex-app/) | Project / Thread / Run、长任务、Skills、Diff 审核和任务监督 | 作为桌面执行核心，Harness 专注移动发起、转向和验收 |
| 22 | [Claude Code](https://github.com/anthropics/claude-code) | 仓库指令、Skills、权限边界和终端任务闭环 | 借规则分层与简洁事件，不复制终端界面 |
| 23 | [Cursor](https://docs.cursor.com/en/background-agent/web-and-mobile) | 手机发起后台 Agent，桌面接管、审核和合并 | 直接借鉴跨设备接力，但执行留在自己的 Mac |
| 24 | [Windsurf](https://docs.windsurf.com/zh/windsurf/cascade/memories) | Memories、Rules、Workflows、Skills 的边界清晰 | 借持久知识与临时记忆分层，避免一个“记忆”概念包打天下 |
| 25 | [GitHub Copilot](https://docs.github.com/en/copilot/concepts/agents/about-third-party-coding-agents) | 手机发起 Agent Session，完成后进入 PR 审核和安全检查 | 借移动审核和生命周期，不绑定 GitHub 云 Agent |
| 26 | [Gemini CLI](https://github.com/google-gemini/gemini-cli) | 开源终端 Agent、工具扩展和可审查执行 | 借开放协议，不在手机呈现终端噪音 |
| 27 | [Cline](https://github.com/cline/cline) | 逐工具审批、检查点和可回滚执行 | 借风险分级审批，不要求每个只读动作确认 |
| 28 | [Roo Code](https://github.com/RooCodeInc/Roo-Code) | 不同模式适配不同任务职责 | 只在内部路由使用，不增加用户可见的 Agent 团队配置 |
| 29 | [Continue](https://github.com/continuedev/continue) | 配置可版本化、模型与上下文可替换 | 借可复现配置，不扩展 Provider 面板 |
| 30 | [Aider](https://github.com/Aider-AI/aider) | Git-first、改动可见、提交边界明确 | 延续 Harness 已有白名单提交与 Diff 审核 |
| 31 | [OpenCode](https://github.com/anomalyco/opencode) | 快速、开放、Provider 无关的编码 Agent | 借轻量运行感，不追 Provider 无关本身 |
| 32 | [OpenHands](https://github.com/OpenHands/OpenHands) | 沙箱、结构化事件流和任务回放 | 借事件模型，不在 Android 运行沙箱 |
| 33 | [Goose](https://github.com/aaif-goose/goose) | 本地 Agent 与扩展能力可组合 | 借扩展边界，不允许未签名代码进入手机 |
| 34 | [Devin](https://docs.devin.ai/work-with-devin/advanced-capabilities) | Session 分析、Playbook、知识建议和历史任务学习 | 借“从完成任务提炼经验”，不做并行 Agent 管理 |
| 35 | [Manus](https://manus.im/blog/manus-agents-telegram) | 通过手机和消息渠道启动长任务，后台完成后返回交付物 | 借交付物优先和移动通知，不采用云电脑代执行 |

### 2.4 Agent Runtime、RAG 与自动化平台

| # | 产品 | 最值得借鉴 | Harness 的取舍 |
| --- | --- | --- | --- |
| 36 | [LangGraph](https://docs.langchain.com/oss/python/langgraph/overview) | Durable execution、checkpoint、streaming、HITL 和恢复 | 借可持久化 Run 状态，不引入图编排 UI |
| 37 | [CrewAI](https://github.com/crewAIInc/crewAI) | Role / Crew / Flow 的职责拆分 | 只用于内部实现思路，不展示“虚拟团队” |
| 38 | [AutoGen](https://github.com/microsoft/autogen) | 事件驱动、多 Agent 消息与运行时分离 | 借事件协议，不做多 Agent 产品层 |
| 39 | [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/human_in_the_loop/) | 审批可暂停、序列化、恢复，并保留同一 Run 状态 | 直接借鉴审批状态机与幂等恢复 |
| 40 | [Google ADK](https://github.com/google/adk-python) | 构建、评估和部署 Agent 的完整工程纪律 | 借评测门槛，不接入其运行时 |
| 41 | [Semantic Kernel](https://github.com/microsoft/semantic-kernel) | Plugin、Memory、Planner 的能力契约 | 借能力声明，不做插件市场 |
| 42 | [PydanticAI](https://github.com/pydantic/pydantic-ai) | Typed dependency、结构化输出和 Evals | 借结构化结果协议和失败校验 |
| 43 | [LlamaIndex](https://github.com/run-llama/llama_index) | 文档解析、索引、检索和 Agent 数据层 | 重加工继续留在 M4 桌面构建器 |
| 44 | [Dify](https://github.com/langgenius/dify) | RAG Pipeline、Workflow、评测和可观测性 | 借检索评测，不做低代码平台 |
| 45 | [Coze Studio](https://github.com/coze-dev/coze-studio) | Agent、Workflow、Knowledge、Plugin 作为独立资源 | 借资源边界，不做可视化 Agent Builder |
| 46 | [n8n](https://github.com/n8n-io/n8n) | 触发器、重试、幂等和大量集成 | 借可靠性模式，连接器和计划任务延后 |
| 47 | [Flowise](https://github.com/FlowiseAI/Flowise) | 可视化组装 Agent 和 RAG | 不做节点画布 |
| 48 | [Langflow](https://github.com/langflow-ai/langflow) | 可视化调试和组件复用 | 不做节点画布 |
| 49 | [Browser Use](https://github.com/browser-use/browser-use) | 浏览器动作日志、状态观察和失败恢复 | 借可见执行，不在近期开放浏览器自动化 |
| 50 | [Mastra](https://github.com/mastra-ai/mastra) | Workflow、Memory、Evals、Observability 一体化 | 借本地 Run 评测和观测，不引入框架 |

## 3. 跨产品共性

### 3.1 Project 正在成为长期上下文边界

ChatGPT、Claude、Notion、AnythingLLM 和 NotebookLM 的共同点不是“支持上传文件”，而是把聊天、资料、指令、记忆和产物放进一个可理解的范围。对 Harness 而言，这个范围已经存在，就是项目，不需要再造 Workspace 或 Notebook 模式。

### 3.2 Agent 的核心对象已经从 Message 变成 Run

[Codex](https://openai.com/index/introducing-the-codex-app/)、[Cursor](https://docs.cursor.com/en/background-agent/web-and-mobile)、Kimi、Devin、Manus、OpenClaw、LangGraph 和 OpenAI Agents SDK 都把长任务建模为可以暂停、离开、恢复、转向和验收的运行。Harness 当前远程控制已经有 Thread / Turn 和审批，但移动端仍主要把它当聊天时间线展示，缺少统一任务状态和完成产物。

### 3.3 可信度来自边界和证据，不来自更长回答

NotebookLM、Perplexity、Claude Project RAG、Dify 和 Harness `.hwiki` 都验证了同一件事：用户需要知道用了哪些来源、引用能否回到原文、范围变化是否会影响旧结果。Harness 已经具备 Wiki 和 Agent 来源基础，下一步应把同样原则扩展到项目历史和远程任务结果。

### 3.4 HITL 必须是可恢复状态，不是弹窗

[OpenAI Agents SDK](https://openai.github.io/openai-agents-python/human_in_the_loop/)、[LangGraph](https://docs.langchain.com/oss/python/langchain/human-in-the-loop)、Cline、RikkaHub 和 GitHub Agent 都把审批放进任务状态。审批丢失、切屏后无法继续、只显示“允许/拒绝”而不显示影响范围，都会直接破坏信任。

### 3.5 Skills 是可复用能力，Memory 是持续上下文

Codex、Claude Code、Windsurf、Kimi 和 LobeHub 都在区分 Skills、Rules 和 Memory。Harness 应保持：

- `.hagent` 决定谁在说话。
- `.hwiki` 决定本轮可以查什么。
- 项目 Markdown 决定长期事实和产物。
- Skill 决定如何完成重复流程。
- Run 只保存一次执行的状态和证据。

### 3.6 移动端的优势是捕获与监督

[Cursor Mobile](https://docs.cursor.com/en/background-agent/web-and-mobile)、[GitHub Mobile](https://docs.github.com/en/copilot/concepts/agents/about-third-party-coding-agents)、[ChatGPT Remote](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex)、Manus 和 OpenClaw 都没有试图在手机复刻完整 IDE。高价值动作是：快速发起、看最新状态、处理审批、补一句方向、查看结果、回到桌面继续。

## 4. 内部 Grilling 结果

评分范围为 1-5。总分越高，越适合进入未来三个月。安全分越高表示风险越可控。

| 候选方向 | 真实痛点 | 差异化 | 移动杠杆 | 三月可行 | 安全 | 总分 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 语音输入 + Android 分享入口 | 5 | 4 | 5 | 5 | 5 | 24 | 进入 M1 |
| 统一任务活动与审批中心 | 5 | 5 | 5 | 4 | 5 | 24 | 进入 M2 |
| 项目与 Mac Codex 工作区弱绑定 | 5 | 5 | 5 | 3 | 4 | 22 | 进入 M2 |
| 全局搜索与一键继续 | 4 | 4 | 5 | 4 | 5 | 22 | 进入 M1 |
| 项目上下文检索与可审核沉淀 | 5 | 5 | 4 | 3 | 4 | 21 | 进入 M3 |
| 计划任务 / 主动 Agent | 3 | 3 | 4 | 2 | 2 | 14 | 三个月后再评估 |
| 通用附件 RAG | 3 | 1 | 3 | 2 | 3 | 12 | 不进入主线 |
| Provider 广度 | 2 | 1 | 2 | 3 | 4 | 12 | 只修兼容问题 |
| Agent / Skill 市场 | 2 | 2 | 2 | 2 | 3 | 11 | 不立项 |
| 多 Agent 管理面板 | 2 | 1 | 2 | 2 | 2 | 9 | 不做 |
| Android MCP / 通用工具沙箱 | 2 | 2 | 1 | 1 | 1 | 7 | 不做 |

## 5. 收敛结论

Harness 不应成为“功能较少的 RikkaHub”，也不应成为“运行在 Android 上的 OpenClaw”。最有价值的位置是：

> 你个人 Agent 系统的移动控制与记忆层。手机负责捕获、选择上下文、监督和验收；M4 Mac 上的 Codex 负责重执行；项目 Markdown 与 Git 负责长期事实。

未来三个月只押三件事：

1. 让意图在手机上十几秒内进入正确上下文。
2. 让一个任务跨手机和 Mac 连续运行、可恢复、可审批。
3. 让完成结果经过审核后回到项目，而不是消失在聊天记录里。
