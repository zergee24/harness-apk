# Agent 生态与豆包能力观察（2026-08-04）

> **状态（2026-08-07）：本文仅保留为 2026-08-04 的趋势快照。50 产品对标已由 `docs/agent-harness-top50-benchmark-2026-08-07.md` 取代，当前路线图以 `docs/product-plan.md` v5 为准。**

> 本文为产品路线图 grilling 前的背景材料，定位类似 `rikkahub-comparison-2026-07-10.md`：把外部生态信号收敛成"对 Harness APK 的启示"，不直接修改路线图。路线图修订在后续 grilling 中逐项决策。
>
> **数据来源与口径**
> - 开源侧：GitHub 月度 Trending（`https://github.com/trending?since=monthly`，抓取于 2026-08-04），由 trending 列表的 22 个仓库过滤得到 19 个 AI/agent 相关项。
> - 豆包侧：火山方舟（Volcengine Ark）官方文档中心当前能力面（`https://www.volcengine.com/docs/82379`，抓取于 2026-08-04），代表豆包模型/平台当前对外能力，非逐版本 changelog。
> - 本文不臆造具体版本号、发布日期或准确星标数绝对值；星标数仅用于体现相对热度量级，决策只参考"趋势方向"，不引用为精确事实。

## 1. 开源 Agent 生态：近两个月的主导趋势

### 1.1 主导主题（按出现强度）

1. **AI 编码 harness 的"技能（Skills）"是破圈品类。** Top 20 里至少 5 个（`skills`、`hallmark`、`archify`、`impeccable`、`astryx`）是把设计/绘图能力打包成给 Claude Code / Cursor / Codex 等 harness 用的 skill 或 agent-ready 设计系统。这是本月最显眼的结构。
2. **编码 agent 的 harness 与编排层。** 多 agent / 并行 agent 运行时与网关占据头部：`orca`（并行 agent 舰队，自带订阅）、`OmniRoute`（290+ provider、500+ 模型的统一网关，兼容 Claude Code/Codex/Cursor/OpenCode/Cline/Copilot，带配额回退与 token 压缩、MCP/A2A）、`jcode`（主打 RAM 占用最低的 harness）、`codex-plugin-cc`（在 Claude Code 里调 Codex 做代码评审/委派）。
3. **MCP 仍在上榜，但份额收窄。** 典型如 `DesktopCommanderMCP`（给 Claude 提供终端/文件系统/diff 编辑）。MCP 不再是头部主题，更多作为底层接线。
4. **垂类自主 agent 是第二波。** 交易（`Vibe-Trading`）、安全/渗透（`strix`）、辅导（`DeepTutor`）、Office 自动化（`OfficeCLI`，单二进制无需装 Office）。
5. **多模态与语音 agent。** HuggingFace 的 `speech-to-speech`（本地语音 agent）、`claude-video`（让 Claude 看任意视频：下载、抽帧、转写、交给 Claude）。
6. **RAG / agent-app 合集与基建仍是常青树。** `awesome-llm-apps`（100+ agent/RAG 应用）、`worldmonitor`（AI 驱动的全球情报聚合看板）。
7. **生态自我繁殖信号：** 大量 trending 仓库的主要贡献者是 `codex`/`claude`/`cursoragent` 等机器人账号，说明"用 agent 给 agent 造工具"已成主流产出方式。

### 1.2 关键趋势判断（对 Harness 相关的部分）

- **统一 Provider 网关（OmniRoute 式）验证了"多 provider + 配额回退 + 压缩"是刚需。** Harness 已有 Provider 配置与本地加密，但缺统一网关/回退；不过这与 RikkaHub 对比结论一致——不是 Harness 的近期主线，仅作"失败可恢复"的参照。
- **Skills 作为独立分发单元正在成熟。** Claude Code/Cursor/Codex 的 skill 包（含设计 skill、绘图 skill）成为新的事实标准。Harness 自己的 `.hbundle` 智能体包与 hwiki 知识包是另一条路线（人格+语料+证据），不与 coding skill 直接竞争，但"包作为可分发的 agent 能力单元"这一心智被强化。
- **Office/文档 agent（OfficeCLI、claude-video）说明"把外部文档/媒体喂给 agent"是高频诉求。** 与 RikkaHub 附件问答结论一致：Harness 仍不追"任意文件问答"，但"项目文件夹可索引、Markdown 受控写回"的差异化要更稳。
- **并行/舰队编排（orca）是大型工程能力，** 与"移动端自用、单用户、低风险确认"定位冲突，明确不追。

## 2. 豆包 / 火山方舟：当前能力面观察

> 以下来自火山方舟文档中心当前目录结构，代表豆包当前对外能力面，作为"主流大模型平台在卷什么"的参照系。

### 2.1 模型与生成能力

- **最新模型 `Seed-Evolving`**（官方标注"最新模型"），延续 Seed 系列演进。
- **视频生成 `Doubao Seedance 2.0` 系列**（含提示词指南）。
- **图片生成 `Doubao Seedream 5.0 pro`**（含交互编辑）。
- **3D 生成、文本向量化、深度思考、上下文管理、流式输出、续写（Prefill Response）。**

### 2.2 Agent / 工具与平台能力（与 Harness 最相关的部分）

- **Managed Agents / 云沙箱 / Remote MCP**：方舟内置 agent 托管、云环境、云沙箱执行、委派任务、Session 管理、Session 事件流。
- **Vaults 认证 / 持久化记忆 / 上下文挂载文件**：平台侧提供 agent 长期记忆与文件挂载。
- **Multi Agent**：原生多 agent 协作。
- **GUI Agent 能力 + 视觉定位（Grounding）**：界面操作型 agent。
- **工具调用体系：** Function Calling、Web Search 联网插件、Image Process 图像处理、Knowledge Search 私域知识库搜索、云部署 MCP / Remote MCP。
- **Responses API：** 官方提供"迁移至 Responses API"路径，与 OpenAI Responses 兼容走向对齐。

### 2.3 知识库 / 应用层

- 零代码 / 低代码 / 高代码应用、应用实验室、知识库插件、文档/音视频知识问答、知识服务与配额分账。

### 2.4 对 Harness 的启示

- **豆包可通过火山方舟以 Responses/Chat API 接入。** Harness 的 Provider 是 OpenAI-compatible 路线，火山方舟的 Chat/Responses API 是潜在 provider，但接入是 P3 横切维护项，不是新主线。
- **"持久化记忆 + 文件挂载 + Knowledge Search"是平台正在标准化的 RAG/记忆心智。** Harness 已有本地的 agent 关系记忆、hwiki 知识包、项目上下文——与平台方向同构，但坚持本地优先、不上云、不依赖方舟 Managed Agents。
- **GUI Agent / 云沙箱执行**与 Harness"受控写回 + diff 审核 + Git 可见"的安全姿态相反；方舟走"平台代你执行"，Harness 走"用户确认每一步"。差异化在"可控性"，不在能力广度。
- **Seedance/Seedream 的多模态生成**是通用 chat 客户端赛道，与 RikkaHub 结论一致——不在 Harness 近期主线。

## 3. 对 Harness 定位的收敛结论

综合开源趋势、豆包能力面，以及既有 `rikkahub-comparison-2026-07-10.md`：

| 外部信号 | Harness 是否跟进 | 理由 |
| --- | --- | --- |
| 多 provider 统一网关 / 配额回退（OmniRoute） | **不追为主线** | 自用单用户；仅借鉴"失败可恢复"。属 P3 维护。 |
| AI coding skills 包生态（Claude/Cursor/Codex） | **不追，但强化"包=能力单元"心智** | Harness 走 `.hbundle`（人格+语料+证据）自有路线，不兼容 coding skill 格式。 |
| Managed Agents / 云沙箱 / GUI Agent（方舟、orca） | **明确不追** | 与"移动端、本机、受控确认"冲突。 |
| 持久化记忆 + 知识挂载（方舟 Knowledge/Vaults） | **方向同构，坚持本地** | Harness 已有关系记忆 + hwiki；不上云。P1 agent 侧 RAG 闭环即此方向的本地实现。 |
| 多模态生成（Seedance/Seedream、语音 agent） | **不追** | 通用 chat 赛道，非项目工作台差异化。 |
| 文档/媒体喂 agent（OfficeCLI、claude-video） | **不追通用，守项目文件** | 保持"项目文件夹可索引 + Markdown 受控写回"。 |
| 豆包 via 方舟 API 接入 | **P3 横切，非主线** | OpenAI-compatible provider 增项，按需补。 |

**一句话：** 外部生态（无论是开源 trending 还是豆包平台）都在向"更广、更自动、平台代执行"加速；Harness 的护城河恰恰是反方向——**移动端、本机、每步可见、每步确认**。这份观察不改变"做窄"的总策略，只强化它。

## 4. 进入路线图 grilling 的待决策点（预读，不预判）

下列决策点来自本文观察 × 现有 `product-plan.md` v3 的张力，将在 grilling 中逐项向用户确认：

1. **provider 接入豆包/方舟** 是否进 P3 清单（还是继续"按需补、不立项"）？
2. **P1 agent 侧 RAG 闭环** 是否需要参考方舟"持久化记忆/Vaults"心智补"agent 长期项目记忆"？（当前 P1 只补语料溯源+核验+绑定+可解释。）
3. **GitHub trending 暴露的"skill 包"心智** 是否反过来要求 `.hbundle` 的对外可发现性/分发？（当前仅侧载导入。）
4. 文档侧：本文是否替换/合并进 `product-plan.md` 的"暂不做"论证，避免三份对比文档（RikkaHub / 本文件 / 产品计划）口径漂移？

## 参考来源

- GitHub Trending (monthly): `https://github.com/trending?since=monthly`（2026-08-04 抓取）
- 火山方舟文档中心: `https://www.volcengine.com/docs/82379`（2026-08-04 抓取）
- 既有: `docs/product-plan.md`（v3，2026-08-02）、`docs/rikkahub-comparison-2026-07-10.md`
